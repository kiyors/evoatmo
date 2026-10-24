---
id: engineering_specification
aliases:
  - Engineering Specification v2 — Nothing OS "Atmosphere" Unlock Engine for Evolution X (Pong)
tags:
  - android
  - rom
---

# Engineering Specification v2 — Nothing OS "Atmosphere" Unlock Engine for Evolution X (`Pong`)

| Field       | Value                                                                                             |
| ----------- | ------------------------------------------------------------------------------------------------- |
| Revision    | **v2** (supersedes v1 — see Appendix B for every change and why)                                  |
| Date        | 2026-10-24                                                                                        |
| Device      | Nothing Phone (2) — codename **`Pong`**, Qualcomm SM8475 (Snapdragon 8+ Gen 1), 120 Hz LTPO       |
| ROM         | Evolution X, **Android 16+** (current default branches: frameworks_base `cnb`, device tree `bka`) |
| Root        | Magisk or KernelSU (Zygisk-capable)                                                               |
| Xposed      | **Vector (JingMatrix)** — supports Android 8.1 → 17; fallback: `mywalkb/LSPosed_mod`              |
| Hooking API | YukiHookAPI **1.2.1** (latest on Maven at verification; upstream renamed to **YukiHook** — see §3)        |

Methodology note: every hook point referenced in this spec was verified against the **actual Evolution X source code** (`Evolution-X/frameworks_base`, branch `cnb`) on the date above. Anything that could not be verified is explicitly marked **VERIFY-FIRST** with the exact commands to verify it yourself. Never trust hook names from documents — verify against your synced tree (Appendix C).

### Documentation Map

This specification is the **technical source of truth** (architecture, verified hooks, code, risks). Operational and conceptual knowledge lives in companion docs — sections below that were extracted carry pointers to where they moved.

| Document | Answers | Contents |
| --- | --- | --- |
| [`engineering_specification.md`](engineering_specification.md) *(this file)* | "What do we build and why?" | Feasibility, hook matrix V1–V12, engine + hook code, Stage 1→2→3 integration, risk register R1–R12, symbol-verification appendices |
| [`basics.md`](basics.md) | "How do the pieces work?" | Plain-English concepts: unlock pipeline, Android rendering APIs, SystemUI internals, the Xposed layer |
| [`setup.md`](setup.md) | "How do I set everything up?" | Workstation tools, device prep, Vector + root manager, Stage-1 module project, AOSP workspace |
| [`operations.md`](operations.md) | "How do I use it and fix it?" | Safety shield, hot-reload loop, ROM-update re-verification, test plan, failure playbook, debug cheat sheet |
| [`ideas.md`](ideas.md) | "What else can we do?" | Tiered feature roadmap T0–T3 with hook references and risk mapping |
| [`architecture.md`](architecture.md) | "How is this project structured and run?" | Two work streams (fixes + features), monorepo layout, component architecture, language inventory (Kotlin / Java / AGSL / Rust), workflow and tagging conventions |
| [`fix-registry.md`](fix-registry.md) | "What in Evolution X annoys me right now?" | Stream-A triage board — captured pain → triage → `FIX-NNN` folders with evidence + branch-tagged patches (lifecycle in `architecture.md` §2) |
| [`README.md`](README.md) | "What is this repo?" | Index, suggested repo name, layout |

---

## 0. Executive Summary

- **Feasibility: YES.** The two-stage architecture is sound on this device+ROM combo:
  - **Stage 1** — LSPosed/Vector module that hot-patches your running Evolution X SystemUI (5-second iteration loop, no reboots).
  - **Stage 2** — port of the identical, pure-SDK engine into `frameworks/base` (no rewrite needed).
  - **Stage 3** — building only `SystemUI` and systemlessly overlaying it via a Magisk/KernelSU module (with correct signing — see §11 and risk R1).
