# AGENTS.md

System prompt and operational instructions for AI agents working in the **evoatmo** codebase —
the personal Evolution X workbench for the Nothing Phone (2) (`Pong`): Stream A **fixes** (things
in Evolution X that annoy me) and Stream B **features** (things EvoX lacks; flagship is the Nothing-style
Atmosphere unlock effect). Everything below exists to keep the device **bootable at all times** and
every experiment **reversible in seconds**.

> **Related Documents**:
>
> - [`ARCHITECTURE.md`](./ARCHITECTURE.md) — agent-facing orientation: structure, components, risks, glossary.
> - [`README.md`](./README.md) — repo overview, layout, quickstart.
> - [`docs/README.md`](./docs/README.md) — index and reading order of the documentation suite.
> - [`docs/engineering_specification.md`](./docs/engineering_specification.md) — the technical source of truth: verified hook matrix (V1–V12), engine code (§7–§8), stage plan (§8–§11), risk register (R1–R12), symbol-grep protocol (§9.6).
> - [`docs/architecture.md`](./docs/architecture.md) — two streams → three deploy forms; languages; change-placement table; doc-sync matrix.
> - [`docs/setup.md`](./docs/setup.md) — environment, device prep, module project wiring, build pins.
> - [`docs/operations.md`](./docs/operations.md) — the safety shield, daily hot-reload loop, post-OTA re-verification, test plan, failure playbook.
> - [`docs/ideas.md`](./docs/ideas.md) — Stream-B roadmap (Tiers T0–T3).
> - [`docs/fix-registry.md`](./docs/fix-registry.md) — Stream-A board: statuses, folder/note templates, rules.

---

## 1. Core Behavioral Directives

Adhere strictly to these rules. Bias toward caution and precision over haste.

### 1.1 Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

- **State Assumptions**: Explicitly state assumptions before touching anything; ask rather than guess.
- **Surface Ambiguities**: If multiple interpretations exist, present them — do not pick one silently.
- **Push Back**: If the requested approach is wrong for the device (risk of boot trouble, persistence, scope creep), say so directly and propose the simpler, safer path. No sycophancy.
- **Halt on Confusion**: If code logic or a hook behavior is ambiguous, stop, name the confusion, ask.

### 1.2 Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- Implement exactly what was requested — no unrequested configurability, no premature abstractions.
- No over-defensive coding: no error handling for impossible internal states; the *mandatory* defensive code is the fail-open hook pattern defined in §3.2, nothing more.
- Prefer the native/SDK path before adding a dependency. Every new dependency must land in `module/gradle/libs.versions.toml` with a version and a reason.

### 1.3 Surgical Changes

**Touch only what you must. Clean up only your own mess.**

- Never reformat or "improve" adjacent code, comments, or styling; match existing style.
- Remove only dead code your own change introduced; pre-existing dead code gets mentioned, not deleted.
- Every changed line traces directly to the task.
- Keep the repo free of local-control words (no references to personal offline notes or home folder paths) — the repo is public-bound; write docs so a stranger understands them alone.

### 1.4 Goal-Driven Execution

**Define success criteria. Loop until verified.**

- Convert every task into concrete, testable milestones before editing. For multi-step work, state the plan as an execution loop:
  ```
  1. [step] → verify: [command or on-device check]
  2. [step] → verify: [command or on-device check]
  ```
- The available verifiers are listed in §4.3 (device checks). Run them before declaring completion; a change is done only when the toggle-off parity check passes.
- Naive implementation first, verification second, optimization only after correctness is proven — then re-verify.

### 1.5 Clear, Ubiquitous Code Comments

**Write comments so any person can understand the code.**

- Every file opens with a header comment: role, purpose, integration points (which spec section it implements).
- Every hook, uniform, and edge-case handler gets a plain-English comment; document the **why** behind non-obvious workarounds (Android/ART quirks, ROM-specific members, timing constraints).
- A comment says what the code does not: no name-restating, no language lessons.

---

## 2. Documentation Governance & The `/docs/` Rule

`docs/` is the authoritative knowledge base of this repository; `ARCHITECTURE.md` and this file carry the agent orientation. Documentation must mirror reality — an undocumented change is technical debt.

### 2.1 Always Take Reference from `/docs/`

