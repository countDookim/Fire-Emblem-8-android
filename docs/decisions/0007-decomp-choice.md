# 0007 — Decomp: fireemblem8u instead of StanHash/fe8

**Context.** The brief named StanHash/fe8. On inspection (2026-10-24) it was last updated in Oct 2021 and
consists of ~31k lines of C plus ~383k lines of disassembly (~7k undecompiled asm functions).
FireEmblemUniverse/fireemblem8u builds the same ROM (SHA1 `c25b145e…509f`), has ~339k lines of C with
~72 asm functions left (mostly libc/syscall/m4a stubs), and is actively maintained.

**Decision.** Use fireemblem8u, pinned to the commit in `reference/DECOMP_VERSION`. Approved by the
owner in session 1. Bumping the pin: re-run `tools/gen_reference.py`, diff the function list, record the
bump in this table.

| Date | Commit |
|---|---|
| 2026-10-24 | `04d1edb163d14bd10777a065697e1a9f17be65f2` |

**Consequences.** Function names in KDoc/`@VerifiesPort` follow fireemblem8u naming. ~8.35k functions in
the denominator (C functions parsed heuristically + remaining asm labels; see `tools/gen_reference.py`).
