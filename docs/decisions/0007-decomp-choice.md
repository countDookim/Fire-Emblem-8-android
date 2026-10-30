# 0007 — Decomp: fireemblem8u instead of StanHash/fe8

**Status.** Accepted (owner, session 1).

**Context.** The brief named StanHash/fe8: stale since 2021, ~7k undecompiled asm functions.
FireEmblemUniverse/fireemblem8u builds the same ROM (SHA1 `c25b145e…509f`), ~72 asm functions left (mostly
libc/syscall/m4a stubs), actively maintained.

**Decision.** Use fireemblem8u, pinned in `reference/DECOMP_VERSION`. Bump: re-run `tools/gen_reference.py`
(see [reference/README.md](../../reference/README.md)), diff the function list, record it here.

| Date | Commit |
|---|---|
| 2026-10-24 | `04d1edb163d14bd10777a065697e1a9f17be65f2` |

**Consequences.** KDoc/`@VerifiesPort` names follow fireemblem8u. ~8.35k functions in the denominator (parsed C
functions + remaining asm labels).
