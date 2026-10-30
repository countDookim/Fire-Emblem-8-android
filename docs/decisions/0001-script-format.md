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

**Consequences.** We own parser, compiler and interpreter; every command is documented in `MODDING.md`.
