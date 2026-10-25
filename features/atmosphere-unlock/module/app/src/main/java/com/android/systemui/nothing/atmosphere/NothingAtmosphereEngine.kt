/*
 * NothingAtmosphereEngine — the pure-SDK Atmosphere morph engine (spec §7).
 *
 * Stage-1 copy lives in the parity package `com.android.systemui.nothing.atmosphere`
 * (spec §3.1/§8.2) so the Stage-2 promotion to `features/atmosphere-unlock/engine/`
 * is a file move, not a rename. Zero Xposed/zero-framework-private imports: hosts
 * call it, it never learns its host (§3.2 invariant 1).
 *
 * Differences from the spec §7 text, deliberately minimal:
 *  - `settledBlurRadius` is a runtime-tunable `var` (spec §8.8 — the selection
 *    surface feeds it; host clamps before pushing).
 *  - `meshStrengthScale` companion multiplier drives the spec §8.8
 *    `nothing_atmosphere_mesh_strength` knob through the existing meshStrength
 *    uniform (no new engine channels beyond this one companion var).
 * Spec states the code is final — engine behavior must not fork per stage.
 */

package com.android.systemui.nothing.atmosphere

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.WallpaperColors
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.HardwareRenderer
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
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
        /** Peak Gaussian radius during the unlock transition. Auto-tuned down by the
         *  present-time guard. */
        var peakBlurRadius = 110f

        /** Radius kept alive while settled on the home screen (idle GPU cost guard).
         *  Runtime-tunable via `nothing_atmosphere_settled_radius` (spec §8.8) since the
         *  selection-surface decision opened it — must stay clamped; the idle-cost guard
         *  itself is unchanged (host reads the key and clamps before pushing). */
        var settledBlurRadius = 28f

        /**
         * Multiplier applied to the WallpaperColors mesh mix before it enters the shader
         * (spec §8.8 knob `nothing_atmosphere_mesh_strength`). 1f = the engine default
         * mix; 0f disables it; values > 1f exaggerate. The mesh gate inside the AGSL
         * source still keys off the (scaled) uniform, so an invalid palette stays off.
         */
        var meshStrengthScale = 1f

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
    private var meshStrength = 0f   // 0 = palette invalid → shader skips the mesh mix

    // ---- Transition state ----

    /** Current morph fraction (0 = sharp/locked, 1 = fully settled), driven by the
     *  surface animator; hook hosts re-render with this value on their draw path (V5). */
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

    /**
     * Feed once per wallpaper change. Reads the public WallpaperColors surface —
     * getPrimaryColor/getSecondaryColor/getTertiaryColor, each returning an
     * android.graphics.Color object (verified against the developer.android.com
     * reference + the android-36 SDK stub via javap on 2026-10-25; the spec §7
     * sketch called getMainColors(), which does not exist in the public SDK).
     * Three or more colors enable the mesh mix; fewer disable it.
     */
    fun setWallpaperColors(colors: WallpaperColors?) {
        val mains = try {
            listOfNotNull(
                colors?.primaryColor,
                colors?.secondaryColor,
                colors?.tertiaryColor
            )
        } catch (t: Throwable) {
            null
        } ?: emptyList()
        palette = mains.map { col ->
            floatArrayOf(
                col.red() / 255f,
                col.green() / 255f,
                col.blue() / 255f
            )
        }
        meshStrength = if (palette.size >= 3) 1f else 0f
    }

    // ---------------------------------------------------------------- effect

    /**
     * Build the composed effect for the current fraction: warm UV warp mesh (inner)
     * + blur (outer); the settled flag switches the radius source. Returns null when
     * no effect is needed (fraction ~0) so the caller can clear its RenderEffect.
     */
    fun buildAtmosphereRenderEffect(
        width: Float,
        height: Float,
        fraction: Float,
        settled: Boolean
    ): RenderEffect? {
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
            val b0 = paletteBlob0
            val b1 = paletteBlob1
            val b2 = paletteBlob2
            shader.setFloatUniform("meshStrength", meshStrength * meshStrengthScale)
            shader.setFloatUniform("blobColor0", b0[0], b0[1], b0[2])
            shader.setFloatUniform("blobPos0", b0[3], b0[4])
            shader.setFloatUniform("blobRad0", b0[5])
            shader.setFloatUniform("blobColor1", b1[0], b1[1], b1[2])
            shader.setFloatUniform("blobPos1", b1[3], b1[4])
            shader.setFloatUniform("blobRad1", b1[5])
            shader.setFloatUniform("blobColor2", b2[0], b2[1], b2[2])
            shader.setFloatUniform("blobPos2", b2[3], b2[4])
            shader.setFloatUniform("blobRad2", b2[5])
        }

        val blurEffect = RenderEffect.createBlurEffect(
            blurRadius, blurRadius, Shader.TileMode.MIRROR
        )

        val meshEffect = atmosphereShader
            ?.let { RenderEffect.createRuntimeShaderEffect(it, "inputShader") }
            ?: RenderEffect.createBlurEffect(
                blurRadius * 1.05f, blurRadius * 1.05f, Shader.TileMode.MIRROR
            )

        // Chain: warp UVs into fluid blobs (inner) → diffuse (outer).
        // Falls back to stronger blur w/o AGSL.
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
                override fun onAnimationEnd(animation: Animator) {
                    targetView.setRenderEffect(null)
                }
            })
            start()
        }
    }

    /** Continuous driver for View mode (e.g. shade-expansion progress). */
    fun applyViewFraction(targetView: View?, fraction: Float) {
        if (targetView == null) return
        if (fraction <= 0.01f) {
            targetView.setRenderEffect(null)
            return
        }
        try {
            targetView.setRenderEffect(
                buildAtmosphereRenderEffect(
                    max(targetView.width, 1).toFloat(),
                    max(targetView.height, 1).toFloat(),
                    fraction, settled = false
                )
            )
        } catch (t: Throwable) {
            targetView.setRenderEffect(null)
        }
    }

    // ---------------------------------------------------------------- Surface mode

    /**
     * Draw ONE frame in Surface mode: (re)bind the surface when needed, install the
     * current-fraction effect chain, submit non-blocking (VSYNC-paced, 120 Hz friendly).
     */
    fun renderFrame(surface: Surface, bitmap: Bitmap, width: Int, height: Int, fraction: Float) {
        if (!surface.isValid || bitmap.isRecycled) return
        if (fraction <= 0f && currentFraction <= 0f) return   // nothing to do while locked/sharp

        if (boundSurface != surface || contentNode == null ||
            width != cachedWidth || height != cachedHeight
        ) {
            bindSurfaceAndBitmap(surface, bitmap, width, height)
        }
        val node = contentNode ?: return
        val renderer = hardwareRenderer ?: return

        node.setRenderEffect(
            buildAtmosphereRenderEffect(
                width.toFloat(), height.toFloat(), fraction, settled = false
            )
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
        cachedWidth = width
        cachedHeight = height
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
        surfaceAnimator?.cancel()
        surfaceAnimator = null
        hardwareRenderer?.destroy()
        hardwareRenderer = null
        contentNode = null
        boundSurface = null
        cachedWidth = 0
        cachedHeight = 0
    }

    /** Called when the display turns off / the wallpaper surface is hidden
     *  (AOD, fullscreen apps). */
    fun setScreenActive(active: Boolean) {
        if (!active) {
            surfaceAnimator?.cancel()
            // Frees the GPU renderer while invisible; rebinds on the next frame.
            releaseSurfaceResources()
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

    /** Settle: persist a cheaper idle state + measure present cost once per unlock,
     *  auto-tune radius. */
    private fun settleAfterUnlock(surface: Surface, bitmap: Bitmap, width: Int, height: Int) {
        mainHandler.post {
            val node = contentNode ?: return@post
            val renderer = hardwareRenderer ?: return@post
            if (!surface.isValid || currentFraction <= 0.01f) return@post

            node.setRenderEffect(
                buildAtmosphereRenderEffect(
                    width.toFloat(), height.toFloat(), 1f, settled = true
                )
            )
            val t0 = SystemClock.elapsedRealtime()
            renderer.createRenderRequest()
                .setWaitForPresent(true)
                .syncAndDraw()   // blocking, ONCE per unlock
            val presentMs = SystemClock.elapsedRealtime() - t0

            if (presentMs > 22L && peakBlurRadius > 55f) {
                peakBlurRadius = (peakBlurRadius * 0.6f).coerceAtLeast(55f)
                android.util.Log.i(
                    TAG, "Auto-tuned peakBlurRadius -> $peakBlurRadius (present=$presentMs ms)"
                )
            }
        }
    }
}
