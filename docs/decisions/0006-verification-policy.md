# 0006 — What counts as "ported"

**Status.** Accepted.

**Context.** Progress must never be inflated: "ported" means proven equal to the original.

**Decision.** A decomp function counts only with both:
1. a main-source KDoc `Ported from <decomp file>: <FunctionName>` (exact `reference/decomp_functions.tsv`
   names; comma-separated allowed), and
2. a `@VerifiesPort("<FunctionName>")` test that **passed** in the latest run (skipped never counts).

Ground truth must not come from the port: **differential** vs mGBA (`mgba-harness`, same inputs; state by decomp
symbol and/or frames on 5-bit channels; preferred) or **ROM golden values**. Tests restating our logic never
carry `@VerifiesPort`. `Partially ported from ...` is ignored by `tools/progress.py`.

**Consequences.** Progress is conservative and lags implementation.

See also [0014](0014-roadmap-scope-capture-first.md): capture-first task kinds and the UNVERIFIED list.
