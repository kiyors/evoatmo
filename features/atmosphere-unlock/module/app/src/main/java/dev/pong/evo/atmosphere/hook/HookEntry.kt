/*
 * HookEntry — the Vector/LSPosed module entry rendering the Atmosphere morph
 * (spec §8.3 verified anchors + §8.8 knob feeds; optional §8.4 palette feed lives
 * on the engine via setWallpaperColors from loadApp scope).
 *
 * Fail-open discipline (AGENTS §3.2): every hook target resolves through the
 * pinned YukiHookAPI 1.2.1 finder API (`toClassOrNull()` + `method { … }`).
 * A renamed member logs once and degrades the feature — never the boot.
 * The `nothing_atmosphere_*` keys are read per transition through AtmosphereKeys
 * (§6.6 runtime re-read pattern); the Vector scope stays com.android.systemui-only (§8.2).
 *
 * API-shape note: spec §8.3's sketch used `resolve().firstMethodOrNull { … }`,
 * which does not exist in YukiHookAPI 1.2.1. The same anchors are hooked through
 * the version's documented finder form (verified against the 1.2.1 tag sources
 * on 2026-10-25): `method { name = …; param(…) }.hook { after/replaceAny }` with
 * `result { onNoSuchMemberFailure { … } }` carrying the NOT-FOUND logging.
 */

package dev.pong.evo.atmosphere.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.provider.Settings
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import android.view.View
import com.android.systemui.nothing.atmosphere.NothingAtmosphereEngine
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.configs
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.highcapable.yukihookapi.hook.type.java.BooleanType
import com.highcapable.yukihookapi.hook.type.java.FloatType
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import de.robv.android.xposed.XposedBridge
import dev.pong.evo.atmosphere.AtmosphereKeys
import kotlin.math.abs

@InjectYukiHookWithXposed
class HookEntry : IYukiHookXposedInit {

    companion object {
        const val TAG = "EvoAtmo"

        // Verified hook targets (spec §5 / §8.3):
        const val SCRIM_CONTROLLER = "com.android.systemui.statusbar.phone.ScrimController"
        const val IMAGE_WALLPAPER_ENGINE = "com.android.systemui.wallpapers.ImageWallpaper" +
            "\$CanvasEngine"

        /** Demo-flavor switch (spec §8.3's "PROTOTYPE_MODE"): one branch pulses the scrim
         *  View, the other owns the wallpaper Surface permanently. */
        val MODE = Mode.WALLPAPER_MORPH

        enum class Mode { VIEW_BLUR, WALLPAPER_MORPH }
    }

    // Live engine instance; created lazily on first use, inside the hook process only.
    private var engine: NothingAtmosphereEngine? = null

    // Cached render targets parked across hook events; nulled on surface death (V6)
    // so no stale Surface/Bitmap survives a recreated wallpaper engine.
    private var activeSurface: SurfaceHolder? = null
    private var activeBitmap: Bitmap? = null

    // V4 throttle: render the scrub only when the fraction moved >= 0.02 since last frame.
    private var lastScrubFraction = -1f

    // One-time WARN latch — an adb-written easing preset logs once, never per transition.
    private var warnedUnknownEasing = false

    // The helper methods below live on HookEntry, outside the PackageParam receiver
    // scope, so they cannot see `appContext`. Capture it once when loadApp attaches.
    private var hostContext: Context? = null

    override fun onInit() {
        configs { isDebug = true }
    }

