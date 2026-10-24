# 0006 — What counts as "ported"

**Context.** The README progress bar must never be inflated. "Ported" must mean behaviour proven equal
to the original, not "code exists".

**Decision.** A decomp function counts as ported only if:
1. a main-source KDoc says `Ported from <decomp file>: <FunctionName>` (exact name from
   `reference/decomp_functions.tsv`; several names may be comma-separated), and
2. a test annotated `@VerifiesPort("<FunctionName>")` **passed** in the latest run. Skipped tests (e.g.
   differential tests without reference data) never count.

A verifying test must compare against ground truth that does not come from our port:
- **Differential (preferred):** mGBA run via `mgba-harness` with identical inputs; compare game state
  located through decomp symbol addresses (test-only code in `:testing`), and/or frames (5-bit channels).
- **ROM-derived golden values:** e.g. decompressed asset bytes, table values read from the ROM.
A unit test that restates our own logic is fine to have but must not carry `@VerifiesPort`.

`Partially ported from ...` marks work in progress and is ignored by `tools/progress.py`.

**Consequences.** Progress grows in steps as differential coverage grows, and lags implementation. Pure
helper functions may only become "ported" once a larger differential test exercises them. That is
intended: the number is a conservative estimate.
