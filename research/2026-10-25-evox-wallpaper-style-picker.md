---
id: research-2026-10-25-evox-wallpaper-style-picker
aliases:
  - evox-wallpaper-style-picker
tags: [android, rom, evo-atmo, research]
---

# Wallpaper & style mechanics — Evolution X (cnb/bka) and Nothing OS Atmosphere selection

Resolves wayfinder research ticket [kiyors/evoatmo#2](https://github.com/kiyors/evoatmo/issues/2) (child of
map issue [#1](https://github.com/kiyors/evoatmo/issues/1); consumer: the entry-surface decision ticket
[#4](https://github.com/kiyors/evoatmo/issues/4)). Facts only — no design decisions, no proposed fixes.
Every nontrivial claim carries an inline `[verified: <source> — 2026-10-25]` citation; anything not
confirmable from cited source is flagged `[VERIFY: …]` per the spec's verify-first rule
[verified: docs/engineering_specification.md §9.6 — 2026-10-25].

## Answer digest

- **A (EvoX)**: the picker EvoX *sources* is the AOSP WallpaperPicker2 fork (`com.android.wallpaper`), but on
  the Pong default (GMS) build path the app that actually *answers* "set wallpaper" at runtime is Google's
  presigned prebuilt `WallpaperPickerGoogleRelease` (`com.google.android.apps.wallpaper`), which overrides
  (LOCAL_OVERRIDES_PACKAGES) the in-tree pickers; the Vanilla path instead installs LineageOS
  `ThemePicker`. Selection funnels through exported picker activities, the Settings hub
  (`top_level_wallpaper` → dynamic `ACTION_SET_WALLPAPER` chooser), and in the fork an `ApplyWallpaperOptionsProvider`
  apply layer that calls `WallpaperManager.setStreamWithDescription`/`setBitmapWithDescription` (static) or
  hidden `setWallpaperComponentWithDescription`/`setWallpaperComponentWithFlags` (live), with destinations
  `BOTH/HOME/LOCK ↔ DEST_* ↔ FLAG_SYSTEM/FLAG_LOCK`. A live-wallpaper category is listed when
  `FEATURE_LIVE_WALLPAPER` exists; the fork carries an "effects" pipeline but only as no-op stubs.
- **B (Nothing OS)**: Atmosphere is exposed inside Nothing OS 2.5's consolidated wallpaper customisation page
  (joint Home+Lock), reached via long-press → Customization → More wallpapers → crop screen → an "Atmosphere"
  toggle (+ optional "Glass") at apply time; it coexists with static lock-screen wallpapers by playing
  during the unlock motion and requires the same wallpaper on both screens. Nothing's implementation details
  are closed source — only user-visible behavior is documented here.
- **C (hookability)**: the only *cited-source* hookable surfaces are (1) system-level
  `android.app.WallpaperManager` setters — process-agnostic to which picker makes the call, (2) the fork's
  apply path (`WallpaperClientImpl` reflection block, `ApplyWallpaperOptionsProvider`), and (3) exported
  manifest entry surfaces + Settings config keys. Google-prebuilt internals and Nothing-OS internals are
  not verifiable from source — they need the §9.6 runtime-dumper protocol before any hooking is trusted.

## A — Wallpaper & style selection on Evolution X (cnb/bka)

### A.1 What EvoX actually builds and ships

- The manifest syncs `packages/apps/WallpaperPicker2` → `Evolution-X/packages_apps_WallpaperPicker2`
  [verified: EvoX manifests `evolution.xml` L114 — fetched 2026-10-25]. The fork tracks AOSP-main-era
  picker code with small EvoX deltas (repo layout `src/com/android/wallpaper/...` verified by code search
  today). Branch naming: frameworks_base defaults `cnb`, device trees `bka`-era; device tree
  `device_nothing_Pong` current branch `bka` [verified: docs/engineering_specification.md L88-93 — 2026-10-25].
- The fork's Soong module keeps package `com.android.wallpaper` (`package_name: "com.android.wallpaper"`,
  Android.bp L184) and `overrides: ["WallpaperPicker"]` (L190) — i.e. it replaces the legacy AOSP
  WallpaperPicker [verified: Android.bp L171/L184/L190 — 2026-10-25].
- Built into the product on the **vanilla** path: LineageOS `ThemePicker` (fork of the same picker family;
  label `"Wallpaper & style"` in strings.xml L19) [verified: LineageOS/android_packages_apps_ThemePicker
  lineage-24.0 strings.xml L19 — 2026-10-25]; `ThemePicker` is the one picker made PRODUCT_PACKAGES
  [verified: Evolution-X/vendor_evolution @ `cnb` config/common_mobile.mk L81 — 2026-10-25].
- On the **GMS** path, `WITH_GMS ?= true` is default in vendor_evolution
  [verified: vendor_evolution @ `cnb` config/common_full_phone.mk L11 — 2026-10-25], and Pong's device tree
  carries no GMS overrides in its overlay/design block [verified: device_nothing_Pong device.mk L330-345
  — fetched 2026-10-25], so `vendor_gms` applies. The GMS package list installs:
  `WallpaperPickerGoogleRelease` (gms_full.mk L174), `WallpaperEffect` (L136),
  `PixelLiveWallpaperPrebuilt-27000055` (L100), `PixelWallpapers2026` (L70), `MagicPortraitWallpapers`
  (L160), `WallpaperAIPrebuilt_10000372` (L61), `WallpaperEmojiPrebuilt-p26-foldable-wallpaper` (L62),
  `MagicPortraitSymLink` (L35) [verified: Evolution-X/vendor_gms `gms_full.mk` — 2026-10-25], plus
  `SettingsGoogle`/`SystemUIGoogle` when `WITH_GMS` is true
  [verified: vendor_evolution config/common.mk L20-22 — 2026-10-25].

### A.2 Which app *answers* "set wallpaper" at runtime (the override decision)

- Google's prebuilt `WallpaperPickerGoogleRelease` is sparked from
  `system_ext/packages/privileged_apps/WallpaperPickerGoogleRelease/Android.mk`: `LOCAL_CERTIFICATE := PRESIGNED`
  (L25), `LOCAL_PRIVILEGED_MODULE := true` (L28), and
  `LOCAL_OVERRIDES_PACKAGES := ThemePicker WallpaperPicker WallpaperPicker2 WallpaperCropper` (L29)
  — i.e. on a GMS build all four in-tree pickers are *replaced at install time* by the Google prebuilt
  [verified: Evolution-X/vendor_gms @ `cnb` Android.mk named above — fetched 2026-10-25]. The package id of the
  prebuilt (`com.google.android.apps.wallpaper`) is corroborated indirectly by the device-tree overlays below; the
  APK itself is closed source and not in any synced tree — `[VERIFY: runtime check on the device —
  pm list packages wallpaper + dumpsys package com.google.android.apps.wallpaper — before trusting, §9.6]`.
- **Device-tree corroborators**: Pong ships static RROs `WallpaperPicker2Res` / `WallpaperPicker2PixelRes`
  [verified: device_nothing_Pong @ bka device.mk "PONG overlays" PRODUCT_PACKAGES block ending
  L341-342 — 2026-10-25]. Their manifests declare overlay package `com.google.android.wallpaper.picker.overlay`
  → `android:targetPackage="com.google.android.apps.wallpaper"` with `android:isStatic="true"` and
  `priority="100"` (and, for the Pixel variant, overlay package `com.google.android.wallpaper.picker.pixel.overlay`
  → `com.google.android.apps.wallpaper.pixel`) [verified: overlay/pong/WallpaperPicker2Res/
  AndroidManifest.xml + AndroidManifest_Pixel.xml — 2026-10-25]. So the Pong tree tailors the
  *Google* picker's resources — strong A-side evidence that the runtime selection surface on Pong GMS builds
  is the Google prebuilt, not the sync'd EvoX fork, which exists mainly for Vanilla.

### A.3 Entry points (fork manifest; for the Google prebuilt they differ — closed source `[VERIFY]`)

- Exported activities in the EvoX cnb fork tree [verified: Evolution-X/packages_apps_WallpaperPicker2 @ `cnb`
  AndroidManifest.xml — fetched 2026-10-25]:
  - `com.android.wallpaper.picker.customization.ui.CustomizationPickerActivity` (L79), exported (L85).
  - `com.android.wallpaper.picker.TrampolinePickerActivity` (L88), exported (L93), filters
    `android.intent.action.SET_WALLPAPER` (L95).
  - `com.android.wallpaper.picker.DeepLinkActivity` (L100), exported (L102), with autoVerify VIEW filter to
    `https://g.co/wallpaper` (L104-112) — standard AOSP scaffold, not Nothing-specific.
  - `activity-alias android:name="com.android.wallpaper.picker.CategoryPickerActivity"` → target
    `TrampolinePickerActivity`, exported, MAIN filter (L114-122).
  - `com.android.wallpaper.picker.preview.ui.WallpaperPreviewActivity` (L124) — no exported line in block.
  - `com.android.wallpaper.picker.StandalonePreviewActivity` (L133), exported (L137), filter
    `android.service.wallpaper.CROP_AND_SET_WALLPAPER` (L139).
- The fork's manifest declares a WallpaperService intent action (L48) and picker-permission set
  `MODIFY_DAY_NIGHT_MODE`, `WRITE_SECURE_SETTINGS`, `READ_DEVICE_CONFIG`, `CUSTOMIZE_SYSTEM_UI`,
  `BIND_WALLPAPER`, `READ_MEDIA_IMAGES`, `READ_WALLPAPER_INTERNAL`, `SET_WALLPAPER`,
  `SET_WALLPAPER_COMPONENT`, `CHANGE_OVERLAY_PACKAGES`, `SET_WALLPAPER_DIM_AMOUNT`, `UPDATE_THEME_SETTINGS`
  [verified: Evolution-X/packages_apps_WallpaperPicker2 @ `cnb` AndroidManifest.xml L13-31
  and Lineage lineage-24.0 AndroidManifest.xml L11-22 — 2026-10-25].
- **Lineage's ThemePicker manifest (the vanilla app) is packed differently**: the three picker activities
  are `exported="false"` (L83, L94, L103) and the *only* exported entry is the
  `activity-alias android:name="com.android.customization.picker.CustomizationPickerActivity"` →
  target `TrampolinePickerActivity`, exported=true, ACTION_SET_WALLPAPER (L105-114); the Lineage
  application class is `com.android.customization.picker.CustomizationPickerApplication` (L74)
  [verified: LineageOS/android_packages_apps_ThemePicker lineage-24.0 AndroidManifest.xml — 2026-10-25].
  The EvoX fork's own manifest (above) goes the other way and exports `CustomizationPickerActivity`
  directly — an actual divergence between fork packaging and Lineage packaging to keep in mind when
  reasoning about what a hooking surface would need to reach.

### A.4 Settings hub wiring (the "Wallpaper" card in Evolution X Settings)

- Top-level row `top_level_wallpaper` sits at `order="-10"`, `title="@string/wallpaper_settings_title"`,
  `controller="com.android.settings.display.TopLevelWallpaperPreferenceController"`
  [verified: Evolution-X/packages_apps_Settings @ `cnb` res/xml/top_level_settings.xml L131-137 — 2026-10-25].
- That controller reads `R.string.config_wallpaper_picker_package` / `config_wallpaper_picker_class` /
  `config_wallpaper_picker_launch_extra` (L52-56), configured as
  `com.android.settings` / `com.android.settings.Settings$WallpaperSettingsActivity` (config.xml L51-52),
  while `config_styles_and_wallpaper_picker_class` and both `config_*_picker_action` keys are empty strings
  (config.xml L54-58)
  [verified: Evolution-X/packages_apps_Settings @ `cnb` res/values/config.xml L50-58
  and TopLevelWallpaperPreferenceController.java L41-56 — 2026-10-25].
- That in-Settings page is thin (`wallpaper_settings.xml` = PreferenceScreen `wallpaper_type`) and its
  controller dynamically lists installed apps that satisfy `ACTION_SET_WALLPAPER`
  (`new Intent(Intent.ACTION_SET_WALLPAPER)` + `pm.queryIntentActivities(...)`, L71-74)
  [verified: Evolution-X/packages_apps_Settings @ `cnb` res/xml/wallpaper_settings.xml L23-30,
  src/com/android/settings/wallpaper/WallpaperTypePreferenceController.java — 2026-10-25].
  On GMS builds Settings is swapped to `SettingsGoogle` (vendor_evolution common.mk L20-22), same string keys.

### A.5 Apply flow (mechanics, not UX)

- **Destination model** (fork): `WallpaperDestination{BOTH, HOME, LOCK}` with mapping
  `fromFlags(FLAG_SYSTEM|FLAG_LOCK) → BOTH`, `fromDestinationInt(DEST_BOTH) → BOTH`, and the reverse
  `toSetWallpaperFlags` / `toScreens → {HOME_SCREEN, LOCK_SCREEN}`
  [verified: Evolution-X/packages_apps_WallpaperPicker2 @ `cnb`
  src/com/android/wallpaper/picker/customization/shared/model/WallpaperDestination.kt (full file) — 2026-10-25].
- **Persister constants**: `WallpaperPersister.DEST_HOME_SCREEN = 0`, `DEST_LOCK_SCREEN = 1`,
  `DEST_BOTH = 2` [verified: …/src/com/android/wallpaper/module/WallpaperPersister.java L43-45 — 2026-10-25].
- **Static imagery** (apply request): `WallpaperClientImpl` (at
  `src/com/android/wallpaper/picker/customization/data/content/WallpaperClientImpl.kt`) calls
  `WallpaperManager.setStreamWithDescription(...)` (L219, with a `WallpaperDescription` at L212) or
  `setBitmapWithDescription(...)` (L226)
  [verified: Evolution-X/packages_apps_WallpaperPicker2 @ `cnb` — fetched 2026-10-25].
  On the AOSP framework side both are public-albeit-flagged WallpaperManager API:
  `setBitmapWithDescription` L2508 (`@FlaggedApi(FLAG_LIVE_WALLPAPER_CONTENT_HANDLING)`, `@TestApi`,
  `@RequiresPermission(SET_WALLPAPER)`, delegate → `setBitmapWithCrops`) and `setStreamWithDescription`
  L2738 (`@SystemApi`, same permission, delegate → `setStreamWithCrops`)
  [verified: AOSP frameworks/base `refs/heads/main` core/java/android/app/WallpaperManager.java
  (base64 of ?format=TEXT page) — 2026-10-25].
- **Live/3rd-party wallpaper components (incl. live "effect" wallpapers)**: the fork tries hidden framework
  calls by *reflection* (a fail-open pattern worth noting here, not endorsing): named-string lookup of
  `"setWallpaperComponentWithDescription"`/`WallpaperDescription::class.java` (WallpaperClientImpl.kt
  L349-350) and `"setWallpaperComponentWithFlags"` (L367), with an explicit pre-16-OS fallback path
  (log variant `"live wallpaper content handling enabled, but Android U setWallpaperComponentWithFlags
  called"` at L378) and a final plain `setWallpaperComponent(wallpaperModel.commonWallpaperData.id.componentName)`
  fallback (L400) [verified: WallpaperClientImpl.kt L212-226, L349-400 — 2026-10-25].
  On the framework side: `setWallpaperComponentWithFlags` (L3084/3105) requires
  `android.Manifest.permission.SET_WALLPAPER_COMPONENT` (google-source line right above the method), and
  `setWallpaperComponentWithDescription` (L3131/3155) requires
  `SET_WALLPAPER_COMPONENT` + `INTERACT_ACROSS_USERS_FULL` (conditional)
  [verified: AOSP WallpaperManager.java L3066-3160 — 2026-10-25] — i.e. live-wallpaper *programmatic
  setting* is not a normal-app capability on the framework level.
- **Legacy home/lock/both dialog** — `SetWallpaperDialogFragment.java` is still in the fork tree at
  `src/com/android/wallpaper/picker/` (code-search hit [verified: 2026-10-25]); whether the current preview
  flow still routes through it is `[VERIFY]` (carried into the open-items list).
- **Apply-option shaping** in the fork's preview surface:
  `src/com/android/wallpaper/picker/preview/ui/binder/ApplyWallpaperOptionsProvider.kt` and
  `.../DefaultApplyWallpaperOptionsProvider.kt`, surfaced by `WallpaperPreviewViewModel.kt`
  (code-search paths [verified: 2026-10-25]).

### A.6 Live-wallpaper listing + third-party picker app listing

- `DefaultCategoryProvider` publishes "My photos" (L161), legacy on-device (L166) [verified:
  Evolution-X/packages_apps_WallpaperPicker2 @ `cnb` src/com/android/wallpaper/module/DefaultCategoryProvider.java
  — fetched 2026-10-25] and only when `hasSystemFeature(PackageManager.FEATURE_LIVE_WALLPAPER)` (L169) it
  collects `LiveWallpaperInfo.getAll(...)` and wraps them in `ThirdPartyLiveWallpaperCategory` (L174);
  separately, `ThirdPartyAppCategory.getAll(...)` (L184) surfaces installed apps that declare the
  `android.intent.action.SET_WALLPAPER` category [verified: DefaultCategoryProvider.java — 2026-10-25].
- Practical read: *what the picker's category list contains* (live wallpapers, third-party apps) is computed
  at runtime from `FEATURE_LIVE_WALLPAPER` and from which installed packages export `ACTION_SET_WALLPAPER` —
  i.e. an observable inventory, not a baked-in list [verified: DefaultCategoryProvider.java L169-184 — 2026-10-25].

### A.7 Effects — plumbing is forked-in and stubbed; real content only via prebuilts

- `EffectsController.java` (abstract; methods `generateEffect(EffectEnumInterface, Uri)` L71,
  `bindEffectsService` L77, `getEffectsPackageName` L99, `triggerEffect(Context)` L123,
  `startForegroundDownload`/`interruptForegroundDownload` L200-209) and its *empty* override
  `DefaultEffectsController` (an empty `@Singleton` subclass), plus `Effect.kt`,
  `WallpaperEffectsView2.kt` (`src/com/android/wallpaper/widget/floatingsheetcontent/`), and
  `src/com/android/wallpaper/picker/preview/data/repository/ImageEffectsRepository.kt`
  [verified: code-search paths + default-impl fetch — 2026-10-25]. Corroborated by `Injector.kt` and
  `src_override/.../WallpaperPicker2AppModule.kt` hits — the pipeline is DI-wired but ships empty.
- Net: on EvoX-built pickers there is **no shipped effects provider**; the runtime behavior (if any) rides on
  Google's prebuilt list pulled in under vendor_gms (`WallpaperEffect`, L136).
  `[VERIFY: whether that WallpaperEffect prebuilt actually binds on a stock EvoX GMS build — on the source side it
  appears only as gms_full.mk L136 — and whether its package is visible to the picker app; needs runtime dumper
  evidence per §9.6 on this build's fingerprint]`

## B — Nothing OS Atmosphere selection (the experience being mirrored)

### B.1 Placement & naming

- Officially, Nothing OS 2.5 Open Beta 1's changelog announces a "New Atmosphere wallpaper effect" that
  "brings motion to colours on your home screen" with a "seamless transition between your lock and home
  screens" [verified: nothing.community API discussion 3992, admin changelog post — fetched 2026-10-25], and
  ships it with the "joint Home Screen and Lock Screen customisation page" (same post).
- Nothing's picker app itself is closed source — its package name/activity names cannot be verified from
  any public tree; `[VERIFY: observable only at runtime — pm list packages / dumpsys on a Nothing device]`.

### B.2 User-visible flow (secondary-source description; not implementation claims)

- Guide: long-press home screen → "Customization" sheet → wallpaper category → "More wallpapers" →
  pick a wallpaper → pinch-crop screen (with an *optional scroll effect* toggle) → tick → at apply time the
  bottom bar carries an **"Atmosphere"** toggle (with an optional **"Glass"** toggle) → "Done"
  [verified: NerdSchalk guide — fetched 2026-10-25]. Nothing OS 2.5 removed its standalone
  live-wallpaper/stock-wallpaper setting routes (folded into this same page) [same guide].
- Consistent picture in community threads (secondary, fetch-blocked in full-text): atmosphere-like wallpaper
  effects are toggled at *wallpaper-apply* time, not as a standalone app [verified: search snippets of
  r/NothingTech thread `18mb4uf` / `18r89pj` — 2026-10-25].

### B.3 Coupling across lock/home + per-screen behavior

- The Atmosphere effect demands the identical wallpaper on both home and lock; with a *static* lock-screen
  wallpaper and an Atmo-enabled home wallpaper, the effect still plays during the unlock-to-home motion
  [verified: NerdSchalk guide; Reddit snippets — 2026-10-25].
- Therefore lock and home don't need to be *both* Atmosphere-engine-driven; the effect survives the
  static-image-elsewhere case — it is the home-screen animation that carries the Atmosphere behavior; the
  two screens merely need to agree on the *image*.
- What the sources actually support: the effect is chosen at wallpaper-apply time inside the merged
  customization page, with the both-screens-same-image invariant above — not as a separately surfaced
  per-screen effect manager. How it is plumbed internally (live-wallpaper component vs. something else) is
  `[VERIFY]`.
- This is the same both-screens coupling that EvoX's own wallpaper blur shows (Stream-A board row FIX-001:
  "wallpaper blur only behaves if enabled on BOTH lock and home screens...")
  [verified: docs/fix-registry.md L47 — 2026-10-25] — i.e. the constraint is a wallpaper-pipeline property,
  not a Nothing-only quirk; a hook seeking per-screen divergence must account for it.
- `[VERIFY: exact runtime representation of Atmosphere (live-wallpaper component? Surface-only overlay?) and
  whether per-screen Atmosphere targets can come from live components. Nothing OS is closed source; the
  Atmo Engine path (below) is the observable proxy]`.

### B.4 Open-ecosystem replication (Atmo Engine) — the actionable precedent

- `NOSAtmosphereEffect` (saad-khan-rind) implements the Atmosphere experience as a standard live wallpaper
  (a WallpaperService), applied through the *system live-wallpaper preview* flow rather than through any
  picker-internal surface, and documents the same both-screens requirement as stock Nothing OS. It is the
  open-source precedent that Atmosphere-like behavior does not need access to Nothing's picker internals —
  it can ride the ordinary live-wallpaper selection path [verified: https://github.com/saad-khan-rind/NOSAtmosphereEffect
  (README) — fetched 2026-10-25].

## C — Hookability orientation (Stage-1; facts only, no design)

### C.1 Choke points visible from cited source

- **System-level funnel**: every picker (Google prebuilt, vanilla ThemePicker, in-Settings dynamic page,
  Atmo-Engine-style live-wallpaper setter) ultimately lands on the `WallpaperManager` setters
  `setStreamWithDescription`/`setBitmapWithDescription` (static) or
  `setWallpaperComponentWithDescription`/`setWallpaperComponentWithFlags`/`setWallpaperComponent` (live),
  [verified: AOSP WallpaperManager.java L2508/L2738/L3066/L3084/L3131/L3155;
  WallpaperClientImpl.kt L219/L226/L349/L367/L400 — 2026-10-25]. These are *process-agnostic* to whichever
  surface made the call — an observable, picker-neutral funnel (orientation only; no design decided here).
- **Fork's own apply layer** (if a vanilla build is what runs):
  `ApplyWallpaperOptionsProvider`/`DefaultApplyWallpaperOptionsProvider.kt` shape the "set wallpaper" options,
  `WallpaperPreviewViewModel.kt` is their surface-level entry, and the
  `WallpaperInteractor` → `WallpaperRepository` chain (at
  `src/com/android/wallpaper/picker/customization/domain/interactor/WallpaperInteractor.kt` — import
  `customization.data.repository.WallpaperRepository` at L23, constructor injection at L35 — and
  `src/com/android/wallpaper/picker/customization/data/repository/WallpaperRepository.kt`) is the model layer
  [verified: code-search paths + cached fetches — 2026-10-25].
- **Manifest-visible entry injection candidates** (no decision here, just the exported surface inventory):
  the fork's exported `CustomizationPickerActivity`/`TrampolinePickerActivity`/`DeepLinkActivity`/`CategoryPickerActivity`
  (alias into TrampolinePickerActivity with MAIN)/`StandalonePreviewActivity` [verified: EvoX fork manifest
  L79-140 — 2026-10-25], and on vanilla, the Lineage-alias `com.android.customization.picker.CustomizationPickerActivity`
  [verified: Lineage manifest L105-114 — 2026-10-25].
- **Settings-hub config**: `config_wallpaper_picker_package` and `config_wallpaper_picker_class` are the
  resource strings the top-level card consults (A.3) — a lever visible at resource level (as a plain RRO
  retarget) without hooking anything [verified: EvoX Settings/res/values/config.xml L50-58,
  TopLevelWallpaperPreferenceController.java L52-56 — 2026-10-25]. On GMS builds the hub runs as
  `SettingsGoogle` with the same wiring (common.mk L20-22).

### C.2 Boundary facts a Stage-1 module must respect

- **The prebuilt reality on Pong**: on a stock Pong (GMS) build, the app actually handling set-wallpaper
  intents is Google's prebuilt (A.2), not the EvoX fork — a Vector module that attaches to EvoX-fork code
  scoped to `com.android.wallpaper` would touch code that is *not on the user-visible path* of this build.
  `[VERIFY: runtime prebuilt presence on the device — pm list packages wallpaper + dumpsys — per §9.6]`.
- **Spec boundary**: this research adds **no new verified hook points**. The spec's V1–V12 matrix covers
  SystemUI-side anchors (`ScrimController.*`, `ImageWallpaper$CanvasEngine`, …)
  [verified: docs/engineering_specification.md L51/L162 — 2026-10-25]; anything picker-scope lives outside
  that matrix and therefore requires the §9.6 greps + a runtime dumper read on *this exact ROM build*, with
  artifacts stamped by fingerprint, before any claim of a hookable member is trusted
  (spec §9.6; AGENTS.md §3.2 invariant 6).
- **Framework-owned layer**: the live-side setters (`setWallpaperComponentWith*`) are `@hide`/permission-gated
  (`SET_WALLPAPER_COMPONENT`, plus `INTERACT_ACROSS_USERS_FULL` conditionally, A.5) — a Stage-1 presence in
  that corner must use the same fail-open pattern the fork itself uses (named-string reflection with
  documented per-OS fallbacks, WallpaperClientImpl.kt L349-400), never direct member references.
- **Selection state is one-shot, not per-unlock**: nothing in the apply path implies per-unlock persistence
  (R6 — no per-unlock `WallpaperManager.setBitmap()`, no Monet/`WallpaperColors` writes, read-only
  `getWallpaperColors(FLAG_SYSTEM)` for palette) [verified: AGENTS.md §3.2; docs/ideas.md L16-18 — 2026-10-25].

## Open `[VERIFY]` items (all as of 2026-10-25)

1. Google-prebuilt internals (Google's picker package behavior, activity list, entries) — closed source;
   only runtime-dumper evidence on the device fingerprint counts.
2. Whether the static RROs `WallpaperPicker2Res`/`WallpaperPicker2PixelRes` (targeting
   `com.google.android.apps.wallpaper(.pixel)`) resolve on a *vanilla* build (whose pickers are
   `com.android.wallpaper`): they are declared for a Google-only target, so on vanilla they can
   be no-ops — appears plausible from overlay mechanics but needs runtime confirmation.
3. Whether `WallpaperEffect` (vendor_gms L136) actually binds/loads on a stock EvoX GMS build, and whether
   its package (presumably `com.google.android.*`) is visible to the picker app (same runtime-dumper rule).
4. Nothing OS's picker package name(s) and Atmosphere storage/naming (Settings keys, whether Atmosphere is a
   component toggle vs a menu toggle) — Nothing OS internals are closed source.
5. Exact runtime representation of Atmosphere (live wallpaper component vs SystemUI-side overlay) — nothing
   beyond NerdSchalk/community threads is confirmable today.
6. Legacy-3-button flow (`SetWallpaperDialogFragment.java`) — present in the fork; whether the current
   preview flow still adopts it on the cnb build is unconfirmed today.

## Sources (all fetched 2026-10-25)

- EvoX manifests: `https://github.com/Evolution-X/manifests` — `evolution.xml` L114.
- EvoX picker fork (`cnb`): `https://github.com/Evolution-X/packages_apps_WallpaperPicker2` — Android.bp,
  AndroidManifest.xml, `src/com/android/wallpaper/module/{DefaultCategoryProvider.java, WallpaperPersister.java}`,
  `src/com/android/wallpaper/picker/customization/ui/{CustomizationPickerActivity.kt, CustomizationPickerFragment.kt}`,
  `src/com/android/wallpaper/picker/customization/data/content/WallpaperClientImpl.kt`,
  `src/com/android/wallpaper/picker/customization/shared/model/WallpaperDestination.kt`,
  `src/com/android/wallpaper/picker/customization/data/repository/WallpaperRepository.kt`,
  `src/com/android/wallpaper/picker/customization/domain/interactor/WallpaperInteractor.kt`,
  `src/com/android/wallpaper/picker/SetWallpaperDialogFragment.java` (code-search hit),
  `src/com/android/wallpaper/effects/*` (EffectsController.java, DefaultEffectsController.kt, Effect.kt),
  `src/com/android/wallpaper/picker/preview/...` (ApplyWallpaperOptionsProvider.kt, Default…, WallpaperPreviewViewModel.kt,
  ImageEffectsRepository.kt), `src/com/android/wallpaper/widget/floatingsheetcontent/WallpaperEffectsView2.kt`.
- EvoX vendor_evolution (`cnb`): `config/common_mobile.mk`, `config/common_full_phone.mk`, `config/common.mk`.
- EvoX vendor_gms (`cnb`): `gms_full.mk`,
  `system_ext/packages/privileged_apps/WallpaperPickerGoogleRelease/Android.mk`.
- Device tree (`bka`): `https://github.com/Evolution-X-Devices/device_nothing_Pong` — `device.mk`,
  `overlay/pong/WallpaperPicker2Res/{Android.bp, AndroidManifest.xml, AndroidManifest_Pixel.xml}`.
- EvoX Settings (`cnb`): `https://github.com/Evolution-X/packages_apps_Settings` —
  `res/xml/top_level_settings.xml`, `res/xml/wallpaper_settings.xml`, `res/values/config.xml`,
  `src/com/android/settings/display/TopLevelWallpaperPreferenceController.java`,
  `src/com/android/settings/wallpaper/WallpaperTypePreferenceController.java`.
- LineageOS ThemePicker (`lineage-24.0`): `https://github.com/LineageOS/android_packages_apps_ThemePicker` —
  AndroidManifest.xml, `res/values/strings.xml`.
- AOSP frameworks/base (`refs/heads/main`): WallpaperManager.java
  (retrieved via `?format=TEXT` base64 payload).
- Nothing OS: nothing.community API `discussions/3992` changelog post; NerdSchalk guide
  `https://www.nerdschalk.com/how-to-add-the-atmosphere-effect-on-wallpapers-on-nothing-os-2-5/`;
  Reddit threads r/NothingTech `18mb4uf` and r/NOTHING `18r89pj` (search-snippet level only).
- Atmo Engine: `https://github.com/saad-khan-rind/NOSAtmosphereEffect` (README).
- Repo cross-refs: docs/engineering_specification.md (§9.6, repo map L88-93, hook table L51/L162),
  AGENTS.md §3.2, docs/ideas.md "Atmosphere Selection Surface" row (L166+), docs/fix-registry.md FIX-001.
