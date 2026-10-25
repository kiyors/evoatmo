# features/atmosphere-unlock — Nothing-style Atmosphere unlock effect

The flagship Stream-B feature: on unlock, a 720 ms GPU-morphed blur + AGSL color-mesh wallpaper
transition, driven by one pure-SDK engine. Technical source of truth (hooks V1–V12, engine code,
risks): `docs/engineering_specification.md` (§7 = engine, §8 = Stage-1 module, §9 = Stage-2 wiring, §11 = Stage-3 packaging).

| Folder | State | Content |
| --- | --- | --- |
| `module/` | **Active (Stage 1)** | Android Gradle project (`:app`), package `dev.pong.evo.atmosphere`, YukiHookAPI 1.2.1; up-to-date with the spec — `./gradlew assembleDebug` green; device verification pending (2026-10-25). Implementation record + deltas: [`note.md`](./note.md) |
| `engine/` | empty (promotion pending) | Canonical engine copy lands here later — promoted from `module/app/src/main/java/com/android/systemui/nothing/atmosphere/` per setup §4.4, shared via Gradle source-sets. One copy, never dual-maintained |
| `systemui/` | deferred (Stage 2) | Java wiring inside EvoX `frameworks/base` — only after the ops §3/§4 gates |
| `evolver/` | deferred (Stage 2) | Evolver XML switches writing `Settings.System` keys |
| `overlay/` | deferred (Stage 3) | Systemless overlay packaging of a remade SystemUI; platform-key signed; never onto CI-signed ROMs (risk R1) |

QA dumps go to `assets/qa/<buildID>/` in this repo.

The **selection surface** (how you turn the effect on) is decision-locked and specced: the module app's own Atmosphere screen + manifest-based system listing — spec **§8.8** (wayfinder map: kiyors/evoatmo#1). No hooks, no preview in v1; keys adopted from the ideas.md registry.
