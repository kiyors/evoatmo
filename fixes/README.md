# fixes/ — Stream-A findings

Current annoyance board & status flow: [`docs/fix-registry.md`](../docs/fix-registry.md)
(capture → triaged → repro'd → fix-designed → patched → verified → upstreamed / keep-private, + stale after ROM updates).

One folder per finding, named `FIX-NNN-<slug>/`, created at triage:

```
FIX-012-nofity-dims/
  note.md        ← symptom, root cause, chosen kill path, status history
  evidence/      ← logs, greps, dumps — stamped with the build-ID (fingerprint)
  check.md       ← scripts to prove the fix works — and that a ROM update revived nothing
  patch/
    cnb/         ← diff on the frameworks_base `cnb` branch
    bka/         ← diff on the device-tree branch
```

Rules: evidence **before** code (spec §9.6 greps + runtime dumper); fail-open hooks; never flash to "fix" anything — a Stage-1 hook or a reverted settings key is always enough, and everything here must be toggle-off reversible.