- **Take Reference First**: Before implementing anything, consult the spec for the relevant hook/layer (`docs/engineering_specification.md`) and the operating rules (`docs/architecture.md` §3 placement table, `docs/operations.md`).
- **Zero Hallucination Policy**: Class and method names may only come from the spec's verified table V1–V12 or from runtime evidence produced on this exact ROM build (§3.4 below). Never invent, extrapolate, or approximate Android-internal names. Claims get a `[verified: <source> — date]` citation; open questions get a `[VERIFY: …]` marker — never silent guesses.

### 2.2 Always Document Changes in `/docs/`

- **Mandatory Update on Change**: Any architectural, code, feature, or configuration change updates the corresponding record **in the same sitting** — follow the doc-sync matrix in `docs/architecture.md` §5 (spec §5/§7 for hooks and engine deltas, `docs/operations.md` for deploy commands, the board for fixes).
- **Keep State Synchronized**: version pins, known issues, and checklists stay current with dated notes.

### 2.3 Agent Operational Records

Record significant work in the slot that already exists for it — never scatter new parallel indexes:

- **Features** (Stream-B): `docs/ideas.md` row for the idea → feature folder under `features/<slug>/` with its `note.md` once promoted; the row's tier/status updated at each step.
- **Fixes** (Stream-A): one folder per finding, `fixes/FIX-NNN-<slug>/` with `note.md` (symptom, root cause, chosen kill path), `evidence/`, `check.md`, `patch/<branch>/`; the board row in `docs/fix-registry.md` carries the status (`captured → triaged → repro'd → fix-designed → patched → verified → upstreamed / keep-private`, `stale` after ROM updates).
- **Investigations / decisions** (spikes, diagnostics, architecture changes): a dated memo in `research/YYYY-MM-DD-<slug>.md` — one decision per memo, what was asked, why, how verified, outcome.

Trivial fixes (typos, formatting, no behavior change) skip the record.

---

## 3. evoatmo Architecture & Engineering Rules

### 3.1 System Overview

A rooted device (`Pong`) runs customer ROM **Evolution X** with the Xposed framework **Vector**. One pure-SDK Kotlin engine renders the Atmosphere unlock morph directly to the wallpaper surface; two streams feed compute into it and three deploy forms carry behavior to the device:

```
Stream A (fixes) ─┐                           Stage 1 ★ everyday: Xposed module, live-patched,
                 ├─► same engine & hooks ──►          no reboot, no flash
Stream B (features)┘                         Stage 2: frameworks/base edit (deferred, gated)
                                              Stage 3: systemless overlay (deferred, gated)
```

Stage 2/3 are **not part of routine work** — they only unlock through the gates in `docs/operations.md` §3/§4, and an agent must never "helpfully" produce a flashing/ROM-building step.

### 3.2 Strict Invariants

1. **Engine purity (dependency rule)**: `NothingAtmosphereEngine` imports neither Xposed nor any framework class — zero `de.robv`, zero `highcapable`, zero `com.android.systemui.*` imports. Hosts call it; the engine never learns its host. Hooks/wiring → engine → nothing.
2. **Fail-open hooks**: every hook is resolved with `firstMethodOrNull` guard + `try/catch → callOriginal()`. A renamed member degrades the *feature*, never the boot; existing features must never break because a new one failed. Every debug/dev APK ships the member dumper (`dumpMembers`).
3. **Runtime-only state**: no persistence per unlock, ever (R6) — never `WallpaperManager.setBitmap()` per unlock; the morph is visual-only on the live Surface. GPU work lives inside the 720 ms transition window; the settled state is cheap; screen-off tears down (R7).
4. **Systemless-only + signature parity** (R10/R1): partitions are never written, slots never switched; an overlay of my remade SystemUI must be signed with my own platform keys and must never be applied onto a CI-signed official ROM.
5. **Toggle-off reversibility**: every feature, fix, and flavor must return the device to stock Evolution X behavior by toggling the module off (or the settings key to 0). If an agent cannot name the off-switch, the change is not done.
6. **VERIFY-FIRST outside the spec**: hook targets beyond the V1–V12 table require the §9.6 greps on the synced tree plus a runtime dumper read on this exact ROM build, with artifacts stamped by build (`ro.build.fingerprint`) under `assets/qa/<buildID>/` or `fixes/*/evidence/`. Build-ID branch prefix (`CN…`/`BK…`) must match the synced branch (`cnb`/`bka`) — see `docs/operations.md` §3.
7. **Monet / `WallpaperColors` isolation**: palette use is read-only (`getWallpaperColors(FLAG_SYSTEM)`); never write into the Monet/color pipeline (§1 non-goals).