    override fun onHook() {
        encase {
            loadApp(name = "com.android.systemui") {
                hostContext = appContext

                // Screen-off teardown guard (R8): free the GPU renderer when the surface hides.
                // Guarded: receiver-registration failure must not abort the hook wiring below.
                try {
                    appContext?.registerReceiver(
                        object : BroadcastReceiver() {
                            override fun onReceive(context: Context?, intent: Intent?) {
                                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                                    engine?.setScreenActive(false)
                                }
                            }
                        },
                        IntentFilter(Intent.ACTION_SCREEN_OFF)
                    )
                } catch (t: Throwable) {
                    log("screen-off receiver registration failed — teardown guard off: $t")
                }

                // Optional member dumps during first bring-up (R4): call
                // dumpMembers(SCRIM_CONTROLLER) and dumpMembers(IMAGE_WALLPAPER_ENGINE)
                // here to recheck the live member lists after a ROM update.

                /**
                 * V2/V3/V4 — ScrimController triggers + continuous scrub driver.
                 * Verified signatures live in this class on branch cnb (spec §5).
                 */
                SCRIM_CONTROLLER.toClassOrNull()?.apply {

                    // V2 — unlock trigger
                    method {
                        name = "onKeyguardFadingAwayChanged"
                        emptyParam()
                    }.hook {
                        after {
                            try {
                                if (!masterEnabled()) return@after
                                pushEngineConfig()
                                val scrim = fieldOf(instance, "mScrimBehind") as? View
                                when (MODE) {
                                    Mode.VIEW_BLUR ->
                                        (engine ?: NothingAtmosphereEngine().also { engine = it })
                                            .pulseView(scrim, unlockMs().toLong())
                                    Mode.WALLPAPER_MORPH -> activeSurface?.let { holder ->
                                        activeBitmap?.let { bmp ->
                                            val frame = holder.surfaceFrame
                                            val eng = engine
                                                ?: NothingAtmosphereEngine().also { engine = it }
                                            eng.animateSurfaceTransition(
                                                holder.surface, bmp,
                                                frame.width(), frame.height(),
                                                true, unlockMs().toLong()
                                            )
                                        }
                                    }
                                }
                            } catch (t: Throwable) {
                                log("fadingAway hook error: $t")
                            }
                        }
                    }.result {
                        onNoSuchMemberFailure {
                            log("onKeyguardFadingAwayChanged NOT FOUND — run dumpMembers")
                        }
                    }

                    // V3 — lock trigger (keyguard became visible again → settle back to sharp)
                    method {
                        name = "onKeyguardVisibilityChanged"
                        param(BooleanType)
                    }.hook {
                        after {
                            try {
                                val visible = args(0).boolean()
                                if (visible && masterEnabled() && MODE == Mode.WALLPAPER_MORPH &&
                                    (engine?.currentFraction ?: 0f) > 0.02f
                                ) {
                                    pushEngineConfig()
                                    activeSurface?.let { holder ->
                                        activeBitmap?.let { bmp ->
                                            val frame = holder.surfaceFrame
                                            engine?.animateSurfaceTransition(
                                                holder.surface, bmp,
                                                frame.width(), frame.height(),
                                                false, lockMs().toLong()
                                            )
                                        }
                                    }
                                }
                                if (visible && MODE == Mode.VIEW_BLUR) {
                                    (fieldOf(instance, "mScrimBehind") as? View)
                                        ?.setRenderEffect(null)
                                }
                            } catch (t: Throwable) {
                                log("visibility hook error: $t")
                            }
                        }
                    }.result {
                        onNoSuchMemberFailure {
                            log("onKeyguardVisibilityChanged NOT FOUND — run dumpMembers")
                        }
                    }

                    // V4 — continuous scrub (only meaningful in MORPH mode; throttled)
                    if (MODE == Mode.WALLPAPER_MORPH) {
                        method {
                            name = "setTransitionToFullShadeProgress"
                            param(FloatType, FloatType)
                        }.hook {
                            after {
                                try {
                                    if (!masterEnabled()) return@after
                                    val progress = args(0).float()
                                    if (abs(progress - lastScrubFraction) < 0.02f) return@after
                                    lastScrubFraction = progress
                                    val holder = activeSurface ?: return@after
                                    val bmp = activeBitmap ?: return@after
                                    val frame = holder.surfaceFrame
                                    val eng = engine
                                        ?: NothingAtmosphereEngine().also { engine = it }
                                    eng.renderFrame(
                                        holder.surface, bmp,
                                        frame.width(), frame.height(),
                                        progress.coerceIn(0f, 1f)
                                    )
                                } catch (t: Throwable) {
                                    log("scrub hook error: $t")
                                }
                            }
                        }.result {
                            onNoSuchMemberFailure {
                                log(
                                    "setTransitionToFullShadeProgress NOT FOUND — run dumpMembers"
                                )
                            }
                        }
                    }
                } ?: log("$SCRIM_CONTROLLER NOT FOUND — run dumpMembers")

                /**
                 * V5/V6 — Wallpaper surface ownership (MORPH mode only).
                 * replaceAny on the lowest verified draw path; remaining internal paths
                 * (drawFrame/drawFrameSynchronized/drawFrameInternal) call
                 * drawFrameOnCanvas on in-stock trees; verify with dumpMembers on your build.
                 */
                if (MODE == Mode.WALLPAPER_MORPH) {
                    IMAGE_WALLPAPER_ENGINE.toClassOrNull()?.apply {

                        // V5 — own the draw path: render the CURRENT fraction
                        // (0 = sharp, 1 = morphed/settled)
                        method {
                            name = "drawFrameOnCanvas"
                            param(Bitmap::class.java)
                        }.hook {
                            replaceAny {
                                try {
                                    val bmp = args(0).cast<Bitmap>()
                                        ?: return@replaceAny callOriginal()
                                    val engineInstance = instance as WallpaperService.Engine
                                    // Public API getter — no reflection needed.
                                    val holder = engineInstance.surfaceHolder
                                    activeSurface = holder
                                    activeBitmap = bmp
                                    if (!masterEnabled()) return@replaceAny callOriginal()

                                    val eng = engine
                                        ?: NothingAtmosphereEngine().also { engine = it }
                                    val frame = holder.surfaceFrame
                                    eng.renderFrame(
                                        holder.surface, bmp,
                                        frame.width(), frame.height(),
                                        eng.currentFraction
                                    )
                                    null
                                } catch (t: Throwable) {
                                    log("drawFrameOnCanvas hook error: $t")
                                    callOriginal()
                                }
                            }
                        }.result {
                            onNoSuchMemberFailure {
                                log("drawFrameOnCanvas NOT FOUND — run dumpMembers")
                            }
                        }

                        // NOTE (§8.8 device checklist): the only param-less finder — confirm
                        // via the dumpMembers log that it resolves (no NOT FOUND) on this build.
                        method {
                            name = "onSurfaceDestroyed"
                        }.hook {
                            after {
                                engine?.releaseSurfaceResources()
                                activeSurface = null
                                activeBitmap = null
                            }
                        }.result {
                            onNoSuchMemberFailure {
                                log("onSurfaceDestroyed NOT FOUND — run dumpMembers")
                            }
                        }
                    } ?: log("ImageWallpaper\$CanvasEngine NOT FOUND — check your tree")
                }

                // §8.4 palette feed — optional seed, re-fed when a transition pushes config.
                // Guarded: a manager-color failure must not abort anything downstream.
                try {
                    appContext?.let { ctx ->
                        android.app.WallpaperManager.getInstance(ctx)
                            .getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM)
                            ?.let { engine?.setWallpaperColors(it) }
                    }
                } catch (t: Throwable) {
                    log("palette feed failed (optional, non-fatal): $t")
                }
            }
        }
    }

    private fun log(msg: String) {
        XposedBridge.log("[$TAG] $msg")
    }

    private fun masterEnabled(): Boolean = AtmosphereKeys.readMaster(hostContext?.contentResolver)

    private fun unlockMs(): Int = AtmosphereKeys.readUnlockDurationMs(hostContext?.contentResolver)

    private fun lockMs(): Int = AtmosphereKeys.readLockDurationMs(hostContext?.contentResolver)

    /**
     * Push the knob keys into the engine's tunable surface
     * (§8.8 + §6.6 per-transition re-read).
     */
    private fun pushEngineConfig() {
        val resolver = hostContext?.contentResolver ?: return
        NothingAtmosphereEngine.peakBlurRadius = AtmosphereKeys.readPeakRadius(resolver).toFloat()
        NothingAtmosphereEngine.settledBlurRadius =
            AtmosphereKeys.readSettledRadius(resolver).toFloat()
        NothingAtmosphereEngine.meshStrengthScale = AtmosphereKeys.readMeshStrength(resolver)
        // Only preset 0 (the spec curve) ships today; anything adb-written fails open
        // to 0. Warn once so the user knows their value was accepted-but-dormant.
        val rawPreset = try {
            Settings.System.getInt(
                resolver,
                AtmosphereKeys.EASING_PRESET,
                AtmosphereKeys.EASING_PRESET_DEFAULT
            )
        } catch (t: Throwable) {
            AtmosphereKeys.EASING_PRESET_DEFAULT
        }
        if (rawPreset != 0 && !warnedUnknownEasing) {
            warnedUnknownEasing = true
            log("easing preset $rawPreset has no mapping yet — using 0 (Nothing curve, spec §7)")
        }
    }

    /** Fail-open read of an instance field by name (the mScrimBehind anchor, spec §5 V2 row). */
    private fun fieldOf(instance: Any?, name: String): Any? {
        if (instance == null) return null
        return try {
            instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)
        } catch (t: Throwable) {
            null
        }
    }

    /** Runtime member dumper — adapt to ROM updates in one logcat read (risk R4). */
    private fun PackageParam.dumpMembers(className: String) {
        val clazz = className.toClassOrNull()
        if (clazz == null) {
            log("dump: $className NOT FOUND")
            return
        }
        val fields = clazz.declaredFields.joinToString(", ") { it.name }
        val methods = clazz.declaredMethods.joinToString(" | ") {
            "${it.name}(${it.parameterTypes.joinToString(",") { p -> p.simpleName }})"
        }
        log("dump fields of $className:\n$fields")
        log("dump methods of $className:\n$methods")
    }
}
