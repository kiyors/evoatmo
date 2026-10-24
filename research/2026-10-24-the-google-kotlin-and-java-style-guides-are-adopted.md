# 2026-10-24 — the Google Kotlin & Java style guides are adopted as the evoatmo style floor

## What was asked

Check Google's style guides for this codebase and adopt them into `AGENTS.md` "as good and clean
as we can write". The user linked the Java guide page
(`google/styleguide` → `gh-pages/javaguide.html`) and pasted the Android **Kotlin** style guide
text alongside it — the two documents that together define Google's Android source-code standards.

## Why

- The tree is 100% Kotlin (and Java is only ever a later, special-case guest for upstream patch
  parity). Inventing a bespoke house style would drift from IDE defaults and add review overhead
  for zero benefit — the exact over-engineering pattern §1.2 of `AGENTS.md` forbids.
- Google's guides are deliberately composed of hard-and-fast, enforceable rules and avoid
  unenforceable advice — the right kind of authority to import wholesale.
- Adopting **before the first line of Kotlin exists** is the cheapest possible moment: no reformat
  churn, no legacy exceptions to negotiate.

## How verified

- Full text fetched and read on 2026-10-24 from the primary sources — not from memory or a mirror:
  - Kotlin guide: `https://developer.android.com/kotlin/style-guide` (page footer states
    "Last updated 2026-09-22 UTC").
  - Java guide: `https://github.com/google/styleguide/blob/gh-pages/javaguide.html`
    (raw `javaguide.html` from the `gh-pages` branch).
- Every rule distilled into `AGENTS.md` §7 was re-checked against the fetched text, quoting the
  guides' own examples (`"μs"`, `XmlHttpRequest`, `enum class Answer { YES, NO, MAYBE }`,
  `_table` backing `table`, the KDoc tag order, the constant-definition clause).
- Noted and reconciled the one asymmetry the two guides carry deliberately: **+4 indent for Kotlin,
  +2 for Java** — each file obeys its own guide's terms.

## Decision

- **Adopted verbatim as the mechanical floor** (authority ladder: house rule > guide > silence,
  never guide-contradiction).
- **Kotlin guide** governs every `.kt` file: file hygiene/order, wildcard ban, one-theme files,
  100-column limit, K&R braces + the single-line exceptions, wrapping discipline, whitespace list,
  enum/annotation forms, implicit-type rule (explicit retained on the engine's public API —
  the guide's own "writing a library" clause), naming (constant definition + object/top-level
  placement rule, deterministic camel-case scheme, backing properties), and KDoc rules.
- **Java guide is dormant** (zero `.java` files today). Listed in §7.5 on its own terms so a future
  upstream-parity `.java` file inherits it without translation.
- **House rules preserved on top** (§7.4): frozen engine package, pure-SDK language split,
  `@InjectYukiHookWithXposed` entry trap, single `EvoAtmo` log tag, the spec-pointer comment layer,
  and KDoc made mandatory on all public engine members (the guide's self-explanatory exception
  is waived there).
- **Enforcement stated honestly** (§7.6): no formatter is wired yet; Android Studio's default
  formatting + the §5 pre-submission checklist are the gate today. When wiring a formatter, pick
  one that implements the adopted guides (ktfmt for Kotlin) — never a contradicting profile.

## Outcome

- `AGENTS.md` §7 rewritten from a six-item sketch into the full adoption (§7.1–§7.6).
- No code affected — the rule lands before the first `.kt` file exists.
- No documentation mirror impact: `AGENTS.md` is a repo-root onboarding file, not one of the eight
  suite files mirrored into `docs/`.
