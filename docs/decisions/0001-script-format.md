# 0001 — Event script format: custom `.fe8s`, not embedded Lua

**Context.** FE8 events are bytecode command lists (`EVBIT_MODIFY`, `LOAD1`, `MUSC`, `TEXTSHOW`, `ENUN`,
`GOTO`, `BEQ`...; see the decomp's `include/eventscript.h` and `src/eventscr*.c`). We must convert them
to a human-readable format, run them with an allocation-free interpreter, and let mods add logic without
recompiling. Candidates: a custom text format, or embedded Lua (LuaJ on the JVM, or native Lua via JNI).

**Decision.** A custom line-oriented format, `.fe8s`, compiled at load time to an `IntArray` of opcodes:

```
# scripts/ch01/beginning.fe8s
event Ch1_Beginning
    music SONG_RAID_ON_THE_CASTLE
    load_units Ch1_Ally_Start           # unit groups live in maps/ch01.tmj
    wait_units
    text Ch1_Opening                    # string id -> text/en/ch01.json
    if flag FLAG_CH1_SEEN_INTRO goto skip
    set_flag FLAG_CH1_SEEN_INTRO
skip:
    end
```

- One `.fe8s` command per original event command (1:1 converter; vanilla semantics preserved), named
  after the decomp's macros in snake_case; symbolic constants (flags, songs, characters) resolve via the
  registries, never raw numbers.
- Control flow: labels, `goto`, `if <cond> goto`, `call`/`return`, integer expressions on variables.
- Interpreter: fixed-size thread pool, per-thread `IntArray` stack/registers, no boxing, no strings at run
  time (string ids are ints after compile). `yield` points match the original's wait semantics.
- Extension: mods add commands by registering a `ScriptCommand` (Kotlin, in a mod's code) — or, without
  code, by composing existing commands and hooks in new `.fe8s` files.

**Why not Lua.** LuaJ allocates per call/table access (GC churn on low-end phones, breaks the
zero-allocation rule); native Lua needs JNI + NDK builds, which breaks "game logic tests on the JVM in
seconds" and complicates differential determinism. FE8 scripts are linear command lists, so a general
language buys little for vanilla. Revisit (new record) if mods need heavy general-purpose logic.

**Consequences.** We own a parser, compiler and interpreter (~1–2k lines) and must document every
command in `MODDING.md`. Converter output is diffable text, good for review and modding.
