# 0010 — Built for reuse by FE6 and FE7 ports

**Context.** The owner may later port Fire Emblem 6 and 7. All three GBA games run on the same Intelligent
Systems engine: LZ77/tile graphics, Huffman text, m4a audio, the proc system, map/unit/item/class/battle
structure. They differ in struct layouts (FE6's are smaller), some formulas and skills, event script
formats, and game-only systems (FE8: world map, skirmishes, Creature Campaign/Tower/Ruins, branched
promotions, trainees, monsters). Reuse must be designed in now: finding out later that shared code
assumes FE8 would mean large rewrites.

**Decision.**
1. **Three layers, dependency only downwards.**
   - `gbafe.*` engine (`:engine:*`, `:tools:extract` minus its `fe8` package): game-neutral. Never
     names a game, an FE8 constant, id or address.
   - Shared FE systems (battle, map, units, items, AI, menus, chapter flow, text boxes, portraits, battle
     and map animations, save structure) go in `gbafe.<system>` modules too, created when first ported
     (M4+). Game differences enter only as data, strategy interfaces (0009's `Mechanics`) or per-game
     profiles, never as `if (game == FE8)`.
   - Game layer `fe8.*` (`:game`, `:app`, `:testing`, `gbafe.extract.fe8`): FE8-only systems (list
     above), wiring of FE8's vanilla strategies, FE8 ROM layouts and ground-truth tests.
2. **Formats are shared.** Data files use `gbafe.<table>` formats with a `game` field (`fe6|fe7|fe8`);
   schemas describe all three games (game-only fields optional, game-only enum values documented). Ids
   are decomp constant names. Assets use the generic formats (indexed PNG, JSON, Tiled, MIDI, WAV,
   `.fe8s` scripts — the script *language* is shared; each game gets its own command table).
3. **ROM layouts are per game.** Shared decoders (LZ77, tiles, Huffman, PNG, JSON, symbols) are generic;
   struct readers live in `gbafe.extract.<game>`. A new game adds a package, not changes to shared code.
4. **Per-game behaviour is a strategy.** E.g. FE6's `NextRN_100` rounds differently, crit/skill rules
   differ: each becomes a strategy with one vanilla implementation per game, verified against that
   game's decomp/ROM. Ports cite the FE8 decomp until another game is added.
5. **Enforced:** `tools/tests/test_layering.py` fails CI if any `gbafe` source imports `fe8.*`.

**What "reusable" means.** Code, formats, loaders, schemas and tools carry over. Asset *files* do not:
each port extracts its own from its own ROM (content differs and is never shared between repos).

**Consequences.** Packages renamed `fe8.*` → `gbafe.*` for engine/extract code (2026-10-25, while small);
data formats renamed `fe8.items` → `gbafe.items` etc. (supersedes the example in 0002). Each newly ported
system must decide shared vs FE8-only and say so in its module README. Untested until a second game
exists: FE7/FE6 compatibility is a design rule, not a verified property.