### 3.3 Workspace Map

```
features/atmosphere-unlock/
  module/            ★ the only active build target today (Gradle :app)
    app/src/main/java/dev/pong/evo/atmosphere/hook/     ← HookEntry (hooks, dumper)
    app/src/main/java/com/android/systemui/nothing/atmosphere/ ← engine copy (parity package)
    gradle/libs.versions.toml                           ← all version pins with reasons
  engine/            ← empty; canonical engine copy promoted here later (setup §4.4)
  systemui/ evolver/ overlay/   ← Stage 2/3 slots (deferred — do not populate casually)
fixes/FIX-NNN-<slug>/  ← Stream-A finding folders
assets/qa/             ← build-ID-stamped dumps and QA outputs
tools/{sh,py,rs}/      ← host-side tooling only (no device code)
```

### 3.4 Scope Constraints

- The everyday lane is Stage 1: build, install, restart SystemUI, observe. **No ROM builds, no image flashing, no slot switching** as part of any task unless the user explicitly asks for a deferred-lane step.
- `module/` is the single active Gradle project. Changes to `tools/` must not require a device; changes to the engine never fork per stage.
- Experiment lanes: the module runs with fail-open guards — a crash-loop is recoverable (`docs/operations.md` §6 ladder: disable module → Safe Mode → armed disabler). Know the ladder before touching SystemUI.

---

## 4. Build, Test & Verification Workflows

### 4.1 Environment

- Enter the dev shell via direnv (`direnv allow`) or `nix develop` — JDK 17, Android SDK (API 36), Gradle, Kotlin, scrcpy, platform-tools/`adb`.
- **No Nix?** Install JDK 17 + Android SDK yourself and set `ANDROID_HOME` (macOS: `~/Library/Android/sdk`, Linux: `~/Android/Sdk`). **Always ask the user for explicit permission before installing anything via the system package manager.**
- All builds via the Gradle wrapper (`./gradlew`) — never a globally installed Gradle. Pins: Gradle 8.11.1, AGP 8.9.2, Kotlin 1.9.24, KSP 1.9.24-1.0.20 (`gradle/libs.versions.toml`); YukiHook `api` and `ksp-xposed` must match one-to-one (spec §8.1, 1.2.1 pin).

### 4.2 Build & Install (from `features/atmosphere-unlock/module/`)

```bash
./gradlew assembleDebug            # build the module APK
./gradlew installDebug             # install on the connected device
adb su -c "pkill -f com.android.systemui"   # restart SystemUI = apply hooks (seconds, no reboot)
adb logcat -d | grep EvoAtmo       # engine/hook log tag
```

### 4.3 Device Verification Loop (every behavior change)

1. **Crash gate**: `adb logcat -b crash -d` — nothing new after the restart.
2. **Visual check** (per feature/fix): run that fix/feature's scenarios from `docs/operations.md` §5.
3. **Toggle-off parity**: settings key → 0 (or module off in Vector) → behavior returns to stock Evolution X.
4. **Evidence stamp**: artifact saved under `assets/qa/<buildID>/` or `fixes/*/evidence/` with the fingerprint recorded.
5. **Post-OTA extra** (`docs/operations.md` §3): fingerprint ↔ branch match, Appendix C greps, member dumper, board re-walk before trusting old evidence.

There are no host-side unit tests by design; verification is device-based. Do not invent a test framework requirement — the spec's evidence protocol replaces it.

---

## 5. Git Commit & PR Standards

- **Commit Format**: Conventional Commits, `<type>(<scope>): imperative subject`
  - types: `feat`, `fix`, `perf`, `refactor`, `docs`, `style`, `chore`, `test`
  - scopes: `module` · `engine` · `hooks` · `tools` · `docs` · `board` · `deps`
  - examples: `feat(module): wire V2 unlock trigger`, `fix(hooks): fail-open guard for scrub app`, `docs(spec): add V6 verification note`
- **Commit Body**: detailed what + why (bullets for multi-change commits); link the evidence artifact for behavior changes; no empty or vague messages; no emojis in commit messages or PR titles.
- **PRs** (Stream-A/Stream-B escalations): minimal diffs, existing style, one concern per PR, test evidence attached, `@string` resources in Evolver XML — the pipeline produces PR-shaped patches by construction. A merged upstream PR does **not** change local behavior until the released EvoX build ships it; the local patch is the fallback.

