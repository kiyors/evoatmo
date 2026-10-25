/*
 * AtmosphereKeys — the single source of truth for the `nothing_atmosphere_*`
 * Settings.System keys (spec §8.8 keys table; key set adopted by the wayfinder
 * decision, never renamed). Both the module-app Atmosphere screen (§8.8) and
 * the hook-side per-event config push (§6.6 runtime re-read pattern) go
 * through this object so key names, defaults and clamps can never diverge.
 *
 * Reads need no permission; writes need the stock WRITE_SETTINGS grant
 * (developer.android.com `Settings.System.canWrite`, API 23+).
 * Pure SDK — no Xposed/API imports, safe for both sides of the module.
 */

package dev.pong.evo.atmosphere

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings

object AtmosphereKeys {

    // ---- Key names (spec §8.8 — adopted verbatim, no new keys) ----
    const val MASTER = "nothing_atmosphere_unlock_enabled"
    const val PEAK_RADIUS = "nothing_atmosphere_peak_radius"
    const val SETTLED_RADIUS = "nothing_atmosphere_settled_radius"
    const val UNLOCK_DURATION_MS = "nothing_atmosphere_unlock_duration_ms"
    const val LOCK_DURATION_MS = "nothing_atmosphere_lock_duration_ms"
    const val MESH_STRENGTH = "nothing_atmosphere_mesh_strength"
    const val EASING_PRESET = "nothing_atmosphere_easing_preset"

    // ---- Spec defaults (§8.8 keys table) ----
    const val MASTER_DEFAULT = 1
    const val PEAK_RADIUS_DEFAULT = 110
    const val SETTLED_RADIUS_DEFAULT = 28
    const val UNLOCK_DURATION_MS_DEFAULT = 720
    const val LOCK_DURATION_MS_DEFAULT = 450
    const val MESH_STRENGTH_DEFAULT = 1f
    const val EASING_PRESET_DEFAULT = 0

    // ---- Clamps. Squeeze on read AND on write, so neither an adb-written
    //      out-of-range value nor a rogue UI state reaches the engine. ----

    // Lower floor stays at/below the engine's hard guard: buildAtmosphereRenderEffect
    // orders `(fraction * peak).coerceIn(0.5f, peak)` — a radius under 0.5f would
    // throw IllegalArgumentException (min > max), crashing the transition (§7).
    const val PEAK_RADIUS_MIN = 1
    const val PEAK_RADIUS_MAX = 300
    const val SETTLED_RADIUS_MIN = 1
    const val SETTLED_RADIUS_MAX = 100

    // The §7 transition is deliberately short; keep the knobs in its neighborhood.
    const val DURATION_MS_MIN = 100
    const val DURATION_MS_MAX = 2000

    // Mesh multiplier: 0 off, 1 = engine default mix, up to 2 exaggeration.
    // Stored as a string ("1.00") so the adb CLI matches the spec's float value
    // (Settings.System float put/get are not part of the public API surface).
    const val MESH_STRENGTH_MIN = 0f
    const val MESH_STRENGTH_MAX = 2f

    // Preset index: only preset 0 (the spec curve) ships today — the engine's
    // nothingInterpolator is the one installed curve. The screen shows the preset
    // as a fixed note row and writes nothing; the hook reads this key raw (see
    // HookEntry.pushEngineConfig) so any non-zero adb value is logged once before
    // failing open to the default curve (§8.8).

    // ---------------------------------------------------------------- reads

    fun readMaster(resolver: ContentResolver?): Boolean {
        if (resolver == null) return true
        return try {
            Settings.System.getInt(resolver, MASTER, MASTER_DEFAULT) == 1
        } catch (t: Throwable) {
            true   // fail-open on read errors (spec §8.3 same guard)
        }
    }

    fun readPeakRadius(resolver: ContentResolver?): Int = readInt(
        resolver, PEAK_RADIUS, PEAK_RADIUS_DEFAULT, PEAK_RADIUS_MIN, PEAK_RADIUS_MAX
    )

