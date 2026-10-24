# Architecture Overview

Critical, living orientation doc for agents and humans: what this codebase is, how it is organized,
and where every kind of change belongs. Update as the codebase evolves. For depth (verified hook
matrix V1–V12, engine code, risk register R1–R12), see `docs/engineering_specification.md`.

## 1. Project Structure

```
evoatmo/
├── docs/                        # Documentation suite: spec (source of truth), guides, fix board
│   ├── README.md                # Index, reading order, repo layout
│   ├── engineering_specification.md  # Hook matrix (V1–V12), engine §7, Stage plan §8–§11, risks R1–R12
│   ├── architecture.md          # Two streams → three deploy forms; languages; doc-sync rules
│   ├── basics.md · setup.md · operations.md · ideas.md
│   └── fix-registry.md          # Stream-A board (capture → … → upstreamed/keep-private)
├── features/
│   └── atmosphere-unlock/       # Flagship feature — Nothing-style Atmosphere unlock effect
│       ├── engine/              # Canonical engine copy (empty today — promoted from module/ later)
│       ├── module/              # ★ Stage-1 Xposed module (Gradle project): app/, wrapper, versions catalog
│       ├── systemui/            # Stage-2 in-tree wiring (deferred)
│       ├── evolver/             # Stage-2 Evolver XML switches (deferred)
│       └── overlay/             # Stage-3 systemless overlay packaging (deferred)
├── fixes/                       # Stream-A findings: FIX-NNN-<slug>/ (note · evidence/ · check.md · patch/<branch>)
├── assets/qa/                   # Per-build-ID runtime dumps & QA artifacts
├── tools/{sh,py,rs}/            # Host-side tooling (no device required to run)
├── research/                    # Feasibility memos & scratch studies
├── flake.nix · .envrc · flake.lock  # Nix dev shell: JDK 17, Android SDK 36, Gradle, Kotlin, scrcpy
├── README.md · AGENTS.md · ARCHITECTURE.md
└── .gitignore
```

## 2. High-Level System Diagram

```
 [Evolver switch]   [adb settings put]                                  (user-visible toggle, read at runtime)
        └────────────────┬──────────────► Settings.System: nothing_atmosphere_unlock_enabled
                         ▼
 [HookEntry — Stage-1 module (YukiHookAPI, KSP)]        ◄── injected by Vector into SystemUI
   hooks (guarded, fail-open): V2 unlock · V3 lock · V4 scrub · V5 drawFrameOnCanvas
                         ▼
 [NothingAtmosphereEngine — pure-SDK Kotlin]                (zero Xposed / zero framework imports)
                         │  fraction 0→1, blur (110→28 px) + AGSL color-mesh, 4–6 float uniforms
                         ▼
 [RenderNode + HardwareRenderer → wallpaper Surface]
                         ▼
 morphed wallpaper rendered BEHIND lockscreen + launcher   (toggle off ⇒ stock EvoX, instantly)

 Deploy ladder:  Stage 1 (module, live-patched)  →  Stage 2 (frameworks/base edit)  →  Stage 3 (systemless overlay)
                 ★ everyday lane                    deferred (gated)                  deferred (gated)
```

## 3. Core Components

### 3.1. Stage-1 Module (`features/atmosphere-unlock/module/`)

Name: EvoAtmo Xposed module (`:app`)

Description: Runtime-only adapter that owns hooks and reads the settings key, then forwards the
verified callbacks to the engine. Does not implement the effect itself; lives/burns with SystemUI
processes. Fail-open guards everywhere; toggling the module off in Vector returns the device to stock.

Technologies: Kotlin, YukiHookAPI 1.2.1 (`ksp-xposed`), Xposed api 82, Android Gradle (AGP 8.9.2), Java 17.

Deployment: sideloaded APK loaded via Vector (JingMatrix) — scope `com.android.systemui` only; LSPosed_mod fallback.

### 3.2. Engine (`features/atmosphere-unlock/module/app/src/main/java/com/android/systemui/nothing/atmosphere/`)

Name: `NothingAtmosphereEngine`

Description: The whole effect: one public transition call, internal blur-ramp + AGSL mesh, seed
randomization, palette borrow from `WallpaperColors`, idle/settle behavior, screen-off teardown.
Package kept identical for Stage-2 parity. One copy is canonical; promotion to `engine/` + Gradle
source-sets happens once, never dual-maintained.

Technologies: pure Android SDK Kotlin, RuntimeShader (AGSL), RenderNode, HardwareRenderer.

Deployment: compiled into the Stage-1 module today; later into the EvoX tree / overlay unchanged.