### Pre-Submission Checklist

- [ ] `./gradlew assembleDebug` exits 0 (module dir).
- [ ] Installed + SystemUI restarted; `adb logcat -b crash -d` clean.
- [ ] Toggle-off parity verified (off = stock behavior).
- [ ] Evidence stamped with build-ID; if hook names were newly verified, recorded per the §9.6 protocol.
- [ ] `/docs/` updated in the same sitting (spec row, ops command deltas, board status) — see §2.2.
- [ ] Repo copy clean of personal-location wording (offline notes / private paths).
- [ ] No secrets, no debug logging left on, no unrelated file churn.
- [ ] Conventional-commit title + a real commit body.

---

## 6. Agent Skills (Reference)

### Operational Frameworks

- **Stream-A board** (`docs/fix-registry.md`): the working "issue tracker" — rows, statuses, folder/note templates, and the 10-second capture habit. GitHub Issues may be added later after publishing; until then the board is canonical.
- **Triage vocabulary**: not GitHub labels — the board's status ladder (`captured → triaged → repro'd → fix-designed → patched → verified → upstreamed / keep-private`; `stale` after a ROM update). Apply the status honestly; never skip the evidence status.
- **Domain reference**: `ARCHITECTURE.md` §11 glossary + `docs/basics.md` for plain-English Android/SystemUI concepts. `docs/engineering_specification.md` is the last word on conflict.
- **Evidence-driven uncertainty**: when a hook/member list differs from the spec, trust the dumper on this build, dump the finding, then either record a spec correction row or an `[VERIFY]` marker — never a silent override.

### Working Modes

- **Bug-fixing → Stream-A mode**: registry row first (10 seconds), reproduce, evidence, then choose the cheapest kill path (setting → Stage-1 hook → deferred source patch). Evidence-before-code, always.
- **Feature work → Stream-B mode**: idea row → tier (T0–T3) → promotion to `features/<slug>/` → VERIFY gate → engine → wiring → UI, per `docs/architecture.md` Stream-B pipeline.
- **Device safety is the hard gate**: if a step's failure mode includes "won't boot", it does not belong in this lane.

---

## 7. Adopted Style — Google's Android Kotlin guide (+ Java for future files)

Both Google style guides for this codebase were fetched and read in full on 2026-10-24 and are adopted **verbatim** as the mechanical floor:

- **Kotlin guide** — Google's Android coding standards for the Kotlin Programming Language, `https://developer.android.com/kotlin/style-guide` (page last updated 2026-09-22) `[verified: fetched 2026-10-24]`. Governs every `.kt` file in this repo.
- **Java guide** — Google's Java Style, `https://github.com/google/styleguide/blob/gh-pages/javaguide.html` `[verified: fetched 2026-10-24]`. Dormant today (zero `.java` files); it takes over any `.java` file written later for upstream EvoX patch parity, on its own terms (§7.5).

Authority ladder: a house rule below that goes beyond a guide wins; anywhere the guides cover something house rules do not, **the guide wins silently**. The bar this sets: a stranger who writes Android for a living reads our code without needing any of our context.

### 7.1 Mechanical floor — Kotlin

