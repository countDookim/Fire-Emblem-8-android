# 0001 — Event script format: custom `.fe8s`, not embedded Lua

**Status.** Accepted.

**Context.** FE8 events are bytecode command lists (decomp `include/eventscript.h`, `src/eventscr*.c`); they need
a readable, moddable format and an allocation-free interpreter.

**Decision.** Line-oriented `.fe8s`, compiled at load time to an `IntArray` of opcodes:
```
event Ch1_Beginning
    music SONG_RAID_ON_THE_CASTLE
    load_units Ch1_Ally_Start           # unit groups live in maps/ch01.tmj
    text Ch1_Opening                    # string id -> text/en/ch01.json
    if flag FLAG_CH1_SEEN_INTRO goto skip
    set_flag FLAG_CH1_SEEN_INTRO
skip:
    end
```
- 1:1 with original commands (decomp macro names, snake_case); constants resolve via registries, no raw numbers.
- Labels, `goto`, `if <cond> goto`, `call`/`return`, integer expressions on variables.
- Interpreter: fixed thread pool, per-thread `IntArray` stack/registers, no boxing or run-time strings; `yield`
  points match the original's waits.
- Mods add commands via `ScriptCommand` (Kotlin) or compose existing ones in new files.
- Not Lua: LuaJ allocates per call; native Lua needs JNI/NDK (no fast JVM tests). Revisit if mods need it.

**Consequences.** We own parser, compiler and interpreter; every command will be documented in `MODDING.md`.

## Amendment (M5, 2026-11-04)
Earlier text stays; where it differs, this section wins.
- **Compiled form:** the original FE8 event words (u16, command header = code | length nibble, per-command arg
  layouts as the `include/eventscript.h` macros in the decomp), not an `IntArray` of private opcodes.
- **Addresses:** every script has a 32-bit virtual address. Vanilla scripts use their ROM address, so vanilla
  pointers in data (chapter event tables, `call`/`set_slot` targets) resolve unchanged. Mod scripts get synthetic
  addresses from `0x0A000000` up, above the 32 MiB ROM window `0x08000000`-`0x09FFFFFF`, so they never overlap it.
  Look-up is a binary search over a sorted address table; no per-frame allocation.
- **Engine model (decomp `src/event.c`):** one standard and one battle event engine (procs
  `ProcScr_StdEventEngine` / `ProcScr_BattleEventEngine`) sharing the globals `gEventSlots`, `gEventCallQueue`,
  `gEventActiveQueue` (the call/return stack), `gEventSlotQueue` and `gEventSlotCounter`. This replaces the fixed thread pool and per-thread stacks above.
- **Names:** decomp `Evt*`/snake_case command names (as printed in `.fe8s` v0) are canonical; the parser accepts
  EAstdlib names as aliases.
- **Constants:** decomp names (`CHARACTER_*`, `CLASS_*`, `ITEM_*`, `SONG_*`, `CHAPTER_*`, `EVT_SLOT_*`, ...). Event
  flags are `EVFLAG_*` (`include/constants/event-flags.h`); unnamed flag ids are written `EVFLAG_<n>`. Labels and
  text ids stay numeric. Mods register new flag/variable names (ids >= a `MOD_BASE` that fits in u16).
- **Module placement (ADR 0010: `gbafe` = game-neutral, `fe8` = FE8-only):**
  - `gbafe.script`: parser, assembler, `ScriptImage`, `ScriptConstants` interface.
  - `gbafe.extract.fe8.events`: FE8 command table and generated constants (so `:game` depends on `:engine:extract`
    `commonMain`).
  - `fe8.game.event`: VM and handlers.
  - `gbafe.core.flags`: `EventFlags` (layout as data) + `FlagRegistry`/`VarRegistry`; FE8 layout in `fe8.game.flags`.
  - Saves: `gba.core.sram` (cart SRAM), `gbafe.save` (container, checksums), `fe8.game.save` (FE8 layout).
- **Deferred to M7 (authoring):** `if <cond> goto`, named labels, integer expressions. Until then `.fe8s` uses
  numeric `label N` and explicit branch commands.
- **Scope (M5):** saves cover boot SRAM init, the New Game write and `.sav` import/export. Loading (Continue) and
  suspend/resume are M6.