### 3.3. Host Tooling (`tools/{sh,py,rs}/`)

Description: log triage, asset prep (depth cutouts), APK/diff inspection — runs entirely on the
dev machine, no device needed at runtime.

Technologies: Python 3, POSIX shell, Rust (CLIs only — never device-side code).

### 3.4. Documentation (`docs/`)

Description: spec + plain-English guides + the Stream-A board; the evidence trail
(`fixes/*/evidence/`, `assets/qa/`) is written for humans first, agent-explainable second.

## 4. Data Stores

Runtime state: `Settings.System` keys (e.g. `nothing_atmosphere_unlock_enabled`) — owned by the
system; read at runtime, never written by the module. Per-unlock persistence is forbidden (R6).

On-disk (this repo): evidence and QA artifacts under `fixes/*/evidence/` and `assets/qa/<buildID>/`;
per-finding notes/scenarios (`note.md`, `check.md`); branch-tagged patches (`patch/<branch>/`).

Deferred (Stage 3): a Magisk module tree under `/data/adb/modules/` — systemless only, never a
direct partition write (R10).

## 5. External Integrations

Vector (JingMatrix): Xposed framework performing injection; our APK is passive. LSPosed_mod fallback.

Evolution X (upstream): ROM we run on; source of truth for behavior; patch/PR target for Stream-A/Stream-B
promotions (`frameworks_base` branch `cnb`, device tree branch `bka`).

Maven `api.xposed.info` (content-filtered): serves `de.robv.android.xposed:api:82`.

## 6. Deployment & Infrastructure

Cloud: none — deliberately. The device (Nothing Phone (2) `Pong`, rooted, Evolution X) is the runtime.

Install path: `adb install` the module APK → enable in Vector → restart SystemUI. No CI yet;
GitHub Actions is a candidate for ktlint + assembleDebug.

Monitoring: `adb logcat` greps (`EvoAtmo` tag), crash buffer after each restart, member dumper builds.

## 7. Security Considerations

Fail-open hooks: `firstMethodOrNull` + `try/catch → callOriginal()` — a rename degrades the
feature, never the boot (R4/R5).

Signature parity (R1): never overlay onto a CI-signed official ROM; Stage-3 output must be signed
with my own platform keys or not ship at all.

Systemless-only (R10): partitions are never written; slots never switched. Worst-case recovery:
armed `magisk --remove-modules` disabler + Safe Mode (`docs/operations.md` §6).

Surface discipline: scope stays `com.android.systemui` only; no network code, no wasted permissions.

## 8. Development & Testing Environment

Local setup: `nix develop` (or Android Studio + JDK 17); module tests = device loop + §5 checklists
(`docs/operations.md`); `ktlint` available (Gradle gate not yet wired); lint via `./gradlew lintDebug`.

## 9. Future Considerations / Roadmap

Promote the engine to `features/atmosphere-unlock/engine/` + Gradle source-sets (setup §4.4).

Stage-2/3 promotion only behind the ops §3/§4 gates; Stream-B roadmap in `docs/ideas.md` (T0–T3).

Add a minimal Actions CI (ktlint + assembleDebug) on the GitHub repo, extend `tools/rs/`.

## 10. Project Identification

Project Name: evoatmo (EvoAtmo)

Repository URL: `https://github.com/kiyors/evoatmo`

Primary Contact: Gaurav (owner) — personal workbench

Date of Last Update: 2026-10-24

## 11. Glossary / Acronyms

EvoX: Evolution X — the custom Android ROM in use.

Pong: Nothing Phone (2) device codename.

Atmosphere (unlock): the Nothing-style 720 ms blur + color-mesh wallpaper morph on unlock.

Stream A / Stream B: fixes (annoyances) / features (missing capabilities).

Stage 1 / 2 / 3: Xposed module / source tree edit / systemless overlay — the promotion ladder.

Vector: Xposed framework loader used on device (LSPosed_mod as fallback).

YukiHook (formerly YukiHookAPI): hooking framework consumed via `api` + `ksp-xposed` 1.2.1.

AGSL: Android Graphics Shading Language (RuntimeShader).

Scrim / keyguard: lockscreen dimming layer / lock UI — where triggers V1–V4 live.

Build-ID: `ro.build.fingerprint` prefix stamping every evidence artifact to a ROM build.

Systemless: making changes via Magisk module payloads instead of writing partitions.

Fail-open hooks: guards that degrade a feature silently instead of crashing the host process.

FIX-NNN: one Stream-A finding = one folder (`fixes/FIX-012-nofity-dims/` style).

KSP: Kotlin Symbol Processing — compiles the YukiHook entry + generates `xposed_init`.