1. **File hygiene**: UTF-8; the only interior whitespace is the ASCII space character (0x20 — tabs never indent); short escape sequences (`\n`, `\t`, `\\`, `\$`, …) instead of Unicode escapes; printable non-ASCII written literally when that reads better (`val unitAbbrev = "μs"` beats `"\u03bcs"`); escapes allowed for non-printables, with a comment when needed.
2. **File order**: optional copyright header (a block comment — never KDoc-style or `//` lines) → file-level annotations → package → imports → top-level declarations, with exactly **one blank line** between each section. `package` and `import` lines are never wrapped and ignore the column limit; imports form ONE combined ASCII-sorted list; **wildcard imports are banned in every form** (a hook file names exactly what it touches).
3. **One file, one theme**: a single public type, or one extension family. A file with a single top-level class takes that class's name (`NothingAtmosphereEngine.kt`, `HookEntry.kt`); multi-declaration files take a descriptive PascalCase name (camelCase acceptable for a plural filename). Public declarations per file are minimized, and member order follows a **logical reading order the maintainer can explain** — never "chronological by date added".
4. **Indent / statements**: four-space indent; one statement per line; no semicolons; **column limit 100** with only the guide's exceptions (impossible lines such as a long URL in KDoc, `package`/`import` lines, and cut-and-pasteable shell command lines in comments).
5. **Braces — K&R ("Egyptian")**: no break before `{`, break after `{`, break before `}`, and a break after `}` only when it terminates a statement or a function/constructor/named-class body (never before `else`). Braces are required for `if`, `for`, `when`, `do`, and `while` bodies — even empty ones — except: single-line `if (x) return`, single-line `when` branches, and an `if/else` used as an expression **only** when the entire expression fits on one line. Empty blocks put the closing brace on its own line (`catch (e: Exception) {}` on one line is wrong).
6. **Wrapping**: break at the highest syntactic level — the goal is clear code, not the fewest lines. Break **after** an operator or infix function name, **before** operator-like symbols (`.`, `?.`, `::`); a method/constructor name stays attached to its `(`; commas attach left; lambda arrows attach left. A too-long signature puts each parameter on its own line at +4, with the closing `)` and return type back at the base column; a too-long property initializer breaks after `=` at +4; accessor bodies format like functions; single-expression bodies may become expression functions (`override fun toString() = "Hey"`).
7. **Whitespace**: one blank line between consecutive members (blank lines between adjacent properties are optional, for logical grouping); multiple consecutive blank lines are permitted but never encouraged. Horizontal spaces appear **only** where the guide lists them: reserved word + `(`; `}` + `else`/`catch`; before `{`; both sides of binary operators and the lambda arrow; after `,` and `:`; a space before `:` only for supertype lists and `where` constraints; both sides of the `//` of a line comment — and **never** around `::`, the dot separator, or the range `..`. The rule governs interior space only.
8. **Enums & annotations**: an enum whose constants have no bodies or docs may be one line (`enum class Answer { YES, NO, MAYBE }`); when constants are listed on separate lines, no blank line is needed unless a constant carries a body. Member/type annotations sit on their own lines immediately before the annotated construct; a single no-arg annotation may share the declaration's line; `@[...]` combining is allowed only with an explicit use-site target for two or more no-arg annotations.
9. **Implicit types**: omit the type on expression-function bodies and property initializers when it is a scalar or clearly inferrable (`private val ICON = IconLoader.getIcon(...)`); **retain explicit types on the public API of a library** — our engine is exactly that (§7.4.3).

### 7.2 Naming — Kotlin

1. Identifiers are ASCII `\w+`; prefixes/suffixes like `name_`, `mName`, `s_name`, `kName` are **not used** — the single exception is a backing property, which exactly mirrors the real property plus a leading underscore (`_table` backs `table`).
2. Packages: all lowercase, consecutive words concatenated (`dev.pong.evo.atmosphere.hook`, never `deepSpace` or `deep_space`).
3. Types: PascalCase nouns (interfaces may be adjectives, `Readable`). Test classes end in `Test` (`HashTest`).
4. Functions: camelCase verbs. Underscores appear only inside test names (`pop_emptyStack`). Backtick names are banned — `` fun `test every possible case`() `` is wrong; `testEveryPossibleCase` is right. (The `@Composable fun NameTag` noun-naming rule is noted but dormant — no Compose in this module.)
5. **Constants**: UPPER_SNAKE_CASE — but the guide's definition rules where they may live. A constant is a `val` with **no custom getter**, whose contents are deeply immutable and whose functions have no detectable side effects ("merely intending to never mutate the object is not enough"); constants may only be defined **at top level or inside an `object`** — the same shape inside a `class` must use a non-constant (camelCase) name — and scalar constants must carry the `const` modifier. Consequence for us: shared engine/shader keys live in the engine's `object` as UPPER_SNAKE; per-hook defaults stay camelCase.
6. Type variables: a single capital letter optionally numbered (`E`, `T`, `T2`), or a class-style name + `T` (`RequestT`).
7. **Camel-case scheme** (deterministic): ASCII-ize and drop apostrophes → split into words on spaces/punctuation (splitting conventionally-camel words too) → **lowercase everything, acronyms included** → capitalize first letters (all words = PascalCase; all but first = camelCase). So: `XmlHttpRequest`, `newCustomerId`, `innerStopwatch`, `supportsIpv6OnIos` — never `XMLHTTPRequest`, `newCustomerID`, `supportsIPv6OnIOS`. (`checkNonempty`/`checkNonEmpty` are both correct — English hyphenation is ambiguous.)

### 7.3 KDoc — guide rules

