---
id: research-2026-10-25-atmo-engine-reference
aliases:
  - Atmo Engine research memo
  - NOSAtmosphereEffect architecture reference
tags:
  - android
  - rom
  - evo-atmo
  - research
---

# Atmo Engine (`NOSAtmosphereEffect`) — architecture and reusability as reference

Resolves wayfinder research ticket #3. Question: what does `saad-khan-rind/NOSAtmosphereEffect`
("Atmo Engine", MIT, Android 13+) actually implement, and what is reusable as **reference** for an
engine-based atmosphere implementation (not as a fork)?

Method: shallow-cloned the repository into the approved temp dir
(`/private/var/folders/zj/_dgmsz8d20x2ch2gvvvtkkcc0000gn/T/opencode/atmo-ref`) and read code
directly; used `git` history, GitHub releases, and the issue tracker for the quality/perf picture.
Nothing was cloned into, or committed to, the evoatmo tree.

**Reading conventions.** Unless a different ref is named, every path is cited at the repository
HEAD of tag `v7.4.2` (commit `5742b3b0`, dated 2026-10-21, GitHub "Latest" release) — what my
working clone checked out. `v7.3.7` = `fa096bc0` (2026-10-14). Every claim carries a
`[verified: <source> — 2026-10-25]` citation. Code comments quoted below are the project's own
words.

## Answer digest

- Atmo Engine is a **live-wallpaper-service app**, not a SystemUI overlay: every one of its 16
  effect variants is a registered `WallpaperService` subclass that draws the (locally stored)
  wallpaper image plus the effect through either an OpenGL ES renderer or an optional native
  Vulkan renderer on the live-wallpaper surface. The effect *is* the wallpaper; there is no
  "morph someone else's wallpaper" step. [verified: AndroidManifest.xml, AnimatedEffectWallpaperService.kt — 2026-10-25]