    fun readSettledRadius(resolver: ContentResolver?): Int = readInt(
        resolver, SETTLED_RADIUS, SETTLED_RADIUS_DEFAULT, SETTLED_RADIUS_MIN, SETTLED_RADIUS_MAX
    )

    fun readUnlockDurationMs(resolver: ContentResolver?): Int = readInt(
        resolver, UNLOCK_DURATION_MS, UNLOCK_DURATION_MS_DEFAULT, DURATION_MS_MIN, DURATION_MS_MAX
    )

    fun readLockDurationMs(resolver: ContentResolver?): Int = readInt(
        resolver, LOCK_DURATION_MS, LOCK_DURATION_MS_DEFAULT, DURATION_MS_MIN, DURATION_MS_MAX
    )

    fun readMeshStrength(resolver: ContentResolver?): Float {
        if (resolver == null) return MESH_STRENGTH_DEFAULT
        return try {
            val parsed = Settings.System.getString(resolver, MESH_STRENGTH)?.toFloatOrNull()
            (parsed ?: MESH_STRENGTH_DEFAULT).coerceIn(MESH_STRENGTH_MIN, MESH_STRENGTH_MAX)
        } catch (t: Throwable) {
            MESH_STRENGTH_DEFAULT
        }
    }

    // ---------------------------------------------------------------- writes

    /** Returns false when the stock WRITE_SETTINGS grant is missing (pre-grant = read-only).
     *  The grant is a user toggle in "Modify system settings"
     *  (Settings.System.canWrite, API 23+). */
    fun isWriteGranted(context: Context?): Boolean {
        if (context == null) return false
        return try {
            Settings.System.canWrite(context)
        } catch (t: Throwable) {
            false
        }
    }

    fun writeMaster(resolver: ContentResolver?, enabled: Boolean): Boolean =
        writeInt(resolver, MASTER, if (enabled) 1 else 0)

    fun writePeakRadius(resolver: ContentResolver?, value: Int): Boolean =
        writeInt(resolver, PEAK_RADIUS, value.coerceIn(PEAK_RADIUS_MIN, PEAK_RADIUS_MAX))

    fun writeSettledRadius(resolver: ContentResolver?, value: Int): Boolean =
        writeInt(resolver, SETTLED_RADIUS, value.coerceIn(SETTLED_RADIUS_MIN, SETTLED_RADIUS_MAX))

    fun writeUnlockDurationMs(resolver: ContentResolver?, value: Int): Boolean =
        writeInt(resolver, UNLOCK_DURATION_MS, value.coerceIn(DURATION_MS_MIN, DURATION_MS_MAX))

    fun writeLockDurationMs(resolver: ContentResolver?, value: Int): Boolean =
        writeInt(resolver, LOCK_DURATION_MS, value.coerceIn(DURATION_MS_MIN, DURATION_MS_MAX))

    fun writeMeshStrength(resolver: ContentResolver?, value: Float): Boolean {
        val cr = resolver ?: return false
        return try {
            val clamped = value.coerceIn(MESH_STRENGTH_MIN, MESH_STRENGTH_MAX)
            val asTwoDecimals = String.format(java.util.Locale.US, "%.2f", clamped)
            Settings.System.putString(cr, MESH_STRENGTH, asTwoDecimals)
        } catch (t: Throwable) {
            false
        }
    }

    // ---------------------------------------------------------------- internals

    private fun readInt(
        resolver: ContentResolver?,
        key: String,
        default: Int,
        min: Int,
        max: Int
    ): Int {
        if (resolver == null) return default
        return try {
            Settings.System.getInt(resolver, key, default).coerceIn(min, max)
        } catch (t: Throwable) {
            default
        }
    }

    private fun writeInt(resolver: ContentResolver?, key: String, value: Int): Boolean {
        val cr = resolver ?: return false
        return try {
            Settings.System.putInt(cr, key, value)
        } catch (t: Throwable) {
            false
        }
    }
}