- Format `/** ... */`; the single-line form `/** Short bit. */` only when the whole block fits one line and carries no block tags. A blank line (a line containing only the aligned `*`) separates paragraphs and precedes the tag group.
- Block tags in the order `@constructor`, `@receiver`, `@param`, `@property`, `@return`, `@throws`, `@see` — never with an empty description; continuation lines indent 4 spaces from the position of the `@`.
- Every KDoc opens with a **summary fragment**: a noun/verb phrase, capitalized and punctuated as if it were a sentence — never "A `Foo` is a…", never "This method returns…".
- KDoc is the minimum for every public type and every public/protected member of such a type, with exactly two guide exceptions: truly self-explanatory members (`getFoo`), and overrides. The self-explanatory exception never hides a term the reader may not know — document `canonicalName`; don't hide it behind "Returns the canonical name."

### 7.4 House rules — evoatmo-specific, on top of the guides

1. **Package discipline**: the engine stays in `com.android.systemui.nothing.atmosphere` (Stage-2 parity; do not rename); hooks in `dev.pong.evo.atmosphere.hook`; namespace `dev.pong.evo.atmosphere` (spec §8.1).
2. **Language**: pure-SDK Kotlin for engine and module (AGSL shader text as compiled `RuntimeShader` sources); Java only where touching upstream EvoX patch points later (then §7.5). No commented-out code left in files; English everywhere.
3. **Explicit-everything surface**: the engine's public API keeps explicit types (the guide's library clause + our Stage-2 parity plan). Uniforms are named after what they drive (`blurPeakPx`, `seed`, `paletteOffset`); positional booleans get argument names at call sites; no `get`-prefixed property accessors.
4. **The one entry**: exactly one `@InjectYukiHookWithXposed` per build; never fill `BuildConfig.APPLICATION_ID` into the annotation (documented trap, setup §4.5); generated files (`xposed_init`, entry class) are never hand-written or committed.
5. **Logging**: single tag `EvoAtmo`; hook-resolution failures log at WARN once, not per frame; never per-frame GPU logging.
6. **Comment layer (goes beyond the guides)**: every file opens with a header comment stating its role + the spec section it implements (engine §7, module §8, or a specific V-number hook row); every uniform and hook field carries an inline plain-English comment explaining the **why** (ROM quirks, ART behavior, timing windows); KDoc is **required on all public engine members** — the guide's self-explanatory exception is waived on the engine surface. Zero ownerless TODO/FIXME; any TODO carries an owner/reason.

### 7.5 Java files — the second guide (dormant)

Zero `.java` files exist. If upstream-patch parity ever forces one, that file follows **Google Java Style** on its own terms — including these deliberate differences from our Kotlin lane: two-space indentation (Java) vs four (Kotlin); braces always, even single-statement bodies; exactly one top-level class per file; static imports in their own group with a blank line; Javadoc required on every *visible* class/member (Markdown `///` form allowed alongside `/** */`); `@Override` used whenever legal; caught exceptions never silently ignored — the catch block comments why ignoring is correct; static members qualified via the class name (`Foo.aStaticMethod()`, never via an instance); no finalizers; every switch exhaustive (add a `default` if the language does not force one), fall-through commented, switch expressions arrow-style; one variable per declaration; arrays `String[] args`, never C-style; modifiers in JLS order; uppercase `L` suffix on long literals; `// TODO: <reference> - explanation`.

### 7.6 Enforcement status

No formatter is wired yet (`gradle/libs.versions.toml` carries none). Today the gate is: Android Studio's default formatting (which follows this guide) + the §5 pre-submission checklist review. When a formatter is wired, pick one that **implements these guides** (ktfmt for the Kotlin side) and does not fight them — never a bespoke formatter, never a profile whose rules contradict the adopted guides.

**Sources**: Kotlin guide `https://developer.android.com/kotlin/style-guide` and Java guide `https://github.com/google/styleguide/blob/gh-pages/javaguide.html` — both `[verified: fetched 2026-10-24]`. Adoption record: `research/2026-10-24-the-google-kotlin-and-java-style-guides-are-adopted.md`.

---

## 8. Agent skills

### Issue tracker

Issues for skills like `to-tickets`, `triage`, and `to-spec` live in GitHub Issues (`kiyors/evoatmo`) via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Triage uses the five canonical label strings (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`) — a separate vocabulary from the Stream-A board status ladder. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout: a root `GLOSSARY.md` and `docs/adr/`, created lazily by `/domain-modeling`. See `docs/agents/domain.md`.
