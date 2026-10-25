# Atmosphere unlock — feature note

Stream-B feature: the Nothing-style Atmosphere unlock morph on the Nothing Phone (2) (`Pong`),
running as a Stage-1 Xposed module (Vector) targeting Evolution X. Spec of record:
[`docs/engineering_specification.md`](../../docs/engineering_specification.md) §7 (engine),
§8.1–§8.4 (module), §8.8 (selection surface); ideas row in [`docs/ideas.md`](../../docs/ideas.md).

## State — implemented, build-green, awaiting device evidence

- **2026-10-25 — selection surface + module materialized** (`feat(module)` commit):
  - Module sources transcribed from spec §7/§8.3 to:
    `module/app/src/main/java/com/android/systemui/nothing/atmosphere/NothingAtmosphereEngine.kt`,
    `module/app/src/main/java/dev/pong/evo/atmosphere/{AtmosphereKeys,AtmosphereActivity}.kt`,
    `module/app/src/main/java/dev/pong/evo/atmosphere/hook/HookEntry.kt`, res (strings/layout/icon),
    manifest (WRITE_SETTINGS + launcher + `ACTION_SET_WALLPAPER` filters per research branch
    `research/evox-wallpaper-style-picker` memo A.4/A.6).
  - `./gradlew assembleDebug` exits 0; APK verified statically (aapt2 dump): `assets/xposed_init`
    = KSP-generated `HookEntry_YukiHookXposedInit`, manifest carries the permission + both filters.

## Decisions & deviations (all documented in the spec, same sitting)

| # | Deviation from the spec text | Why |
| --- | --- | --- |
| 1 | §7 import block lacked the `Handler`/`Looper` imports its own note claimed were "already listed" | Spec bug; fixed in both the engine copy and §7 |
| 2 | §7 `setWallpaperColors` called `WallpaperColors.getMainColors()` — **not in the public SDK** | Rewritten over `getPrimaryColor()/getSecondaryColor()/getTertiaryColor()` (each returns an `android.graphics.Color` object) [verified: developer.android.com reference + android-36 stub via javap — 2026-10-25]; spec §7 updated |
| 3 | `meshStrength` (private field) had no host-pushable channel for the §8.8 knob | Added engine companion `var meshStrengthScale = 1f` + uniform feed `meshStrength * meshStrengthScale`; spec §7 + §8.8 row updated. Otherwise identical render path |
| 4 | §8.3 sketch used `resolve().firstMethodOrNull{}` — a phantom API (does not exist in YukiHookAPI 1.2.1) | Translated to the verified 1.2.1 finder form (`toClassOrNull()` + `method { … }.hook { }` + `result { onNoSuchMemberFailure { … } }`); every anchor name unchanged; spec §8.3 carries the API-truth note. `mScrimBehind` read via plain fail-open reflection (no 1.2.1 field-style finder); wildcard `hook.factory.*` expanded to named imports |
| 5 | Knob clamps (§8.8): settled **1–100** (documented now — lower bound exists because the engine's `coerceIn(0.5f, peak)` throws when `peak < 0.5`), peak **1–300**, durations **100–2000**, mesh **0.0–2.0** stored as a string, easing fails open to 0 (hook reads the key raw — no clamp pair until a second preset exists) | Spec §8.8 rows updated; `AtmosphereKeys` is the single source for names/defaults/clamps used by both screen and hook |
| 6 | Build pins grew: `androidx.annotation:annotation:1.9.1` (Yuki's KSP emits `@Keep`), JitPack repo (FreeReflection), Appodeal mirror for `de.robv.android.xposed:api:82` (official `api.xposed.info` dead), `buildToolsVersion = "36.0.0"` (Nix flake SDK) | Spec §8.1 delta list updated |

## Known warnings (accepted)

- Yuki 1.2.1 logs a deprecation per finder-hook site ("will be removed in 2.0.0") — inert while
  pinned to 1.2.1; revisit if the 2.0.0 port happens (see §8.3 note).
- `nothing_atmosphere_easing_preset` has exactly one live preset (0 = spec curve); nonzero values
  fail open to 0 with a one-time `EvoAtmo` WARN. A visible preset chooser arrives with the
  easing work (ideas row 15).

## Verification status

| Check | Status |
| --- | --- |
| `./gradlew assembleDebug` exit 0 | PASS (host) |
| APK manifest/entry static verify (aapt2) | PASS (host) |
| Two-axis code review (fixed point `f942094`, standards + spec) | PASS after fixes — hard findings (col-0 continuation, 101-char line, commented-out code, missing engine KDoc, unguarded receiver/palette wiring) fixed in-commit; judgement-call smells accepted (see the commit body) |
| §8.8 acceptance checklist device items (install, restart, crash gate, grant flow, knob rounds, toggle-off parity, evidence stamp) | PENDING — no device attached during this session; run per [`docs/operations.md`](../../docs/operations.md) §5 rows 14–16 + §8.8 checklist, stamp to `assets/qa/<buildID>/` |
