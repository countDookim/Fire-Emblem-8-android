# reference/

Decomp-derived, committed, from `tools/gen_reference.py`; **no ROM data**. Regenerate only on a decomp pin bump.

| File | Columns |
|---|---|
| `DECOMP_VERSION` | repo URL, commit |
| `decomp_functions.tsv` | name, file, line, kind (c/asm), system, address |
| `ram_symbols.tsv` | name, address, size, binding (global/local=`static`), region (ewram/iwram) |
| `iwram_symbols.tsv` | name, address (legacy, from `sym_iwram.txt`; prefer `ram_symbols.tsv`) |

`decomp_functions.tsv` is the progress denominator (8352); 15 have no address (`static inline`) but count.

## Regenerate
1. Bump `decomp/PIN` in the ROM repo; its `decomp-map` workflow builds the decomp (byte-identical to the ROM)
   and commits `decomp/fireemblem8.nm.txt` + `fireemblem8.map`.
2. `git clone https://github.com/FireEmblemUniverse/fireemblem8u /tmp/fe8u && git -C /tmp/fe8u checkout <pin>`
3. `python3 tools/gen_reference.py --decomp /tmp/fe8u --nm <rom-repo>/decomp/fireemblem8.nm.txt`
4. Record the bump in `docs/decisions/0007-decomp-choice.md`; review the function-list diff.
