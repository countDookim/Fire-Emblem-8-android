# Architecture

## Principles
1. **Decomp = spec, ROM = oracle;** ported only with ground-truth tests
   ([0006](docs/decisions/0006-verification-policy.md)).
2. **Original behaviour, modern design:** idiomatic Kotlin per game system. Tests use GBA addresses freely; main sources still read ROM tables by
   `0x08…` address and keep some GBA RAM layouts (`BootRom`, `worldmap`, `bm`); TASKS row `M7-0` audits this.
3. **1:1 product; modding = extension points** ([0009](docs/decisions/0009-vanilla-product-mod-groundwork.md),
   [MODDING.md](MODDING.md)).
4. **Zero allocations per frame** (`AllocationTest`, CI benchmark). All but `:app` is Android-free.
5. **`gba.*` knows no Fire Emblem, `gbafe.*` is game-neutral FE code, `fe8.*` is FE8-only**
   ([0010](docs/decisions/0010-multi-game-reuse.md), [0012](docs/decisions/0012-gba-library.md)), CI-enforced.
6. **The imported ROM is the vanilla data source; mods override per table through the layered VFS**
   (JSON, Tiled, `.fe8s`, PNG/MIDI/WAV; [0015](docs/decisions/0015-rom-base-mod-overrides.md)). No override
   loader exists yet (M7).

## Modules

```
app ──► game ──► gba:{vfs,data,core,render,input,audio,extract}, engine:{core,data,input,render,script,text,save,extract}
testing ──► game, gba:testkit    (engine modules depend on their gba counterpart and :engine:core)
tools:extract ──► engine:extract
```

All `gba:*` (except `gba:testkit`), `engine:*` and `game` are Kotlin Multiplatform (`commonMain`; jvm + android
targets, ADR 0013); `gba:testkit`, `testing` and `tools:extract` are plain JVM; `app` is Android.

| Module | Holds | Status |
|---|---|---|
| `gba:vfs` | layered VFS, mod manifest, texture overrides | dir layers |
| `gba:data` | LZ77, tile, colour decoders; JSON reader | decoders |
| `gba:core` | `VerifiesPort`, fixed-step clock + loop (59.7275 Hz), event bus, cart SRAM | working |
| `gba:input` | `GameAction`, `InputFrame`, `TouchButtons`, `UiElements`, button map | working |
| `gba:render` | `RenderState`, `SoftwareRenderer`, `GpuFrame` + `PackedCompositor` (shader spec), `TileSheets`, `FrameBuffer`, `BgAffine`, `HBlankLines` | software + GPU |
| `gba:audio` | m4a sequencer, DirectSound mixer + CGB/PSG, `M4aDriver`, `PcmStream`, `AudioSink` | working |
| `gba:extract` | generic extraction helpers: `Json`, `Symbols`, `IndexedPng`, `RomImporter` | working |
| `gba:testkit` | `ReferenceData` (+ `WatchTrace`, `GbaMem`), `RamSymbols`, `AllocationMeter`, `MgbaColorMath` (test support) | working |
| `engine:core` | game clock, RNG, procs, event flags + flag/var registry, feature flags, mechanics (FE code) | working |
| `engine:data` | TSA decoder; JSON table contract (trig) | decoders |
| `engine:input` | `KeyStatus`, `KeyComboReset` (FE ports) | working |
| `engine:render` | `LcdControl`, `BgHardware`, `OamBuffer`, `HardwareScene`, fades, HBlank handlers (FE ports) | software + GPU |
| `engine:script` | `.fe8s` parser + assembler to FE8 event words (ADR 0001 amendment), `ScriptVm` contract | working |
| `engine:save` | save container v1, FE checksums | working |
| `engine:text` | text tags, Huffman messages | partial |
| `engine:extract` | ROM → assets extraction core (JSON/PNG/Tiled/MIDI/WAV/`.fe8s`): file-free, runs from ROM + bundled manifest (ADR 0011); used by `tools:extract` and `:app` | working |
| `game` | `fe8.game` packages: `bm`, `event`, `flags`, `opanim`, `save`, `savemenu`, `scene`, `text`, `title`, `ui`, `worldmap`; shared FE systems become `gbafe.<system>` (0010) | boot → title with sound; New Game plays the Prologue opening to the turn-1 map (cursor, suspend/resume); open work in TASKS lanes A and B |
| `testing` | differential tests vs mGBA, ROM golden tests, benchmark | working |
| `tools:extract` | extraction CLI: decomp reads, files (`DecompSources`, `FileOutput`); never shipped | working |
| `app` | launcher (ROM import, Play), GLES 3.0 `GpuBackend`, `AudioTrackSink` + `AudioThread`, keys/gamepad + safe-area touch overlay, APK asset layer | playable to the Prologue turn-1 map |

`fe8.game` packages group ported code by role (above); the 15 systems of the README progress table come from the
`system` column of `reference/decomp_functions.tsv` (e.g. `battle_map`, `worldmap`, `events`) and do not map 1:1.

## Frame loop
`GameLoop.frame(now)` runs per display refresh on the GL thread. `FixedStepClock` converts wall time to whole
GBA frames (exact 2^24 Hz / 280 896 cycles, catch-up cap). Tick: `InputFrame.update` → `core.onTickStart` →
systems → `core.onTickEnd`; then `present()`. Logic never reads wall time, so tests drive `tick()` with mGBA's
inputs. Inside a GBA frame, `fe8.game.GameLoop` mirrors `src/bm.c`: the game step (keys, proc trees), then the
VBlank (clock, `m4aSoundVSync`, proc tree 0, display sync, `m4aSoundMain` → mixer → `PcmStream` → audio thread).

## Rendering
One semantic render state ([0008](docs/decisions/0008-render-model.md), per-line `LineTable`), two backends:
software (JVM reference, compared with mGBA on 5-bit channels) and GL ES 3.0 (`app/GpuBackend`: `GpuFrame` packs
per-line state, the shader composites 240×160 into an FBO, then aspect-correct nearest scaling; `PackedCompositor`
is its Kotlin spec, checked against `SoftwareRenderer` and, via `tools/shader_parity.mjs`, WebGL2). Sprites are
rasterised on the CPU (`SpriteRasterizer`). Fixed 240×160.

## Verification pipeline
```
ROM repo: rom/baserom.gba ─► mgba-harness (headless mGBA 0.10.5) + reference/scripts/*.txt
          ─► reference/out/<script>/{manifest,inputs.bin,<dump>.{ewram,iwram,frame.rgb,...}}
          ─► release assets-<sha> ─► $FE8_ASSETS (tools/setup.sh / CI)
code repo: :testing differential tests replay inputs.bin into the port and compare state/frames
          ─► JUnit XML ─► tools/progress.py ─► README bar + docs/port_map.json
```

## Planning and verification
- Work is planned capture-first: **CAP** (new capture in the ROM repo), **INV** (inventory test of one capture
  window), **PORT** (one behaviour, ≤ ~8 functions), **WIRE** (into `Fe8Boot`/`GameControl`, device-testable).
- Captures live in the ROM repo (`reference/scripts`, `reference/out`); the code repo reads them via `$FE8_ASSETS`.
- `tools/progress.py` counts in-scope functions only: link-cable `sio_*` and the debug menus are shown separately
  (`OUT_OF_SCOPE_*`; a function with a retail caller stays in scope, `tools/check_scope_callers.py`).
- `tools/unverified.py` generates [UNVERIFIED](docs/plans/UNVERIFIED.md), the written-but-unverified code.
- [ROADMAP](docs/plans/ROADMAP.md) (milestones, rules) · [TASKS](docs/TASKS.md) (open rows).
