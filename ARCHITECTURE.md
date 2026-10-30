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
app ──► game ──► engine:{core,vfs,data,input,render,audio,script,text,save}
testing ──► game         (every engine module depends only on engine:core)
```

| Module | Holds | Status |
|---|---|---|
| `engine:core` | clock + loop (59.7275 Hz), RNG, procs, event bus, flags, mechanics | working |
| `engine:vfs` | layered VFS, mod manifest, texture overrides | dir layers |
| `engine:data` | LZ77/tile/TSA/colour decoders; JSON table contract | decoders |
| `engine:input` | actions, `KeyStatus`, `TouchButtons`, button map | working |
| `engine:render` | `RenderState`, `SoftwareRenderer`, `LcdControl` | software |
| `engine:audio` / `script` / `save` | m4a + `AudioSink` / `.fe8s` VM / save container | contracts |
| `engine:text` | text tags, Huffman messages | partial |
| `game` | `fe8.game` systems; shared FE systems become `gbafe.<system>` (0010) | boot → title |
| `testing` | reference data, differential tests, allocation meter, benchmark | working |
| `app` | Activity, GL view, key + on-screen-button input, APK asset layer | shell |
| `tools:extract` | ROM → JSON/PNG/Tiled/MIDI/WAV/`.fe8s`; never shipped | working |

Systems follow the `system` column of `reference/decomp_functions.tsv` (= README progress table).

## Frame loop
`GameLoop.frame(now)` runs per display refresh on the GL thread. `FixedStepClock` converts wall time to whole
GBA frames (exact 2^24 Hz / 280 896 cycles, catch-up cap). Tick: `InputFrame.update` → `core.onTickStart` →
systems → `core.onTickEnd`; then `present()`. Logic never reads wall time, so tests drive `tick()` with mGBA's
inputs.

## Rendering
One semantic render state ([0008](docs/decisions/0008-render-model.md), per-line `LineTable`), two backends:
software (JVM reference, compared with mGBA on 5-bit channels) and GL ES 3.0 (240×160, aspect-correct nearest
scaling; shader backend M3-07). Fixed 240×160.

## Verification pipeline
```
ROM repo: rom/baserom.gba ─► mgba-harness (headless mGBA 0.10.5) + reference/scripts/*.txt
          ─► reference/out/<script>/{manifest,inputs.bin,<dump>.{ewram,iwram,frame.rgb,...}}
          ─► release assets-<sha> ─► $FE8_ASSETS (tools/setup.sh / CI)
code repo: :testing differential tests replay inputs.bin into the port and compare state/frames
          ─► JUnit XML ─► tools/progress.py ─► README bar + docs/port_map.json
```
