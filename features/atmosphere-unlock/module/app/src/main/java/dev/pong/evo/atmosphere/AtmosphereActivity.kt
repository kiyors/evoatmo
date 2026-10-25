/*
 * AtmosphereActivity — the module app's selection surface (spec §8.8).
 *
 * Ordinary module-app UI over the public Settings.System API: master toggle
 * plus the six tuning knobs, all backed by AtmosphereKeys (single source of
 * truth for key names, defaults and clamps). Entry paths: launcher icon plus
 * the manifest ACTION_SET_WALLPAPER filter (Settings → Wallpaper hub and
 * fork-picker third-party listing). Plain platform widgets, no extra dependency.
 *
 * Never does (spec §8.8): set or change a wallpaper (zero WallpaperManager
 * writes), touch EvoX blur keys or Monet, persist anything per unlock.
 * Pre-grant the screen is read-only + a hint; the stock "Modify system
 * settings" grant (Settings.System.canWrite, API 23+) unlocks the controls.
 */

package dev.pong.evo.atmosphere

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

class AtmosphereActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var managePermissionButton: Button
    private lateinit var masterSwitch: Switch

    private lateinit var peakBar: SeekBar
    private lateinit var peakLabel: TextView
    private lateinit var settledBar: SeekBar
    private lateinit var settledLabel: TextView
    private lateinit var unlockBar: SeekBar
    private lateinit var unlockLabel: TextView
    private lateinit var lockBar: SeekBar
    private lateinit var lockLabel: TextView
    private lateinit var meshBar: SeekBar
    private lateinit var meshLabel: TextView
    private lateinit var easingNote: TextView

    private val resolver get() = contentResolver

    /** True when the controls may write; false keeps the group read-only (pre-grant state). */
    private fun granted(): Boolean = AtmosphereKeys.isWriteGranted(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_atmosphere)

        statusText = findViewById(R.id.atmosphere_status_text)
        managePermissionButton = findViewById(R.id.atmosphere_manage_button)
        masterSwitch = findViewById(R.id.atmosphere_master_switch)
        peakBar = findViewById(R.id.atmosphere_peak_bar)
        peakLabel = findViewById(R.id.atmosphere_peak_label)
        settledBar = findViewById(R.id.atmosphere_settled_bar)
        settledLabel = findViewById(R.id.atmosphere_settled_label)
        unlockBar = findViewById(R.id.atmosphere_unlock_bar)
        unlockLabel = findViewById(R.id.atmosphere_unlock_label)
        lockBar = findViewById(R.id.atmosphere_lock_bar)
        lockLabel = findViewById(R.id.atmosphere_lock_label)
        meshBar = findViewById(R.id.atmosphere_mesh_bar)
        meshLabel = findViewById(R.id.atmosphere_mesh_label)
        easingNote = findViewById(R.id.atmosphere_easing_note)

        // Bar ranges carry the clamps' min offsets so progress + min == the real value.
        peakBar.max = AtmosphereKeys.PEAK_RADIUS_MAX - AtmosphereKeys.PEAK_RADIUS_MIN
        settledBar.max = AtmosphereKeys.SETTLED_RADIUS_MAX - AtmosphereKeys.SETTLED_RADIUS_MIN
        unlockBar.max = AtmosphereKeys.DURATION_MS_MAX - AtmosphereKeys.DURATION_MS_MIN
        lockBar.max = AtmosphereKeys.DURATION_MS_MAX - AtmosphereKeys.DURATION_MS_MIN
        meshBar.max = MESH_BAR_MAX

        masterSwitch.setOnCheckedChangeListener(masterListener)

        attachKnob(
            bar = peakBar, min = AtmosphereKeys.PEAK_RADIUS_MIN, label = peakLabel,
            format = { getString(R.string.atmosphere_value_px, it) },
            write = { AtmosphereKeys.writePeakRadius(resolver, it) }
        )
        attachKnob(
            bar = settledBar, min = AtmosphereKeys.SETTLED_RADIUS_MIN, label = settledLabel,
            format = { getString(R.string.atmosphere_value_px, it) },
            write = { AtmosphereKeys.writeSettledRadius(resolver, it) }
        )
        attachKnob(
            bar = unlockBar, min = AtmosphereKeys.DURATION_MS_MIN, label = unlockLabel,
            format = { getString(R.string.atmosphere_value_ms, it) },
            write = { AtmosphereKeys.writeUnlockDurationMs(resolver, it) }
        )
        attachKnob(
            bar = lockBar, min = AtmosphereKeys.DURATION_MS_MIN, label = lockLabel,
            format = { getString(R.string.atmosphere_value_ms, it) },
            write = { AtmosphereKeys.writeLockDurationMs(resolver, it) }
        )

        meshBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                meshLabel.text = getString(R.string.atmosphere_value_percent, progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (granted()) {
                    AtmosphereKeys.writeMeshStrength(
                        resolver, seekBar.progress / MESH_BAR_SCALE.toFloat()
                    )
                } else {
                    refreshUi()
                }
            }
        })

        managePermissionButton.setOnClickListener {
            // Stock special-access screen; reads stay permissionless, writes flip on approval.
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private val masterListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        if (granted()) {
            AtmosphereKeys.writeMaster(resolver, checked)
        } else {
            refreshUi()
        }
    }

    /**
     * Common knob behavior: the label tracks the current progress; the Setting
     * is written only on release (one write per gesture, never during drag).
     */
    private fun attachKnob(
        bar: SeekBar,
        min: Int,
        label: TextView,
        format: (Int) -> String,
        write: (Int) -> Boolean
    ) {
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                label.text = format(progress + min)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (granted()) {
                    write(seekBar.progress + min)
                } else {
                    refreshUi()
                }
            }
        })
    }

    /**
     * Seed every control from the live Settings values (reads need no grant),
     * then set the group's enabled state.
     */
    private fun refreshUi() {
        val canWrite = granted()

        val statusRes = if (canWrite) {
            R.string.atmosphere_status_writable
        } else {
            R.string.atmosphere_status_read_only
        }
        statusText.setText(statusRes)
        managePermissionButton.visibility = if (canWrite) View.GONE else View.VISIBLE

        // Pause the master listener so seeding does not re-write the key it just read.
        masterSwitch.setOnCheckedChangeListener(null)
        masterSwitch.isChecked = AtmosphereKeys.readMaster(resolver)
        masterSwitch.setOnCheckedChangeListener(masterListener)

        val peakRadius = AtmosphereKeys.readPeakRadius(resolver)
        peakBar.progress = (peakRadius - AtmosphereKeys.PEAK_RADIUS_MIN).coerceIn(0, peakBar.max)
        peakLabel.text = getString(R.string.atmosphere_value_px, peakRadius)

        val settledRadius = AtmosphereKeys.readSettledRadius(resolver)
        settledBar.progress =
            (settledRadius - AtmosphereKeys.SETTLED_RADIUS_MIN).coerceIn(0, settledBar.max)
        settledLabel.text = getString(R.string.atmosphere_value_px, settledRadius)

        val unlockMs = AtmosphereKeys.readUnlockDurationMs(resolver)
        unlockBar.progress = (unlockMs - AtmosphereKeys.DURATION_MS_MIN).coerceIn(0, unlockBar.max)
        unlockLabel.text = getString(R.string.atmosphere_value_ms, unlockMs)

        val lockMs = AtmosphereKeys.readLockDurationMs(resolver)
        lockBar.progress = (lockMs - AtmosphereKeys.DURATION_MS_MIN).coerceIn(0, lockBar.max)
        lockLabel.text = getString(R.string.atmosphere_value_ms, lockMs)

        val meshPercent = (AtmosphereKeys.readMeshStrength(resolver) * MESH_BAR_SCALE).roundToInt()
        meshBar.progress = meshPercent.coerceIn(0, meshBar.max)
        meshLabel.text = getString(R.string.atmosphere_value_percent, meshPercent)

        // Preset 0 is the only shipped curve today; other adb-written values fail
        // open to 0 (spec §8.8).
        easingNote.setText(R.string.atmosphere_easing_note)

        // Pre-grant, the whole tuning group is enabled=false — read-only + hint, per §8.8.
        masterSwitch.isEnabled = canWrite
        peakBar.isEnabled = canWrite
        settledBar.isEnabled = canWrite
        unlockBar.isEnabled = canWrite
        lockBar.isEnabled = canWrite
        meshBar.isEnabled = canWrite
    }

    private companion object {
        /** Mesh strength slider runs in percent (0..200) over the 0..2.0 clamp. */
        const val MESH_BAR_MAX = 200
        const val MESH_BAR_SCALE = 100
    }
}
