# Architecture

## Principles
1. **Decomp = spec, ROM = oracle;** ported only with ground-truth tests
   ([0006](docs/decisions/0006-verification-policy.md)).
2. **Original behaviour, modern design:** idiomatic Kotlin per game system; GBA addresses only in `:testing`.
3. **1:1 product; modding = extension points** ([0009](docs/decisions/0009-vanilla-product-mod-groundwork.md),
   [MODDING.md](MODDING.md)).
4. **Zero allocations per frame** (`AllocationTest`, CI benchmark). All but `:app` is Android-free.
5. **`gbafe.*` game-neutral, `fe8.*` FE8-only** ([0010](docs/decisions/0010-multi-game-reuse.md)), CI-enforced.
6. **All content through the layered VFS** (JSON, Tiled, `.fe8s`, PNG/MIDI/WAV).

## Modules

```
app ──► game ──► gba:{vfs,data,core}, engine:{core,data,input,render,audio,script,text,save}
testing ──► game         (every engine module depends only on engine:core)
```

| Module | Holds | Status |
|---|---|---|
| `gba:core` | `VerifiesPort`, fixed-step clock + loop (59.7275 Hz), event bus | working |
| `engine:core` | game clock, RNG, procs, flags, mechanics (FE code) | working |
| `gba:vfs` | layered VFS, mod manifest, texture overrides | dir layers |
| `gba:data` | LZ77, tile, colour decoders; JSON reader (package `gba.data`) | decoders |
| `engine:data` | TSA decoder; JSON table contract (trig) | decoders |
| `engine:input` | actions, `KeyStatus`, `TouchButtons`, button map | working |
| `engine:render` | `RenderState`, `SoftwareRenderer`, `GpuFrame` + `PackedCompositor` (shader spec), `LcdControl` | software + GPU |
| `engine:audio` | m4a sequencer, DirectSound mixer, `M4aDriver`, `PcmStream`, `AudioSink` (CGB/PSG: todo) | working |
| `engine:script` / `save` | `.fe8s` VM / save container | contracts |
| `engine:text` | text tags, Huffman messages | partial |
| `game` | `fe8.game` systems; shared FE systems become `gbafe.<system>` (0010) | boot → title, sound |
| `testing` | reference data, differential tests, allocation meter, benchmark | working |
| `app` | launcher (one Play), GLES 3.0 `GpuBackend`, `AudioTrackSink` + `AudioThread`, keys/gamepad + safe-area touch overlay, APK asset layer | boot playable |
| `engine:extract` | ROM → assets extraction core (JSON/PNG/Tiled/MIDI/WAV/`.fe8s`): file-free, runs from ROM + bundled manifest (ADR 0011); used by `tools:extract` and later `:app` | working |
| `tools:extract` | The extraction CLI: decomp reads, files (`DecompSources`, `FileOutput`); never shipped | working |

Systems follow the `system` column of `reference/decomp_functions.tsv` (= README progress table).

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