- The lock→home transition is driven by **event + polling unlock detection** (`SCREEN_ON/OFF`,
  `USER_PRESENT` broadcasts plus `KeyguardManager` polling, because "USER_PRESENT can arrive
  late"), then a **`ValueAnimator` over a single progress float** with a linear interpolator and a
  per-effect duration (Atmosphere: 2.5 s forward, 1.5 s reverse). Wake-on-screen animations
  deliberately use `Choreographer` instead of `ValueAnimator` because "ValueAnimator follows the
  system's animator duration scale, which custom ROMs often lower or turn off".
  [verified: WallpaperEventController.kt, AnimatedEffectWallpaperService.kt — 2026-10-25]
- The cloud/blob math that defines the Atmosphere look is small and portable: sample the blurred
  wallpaper bitmap (every 10th pixel), median-cut it to ≤16 color clusters, merge nearby clusters,
  then per wallpaper-reroll give each blob a random quadratic-Bézier target + size ramp, evaluated
  per frame on the CPU and pushed as uniform arrays into the fragment shader. A
  `physicsProgress` remap (drop the first 10 %, then `1−(1−x)³`) spaces the motion.
  [verified: AtmosphereBlobPlanner.kt, AtmosphereRenderer.kt — 2026-10-25]
- **Reusability verdict**: the *math* (blob extraction/Bézier/seeding, smoothstep layering windows,
  grain hashing, reeded-glass rib sampling, progress→layer mapping) is concept-free, framework-free
  and charitable to transliteration into our AGSL `RuntimeShader` engine; the *service* layer
  (WallpaperService ownership, keyguard polling, playlist rotation, backend hosts) is inseparable
  from Atmo's live-wallpaper ownership model and is reference-only. No AGSL wallpaper renderer
  ever existed in this project — GL ES from the first commit, Vulkan added later (v7.1.1,
  2026-08-19, PR #88); AGSL appears only in a clock *preview* composable.
- Borrowing under MIT: keep their copyright + permission notice for any copied substantive portion;
  the project's own `THIRD_PARTY_NOTICES.md` shows the house norm — one section per third-party
  source with purpose, upstream link, license, and the full notice text, even for purely
  visually-calibrated reimplementation.

## Architecture

### What it is

- Kotlin (SDK) + C++17/NDK (Vulkan) Android app, MIT-licensed, minSdk varies per flavor from
  Android 13/API 33 (both `v33` flavors) to API 36; targetSdk 33–36. Five build flavors
  (`v33Play`, `v33Fdroid`, `v35Play`, `v36Play`, `v36Fdroid`) with version codes like `300742`.
  [verified: README.md "Build & Installation" — 2026-10-25]
- Distributed via Google Play, F-Droid ("Orion Store" also named). [verified: README.md "Download" — 2026-10-25]
- First commit `e14ba47` ("init") is dated 2025-12-10 and already contains the GL ES pipeline
  (`assets/shaders/atmosphere.frag`, `AtmosphereRenderer.kt`, `GLWallpaperService.kt`,
  `AtmosphereService.kt`). [verified: `git log --reverse` in temp clone — 2026-10-25]
- Project size at `v7.4.2`: 182 `.kt`/`.java` files under `app/src/main/java`, 8 C++ files
  (5130 lines: one shared `vulkan_one_pass_engine.cpp` of 2945 lines + six per-effect JNI bridges
  of ~300 lines each). [verified: clone file listing, `wc -l` — 2026-10-25]

### The ownership model: the effect *is* the live wallpaper

- `AndroidManifest.xml` registers **one `WallpaperService` per effect variant** (16 services,
  e.g. `.service.AtmosphereService`, `.service.BlurToSharpService` labeled
  "atmosphere_reverse", `.service.GlassService`, …), each with `BIND_WALLPAPER`,
  the `android.service.wallpaper.WallpaperService` intent action and `@xml/wallpaper` metadata
  (thumbnail only). [verified: AndroidManifest.xml lines 77–214; res/xml/wallpaper.xml — 2026-10-25]
- Each engine extends `GLWallpaperService.GLEngine`, which owns a `WallpaperRenderHost`:
  - the **OpenGL ES host** is a `GLSurfaceView` subclass whose `getHolder()` is overridden to
    return the wallpaper's own `SurfaceHolder` (the classic trick to attach GL rendering to the
    wallpaper surface), configured `RENDERMODE_WHEN_DIRTY`, `EGLContextClientVersion(3)`,
    8/8/8/0 RGBA chooser, `preserveEGLContextOnPause = true`. [verified: GLWallpaperService.kt lines 541–647 — 2026-10-25]
  - the **Vulkan host** (per effect family) implements the same `WallpaperRenderHost` interface
    (surface lifecycle, `requestRender`, offset, close), so the two backends are interchangeable
    per engine, including a live swap that replays the recorded surface state (created/changed/
    offset/visibility) into the new host without losing effect state or scroll position.
    [verified: GLWallpaperService.kt `installRenderHost`/`replaceRenderHost`/`replaySurfaceState`; WallpaperRenderHost.kt — 2026-10-25]
- Per effect family there is a controller that owns immutable-ish state and dispatches it to the
  active backend, e.g. `AtmosphereRenderController` (forward Atmosphere = `AtmosphereRenderer`
  GLES; reverse = `BlurToSharpRenderer` GLES; or `VulkanAtmosphereHost`). Backend selection,
  per-effect failure persistence and fallback brokerage live in `VulkanSupport`.
  [verified: AtmosphereRenderController.kt — 2026-10-25]
- The wallpaper *image* is owned by the app: it is decoded, crop-fitted to the surface
  (`WallpaperFitHelper.ACTIVE_WALLPAPER_FILE` in `filesDir`) and published as system
  `WallpaperColors` via `onComputeColors()`/`notifyColorsChanged()` with an optional
  "Sync System Colors" preference and a `(lastModified, length)` cache key; the README and code
  both stress extraction runs locally, files never leave the device, and the whole behavior is
  off-switchable. [verified: GLWallpaperService.kt lines 230–374; README.md "Palette Diagnostics" — 2026-10-25]
- Permission/feature note: the manifest *removes* `INTERNET` and `ACCESS_NETWORK_STATE`
  permissions via `tools:node="remove"` (stripping anything a merged dependency asks for), a
  required-feature declaration demands OpenGL ES 3.0 (`glEsVersion 0x00030000`), and the Vulkan
  feature is declared `required="false"`; ML Kit builds download Google's subject model via Play
  services *outside* the app process, F-Droid flavors bundle U2NetP + a FOSS LiteRT runtime
  instead. [verified: AndroidManifest.xml lines 5–19, THIRD_PARTY_NOTICES.md — 2026-10-25]

### Engine internals for the Atmosphere look (the part relevant to us)

- **Textures**: two per loaded wallpaper set — `uTextureSharp` (the fitted photo) and
  `uTextureBlur` (a pre-baked blur). [verified: assets/shaders/atmosphere/atmosphere.frag lines 10–12 — 2026-10-25]
- **Blur bake**: at image load/rotation the sharp texture is blurred on the GPU with a separable
  two-pass kernel (linear/triangular weights `1 − |i|/r`, radius 200 texels, inline GLSL in a
  Kotlin string) rendered into an off-screen FBO, kept as a texture, and additionally
  `downloadTexture()`ed to a `Bitmap` to seed blob extraction. Runtime per-frame cost is therefore
  one composite pass, not the blur repeats. [verified: AtmosphereRenderer.kt lines 371–391, 429–440, 940–978 — 2026-10-25]
- **Blobs (16 max)**: the GLES renderer extracts colors from the *blurred* bitmap (sample every
  10th pixel → median-cut into up to 16 buckets → average each bucket into a color and a normalized
  centroid), merges clusters that are near in both RGB distance (< 90) and position (< 0.25), and
  for each blob draws random per-wallpaper targets: end position uniform in [0.05, 0.95]², a
  quadratic-Bézier control point = midpoint + jitter of ±0.25 (per axis), `startSize = 0.05`,
  `endSize = (0.12 + rand·0.08) × massScale` with `massScale = min(1.4, 1 + 0.05·clusterCount)`.
  Per frame each blob's position follows the quadratic Bézier `(1−t)²·start + 2(1−t)t·control +
  t²·end` and size lerps start→end; positions/sizes/colors go to the shader as uniform arrays
  (`uBlobPositions[MAX_BLOBS]`, `uBlobSizes[MAX_BLOBS]`, `uBlobColors[MAX_BLOBS]`, `uBlobCount`).
  [verified: AtmosphereRenderer.kt lines 544–604 (initBaseBlobs), 1210–1235 (extractColorsFromBlurred + medianCut), 606–698; atmosphere.frag lines 14–19 — 2026-10-25]
- The **Vulkan path reuses the identical math** via `renderer/vulkan/AtmosphereBlobPlanner`
  (median cut + merge + `rerollTargets()` + `frame(progress)` returning a sanitized
  `AtmosphereBlobFrame` of `MAX_BLOBS = 16` blobs); the GLES renderer carries its own inline copy
  of the same constants and Bézier eval in `onDrawFrame()`. Two parallel implementations of one
  idea, kept in lockstep by hand. [verified: AtmosphereBlobPlanner.kt; VulkanAtmosphereHost.kt lines 37–202; AtmosphereRenderer.kt lines 606–698 — 2026-10-25]
- **Reroll semantics differ by backend**: on GLES, targets re-roll only at image load/rotation
  (`reRollTargets()` after `initBaseBlobs`); on the Vulkan host they *also* re-roll whenever the
  state's progress returns to exactly 0 (`updateState`: `if (safe.progress == 0f && current.progress != 0f)
  blobPlanner.rerollTargets()`) — i.e., every lock for forward Atmosphere and the tail of every
  unlock for reverse — so each arc flies differently. [verified: AtmosphereRenderer.kt lines 444–456, 517–525; VulkanAtmosphereHost.kt lines 104–113 — 2026-10-25]
- **Fragment-side layering** (the visual recipe, forward Atmosphere direction): with
  `t = uBlurStrength` (the transition progress), `blurPhase = smoothstep(0.00, 0.20, t)`
  (sharp→pre-baked blur), `cloudMorph = smoothstep(0.18, 0.50, t)` (blur→weighted average "cloud
  field" of all blob colors), blob discs overlaid from `blobOpacity = smoothstep(0.15, 0.30, t)`
  with radial falloff `1 − smoothstep(0, radius, dist)`, then dimming `mix(color, vec3(0),
  uDimLevel·t)`; optional film grain gated on `smoothstep(0.4, 1.0, t)`.
  [verified: atmosphere.frag main() lines 493–574 — 2026-10-25]
- **Blob motion timing**: on the CPU, the global progress is remapped before the Bézier eval:
  `physicsProgress = 1 − (1−((t−0.1)/0.9))³` coerced — the first 10 % of the transition holds the
  lock state, then the motion runs with a cubic ease-out. Identical in both backends.
  [verified: AtmosphereBlobPlanner.kt companion `physicsProgress`; AtmosphereRenderer.kt lines 668–671 — 2026-10-25]
- **Seeds/randomness**: everything is seeded from `java.util.Random()` per renderer; targets are
  re-rolled per wallpaper load and per playlist rotation on GLES (`reRollTargets()` after
  `initBaseBlobs(blurredBitmap)`); the Vulkan host re-rolls in addition whenever progress crosses
  back to its zero endpoint (see reroll semantics above), so each unlock/lock arc re-randomizes
  blob trajectories. [verified: AtmosphereRenderer.kt lines 525, 544–622; VulkanAtmosphereHost.kt lines 104–109 — 2026-10-25]
- **Grain**: integer bit-mixing hash (multiply-xor-shift, `grainHash(cell)`) quantized to whole
  screen pixels `max(1, round(1/(scale·uvPerPixel)))` so grains never sub-pixel; luminance-scaled
  so shadows stay black. The comment explains two rejected alternatives: `fract(sin(…))`
  "collapses to a repeating pattern" and pcg2d "whose outputs … line up into faint diagonal
  streaks" — both "were tried, and both showed". [verified: atmosphere.frag lines 163–210 — 2026-10-25]
- **Reeded glass** (Glass effect + Atmosphere's optional glass face): `profile = sign(wave)·
  |wave|^exp` with `wave = sin(2π·fract(screenX·count))`, displacement `= profile·(1.08·window/
  count)` sampling the sharp texture, plus a directional softened mix and a small edge-light
  funnel. `app/src/main/assets/shaders/atmosphere` copies `vEffectCoord` (screen-anchored) while
  `vTexCoord` pans with launcher parallax (`uScrollOffsetX/uScrollWindowX`), so ribs stay fixed on
  the screen while the photo scrolls — "Blobs share vTexCoord with the photo, so they pan together
  coherently". [verified: atmosphere.frag `sampleStaticAtmosphereGlass` lines 96–154; atmosphere.vert lines 7–26; AtmosphereRenderer.kt lines 734–737 — 2026-10-25]

### How the image fit, crops and postures are handled

- `loadAndApplyTextures()` fits the stored image to the current surface size each reload; a
  playlist rotation downloads/uploads into a **double-buffered `TextureSet`** (`nextSet` built
  invisibly, then swapped with `currentSet`, blobs re-rolled, subject mask requested by
  generation counter), and the engine is told to `renderWhileHidden(keepMs)` so the new image is
  already on the dark surface before wake ("Wake on the new playlist image, not the previous
  one" — commit `d51fc12`). [verified: AtmosphereRenderer.kt `processPlaylistTransition`; AnimatedEffectWallpaperService.kt `rotateWallpaper` — 2026-10-25]
- Foldables: after the surface size "settles" for 700 ms, the *other* fold posture's fitted image
  is pre-warmed in a background executor so the next posture change only uploads a texture.
  [verified: GLWallpaperService.kt `schedulePosturePrewarm`/`prewarmAlternatePosture` lines 448–489 — 2026-10-25]

## Transition mechanics

### Unlock detection (live-wallpaper-specific)

- `WallpaperEventController` listens (runtime-registered exported receiver) to
  `ACTION_SCREEN_ON`, `ACTION_SCREEN_OFF`, `ACTION_USER_PRESENT`, and **also polls the keyguard**
  every `pollIntervalMs` while locked, because "USER_PRESENT can arrive late". A second,
  app-private `RECEIVER_NOT_EXPORTED` pair (`RELOAD_WALLPAPER`, `UPDATE_CONFIG`) carries config
  updates from the UI. [verified: WallpaperEventController.kt lines 68–112, 292–303 — 2026-10-25]
- The polling schedule degrades: the configured interval for the first 90 s of a lock run
  (`UnlockPolling.FAST_WINDOW_MS = 90_000`), then "no faster than twice a second"
  (`SLOW_INTERVAL_MS = 500`). Default configured interval is **50 ms** — but **30 000 ms on
  Samsung** (`isSamsungDevice()` via `Build.MANUFACTURER`). Polling stops when the wallpaper is
  hidden or the screen is not interactive; the comment documents why: on Motorola and Oppo the
  wallpaper "can report itself visible after SCREEN_OFF has run", and a poll started then "used
  to keep going every 50 ms all night. The OEM battery managers flag that as abnormal background
  use and force-stop the app, and Android answers a force-stopped live wallpaper by restoring the
  default one." [verified: WallpaperEventController.kt lines 68–93, 333–357; AnimatedEffectWallpaperService.kt lines 806–816 — 2026-10-25]
- **Lock delay (anti-flicker)**: on `SCREEN_OFF` the "prepare next unlock" reset is posted after
  `lockDelayMs` — default **800 ms** (0 ms on Samsung) — "This prevents the visual glitch where
  the wallpaper 'snaps' back to its initial state before the screen turns fully black" (README).
  An unlock settle window (`UNLOCK_SETTLE_MS = 1500`) plus a 400 ms re-confirm
  (`LOCK_CONFIRM_DELAY_MS`) defer "locked" visuals that arrive right after an unlock because some
  skins report SCREEN_OFF-fingerprint unlocks out of order. [verified: WallpaperEventController.kt lines 249–254, 333–340; README.md "Animation & Behavior" — 2026-10-25]
- `isKeyguardLocked`/`isDeviceInteractive` read `KeyguardManager`/`PowerManager` and fail safe
  (assume locked / interactive). [verified: AnimatedEffectWallpaperService.kt lines 728–782 — 2026-10-25]

### Driving the animation

- **Unlock**: `playUnlockAnimation()` cancels everything, snaps to `lockedProgress`, then runs
  `ValueAnimator.ofFloat(lockedProgress, unlockedProgress)` with `LinearInterpolator` and
  duration `timing.animationDurationMs`, calling `setEffectProgress(...)` + `requestRender()` per
  frame — i.e. **no easing on the unlock itself; the shader-side smoothstep windows do the
  easing**. Endpoints are per-service constants: forward Atmosphere `lockedProgress = 0f`,
  `unlockedProgress = 1f` (sharpest at lock, clouds grow through unlock); reverse Atmosphere
  (`BlurToSharpService`) `lockedProgress = 1f`, `unlockedProgress = 0f` (clouds at lock, "blur to
  sharp"), and the effect catalog carries per-effect recommended durations (Atmosphere 2500 ms /
  reverse 1500 ms; see Effect inventory below). [verified: AnimatedEffectWallpaperService.kt lines 582–649; AtmosphereService.kt lines 14–17; BlurToSharpService.kt lines 14–19; EffectCatalog.kt `recommendedDurationMillis` — 2026-10-25]
- **Waking**: screen-off → next visibility or `SCREEN_ON` replays a **wake transition** (10
  styles: Zoom, ColorFill, Blur, Stretch, Shake, Ripple, Glitch, Blinds, Breathe, Random). Wake
  is deliberately *not* a `ValueAnimator`: it runs on `Choreographer.postFrameCallback` and
  `SystemClock.uptimeMillis()`, because "ValueAnimator follows the system's animator duration
  scale, which custom ROMs often lower or turn off, and then the wake ran at one fixed, too-fast
  speed whatever its Length". It holds its start frame for 320 ms (160 ms for Zoom) because "on a
  Nothing Phone (1) it [the skin's fade-in from the always-on display] ends ~420 ms after the
  wallpaper is visible". Easing: `PathInterpolator(0.05f, 0.7f, 0.1f, 1f)` (annotated
  "Material's emphasized decelerate: quick off the mark, settling softly"), or linear for
  even-paced styles, and `PathInterpolator(0.4f, 0f, 0.2f, 1f)` for Zoom. The wake post pass is
  shared: every GLES renderer is wrapped by `WakePostRenderer` in the host (and the equivalent
  lives in the Vulkan bridge), so wake styles draw over the finished frame "clock included, by
  one post pass shared by every effect on both Vulkan and OpenGL ES" (README). [verified: AnimatedEffectWallpaperService.kt lines 303–410, 827–840; GLWallpaperService.kt line 553; README.md "Wake Transitions" — 2026-10-25]
- **Lock/visibility reconciliation**: on `onVisibilityChanged` the event controller's
  `onVisible(keyguardLocked)` picks exactly one of {play unlock animation, show locked state,
  show settled home}; the *hidden* side carries OEM workarounds (MIUI "briefly hides the wallpaper
  for a moment with the screen on", some skins hide it during fingerprint unlock, some "never
  hide the wallpaper while the screen is off, so no visibility change comes to start the wake").
  [verified: AnimatedEffectWallpaperService.kt lines 148–301 and inline comments — 2026-10-25]
- **Wallpaper commands**: sleep/wake also arrive as `WallpaperManager` engine commands
  (`COMMAND_GOING_TO_SLEEP`/`COMMAND_WAKING_UP`), used for the per-lock playlist blackout so a
  quick lock+unlock never wakes on a stale image. [verified: GLWallpaperService.kt lines 191–211, 632–636; AnimatedEffectWallpaperService.kt lines 458–472 — 2026-10-25]
- **Home-screen parallax**: `onOffsetsChanged` feeds `scrollOffsetX` into the vertex shader's
  windowed sampling (`uScrollOffsetX`/`uScrollWindowX`); blobs share `vTexCoord` with the photo,
  so they pan together coherently. `setOffsetNotificationsEnabled(true)` is on by default.
  [verified: GLWallpaperService.kt lines 79–99, 385–399; atmosphere.vert lines 7–26; AtmosphereRenderer.kt lines 734–737 — 2026-10-25]
- **Fixed (transitions-off) mode**: with `Animate transitions` off, the same engine renders the
  effect fully applied on the chosen screen(s) and the original on the rest, resolved via
  `getWallpaperFlags()` (API 34+) to tell a home engine from a lock engine; the app never installs
  a baked static image. [verified: AnimatedEffectWallpaperService.kt lines 61–67, 412–424, 651–667; README.md "Transitions or a Continuously Applied Effect" — 2026-10-25]

## Effect inventory

Per `EffectCatalog.kt` (id, family, both directions; forward id == family key unless noted):

| Family | Forward | Reverse | Recommended duration (ms) | Default dimness | Structure |
| --- | --- | --- | ---: | ---: | --- |
| ATMOSPHERE | `ORIGINAL` ("Atmosphere") | `REVERSE` ("Atmosphere (Reverse)") | 2500 / 1500 | 0.2 | two GLES renderers (`AtmosphereRenderer` / `BlurToSharpRenderer`) + `VulkanAtmosphereHost`; assets/shaders/atmosphere pair |
| GLASS | `GLASS` | `GLASS_REVERSE` | 1200 | 0 | `GlassRenderer` (+ Vulkan GL host + JNI) |
| COLORFILL | `COLORFILL` | `COLORFILL_REVERSE` | 1500 | 0 | `ColorFillRenderer` (+ `vulkan_color_fill_jni.cpp`) |
| CANVAS | `NEON` ("Canvas Sketch") | `NEON_REVERSE` | 1000 | 0 | `NeonRenderer` (+ `vulkan_neon_jni.cpp`; contour processor) |
| FROSTED | `FROSTED` ("Simple Frosted") | `FROSTED_REVERSE` | 500 | 0.2 (else-branch) | `FrostedRenderer` (+ `vulkan_frosted_jni.cpp`) |
| HALFTONE | `HALFTONE` | `HALFTONE_REVERSE` | 500 | 0 | shared `SinglePassLook` renderer (see below) |
| VHS | `VHS` | `VHS_REVERSE` | 900 | 0 | shared `SinglePassLook` renderer |
| ZOOM | `ZOOM` | `ZOOM_REVERSE` | 500 | 0 | shared `SinglePassLook` renderer (`zoomRange`) |

[verified: EffectCatalog.kt lines 29–193; service package listing — 2026-10-25]

- `SinglePassLook` is the "adding a look is a pair of shaders and a row here, not another
  renderer" abstraction: HALFTONE/VHS/ZOOM (+reverses) share one renderer, one texture/subject-
  mask/clock pipeline, one Vulkan native bridge, each look distinguishing itself by a frag-shader
  pair (`halftone_to_sharp.frag` / `sharp_to_halftone.frag`, …) selected by enum. Direction flags
  (`forwardEffectFirst`) encode which direction starts from the original wallpaper; the catalog
  keeps an explicit `originalFirstEffectIds` set for that. [verified: SinglePassLook.kt lines 1–45; EffectCatalog.kt lines 29–38, 168 — 2026-10-25]
- Playlists: rotation happens **only when the screen is off** ("Smart Rotation — To prevent lag
  or visual glitches, the wallpaper only rotates when the screen is OFF"), through
  `PlaylistRotationController.rotateAsync`, switchable between per-lock, minute/hour intervals,
  Light/Dark theme collections, a Fisher–Yates shuffle bag ("every image once before any
  repeats"), folder-synced playlists watched at screen-off/app-open, and saved playlists.
  [verified: AnimatedEffectWallpaperService.kt lines 438–507; README.md "Playlist & Rotation" — 2026-10-25]
- Extras that ride along (all optional, all off-switchable): film grain (two sliders), reeded
  glass additive for Atmosphere-exclusive, subject-isolation model shared by Canvas/Glass/
  Halftone (+ the Atmosphere glass "background only" mode), a distance-field Glass/Adaptive
  wallpaper clock drawn into the effect shader with its own entry animation, and a hidden
  7-tap "Palette Diagnostics" screen. [verified: README.md "Advanced Customization" — 2026-10-25]

## Quality & performance

- **Tested devices, by the author's own report**: "tested exclusively on the Samsung Galaxy S25
  Ultra and Nothing Phone 1"; other OEM skins "not guaranteed". All OEM-specific behavior is
  addressed in code by branch-on-manufacturer (Samsung) and comment-documented settle windows.
  [verified: README.md "Device Support & Disclaimer"; AnimatedEffectWallpaperService.kt — 2026-10-25]
- **Graphics backend history**: GL ES 3.0 asset shaders from the first commit (`e14ba47`,
  2025-12-10). **Vulkan was added in v7.1.1 (2026-08-19, PR #88 "Vulkan support")** — no AGSL
  wallpaper path ever existed; `RuntimeShader` appears once in the whole tree, in the clock's
  Compose preview (`ui/components/ClockGlassPreview.kt`, whose comment notes "AGSL has no
  derivatives, so the field's slope per pixel is measured" — an AGSL limitation they worked
  around). [verified: `gh release view v7.1.1`; `git log -S RuntimeShader --all`; ClockGlassPreview.kt lines 7–232 — 2026-10-25]
- **Vulkan posture**: opt-in per user (`Automatic`/`Vulkan`/`OpenGL ES`, global preference), probed
  in-process at engine attach against the Android Vulkan loader + a render-capable physical
  device, negotiating the highest common core API "from Vulkan 1.4 down through 1.1" (constant
  `VULKAN_1_1 = 0x00401000`); any failed init/texture-upload/present flips that **single effect**
  to its GLES renderer and is persisted per effect+device so the wallpaper preview cannot
  crash-loop ("a failure in one effect does not disable Vulkan for the others"); a live
  backend swap keeps effect state, surface state, scroll and playlist position.
  [verified: README.md "Graphics backend"; AtmosphereRenderController.kt `fallbackToOpenGl`; VulkanSupport.kt — 2026-10-25]
- **Render-loop economy**: `RENDERMODE_WHEN_DIRTY` everywhere; the engine pauses ~80 ms after the
  wallpaper hides (last-frame drain), can render-once while hidden for ≤1 s so playlist swaps land
  pre-wake, and otherwise renders only on progress changes, clock ticks, wake frames, offsets and
  reloads. Blur is baked once at image load, not per frame. Mask extraction, palette extraction,
  rotation fitting and posture conformance all run off the render thread in single-thread executors.
  [verified: GLWallpaperService.kt; AtmosphereRenderer.kt; AtmosphereRenderController.kt — 2026-10-25]
- **Battery**: no battery section exists anywhere in the README; the only runtime cost levers are the
  poll-interval setting ("Unlock Check Interval — adjusts how frequently the app detects unlock
  events"), the 500 ms poll degradation after 90 s, and the Motorola/Oppo comment (see above)
  whose failure mode ends with the OS restoring the default wallpaper. [verified: README.md "Animation & Behavior"; WallpaperEventController.kt — 2026-10-25]
- **Known rough edges (from README/issues/commits)**: the wallpaper-clock setup screen is
  self-described as "still buggy and unpolished"; One UI may disable its own adaptive clock while
  a live wallpaper is active; wallpaper scrolling is flagged Experimental; issue #111 ("wallpaper
  resets after unlocking") was the flicker class the lock-delay/settle machinery now addresses;
  GitHub issues are sparse (~15 total, mostly feature requests — the author funnels feedback via
  Telegram). [verified: README.md "Known Issues"; issue list; AnimatedEffectWallpaperService.kt — 2026-10-25]
- **Release history signals**: init 2025-12-10; 676 commits and ~188 PRs, all essentially by one
  author (two author aliases; two one-commit external contributors), merged by the author
  himself; ~130 commits in Sept–Oct 2026 alone; stables plus individually-tagged betas built from
  `develop` several per week (betas tagged on empty commits dated 2000-01-01 so F-Droid's
  "newest-tags" rule can't pick them up); CI checks artifact sizes and presence of SPIR-V + native
  libs per flavor. [verified: `git log` in temp clone; `gh release list`; README.md "Betas are built from develop…"; v7.2.6 notes — 2026-10-25]

## Reusability split (borrowable vs ownership-bound)

Context for the split: our target is `NothingAtmosphereEngine` — a pure-SDK Kotlin engine using
`RenderEffect` (API 31) + `RuntimeShader`/AGSL (API 33) + `RenderNode`/`HardwareRenderer`
(present-on-API 29+) — that morphs **EvoX's own applied wallpaper surface** from inside SystemUI
hooks (spec §7 in `docs/engineering_specification.md`). Atmo Engine lives in the other half of
Android's wallpaper world: it *is* the wallpaper. The two models share almost no lifecycle code
but share plenty of math.

### A. Directly borrowable as reference (math and small-use patterns, framework-free)

1. **The transition progress → layer recipe.** `progress`-driven smoothstep windows
   (`blurPhase 0–0.2`, `cloudMorph 0.18–0.5`, `blobOpacity 0.15–0.3`, `dim·t`) are a clean,
   testable temporal storyboard for "blur grows → clouds arrive → blobs peel out → darkening
   ramps"; the shape and band orderings are directly re-expressible in AGSL with a uniform
   progress. [verified: atmosphere.frag main() — 2026-10-25]
2. **Blob machinery.** Median-cut over a blurred downsample → ≤16 clusters with positions/colors;
   nearby-cluster merge thresholds (RGB distance < 90 **and** spatial distance < 0.25 — both must
   hold); per-image random
   reroll of Bézier targets (end ∈ [0.05, 0.95]², control = midpoint ± 0.25), plus the Vulkan
   "reroll whenever progress returns to 0" habit of re-randomizing each arc; size ramp
   0.05→(0.12–0.20)·massScale, `massScale = min(1.4, 1 + 0.05·count)`; per-frame CPU evaluation of
   `((1−t)², 2(1−t)t, t²)` Béziers over uniform arrays; `physicsProgress = 1−(1−(t−0.1)/0.9)³`
   for the perceived motion. All of it ports to pure Kotlin + AGSL uniforms; the sanitization
   discipline (`AtmosphereBlobFrame.sanitized()` coercing every float finite/range-bounded) is a
   pattern worth copying. [verified: AtmosphereBlobPlanner.kt; AtmosphereRenderer.kt; VulkanAtmosphereHost.kt; AtmosphereRenderState.kt — 2026-10-25]
3. **Grain hashing.** The integer multiply-xor-shift cell hash with documented *rejections*
   (fract(sin(·)) repeats with low-precision sin; pcg2d streaks on a regular grid) saves us
   rediscovering those dead ends; it needs a porting note (their code leans on **unsigned 32-bit
   ints** and `dFdy`, AGSL has neither — but cell UV-per-pixel can be supplied as a uniform, the
   same way the shader gets `grainUvPerPixel` in GLES, and the hash can be re-expressed with
   signed overflow arithmetic). [verified: atmosphere.frag lines 163–210; ClockGlassPreview.kt line 232 — 2026-10-25]
4. **Reeded-glass rib sampling math** (sin-profile displacement, softened offsampling, screen-
   anchored `vEffectCoord`) plus the assurance that the visual targets were calibrated against an
   independent MIT reference (`Vauth/nothing-glass`) — useful for *validating* our own glass-rib
   shader output. [verified: atmosphere.frag lines 96–154; THIRD_PARTY_NOTICES.md "Nothing Glass Reference" — 2026-10-25]
5. **Timing/easing findings** as *engineering knowledge*: linear `ValueAnimator` for the unlock
   (shader-side windows ease it), Choreographer-based frame loop for wake because ROMs zero the
   animation duration scale, hold-frames to survive skin AOD fades (Nothing Phone (1) ~420 ms
   datum), lock-delay and settle windows for OEM event reordering. These directly inform our
   engine-internal progress drivers and hook-side trigger guards (V2 trigger). [verified: AnimatedEffectWallpaperService.kt — 2026-10-25]
6. **State sanitization on write** (`AtmosphereRenderState.sanitized()` coerces everything), the
   generation-counter pattern for async loads (subject mask / playlist sets), and
   `WallpaperColors` publishing keyed on `(lastModified, length)` — a set of transferable
   approaches for our engine's uniform/state plumbing. [verified: AtmosphereRenderState.kt; GLWallpaperService.kt — 2026-10-25]

Not borrowable, but confirmatory: our spec's R5 mitigation ("all-half arithmetic in shader") and
their comment "AGSL has no derivatives" independently indicate the AGSL mapping of their
highp-int/derivative-based GL idioms needs care — the above borrowables are all expressible
without getting into those corners. [verified: docs/engineering_specification.md R5; ClockGlassPreview.kt — 2026-10-25]

### B. Concept-only (read, don't lift)

- **Baked-blur economics**: blur the source once at load; composite per frame. Our engine uploads
  EvoX's own wallpaper surface input, so the analogous move is a *shader-side* blur or a chained
  `RenderEffect` blur — the cost idea transfers, the code does not (they use two-pass FBO GLSL).
- **Two backends + probe/fallback lattice** (`VulkanSupport`, `RendererRuntimeStatusRepository`):
  irrelevant to a single `RuntimeShader` path, but the *politeness details* (preview can't enter a
  crash loop because one effect's failure is persisted; status footer claims Vulkan "only after
  the wallpaper has successfully presented a frame") are good hygiene ideas for our QA gates.
- **Subject segmentation plumbing** (ML Kit/U2NetP, crash guard, mask cache keyed by generation),
  wallpaper clock (distance-field glyph atlas, adaptive stroke faces), folder/theme playlists and
  the crop/fit pipeline: all product features living inside Atmo's ownership lane; nothing in them
  is needed by a read-only trigger-driven morph.
- **WallpaperService-per-direction registration**: a UX architecture for "effects you register as
  wallpapers"; for EvoX the analogous choice surface is what ticket #4/#5 decide (out of scope
  here, per the ticket).

### C. Ownership-bound (inseparable from the live-wallpaper model)

- `GLWallpaperService`/`AnimatedEffectWallpaperService`(+`GlAnimatedEffectWallpaperService`) engine classes, the
  `WallpaperRenderHost` abstraction and its swap/replay guards, the posture-prewarm ladder, and
  the `GLSurfaceView.getHolder()` wallpaper-surface override: these exist to render *into the
  wallpaper's SurfaceHolder* as its owner. Our engine doesn't own a surface; it decorates EvoX's.
- All of `WallpaperEventController`'s OEM workaround lattice: meaningful only because a live
  wallpaper sees neither the keyguard UI nor SystemUI's state and must re-derive "am I locked?"
  by polling. An Xposed-side trigger (spec V2) reads the *same state the lock screen reads*, so
  the polling/degradation/force-stop machinery is exactly the class of problem we avoid.
- Playlist/folder/theme rotation, `COMMAND_GOING_TO_SLEEP`-driven image blackout, crop/fit/style
  persistence, and multi-engine (preview + live) bookkeeping (`LiveWallpaperEngines`): the
  response to "my app is the wallpaper" — undefined for us.
- The main-screen renderer-status footer (`RendererRuntimeStatusRepository`) that claims Vulkan
  only after a frame has actually presented; and the `effectId`-keyed failure persistence.

## License norms

- Upstream MIT (`LICENSE`): "The above copyright notice and this permission notice shall be
  included in all copies or substantial portions of the Software." MIT leaves "substantial
  portions" undefined, but copied shader/planner code is exactly the category the clause targets,
  so carrying the notice is the safe norm for any nontrivial borrowing.
  [verified: LICENSE — 2026-10-25]
- Upstream's *own* house norm (our best template): `THIRD_PARTY_NOTICES.md` contains one section
  per third-party source — purpose, distribution scope, upstream project/license link — and, for
  MIT-referenced works, the full four-paragraph notice verbatim. Notably, even
  `Nothing Glass Reference` — "visually calibrated against … and independently implemented" —
  still carries the entire MIT block. [verified: THIRD_PARTY_NOTICES.md — 2026-10-25]
- Practical mapping for evoatmo: if any Atmo Engine shader/planner math is borrowed, add a
  THIRD_PARTY section (or a NOTICE entry in the module) naming
  `saad-khan-rind/NOSAtmosphereEffect`, MIT, © 2025 Saad Ullah Khan, with the notice text; the
  repo being public-bound makes this non-negotiable care, not decoration. (Decision on whether we
  ever copy rather than re-derive is grilling territory, not a fact in this memo.)

## Open [VERIFY] items

- [VERIFY: device behavior on Pong] All timing constants coded *this ROM family-side* are
  author-reported for Samsung/Nothing Phone (1)/Motorola/Oppo/MIUI; our P2 (Nothing Phone 2,
  Evolution X) trigger paths differ (spec V-table). Nothing here is applicable until
  §4.3 device-loop evidence replaces README/comment claims for our device.
- [VERIFY: `Orion Store` listing] The README names a third store ("Orion Store") that I did not
  fetch; store metadata (F-Droid description/version parity) unchecked in this memo.
- [VERIFY: PR #159 "Notify a downstream repository…"] A downstream-repository watcher exists in
  their CI; who consumes it (an overlay or fork of this project?) is unknown and irrelevant unless
  license-chain questions arise later.
- [VERIFY: Vulkan perf delta] The repo documents the *policy* (probe, fallback, per-effect
  failure) but publishes no measured GLES-vs-Vulkan frame-time/battery comparison; "v7.1.1 added
  Vulkan" is a code-availability fact, not a performance finding.

## Sources

- Clone base: `https://github.com/saad-khan-rind/NOSAtmosphereEffect`, read at tag
  `v7.4.2` (commit `5742b3b0971164f0e2f739ce124ff30d226f86b6`, 2026-10-21); `v7.3.7` =
  `fa096bca4d6f2968ece5d7055ebbb85a060ec0c7` (2026-10-14); init = `e14ba47` (2025-12-10).
- GitHub: release list (stables + betas), release notes for v7.1.1 (PR #88 "Vulkan support") and
  v7.4.2 / v7.3.7; issue tracker (open set small, feature-request-heavy).
- Files cited (paths relative to repo root at `v7.4.2` unless another tag is named):
  `README.md`, `LICENSE`, `THIRD_PARTY_NOTICES.md`, `app/src/main/AndroidManifest.xml`,
  `app/src/main/res/xml/wallpaper.xml`,
  `app/src/main/java/com/app/nosatmosphereeffect/helper/GLWallpaperService.kt`,
  `…/helper/WallpaperRenderHost.kt`,
  `…/service/AnimatedEffectWallpaperService.kt`, `…/service/WallpaperEventController.kt`,
  `…/service/AtmosphereService.kt`, `…/service/BlurToSharpService.kt`,
  `…/renderer/AtmosphereRenderController.kt`, `…/renderer/AtmosphereRenderer.kt`,
  `…/renderer/AtmosphereRenderState.kt`, `…/renderer/SinglePassLook.kt`,
  `…/renderer/vulkan/AtmosphereBlobPlanner.kt`, `…/renderer/vulkan/VulkanAtmosphereHost.kt`,
  `…/renderer/vulkan/VulkanSupport.kt`,
  `…/ui/components/ClockGlassPreview.kt`, `…/ui/model/EffectCatalog.kt`,
  `app/src/main/assets/shaders/atmosphere/atmosphere.frag`,
  `app/src/main/assets/shaders/atmosphere/atmosphere.vert`,
  `app/src/main/cpp/*` (per-effect JNI + one-pass engine),
  `app/src/main/java/com/app/nosatmosphereeffect/helper/WakeTransition.kt`.
- evoatmo context for the reuse split: `docs/engineering_specification.md` §7 (engine + shapes)
  and `AGENTS.md` (doc governance, citation style).
