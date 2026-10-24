# evoatmo

Personal Evolution X workbench for the **Nothing Phone (2) (`Pong`)** — rooted, systemless-only.

Two streams:

- **Stream A — Fixes** (`fixes/`): things in Evolution X that annoy me — captured, reproduced, patch-per-branch, evidence-first.
- **Stream B — Features** (`features/`): things EvoX doesn't have. Flagship: the Nothing-style **Atmosphere unlock effect** (animated Gaussian blur + AGSL color-mesh wallpaper morph), as an Xposed (Vector) module first. In-tree / ROM work (Stage 2/3) is **deferred** — not part of the everyday loop.

> **Documentation lives in this repo:** [`docs/`](docs/) — `docs/engineering_specification.md` is the technical source of truth; `basics.md` / `setup.md` / `operations.md` / `ideas.md` are the plain-English guides; `fix-registry.md` is the Stream-A board.

## Layout

```
docs/          ← documentation suite (spec · guides · fix board)
AGENTS.md      ← agent system prompt: five principles, /docs governance, engineering invariants, verification loop
ARCHITECTURE.md ← fast orientation for agents (structure, components, risks, glossary)
flake.nix      ← Nix dev shell (JDK 17 · Android SDK 36 · Gradle · scrcpy)
features/atmosphere-unlock/
  engine/      ← empty until the engine copy is promoted (setup §4.4); module/ holds the copy first
  module/      ← Stage-1 Xposed module (Android Gradle project) — the everyday lane
  systemui/    ← Stage-2 in-tree wiring (deferred)
  evolver/     ← Stage-2 Evolver XML (deferred)
  overlay/     ← Stage-3 systemless overlay packaging (deferred)
fixes/         ← FIX-NNN-<slug>/ finding folders (note + evidence + check + patch-per-branch)
assets/qa/     ← dumper outputs & per-build-ID QA artifacts
tools/{sh,py,rs}/ ← host-side tooling (no device required to run)
research/      ← feasibility memos & scratch studies
```

## Quickstart (code side)

0. Optional: `nix develop` — the flake provides JDK 17, the Android SDK (API 36), Gradle, Kotlin, and scrcpy, and keeps `local.properties` pointing at a valid SDK.
1. Open `features/atmosphere-unlock/module/` in Android Studio (JDK 17+) and let it sync.
2. Copy the code from the spec (`docs/engineering_specification.md`) into the module exactly per `docs/setup.md` §4.4:
   engine → `app/src/main/java/com/android/systemui/nothing/atmosphere/NothingAtmosphereEngine.kt`,
   hooks → `app/src/main/java/dev/pong/evo/atmosphere/hook/HookEntry.kt`.
3. Build → install → restart SystemUI = the whole test loop (no build ❌ no flash ❌ no reboot ❌; details: `docs/operations.md`).

Version pins live in `module/gradle/libs.versions.toml`; build pins are provisional — record any change with `[verified: …]` in `docs/setup.md` §4.2.
