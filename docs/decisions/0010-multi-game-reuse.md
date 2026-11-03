# 0010 — Built for reuse by FE6 and FE7 ports

**Status.** Accepted (owner, 2026-10-25).

**Context.** FE6/FE7 may be ported later. They share FE8's engine (LZ77/tiles, Huffman text, m4a, procs,
map/unit/item/class/battle structure) but differ in struct layouts, some formulas/skills, event formats and
game-only systems (FE8: world map, skirmishes, Creature Campaign, branched promotions, trainees, monsters).

**Decision.**
1. **Layers, dependencies downwards only:**
   - `gba.*` (`:gba:*`, M4-06): any GBA port; imports neither `gbafe.*` nor `fe8.*` (`tools/tests/test_layering.py`).
   - `gbafe.*` (`:engine:*`, `:tools:extract` minus `gbafe.extract.fe8`): game-neutral; never names a game,
     FE8 constant, id or address.
   - Shared FE systems (battle, map, units, items, AI, menus, chapter flow, text, portraits, animations, saves):
     `gbafe.<system>` modules, created when first ported. Differences enter as data, strategies (0009) or
     per-game profiles, never `if (game == FE8)`.
   - `fe8.*` (`:game`, `:app`, `:testing`) + `gbafe.extract.fe8`: FE8-only systems, strategy wiring, ROM
     layouts, ground-truth tests.
2. **Shared formats:** `gbafe.<table>` with a `game` field (`fe6|fe7|fe8`); schemas cover all three. Assets:
   indexed PNG, JSON, Tiled, MIDI, WAV, `.fe8s` (per-game command table).
3. **Per-game ROM layouts** in `gbafe.extract.<game>`; decoders stay generic.
4. **Per-game behaviour = strategy** (e.g. FE6 `NextRN_100` rounding), verified per game.
5. **Enforced** by `tools/tests/test_layering.py` (no `fe8.*` in `gbafe`).

**Consequences.** Code, formats and tools carry over; asset files never (each port extracts its own ROM). Each
ported system states shared vs FE8-only in its module README. Unverified until a second game exists.