- **The engine is pure Android SDK** (`RenderEffect`, `RuntimeShader`, `RenderNode`, `HardwareRenderer` — all public APIs, present on this device's Android version). The same class compiles both inside the Xposed module and inside `frameworks_base`.
- **Persistence strategy (correct):** the "new wallpaper every unlock" is a **visual morph rendered to the live wallpaper `Surface`**. We never call `WallpaperManager.setBitmap()` per unlock (v1's `bakeAndSaveHomeWallpaperAsync()` did exactly that and was **removed** — it was wrong; see Appendix B, change 4).
- **Biggest risks:** signature-mismatch bootloop from replacing `SystemUI.apk` (R1), silent hook failures after ROM updates (R4), and two producers fighting over one wallpaper `Surface` (R3). All three have concrete mitigations in §13.

**Verified hook points (from Evolution X `cnb`):** `ScrimController.onKeyguardFadingAwayChanged()`, `ScrimController.setTransitionToFullShadeProgress(float, float)`, `ScrimController.onKeyguardVisibilityChanged(boolean)`, `ImageWallpaper$CanvasEngine.drawFrameOnCanvas(Bitmap)`, plus `KeyguardStateController` as the long-term stable anchor. Note: **`KeyguardViewMediator.onKeyguardGoingAway()` does NOT exist** — v1 relied on it; v2 does not.

---

## 1. Scope & Non-Goals

**In scope**

1. Nothing OS–style animated Gaussian blur + AGSL "color-mesh" distortion on unlock.
2. Wallpaper visually morphs to a fresh, seeded state on every unlock and persists on the home screen until the next lock.
3. Optional depth effect (pre-cut foreground subject floating above the lockscreen clock).
4. Runtime toggle in The Evolver (`Settings.System` key), usable from Stage 1 already.
5. A build/test/deploy pipeline that can never soft-brick the device (systemless only).

**Non-goals**

- Real-time on-device ML subject segmentation (requires pre-processed cutout assets — see §10).
- Actually writing a new bitmap to `WallpaperManager` per unlock (explicitly rejected — R6).
- Changing launcher/Monet behavior (we must not disturb `WallpaperColors` computation).

---

## 2. Target Environment & Repository Map

### 2.1 Device & ROM

| Item                             | Value                                                                                                               |
| -------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| Device codename                  | `Pong` (SM8475)                                                                                                     |
| ROM                              | Evolution X (LineageOS-based) — device page: <https://evolution-x.org/device/Pong>                                  |
| Device update channel            | Telegram archive: <https://t.me/s/NothingPhone2updates> (tracks official Evo/Pong releases incl. Android 17 builds) |
| Device hardware features in tree | `nothing-fwk` stubs, `DeviceExtras`, `vibrator` (RichTap haptics)                                                   |

### 2.2 Source repositories (with branches)

| Purpose                           | Repository                                                   | Default branch at verification                                      |
| --------------------------------- | ------------------------------------------------------------ | ------------------------------------------------------------------- |
| Core framework & SystemUI         | <https://github.com/Evolution-X/frameworks_base>             | `cnb` (other: `bka`, and legacy `pie`/`ten`/`tiramisu`/`udc`/`vic`) |
| Settings hub (The Evolver)        | <https://github.com/Evolution-X/packages_apps_Evolver>       | `cnb`                                                               |
| Device tree & Nothing stubs       | <https://github.com/Evolution-X-Devices/device_nothing_Pong> | `bka` (other: `bka-old`, `bq1`, `cnb`)                              |
| Evolution X org (everything else) | <https://github.com/Evolution-X>                             | —                                                                   |

Branch names follow AOSP build-ID families: `bp1a` ≈ Android 16 QPR1-era, `bka` ≈ Android 16 newer QPR, `cnb` ≈ the current (Android 16 QPR2/17-timeframe) line. **`bp1a` is stale — do not build against it unless your ROM is that exact build.**

### 2.3 Version-match procedure (mandatory before any Stage-2 build)

```bash
# On the device:
adb shell getprop ro.build.version.release
adb shell getprop ro.build.fingerprint     # embeds the build-ID (e.g. BP2A.250605.x / BK... / CN...)
```

Rule: the **first two letters of the build ID in your fingerprint must match the source branch** you sync and build (`cnb → CN…`, `bka → BK…`, `bp1a → BP1A…`). If they don't match, `m SystemUI` will produce an APK built against a different Android base than your ROM has → crash risk (R9). When unsure, check the release notes for your build date on <https://evolution-x.org/device/Pong> or the Telegram feed above, and ask the maintainer which branch matches.

```bash
# Then pick the exact lunch target from your synced tree (don't hardcode it):
source build/envsetup.sh
lunch | grep -i pong
```

### 2.4 Key source paths in `frameworks/base`

```
packages/SystemUI/src/com/android/systemui/statusbar/phone/ScrimController.java
packages/SystemUI/src/com/android/systemui/wallpapers/ImageWallpaper.java
packages/SystemUI/src/com/android/systemui/keyguard/KeyguardViewMediator.java
packages/SystemUI/src/com/android/systemui/statusbar/policy/KeyguardStateController.java
packages/SystemUI/src/com/android/systemui/nothing/atmosphere/   ← our new engine (Stage 2)
core/java/android/provider/Settings.java                                        ← flag (Stage 2)
```

Evolver paths (verified):

```
packages/apps/Evolver/res/xml/evolution_settings_lock_screen.xml
packages/apps/Evolver/src/org/evolution/settings/preferences/SystemSettingSwitchPreference.java
```

---

## 3. Environment & Tooling

The complete step-by-step setup lives in **[`setup.md`](setup.md)** (workstation, device prep, Vector/Xposed install, Stage-1 module project, AOSP workspace). Quick inventory:

| Requirement                                                            | Purpose                                                                    |
| ---------------------------------------------------------------------- | -------------------------------------------------------------------------- |
| Linux host, ≥32 GB RAM for full sync *(AOSP sync only for Stage 2/3)*  | Building `SystemUI` from source                                            |
| Android Studio + SDK 36 + platform-tools (`adb`/`fastboot`)             | Stage-1 module project + device control                                    |
| Vector (Zygisk) or LSPosed_mod, rooted via Magisk or KernelSU          | The Xposed runtime on the device                                           |
| YukiHookAPI 1.2.1 + KSP plugin (§8.1)                                   | Hooking DSL for the Stage-1 module                                         |
| scrcpy · Perfetto                                                       | Debugging crash-loops · jank tracing                                       |

> **Upstream rename note (verified 2026-10-24):** the YukiHookAPI project has been renamed **YukiHook** (<https://github.com/HighCapable/YukiHook> — the old repo URL still redirects). The old docs at `highcapable.github.io/YukiHookAPI/` are now 404; current docs live at <https://highcapable.github.io/YukiHook/en>. The Maven artifact this spec pins (`com.highcapable.yukihookapi:api:1.2.1` + `ksp-xposed:1.2.1`) is unchanged, so **§8.1 stays valid as written**; if you move to a newer release, first check the renamed project's quick start — the modern rewrite uses different entry-point APIs than the 1.2.1 code in §8.3.

---

## 4. Fail-Safe Recovery Protocol (summary)

The full operational pre-flight ritual — with the commands commented — lives in **[`operations.md`](operations.md) §1 "The Safety Shield"**. The four protections:

1. **Verify the active A/B slot** (`fastboot getvar current-slot`) — flashing stays systemless; partitions are never written.
2. **Keep the bootloop disabler armed** in a terminal *before* touching SystemUI (`magisk --remove-modules && reboot` on device enumeration).
3. **Hardware Safe Mode fallback** (Power + Volume Down at the boot animation) — works even when USB debugging is broken.
4. **ADB redundancy:** Wireless debugging enabled + the manual module-disable command ready.

The design rationale behind this protocol (never write partitions, R10) is in §13.

---

## 5. Verified Hook-Point Matrix (source of truth)

All entries below were confirmed to exist **in `Evolution-X/frameworks_base` branch `cnb`** (2026-10-24). Signatures quoted verbatim.

| #   | Class / member                                                                  | Signature                                                                                                       | Use in this spec                                                                    |
| --- | ------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| V1  | `com.android.systemui.statusbar.phone.ScrimController.mScrimBehind`             | field                                                                                                           | Blur/morph target for Scrim-mode (57 refs in file)                                  |
| V2  | `ScrimController.onKeyguardFadingAwayChanged`                                   | `public void onKeyguardFadingAwayChanged()`                                                                     | **Unlock trigger** (0→1 morph)                                                      |
| V3  | `ScrimController.onKeyguardVisibilityChanged`                                   | `public void onKeyguardVisibilityChanged(boolean visible)`                                                      | **Lock trigger** (1→0 morph)                                                        |
| V4  | `ScrimController.setTransitionToFullShadeProgress`                              | `public void setTransitionToFullShadeProgress(float progress, float lockScreenNotificationsProgress)`           | Continuous scrub blur (two floats — v1's `param(FloatType, FloatType)` was correct) |
| V5  | `com.android.systemui.wallpapers.ImageWallpaper$CanvasEngine.drawFrameOnCanvas` | `void drawFrameOnCanvas(Bitmap bitmap)`                                                                         | Surface ownership for the morph engine                                              |
| V6  | `ImageWallpaper$CanvasEngine` internal draw chain                               | `private void drawFrame() / drawFrameSynchronized() / drawFrameInternal()`                                      | Must not run concurrently with ours (R3)                                            |
| V7  | `ImageWallpaper` drawing today                                                  | uses `surface.lockHardwareCanvas()` — **no** `RenderEffect`/`HardwareRenderer` in the stock file                | We replace this path only when enabled                                              |
| V8  | `com.android.systemui.keyguard.KeyguardViewMediator.doKeyguardLocked`           | `private void doKeyguardLocked(Bundle options)` (+1 overload)                                                   | Backup lock trigger (specify params when hooking)                                   |
| V9  | `com.android.systemui.keyguard.KeyguardViewMediator.onKeyguardGoingAway`        | **DOES NOT EXIST** (only `onKeyguardGoingAwayRemoteAnimationStart/Cancelled` fired on other objects)            | ❌ never hook this (v1 bug)                                                         |
| V10 | `com.android.systemui.statusbar.policy.KeyguardStateController`                 | interface w/ `KeyguardStateController.Callback` (`onKeyguardShowingChanged()`, `onKeyguardFadingAwayChanged()`) | **Preferred native anchor** for Stage 2                                             |
| V11 | Evolver lock-screen prefs XML                                                   | `res/xml/evolution_settings_lock_screen.xml`                                                                    | Toggle placement                                                                    |
| V12 | Evolver setting-switch class                                                    | `org.evolution.settings.preferences.SystemSettingSwitchPreference`                                              | Correct FQCN for toggle                                                             |

Anything not in this table that v1 mentioned (`KeyguardStatusView`, `KeyguardClockSwitch` paths from the depth section) **could not be located at v1's stated paths** — treat as VERIFY-FIRST (§9.6, §10).

---

## 6. Core Architecture Principles

1. **Zero Xposed imports in the engine.** `NothingAtmosphereEngine.kt` compiles unchanged inside the module (Stage 1) and inside `frameworks_base` (Stage 2).
2. **Pure-SDK GPU pipeline:** `RenderEffect` (API 31) + `RuntimeShader`/AGSL (API 33) + `RenderNode`/`HardwareRenderer` (public since API 29). All available on this device/ROM.
3. **One producer per `Surface`.** When the morph engine owns the wallpaper surface, nothing else may draw to it (and vice versa). Own the lowest draw path, not an intermediate one (R3).
4. **No per-unlock disk writes / no wallpaper-system churn** — visual morph only (R6).
5. **Every hook is name-based → every hook must be `firstMethodOrNull`-guarded + logged**, and the module ships a **member dumper** so a ROM update can be adapted in one logcat read (R4).
6. **The feature is toggle-driven** via `Settings.System`: `"nothing_atmosphere_unlock_enabled"`. Stage 1 already reads this key, so the Evolver switch works before any source change.
7. **Fail-open:** any exception in a hook path falls back to `callOriginal()` — a broken effect must never crash SystemUI.

---

## 7. `NothingAtmosphereEngine.kt` (final — single version, no iterations)

Features vs v1: fixed AGSL `half/float` typing (R5), `WallpaperColors`-driven color-mesh blobs, persistent `HardwareRenderer` (zero per-frame allocation), blur **settle-down** + one-shot present-time auto-tune after unlock, screen-off teardown, removed the `WallpaperManager` bake path, removed `HandlerThread` (unused), removed `Choreographer`/`SurfaceControl`/`PixelFormat` imports (unused), `Canvas` instead of the deprecated-ish `RecordingCanvas` type in local val.

```kotlin
package com.android.systemui.nothing.atmosphere

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.WallpaperColors
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.SystemClock
import android.view.Surface
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.max
import kotlin.random.Random

/**
 * Pure Android-SDK engine. No Xposed, no framework-private imports.
 * Two output modes:
 *  - View mode   : attachable RenderEffect chain on any View (Scrim prototyping).
 *  - Surface mode: persistent HardwareRenderer + RenderNode owning the wallpaper Surface.
 */
class NothingAtmosphereEngine {

    companion object {
        /** Peak Gaussian radius during the unlock transition. Auto-tuned down by the present-time guard. */
        var peakBlurRadius = 110f

        /** Radius kept alive while settled on the home screen (idle GPU cost guard). */
        const val settledBlurRadius = 28f

        private const val TAG = "EvoAtmo"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var surfaceAnimator: ValueAnimator? = null
    private var viewAnimator: ValueAnimator? = null

    private val nothingInterpolator = PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f)

    // ---- Persistent GPU pipeline (zero allocation per frame) ----
    private var hardwareRenderer: HardwareRenderer? = null
    private var contentNode: RenderNode? = null
    private var boundSurface: Surface? = null
    private var cachedWidth = 0
    private var cachedHeight = 0

    // ---- Mesh seed (mutates on every unlock) ----
    private var currentSeedX = 0.25f
    private var currentSeedY = 0.75f
    private var targetSeedX = 0.65f
    private var targetSeedY = 0.35f
    private var currentSwirlAngle = 0f

    // ---- WallpaperColors-driven mesh palette ----
    private var palette: List<FloatArray> = emptyList()   // each = [r,g,b] in 0..1
    private var meshStrength = 0f                          // 0 = palette invalid → shader skips mesh mix

    // ---- Transition state ----
    var currentFraction: Float = 0f
        private set

    private val agslSource = """
        uniform shader inputShader;
        uniform float2 resolution;
        uniform float progress;
        uniform float2 seedOffset;
        uniform float swirlAngle;
        uniform float meshStrength;
        uniform float3 blobColor0; uniform float2 blobPos0; uniform float blobRad0;
        uniform float3 blobColor1; uniform float2 blobPos1; uniform float blobRad1;
        uniform float3 blobColor2; uniform float2 blobPos2; uniform float blobRad2;

        half4 main(float2 coord) {
            float2 uv = coord / resolution;

            // Organic UV swirl (seed randomized per unlock)
            float2 centered = uv - seedOffset;
            float dist = length(centered);
            float angle = swirlAngle * progress * exp(-dist * 2.2);
            float s = sin(angle);
            float c = cos(angle);
            float2 warped = float2(centered.x * c - centered.y * s,
                                   centered.x * s + centered.y * c) + seedOffset;

            // Low-frequency sine displacement (mesh diffusion)
            warped.x += sin(uv.y * 4.5 + seedOffset.x * 6.2831) * 0.14 * progress;
            warped.y += cos(uv.x * 4.5 + seedOffset.y * 6.2831) * 0.14 * progress;

            float2 sampleUv = clamp(warped, float2(0.02), float2(0.98));
            half4 base = inputShader.eval(sampleUv * resolution);

            // Luminance-preserving saturation boost (all-half: satisfies SkSL typing)
            float luma = dot(base.rgb, half3(0.2126, 0.7152, 0.0722));
            half sat = half(1.0 + 0.42 * progress);
            half3 vibrant = mix(half3(half(luma)), base.rgb, sat);

            // WallpaperColors-driven soft color-mesh blend
            float2 d0 = uv - blobPos0;
            float g0 = exp(-dot(d0, d0) / max(blobRad0 * blobRad0, 1e-6));
            float2 d1 = uv - blobPos1;
            float g1 = exp(-dot(d1, d1) / max(blobRad1 * blobRad1, 1e-6));
            float2 d2 = uv - blobPos2;
            float g2 = exp(-dot(d2, d2) / max(blobRad2 * blobRad2, 1e-6));
            float wsum = g0 + g1 + g2;
            if (wsum > 0.0001 && meshStrength > 0.0) {
                float3 avg = (blobColor0 * g0 + blobColor1 * g1 + blobColor2 * g2) / wsum;
                float strength = meshStrength * progress * min(wsum * 3.0, 1.0);
                vibrant = mix(vibrant, half3(avg), half(strength * 0.55));
            }
            return half4(vibrant, base.a);
        }
    """.trimIndent()

    /** Compiles once; falls back to blur-only if AGSL fails on this vendor driver (R5). */
    private val atmosphereShader: RuntimeShader? = try {
        RuntimeShader(agslSource)
    } catch (t: Throwable) {
        android.util.Log.w(TAG, "AGSL compile failed, degrading to blur-only", t)
        null
    }

    // ---------------------------------------------------------------- seeds

    /** Call at the exact moment the keyguard begins going away → fresh morph per unlock. */
    fun randomizeUnlockSeed() {
        currentSeedX = targetSeedX
        currentSeedY = targetSeedY
        targetSeedX = Random.nextFloat() * 0.6f + 0.2f
        targetSeedY = Random.nextFloat() * 0.6f + 0.2f
        currentSwirlAngle = (Random.nextFloat() - 0.5f) * 4.8f
        if (palette.isNotEmpty()) {
            paletteBlob0 = pickRandomBlob()
            paletteBlob1 = pickRandomBlob()
            paletteBlob2 = pickRandomBlob()
        }
    }

    private var paletteBlob0 = FloatArray(6) // [r,g,b, x,y,radius]
    private var paletteBlob1 = FloatArray(6)
    private var paletteBlob2 = FloatArray(6)

    private fun pickRandomBlob(): FloatArray {
        val c = palette[Random.nextInt(palette.size)]
        return floatArrayOf(
            c[0], c[1], c[2],
            Random.nextFloat() * 0.6f + 0.2f,
            Random.nextFloat() * 0.6f + 0.2f,
            0.22f + Random.nextFloat() * 0.25f
        )
    }

    /** Feed once per wallpaper change. Null/empty palette disables the mesh mix. */
    fun setWallpaperColors(colors: WallpaperColors?) {
        val mains = try { colors?.mainColors } catch (t: Throwable) { null } ?: emptyList()
        palette = mains.take(3).map { col ->
            floatArrayOf(Color.red(col) / 255f, Color.green(col) / 255f, Color.blue(col) / 255f)
        }
        meshStrength = if (palette.size >= 3) 1f else 0f
    }

    // ---------------------------------------------------------------- effect

    fun buildAtmosphereRenderEffect(width: Float, height: Float, fraction: Float, settled: Boolean): RenderEffect? {
        if (fraction <= 0.005f) return null

        val peak = if (settled) settledBlurRadius else peakBlurRadius
        val blurRadius = (fraction * peak).coerceIn(0.5f, peak)

        atmosphereShader?.let { shader ->
            val activeX = currentSeedX + (targetSeedX - currentSeedX) * fraction
            val activeY = currentSeedY + (targetSeedY - currentSeedY) * fraction
            shader.setFloatUniform("resolution", width, height)
            shader.setFloatUniform("progress", fraction)
            shader.setFloatUniform("seedOffset", activeX, activeY)
            shader.setFloatUniform("swirlAngle", currentSwirlAngle)
            val b0 = paletteBlob0; val b1 = paletteBlob1; val b2 = paletteBlob2
            shader.setFloatUniform("meshStrength", meshStrength)
            shader.setFloatUniform("blobColor0", b0[0], b0[1], b0[2])
            shader.setFloatUniform("blobPos0", b0[3], b0[4]); shader.setFloatUniform("blobRad0", b0[5])
            shader.setFloatUniform("blobColor1", b1[0], b1[1], b1[2])
            shader.setFloatUniform("blobPos1", b1[3], b1[4]); shader.setFloatUniform("blobRad1", b1[5])
            shader.setFloatUniform("blobColor2", b2[0], b2[1], b2[2])
            shader.setFloatUniform("blobPos2", b2[3], b2[4]); shader.setFloatUniform("blobRad2", b2[5])
        }

        val blurEffect = RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.MIRROR)

        val meshEffect = atmosphereShader
            ?.let { RenderEffect.createRuntimeShaderEffect(it, "inputShader") }
            ?: RenderEffect.createBlurEffect(blurRadius * 1.05f, blurRadius * 1.05f, Shader.TileMode.MIRROR)

        // Chain: warp UVs into fluid blobs (inner) → diffuse (outer). Falls back to stronger blur w/o AGSL.
        return RenderEffect.createChainEffect(blurEffect, meshEffect)
    }

    // ---------------------------------------------------------------- View mode

    /** One-shot unlock pulse on a View (e.g. ScrimView). Ends with the effect removed. */
    fun pulseView(targetView: View?, durationMs: Long = 650L) {
        if (targetView == null) return
        randomizeUnlockSeed()
        viewAnimator?.cancel()
        val w = max(targetView.width, 1).toFloat()
        val h = max(targetView.height, 1).toFloat()
        viewAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = nothingInterpolator
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                try {
                    targetView.setRenderEffect(
                        buildAtmosphereRenderEffect(w, h, fraction, settled = false)
                    )
                } catch (t: Throwable) {
                    targetView.setRenderEffect(null)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = run { targetView.setRenderEffect(null) }
            })
            start()
        }
    }

    /** Continuous driver for View mode (e.g. shade-expansion progress). */
    fun applyViewFraction(targetView: View?, fraction: Float) {
        if (targetView == null) return
        if (fraction <= 0.01f) { targetView.setRenderEffect(null); return }
        try {
            targetView.setRenderEffect(
                buildAtmosphereRenderEffect(
                    max(targetView.width, 1).toFloat(),
                    max(targetView.height, 1).toFloat(),
                    fraction, settled = false
                )
            )
        } catch (t: Throwable) { targetView.setRenderEffect(null) }
    }

    // ---------------------------------------------------------------- Surface mode

    fun renderFrame(surface: Surface, bitmap: Bitmap, width: Int, height: Int, fraction: Float) {
        if (!surface.isValid || bitmap.isRecycled) return
        if (fraction <= 0f && currentFraction <= 0f) return   // nothing to do while locked/sharp

        if (boundSurface != surface || contentNode == null || width != cachedWidth || height != cachedHeight) {
            bindSurfaceAndBitmap(surface, bitmap, width, height)
        }
        val node = contentNode ?: return
        val renderer = hardwareRenderer ?: return

        node.setRenderEffect(
            buildAtmosphereRenderEffect(width.toFloat(), height.toFloat(), fraction, settled = false)
        )
        renderer.createRenderRequest()
            .setWaitForPresent(false)      // non-blocking → VSYNC-paced, 120 Hz friendly
            .syncAndDraw()
        currentFraction = fraction.coerceIn(0f, 1f)
    }

    private fun bindSurfaceAndBitmap(surface: Surface, bitmap: Bitmap, width: Int, height: Int) {
        if (!surface.isValid || bitmap.isRecycled) return
        if (hardwareRenderer == null || boundSurface != surface) {
            releaseSurfaceResources()
            boundSurface = surface
            hardwareRenderer = HardwareRenderer().apply {
                setSurface(surface)
                setLightSourceAlpha(0f, 0f)
                setLightSourceGeometry(0f, 0f, 0f, 0f)
            }
        }
        cachedWidth = width; cachedHeight = height
        val node = RenderNode("AtmosphereWallpaperNode").apply {
            setPosition(0, 0, width, height)
        }
        hardwareRenderer?.setContentRoot(node)
        contentNode = node

        // Record the bitmap ONCE; only uniforms change per frame afterwards.
        val canvas: Canvas = node.beginRecording(width, height)
        canvas.drawBitmap(bitmap, null, android.graphics.Rect(0, 0, width, height), null)
        node.endRecording()
    }

    fun releaseSurfaceResources() {
        surfaceAnimator?.cancel(); surfaceAnimator = null
        hardwareRenderer?.destroy()
        hardwareRenderer = null
        contentNode = null
        boundSurface = null
        cachedWidth = 0; cachedHeight = 0
    }

    /** Called when the display turns off / the wallpaper surface is hidden (AOD, fullscreen apps). */
    fun setScreenActive(active: Boolean) {
        if (!active) {
            surfaceAnimator?.cancel()
            releaseSurfaceResources()   // frees the GPU renderer while invisible; rebinds on next frame
        }
    }

    // ---------------------------------------------------------------- transitions

    /**
     * Animates the wallpaper Surface 0↔1 with the Nothing easing curve.
     * On unlock-end, settles once with a reduced radius + a one-shot present-time auto-tune.
     */
    fun animateSurfaceTransition(
        surface: Surface,
        bitmap: Bitmap,
        width: Int,
        height: Int,
        toUnlockedState: Boolean,
        durationMs: Long = 720L
    ) {
        mainHandler.post {
            if (!surface.isValid) return@post
            surfaceAnimator?.cancel()

            val startFraction = currentFraction
            val endFraction = if (toUnlockedState) 1f else 0f

            if (toUnlockedState && startFraction <= 0.05f) randomizeUnlockSeed()

            bindSurfaceAndBitmap(surface, bitmap, width, height)

            surfaceAnimator = ValueAnimator.ofFloat(startFraction, endFraction).apply {
                duration = durationMs
                interpolator = nothingInterpolator
                addUpdateListener { anim ->
                    val fraction = anim.animatedValue as Float
                    renderFrame(surface, bitmap, width, height, fraction)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (toUnlockedState) settleAfterUnlock(surface, bitmap, width, height)
                    }
                })
                start()
            }
        }
    }

    /** Settle: persist a cheaper idle state + measure present cost once per unlock, auto-tune radius. */
    private fun settleAfterUnlock(surface: Surface, bitmap: Bitmap, width: Int, height: Int) {
        mainHandler.post {
            val node = contentNode ?: return@post
            val renderer = hardwareRenderer ?: return@post
            if (!surface.isValid || currentFraction <= 0.01f) return@post

            node.setRenderEffect(
                buildAtmosphereRenderEffect(width.toFloat(), height.toFloat(), 1f, settled = true)
            )
            val t0 = SystemClock.elapsedRealtime()
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()   // blocking, ONCE per unlock
            val presentMs = SystemClock.elapsedRealtime() - t0

            if (presentMs > 22L && peakBlurRadius > 55f) {
                peakBlurRadius = (peakBlurRadius * 0.6f).coerceAtLeast(55f)
                android.util.Log.i(TAG, "Auto-tuned peakBlurRadius -> $peakBlurRadius (present=$presentMs ms)")
            }
        }
    }
}
```

> Required imports you'll add in-file (already listed above): `android.os.Handler`, `android.os.Looper`. The `Handler(Looper.getMainLooper())` pair replaces v1's stray `mainHandler`/`renderThread` mixture.

---

## 8. Stage 1 — Live Prototype Module (Vector / LSPosed + YukiHookAPI)

### 8.1 Gradle dependencies (`build.gradle.kts`)

```kotlin
dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    implementation("com.highcapable.yukihookapi:api:1.2.1")
    ksp("com.highcapable.yukihookapi:ksp-xposed:1.2.1")
}
```

(1.2.1 is the current Maven release — verified. Module namespace used below: `dev.pong.evo.atmosphere`.)

> The upstream project renamed **YukiHookAPI → YukiHook** (see §3); these exact coordinates remain valid, but the renamed project's newer rewrite uses different entry-point APIs — stay on 1.2.1 for the code below, or port §8.3 when you upgrade deliberately.

### 8.2 Scope

In the Vector/LSPosed manager, scope the module to **`com.android.systemui`** only. Do not request other apps. (Optionally add manifest meta-data `xposedscope` = `com.android.systemui` so the scope is pre-selected.)

### 8.3 `HookEntry.kt` — full, verified-anchor version

```kotlin
package dev.pong.evo.atmosphere.hook

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.Settings
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import android.view.View
import com.android.systemui.nothing.atmosphere.NothingAtmosphereEngine
import de.robv.android.xposed.XposedBridge
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.*
import com.highcapable.yukihookapi.hook.type.java.BooleanType
import com.highcapable.yukihookapi.hook.type.java.FloatType
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import java.io.File
import kotlin.math.abs

@InjectYukiHookWithXposed
class HookEntry : IYukiHookXposedInit {

    companion object {
        const val TAG = "EvoAtmo"
        const val SETTING_KEY = "nothing_atmosphere_unlock_enabled"

        // Verified hook targets (see §5 of the spec):
        const val SCRIM_CONTROLLER = "com.android.systemui.statusbar.phone.ScrimController"
        const val IMAGE_WALLPAPER_ENGINE = "com.android.systemui.wallpapers.ImageWallpaper\$CanvasEngine"

        /** PROTOTYPE_MODE switches the demo flavor without changing engine code. */
        val MODE = Mode.WALLPAPER_MORPH           // start with VIEW_BLUR for your first run

        enum class Mode { VIEW_BLUR, WALLPAPER_MORPH }
    }

    private var engine: NothingAtmosphereEngine? = null
    private var activeSurface: SurfaceHolder? = null
    private var activeBitmap: Bitmap? = null
    private var lastScrubFraction = -1f

    override fun onInit() = configs { isDebug = true }

    private fun isEnabled(): Boolean = appContext?.let {
        try {
            Settings.System.getInt(it.contentResolver, SETTING_KEY, 1) == 1
        } catch (t: Throwable) { true }
    } ?: true

    private fun log(msg: String) { XposedBridge.log("[$TAG] $msg") }

    /** Runtime member dumper — adapt to ROM updates in one logcat read (risk R4). */
    private fun dumpMembers(className: String) {
        val clazz = className.toClassOrNull()
        if (clazz == null) { log("dump: $className NOT FOUND"); return }
        val fields = clazz.declaredFields.joinToString(", ") { it.name }
        val methods = clazz.declaredMethods.joinToString(" | ") {
            "${it.name}(${it.parameterTypes.joinToString(",") { p -> p.simpleName }})"
        }
        log("dump fields of $className:\n$fields")
        log("dump methods of $className:\n$methods")
    }

    override fun onHook() = encase {
        loadApp(name = "com.android.systemui") {

            // Screen-off/teardown guard (S9): free the GPU renderer when the surface is hidden.
            appContext?.registerReceiver(
                object : android.content.BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                            engine?.setScreenActive(false)
                        }
                    }
                },
                IntentFilter(Intent.ACTION_SCREEN_OFF)
            )

            // Optionally dump members during first bring-up:
            // dumpMembers(SCRIM_CONTROLLER)
            // dumpMembers(IMAGE_WALLPAPER_ENGINE)

            /**
             * V2/V4 — ScrimController triggers + optional continuous scrub driver.
             * Verified signatures live in this class on branch cnb.
             */
            SCRIM_CONTROLLER.toClass().apply {

                // V2 — unlock trigger
                resolve().firstMethodOrNull {
                    name = "onKeyguardFadingAwayChanged"; emptyParam()
                }?.hook {
                    after {
                        try {
                            if (!isEnabled()) return@after
                            val scrim = instance.resolve()
                                .firstFieldOrNull { name = "mScrimBehind" }?.get<View>()
                            when (MODE) {
                                Mode.VIEW_BLUR ->
                                    (engine ?: NothingAtmosphereEngine().also { engine = it })
                                        .pulseView(scrim, 650L)
                                Mode.WALLPAPER_MORPH -> activeSurface?.let { holder ->
                                    activeBitmap?.let { bmp ->
                                        val frame = holder.surfaceFrame
                                        (engine ?: NothingAtmosphereEngine().also { engine = it })
                                            .animateSurfaceTransition(
                                                holder.surface, bmp,
                                                frame.width(), frame.height(), true, 720L
                                            )
                                    }
                                }
                            }
                        } catch (t: Throwable) { log("fadingAway hook error: $t") }
                    }
                } ?: log("onKeyguardFadingAwayChanged NOT FOUND — run dumpMembers")

                // V3 — lock trigger (keyguard became visible again → settle back to sharp)
                resolve().firstMethodOrNull {
                    name = "onKeyguardVisibilityChanged"; param(BooleanType)
                }?.hook {
                    after {
                        try {
                            val visible = args(0).boolean()
                            if (visible && isEnabled() && MODE == Mode.WALLPAPER_MORPH
                                && (engine?.currentFraction ?: 0f) > 0.02f) {
                                activeSurface?.let { holder ->
                                    activeBitmap?.let { bmp ->
                                        val frame = holder.surfaceFrame
                                        engine?.animateSurfaceTransition(
                                            holder.surface, bmp,
                                            frame.width(), frame.height(), false, 450L
                                        )
                                    }
                                }
                            }
                            if (visible && MODE == Mode.VIEW_BLUR) {
                                instance.resolve()
                                    .firstFieldOrNull { name = "mScrimBehind" }?.get<View>()
                                    ?.setRenderEffect(null)
                            }
                        } catch (t: Throwable) { log("visibility hook error: $t") }
                    }
                } ?: log("onKeyguardVisibilityChanged NOT FOUND — run dumpMembers")

                // V4 — continuous scrub (only meaningful in MORPH mode; throttled)
                if (MODE == Mode.WALLPAPER_MORPH) {
                    resolve().firstMethodOrNull {
                        name = "setTransitionToFullShadeProgress"; param(FloatType, FloatType)
                    }?.hook {
                        after {
                            try {
                                if (!isEnabled()) return@after
                                val progress = args(0).float()
                                if (abs(progress - lastScrubFraction) < 0.02f) return@after
                                lastScrubFraction = progress
                                val holder = activeSurface ?: return@after
                                val bmp = activeBitmap ?: return@after
                                val frame = holder.surfaceFrame
                                (engine ?: NothingAtmosphereEngine().also { engine = it }).renderFrame(
                                    holder.surface, bmp,
                                    frame.width(), frame.height(),
                                    progress.coerceIn(0f, 1f)
                                )
                            } catch (t: Throwable) { log("scrub hook error: $t") }
                        }
                    } ?: log("setTransitionToFullShadeProgress NOT FOUND — run dumpMembers")
                }
            }

            /**
             * V5/V6 — Wallpaper surface ownership (MORPH mode only).
             * replaceAny on the lowest verified draw path:
             *   remaining internal paths drawFrame()/drawFrameSynchronized()/drawFrameInternal()
             *   call drawFrameOnCanvas in-stock trees; verify with dumpMembers on your build.
             */
            if (MODE == Mode.WALLPAPER_MORPH) {
                IMAGE_WALLPAPER_ENGINE.toClassOrNull()?.apply {
                    resolve().firstMethodOrNull {
                        name = "drawFrameOnCanvas"; param(Bitmap::class.java)
                    }?.hook {
                        replaceAny {
                            try {
                                val bmp = args(0).cast<Bitmap>()
                                    ?: return@replaceAny callOriginal()
                                val engineInstance = instance as WallpaperService.Engine
                                val holder = engineInstance.surfaceHolder   // public API — no reflection needed
                                activeSurface = holder
                                activeBitmap = bmp
                                if (!isEnabled()) return@replaceAny callOriginal()

                                val eng = engine ?: NothingAtmosphereEngine().also { engine = it }
                                val frame = holder.surfaceFrame
                                // Draw the CURRENT fraction: 0 = sharp lockscreen, 1 = morphed/settled home
                                eng.renderFrame(holder.surface, bmp, frame.width(), frame.height(), eng.currentFraction)
                                null
                            } catch (t: Throwable) {
                                log("drawFrameOnCanvas hook error: $t")
                                callOriginal()
                            }
                        }
                    } ?: log("drawFrameOnCanvas NOT FOUND — run dumpMembers")

                    resolve().firstMethodOrNull { name = "onSurfaceDestroyed" }?.hook {
                        after {
                            engine?.releaseSurfaceResources()
                            activeSurface = null
                            activeBitmap = null
                        }
                    }
                } ?: log("ImageWallpaper\$CanvasEngine NOT FOUND — check FQCN against your tree")
            }
        }
    }
}
```

> Note: the one engine instance lives in the `var engine: NothingAtmosphereEngine?` field and is lazily created with `engine ?: NothingAtmosphereEngine().also { engine = it }` everywhere, so every hook path shares one persistent GPU pipeline.

### 8.4 Palette feed (Stage 1 optional one-liner)

Inside `loadApp`, after engine init:

```kotlin
appContext?.let { ctx ->
    android.app.WallpaperManager.getInstance(ctx)
        .getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM)
        ?.let { engine?.setWallpaperColors(it) }
}
```

### 8.5 Depth-effect foreground asset (Stage 1)

```bash
# Pre-processed cutout PNG (subject on transparency, see §10):
adb push subject_cutout.png /sdcard/
adb shell su -c "mkdir -p /data/data/dev.pong.evo.atmosphere/files && \
cp /sdcard/subject_cutout.png /data/data/dev.pong.evo.atmosphere/files/fg_subject.png"
```

```kotlin
private fun loadForegroundCutout(): Bitmap? = appContext?.let {
    val f = File(it.filesDir, "fg_subject.png")
    if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
}
```

### 8.6 Hot-reload iteration loop

→ Moved to **[`operations.md`](operations.md) §2 "Daily Stage-1 Loop"** — the build → install → SystemUI-restart cycle, expected screen behavior (≈1 s black blink), log watching (`EvoAtmo|YukiHook|Vector|LSPosed`), and the crash-buffer check.

### 8.7 Runtime toggle (no reinstall)

→ The live `adb shell settings put system nothing_atmosphere_unlock_enabled 1|0` switch and the full command cheat sheet live in **[`operations.md`](operations.md) §2/§7**. The module reads the key at runtime (§6 principle 6), so toggling needs no reinstall.

---

## 9. Stage 2 — Native Integration into Evolution X source

### 9.1 Placement

```
frameworks/base/packages/SystemUI/src/com/android/systemui/nothing/atmosphere/NothingAtmosphereEngine.kt
```

### 9.2 Settings flag (`frameworks/base/core/java/android/provider/Settings.java`, `System` block)

```java
/**
 * Nothing OS-style atmosphere unlock blur + color-mesh wallpaper morph.
 * @hide
 */
public static final String NOTHING_ATMOSPHERE_UNLOCK_ENABLED =
        "nothing_atmosphere_unlock_enabled";
```

### 9.3 `ScrimController.java` wiring (VIEW-blur variant — matches verified V1–V4)

```java
// imports
import com.android.systemui.nothing.atmosphere.NothingAtmosphereEngine;
import android.os.UserHandle;
import android.provider.Settings;

// field
private NothingAtmosphereEngine mAtmosphereEngine;

private boolean isAtmosphereEnabled() {
    return Settings.System.getIntForUser(mContext.getContentResolver(),
            Settings.System.NOTHING_ATMOSPHERE_UNLOCK_ENABLED /* @hide key */,
            1, UserHandle.USER_CURRENT) == 1;
}

// inside the existing (verified) callback methods — do NOT create new lifecycle paths:
@Override
public void onKeyguardFadingAwayChanged() {
    // ...existing Evo code stays first...
    if (isAtmosphereEnabled() && isKeyguardFadingAway()) {
        if (mAtmosphereEngine == null) mAtmosphereEngine = new NothingAtmosphereEngine();
        mAtmosphereEngine.pulseView(mScrimBehind, 650L);
    }
}

@Override
public void onKeyguardVisibilityChanged(boolean visible) {
    // ...existing Evo code stays first...
    if (visible && mAtmosphereEngine != null) mScrimBehind.setRenderEffect(null);
}

// optional: inside setTransitionToFullShadeProgress(float progress, float lockScreenNotificationsProgress)
//   → mAtmosphereEngine.applyViewFraction(mScrimBehind, progress) when enabled
```

Notes:

- In this branch `ScrimController` is Java and already consumes `KeyguardStateController` — reuse whatever controller reference the class already has instead of introducing a new dependency.
- For the **morph variant**, the wiring lives in `ImageWallpaper` (§9.4) instead, and ScrimController only triggers the transition callbacks.

### 9.4 `ImageWallpaper.java` routing (MORPH variant)

- Verified today: the engine draws through `CanvasEngine.drawFrameOnCanvas(Bitmap)` (V5) and stock internal paths `drawFrame()/drawFrameSynchronized()/drawFrameInternal()` (V6) — the stock file has **no** `RenderEffect`/`HardwareRenderer` usage (V7).
- Rule (R3): when the atmosphere toggle is ON, **every** wallpaper frame goes through our `HardwareRenderer`; when OFF, stock `lockHardwareCanvas` path stays untouched. Never let both share the `Surface` concurrently.

```java
// In class CanvasEngine:
private NothingAtmosphereEngine mAtmosphereEngine;
private boolean mIsKeyguardShowing = true;

// onCreate(SurfaceHolder): (before stock draw setup)
mAtmosphereEngine = new NothingAtmosphereEngine();

@Override
public void onVisibilityChanged(boolean visible) {
    super.onVisibilityChanged(visible);
    if (mAtmosphereEngine != null) mAtmosphereEngine.setScreenActive(visible);
    // also: re-read the toggle when the engine becomes visible
}

@Override
public void onSurfaceDestroyed(SurfaceHolder holder) {
    super.onSurfaceDestroyed(holder);
    if (mAtmosphereEngine != null) mAtmosphereEngine.releaseSurfaceResources();
}

// NOTE: verify the exact visibility-change method names in YOUR tree (grep in §9.6);
// WallpaperService.Engine provides onVisibilityChanged(boolean) as a standard hook.
```

And in `drawFrameOnCanvas(Bitmap bitmap)`:

```java
if (isAtmosphereEnabled()) {
    float targetFraction = mIsKeyguardShowing ? 0.0f : 1.0f;
    Surface surface = getSurfaceHolder().getSurface();
    Rect frame = getSurfaceHolder().getSurfaceFrame();
    if (surface != null && surface.isValid()) {
        mAtmosphereEngine.renderFrame(surface, bitmap, frame.width(), frame.height(), targetFraction);
        return;   // we own the surface for this frame — skip stock lockHardwareCanvas path
    }
}
// ...stock Evo drawing path unchanged...
```

Transition triggers in native mode: register `KeyguardStateController.Callback` (V10) at `CanvasEngine` init — `onKeyguardFadingAwayChanged()` → `animateSurfaceTransition(..., true, 720L)`; `onKeyguardShowingChanged()` with `isShowing()==true` → `animateSurfaceTransition(..., false, 450L)`. If your tree requires additional callback methods in the interface, implement them as no-ops.

The palette feed runs natively at wallpaper-apply time:

```java
mAtmosphereEngine.setWallpaperColors(WallpaperManager.getInstance(context)
        .getWallpaperColors(WallpaperManager.FLAG_SYSTEM));
```

### 9.5 Evolver toggle (corrected paths — V11/V12)

File: `packages/apps/Evolver/res/xml/evolution_settings_lock_screen.xml`

```xml
<org.evolution.settings.preferences.SystemSettingSwitchPreference
    android:key="nothing_atmosphere_unlock_enabled"
    android:title="Nothing Atmosphere Unlock Effect"
    android:summary="Animated blur + color-mesh wallpaper morph on every unlock"
    android:defaultValue="true" />
```

(Copy the exact attribute style/order used by neighboring preferences in that file; prefer `@string` resources in a patch you intend to upstream.)

### 9.6 VERIFY-FIRST locations (do this before trusting any class name)

```bash
cd frameworks/base/packages/SystemUI
# Lockscreen status/clock classes for the depth effect (§10):
grep -rn "class .*StatusView\|KeyguardClockSwitch" src
# Confirm wallpaper draw chain calls drawFrameOnCanvas in your tree:
grep -n "drawFrameOnCanvas(" src/com/android/systemui/wallpapers/ImageWallpaper.java
# Confirm ScrimController symbols:
grep -n "onKeyguardFadingAwayChanged\|onKeyguardVisibilityChanged\|setTransitionToFullShadeProgress\|mScrimBehind" \
  src/com/android/systemui/statusbar/phone/ScrimController.java
```

Then cross-check with the runtime dumper (`dumpMembers`) from §8.3 — the module log lists every actual field/method name on your exact ROM build.

---

## 10. Depth Effect (foreground subject above the lockscreen clock)

**Realistic scope:** real Nothing OS depth requires an on-device segmentation model. There is **no public AOSP API** for extracting a subject mask. Therefore this feature ships with **pre-processed cutout assets** (from ML Kit Subject Segmentation, or any photo editor with AI subject selection). That's an input-design problem, not a rendering problem — the rendering side is trivial.

**Rendering approach:** inject a transparent `FrameLayout` container above the clock layer in the lockscreen view hierarchy.

1. **Locate the hierarchy first** (§9.6 grep). The lockscreen classes renamed across Android reworks — hook/inject whatever the grep returns; do not hardcode v1's paths.
2. **XML (native)** — in the lockscreen root layout that grep identifies:

```xml
<FrameLayout
    android:id="@+id/nothing_depth_foreground_container"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:elevation="32dp"
    android:clickable="false"
    android:importantForAccessibility="no" />
```

3. **Controller** (`packages/SystemUI/src/com/android/systemui/nothing/depth/NothingDepthEffectController.kt` in Stage 2; same file in the module for Stage 1):

```kotlin
package com.android.systemui.nothing.depth

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

class NothingDepthEffectController(private val rootContainer: ViewGroup) {

    companion object { private val Ease = PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f) }

    private var foregroundImageView: ImageView? = null
    private var currentAnimator: ValueAnimator? = null

    fun attachForegroundLayer(foregroundBitmap: Bitmap?) {
        if (foregroundBitmap == null || foregroundImageView != null) return
        val imageView = ImageView(rootContainer.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(foregroundBitmap)
            elevation = 32f                                  // code API takes px
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        rootContainer.addView(imageView)
        foregroundImageView = imageView
    }

    /** 0 = locked (visible), 1 = unlocked (6% parallax zoom + fade out). */
    fun animateDepthOnUnlock(toUnlocked: Boolean, durationMs: Long = 720L) {
        val view = foregroundImageView ?: return
        currentAnimator?.cancel()
        val startAlpha = view.alpha
        val endAlpha = if (toUnlocked) 0f else 1f
        val startScale = view.scaleX
        val endScale = if (toUnlocked) 1.06f else 1f

        currentAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = Ease
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                view.alpha = startAlpha + (endAlpha - startAlpha) * f
                val scale = startScale + (endScale - startScale) * f
                view.scaleX = scale; view.scaleY = scale
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) =
                    run { if (toUnlocked) view.visibility = View.GONE else view.visibility = View.VISIBLE }
            })
            start()
        }
    }

    fun removeForegroundLayer() {
        currentAnimator?.cancel()
        foregroundImageView?.let { rootContainer.removeView(it) }
        foregroundImageView = null
    }
}
```

4. Drive it from the **same verified triggers** as the engine (`onKeyguardFadingAwayChanged` / `onKeyguardVisibilityChanged`) so blur + depth stay in sync. Settings key: `nothing_depth_foreground_enabled` (default `0`).

---

## 11. Stage 3 — Incremental Build & Systemless Overlay (signature-safe)

### 11.1 Build only SystemUI

```bash
source build/envsetup.sh
lunch | grep -i pong            # select the target that matches YOUR ROM's build-ID (§2.3)
m SystemUI -j$(nproc)
```

Output: `out/target/product/Pong/system_ext/priv-app/SystemUI/SystemUI.apk`

(Resource-only changes are picked up by the same target; no full OTA needed to iterate.)

### 11.2 Signing (risk R1 — mandatory step)

A systemless overlay with **different platform keys** than the installed ROM can abort package scanning at boot → SystemServer crash loop. Only proceed if you can sign with the **same platform key your ROM was built with**:

```bash
# Default AOSP-derived platform keypair used by your local ROM build (or substitute your release keys):
java -jar out/host/linux-x86/framework/signapk.jar \
    build/target/product/security/platform.x509.pem \
    build/target/product/security/platform.pk8 \
    out/target/product/Pong/system_ext/priv-app/SystemUI/SystemUI.apk \
    /data/local/tmp/SystemUI-signed.apk
```

- If your flashed ROM is an **official Evolution X CI build** (signed with their keys): **do not overlay your SystemUI APK.** Instead validate Stage-2 inside a ROM you build yourself with your own keys (§12), or keep Stage-1 module-only testing.
- Signing is additive: overlay MUST be signed, never raw.

### 11.3 Deploy the overlay module (systemless — zero partition writes)

```bash
adb push /data/local/tmp/SystemUI-signed.apk /data/local/tmp/

adb shell su -c "mkdir -p /data/adb/modules/evo_pong_systemui_test/system/system_ext/priv-app/SystemUI"
adb shell su -c "cp /data/local/tmp/SystemUI-signed.apk /data/adb/modules/evo_pong_systemui_test/system/system_ext/priv-app/SystemUI/SystemUI.apk"
adb shell su -c "chmod 644 /data/adb/modules/evo_pong_systemui_test/system/system_ext/priv-app/SystemUI/SystemUI.apk"

adb shell su -c "cat << 'EOF' > /data/adb/modules/evo_pong_systemui_test/module.prop
id=evo_pong_systemui_test
name=Evolution X Pong SystemUI Native Test
version=v2.0
versionCode=2
author=Gaurav
description=Systemless overlay for custom Evolution X SystemUI build (platform-key signed)
EOF"
adb reboot
```

### 11.4 Post-boot verification

→ Moved to **[`operations.md`](operations.md) §4** — the `dumpsys package` + logcat check after deploying the overlay, and §6 for the revert drill. Rule unchanged: if the device never reaches the launcher, the §4 disabler or Safe Mode reverts the overlay — nothing on disk was touched.

---

## 12. Full ROM Packaging (final distribution)

Once staged natively and verified, produce your full flashable zip with your own keys and flash **it** (not the overlay). The exact packaging target depends on the vendor tree version of your sync — check `vendor/evolution`/`README`s; historically `m evolution` (or the `brunch` wrapper). The device tree's `lineage_Pong.mk` + `evolution.dependencies` in `Evolution-X-Devices/device_nothing_Pong` define the product.

---

## 13. Risk Register & Mitigations

| #   | Risk                                                                                                                      | Impact                               | Likelihood                    | Mitigation (this spec)                                                                                             | Detection                        |
| --- | ------------------------------------------------------------------------------------------------------------------------- | ------------------------------------ | ----------------------------- | ------------------------------------------------------------------------------------------------------------------ | -------------------------------- |
| R1  | Overlayed `SystemUI.apk` signed with different platform key → package scan failure → SystemServer crash loop              | Bootloop                             | High if unmanaged             | Sign with the ROM's platform keys (§11.2); never overlay raw APKs; don't overlay onto CI-signed official ROMs      | `dumpsys package` + boot logcat  |
| R2  | SystemUI crash loop during hook development (black screen, no UI)                                                         | Unusable UI (recoverable)            | Medium                        | Fail-open hooks (`try/catch + callOriginal`), bootloop disabler armed, Safe Mode, wireless ADB                     | `logcat -b crash`                |
| R3  | Two producers on the wallpaper `Surface` (our `HardwareRenderer` vs stock `lockHardwareCanvas`) → flicker/black wallpaper | Visual corruption                    | Medium when hooks are partial | Own the **lowest** draw path (`drawFrameOnCanvas`), route all frames when enabled, release on `onSurfaceDestroyed` | Visual: flicker at unlock        |
| R4  | ROM update renames hooked members → silent no-op or exception                                                             | Feature stops working                | High over time                | `firstMethodOrNull` + error-only logs + built-in member dumper (§8.3); re-verify with Appendix C after every OTA   | dumper/logcat greps              |
| R5  | AGSL fails to compile on vendor driver (type issues)                                                                      | RuntimeException, crash if unhandled | Low–Med                       | All-half arithmetic in shader; `RuntimeShader` creation guarded → degrade to blur-only                             | engine log "AGSL compile failed" |
| R6  | `WallpaperManager.setBitmap()` per unlock → Monet recompute, ANR, flash wear                                              | Jank + storage damage                | Removed in v2                 | Morph is **visual-only** via `Surface`; never persist per unlock                                                   | N/A                              |
| R7  | Persistent 110 px blur on redraw-capable surface → GPU load / battery                                                     | Jank, battery                        | Medium                        | Settle-down to 28 px after unlock + one-shot present-time auto-tune (§7 `settleAfterUnlock`)                       | Perfetto trace                   |
| R8  | Hooks run during AOD/screen-off → wasted work                                                                             | Battery                              | Medium                        | Screen-off broadcast → `setScreenActive(false)` → renderer released; `renderFrame` early-returns when invisible    | Battery historian                |
| R9  | Built SystemUI against mismatched branch/build-ID vs flashed ROM | Crash on boot | Medium if sloppy | Version-match procedure (§2.3) + select lunch by grep — never hardcode | §11.4 dumpsys check |
| R10 | Partition corruption from direct `/system_ext` writes                                                                     | Brick-tier (avoided by design)       | Low                           | **Everything is systemless** (modules or full signed ROM); no `mount -o remount,rw` in the daily workflow          | N/A                              |
| R11 | Randomized seeds make regression-testing hard                                                                             | Flaky QA                             | Low                           | Fixed-seed debug flag: log `currentSeedX/Y/swirl` each unlock; optionally freeze seeds in debug builds             | logcat seeds                     |
| R12 | Vector/LSPosed scope regressions after Vector updates                                                                     | Module stops loading                 | Low                           | Scope only `com.android.systemui`; validate load via `logcat YukiHook/Vector` after Vector updates                 | logcat                           |

---

## 14. Performance & Battery Budget

- Unlock transition: 720 ms ≈ 86 frames @120 Hz; per-frame CPU work = 4–6 float uniform writes + `syncAndDraw()` submit; **no display-list re-recording** (bitmap recorded once per bind).
- Target: < 2 ms GPU per frame during transition on SM8475 at maximum 2400×1080.
- Settled state: 28 px blur only re-renders when the wallpaper engine actually redraws (scrolling/lifecycle) — negligible idle cost.
- Auto-tune: if one-shot present time > 22 ms, peak radius drops 40% (repeated over unlocks until it settles within budget).
- Memory: 1 display list + 1 GPU texture + uniforms ≈ a few MB. No offscreen bitmap copies, no per-frame allocations.

---

## 15. Test Plan (checklist)

→ The full 10-scenario checklist with pass criteria lives in **[`operations.md`](operations.md) §5 "Test Plan"**. Coverage: cold boot logs, fingerprint unlock/lock morph both directions, swipe-scrub tracking, toggle-off stock parity, AOD/screen-off teardown, wallpaper-change palette refresh, fullscreen app surface rebind, Perfetto frame budget, and the Stage-3 overlay boot + clean-revert cycle.

---

## 16. Debug & Rescue Cheat Sheet

→ All commands moved to **[`operations.md`](operations.md) §7 "Command Cheat Sheet"** — SystemUI restart, module/engine log greps, crash history, live toggle, Stage-3 overlay removal, and the full nuke (§4 disabler).

---

## 17. Links & References

**Evolution X / device**

- Device page & downloads: <https://evolution-x.org/device/Pong>
- Source org: <https://github.com/Evolution-X>
- `frameworks_base`: <https://github.com/Evolution-X/frameworks_base> (default `cnb`)
- The Evolver: <https://github.com/Evolution-X/packages_apps_Evolver>
- Device tree (`nothing-fwk`, `DeviceExtras`, RichTap vibrator): <https://github.com/Evolution-X-Devices/device_nothing_Pong>
- Update tracking (contains official A16/A17 Pong releases): <https://t.me/s/NothingPhone2updates>

**Xposed ecosystem**

- Vector (active successor of LSPosed; Android 8.1 → 17): <https://github.com/JingMatrix/Vector> (releases: <https://github.com/JingMatrix/Vector/releases>)
- LSPosed_mod (classic-UI community fork): <https://github.com/mywalkb/LSPosed_mod>
- YukiHook (**formerly YukiHookAPI** — repo renamed, verified 2026-10-24): <https://github.com/HighCapable/YukiHook> · Docs: <https://highcapable.github.io/YukiHook/en> (old `YukiHookAPI` doc URLs are 404)
- Root managers: Magisk <https://github.com/topjohnwu/Magisk> · KernelSU <https://github.com/tiann/KernelSU>

**Android platform / AOSP**

- AOSP Code Search (pin to your branch): <https://cs.android.com/>
- frameworks/base live tree: <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/>
- AGSL / RuntimeShader guide: <https://developer.android.com/develop/ui/views/graphics/agsl>
- API references: [RenderEffect](https://developer.android.com/reference/android/graphics/RenderEffect) · [RuntimeShader](https://developer.android.com/reference/android/graphics/RuntimeShader) · [RenderNode](https://developer.android.com/reference/android/graphics/RenderNode) · [HardwareRenderer](https://developer.android.com/reference/android/graphics/HardwareRenderer) · [WallpaperManager](https://developer.android.com/reference/android/app/WallpaperManager) · [WallpaperColors](https://developer.android.com/reference/android/app/WallpaperColors)

**Tooling**

- scrcpy (debug UI mirror): <https://github.com/Genymobile/scrcpy>
- Perfetto (jank tracing): <https://perfetto.dev>
- Android Studio / SDK: <https://developer.android.com/studio>

---

## Appendix A — Symbol Verification Log (2026-10-24, branch `cnb`)

Verified by direct source fetch of `Evolution-X/frameworks_base` @ `cnb`:

| File                                     | Evidence                                                                                                                                                                                                                                                              |
| ---------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `…/statusbar/phone/ScrimController.java` | `mScrimBehind` (59 refs); `public void onKeyguardFadingAwayChanged()`; `public void onKeyguardVisibilityChanged(boolean visible)`; `public void setTransitionToFullShadeProgress(float progress, float lockScreenNotificationsProgress)`; `private void applyState()` |
| `…/wallpapers/ImageWallpaper.java`       | `class CanvasEngine`; `void drawFrameOnCanvas(Bitmap bitmap)`; `private void drawFrame()/drawFrameSynchronized()/drawFrameInternal()`; draws via `lockHardwareCanvas()`; zero `RenderEffect`/`HardwareRenderer`/`SurfaceControl` usage in stock file                  |
| `…/keyguard/KeyguardViewMediator.java`   | `private void doKeyguardLocked(Bundle options)` + 1 overload; `keyguardGoingAway(` appears; **no public `onKeyguardGoingAway()`** (only `onKeyguardGoingAwayRemoteAnimationStart/Cancelled` invoked on other objects)                                                 |
| `packages_apps_Evolver`                  | `res/xml/evolution_settings_lock_screen.xml`; `src/org/evolution/settings/preferences/SystemSettingSwitchPreference.java`                                                                                                                                             |
| `device_nothing_Pong` (`bka`)            | `nothing-fwk/`, `DeviceExtras/`, `vibrator/`, `lineage_Pong.mk`, `evolution.dependencies` present                                                                                                                                                                     |
| Branch surfaces                          | devices: `bka`,`bka-old`,`bq1`,`cnb`; frameworks_base: `bka`,`cnb`,`cnb-bak`,`pie`,`snow`,`ten`,`tiramisu`,`udc`,`vic`                                                                                                                                                |
| Tooling                                  | YukiHookAPI latest = 1.2.1 (Maven; upstream project renamed **YukiHook** — docs at highcapable.github.io/YukiHook/en); Vector README: "Android 8.1 through Android 17"; `HardwareRenderer.createRenderRequest/setWaitForPresent/setFrameCommitCallback` public API confirmed in source                                                                   |

Not found at v1's claimed paths (moved across lockscreen reworks): `com.android.systemui.keyguard.ui.view.KeyguardStatusView`, `KeyguardClockSwitch` — resolve via §9.6 grep + runtime dumper before hooking.

## Appendix B — v1 → v2 Change Log

1. **Removed `KeyguardViewMediator.onKeyguardGoingAway` hooks** (method doesn't exist) → replaced with verified `ScrimController.onKeyguardFadingAwayChanged` / `onKeyguardVisibilityChanged` + `KeyguardStateController.Callback` (V2/V3/V10).
2. **Fixed Evolver integration**: correct XML file (`evolution_settings_lock_screen.xml`) and correct FQCN (`org.evolution.settings.preferences.SystemSettingSwitchPreference`).
3. **Removed the per-unlock `WallpaperManager.setBitmap()` bake path** (self-contradictory in v1) — morphing is visual-only on the live surface.
4. **Consolidated three successive engine versions into one final engine** with: fixed AGSL `half()` typing + compile fallback (blur-only), `WallpaperColors`-driven mesh blobs, persistent zero-allocation pipeline, blank-free unused imports, `Canvas` instead of `RecordingCanvas`, screen-off teardown, settle-down + present-time auto-tune.
5. **Added the member-dumper + `firstMethodOrNull` + fail-open policy** to survive ROM updates (R4) and to make every hook failure loud instead of silent.
6. **Added surface-ownership rules** to prevent dual-producer flicker (R3), including an explicit stock-vs-ours routing table for `ImageWallpaper`.
7. **Stage-1 now reads the real setting key** so the toggle is testable without a source change; added adb toggle + logcat cheat sheet.
8. **Stage-3 hardened**: explicit platform-key signing step, prohibition on overlaying CI-signed ROMs, post-boot verification commands.
9. **Version-match procedure** (§2.3) replaces the stale hardcoded lunch target; documented the real branch inventory and the build-ID ↔ branch mapping rule.
10. **Depth effect scoped honestly**: requires pre-processed cutout assets; valid XML elevation (`32dp`); VERIFY-FIRST instructions replaced assumed class paths.
11. **Docs restructured into a suite** (2026-10-24): environment/tooling detail → `setup.md`; fail-safe commands, hot-reload loop, toggle, post-boot checks, test plan §15, and cheat sheet §16 → `operations.md` (pointers left in place); plain-English concepts → `basics.md`; feature roadmap → `ideas.md`; repo index → `README.md`. Recorded upstream rename YukiHookAPI → YukiHook (repo + docs; Maven artifact 1.2.1 unchanged).

## Appendix C — Self-Verification Commands (run before every coding session)

```bash
# 1. Which branch does your ROM match?
adb shell getprop ro.build.fingerprint

# 2. Symbol spot-checks against your synced tree:
grep -n "onKeyguardFadingAwayChanged\|onKeyguardVisibilityChanged\|setTransitionToFullShadeProgress\|mScrimBehind" \
  frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/phone/ScrimController.java
grep -n "drawFrameOnCanvas(\|class CanvasEngine" \
  frameworks/base/packages/SystemUI/src/com/android/systemui/wallpapers/ImageWallpaper.java
grep -n "doKeyguardLocked" \
  frameworks/base/packages/SystemUI/src/com/android/systemui/keyguard/KeyguardViewMediator.java
grep -rn "KeyguardClockSwitch\|class .*StatusView" frameworks/base/packages/SystemUI/src

# 3. Runtime ground truth (after installing the Stage-1 module):
adb logcat -d | grep "EvoAtmo"        # dump lines list real field/method names on YOUR ROM
```

If any grep comes back empty on your tree, stop — resolve the actual names first (dumper + code search), because blind hooking is the #1 source of wasted reboots.
