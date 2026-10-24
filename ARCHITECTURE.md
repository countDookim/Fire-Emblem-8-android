# Architecture

## Principles
1. **The decomp is the spec, the ROM is the test oracle.** Every ported function cites its source
   (`Ported from src/x.c: Func`) and only counts once a test against mGBA/ROM ground truth passes
   ([0006](docs/decisions/0006-verification-policy.md)).
2. **Modern design, original behaviour.** Organised by game system with idiomatic Kotlin (sealed state
   classes, enums, value classes, data classes). No GBA memory map, registers or VRAM mimicry in game
   code; only `:testing` knows GBA addresses (to read reference dumps).
3. **Vanilla is the default and is bit-exact.** Every non-original behaviour is behind a
   `FeatureFlag`; `FeatureFlags.vanilla` forces all of them off.
4. **Zero allocations per frame.** Hot data lives in primitive arrays behind typed wrappers; event
   dispatch is inline over arrays; enforced by `AllocationTest` and the CI benchmark.
5. **Android-free core.** Everything except `:app` is pure Kotlin/JVM and testable headless in seconds.
6. **Moddable by construction.** All content is read through the layered VFS; data is JSON (compiled to
   primitive tables), maps are Tiled, scripts are `.fe8s`, assets are PNG/MIDI/WAV with JSON metadata.

## Modules

```
app ─────────────► game ───► engine:{core,vfs,data,input,render,audio,script,text,save}
(Android shell)    (game systems)          ▲
testing ───────────► game                  └── engine:core is the only dependency of every engine module
(differential, allocation, benchmark)
```

| Module | Responsibility | Status |
|---|---|---|
| `engine:core` | Fixed-step clock + loop (59.7275 Hz), RNG service (ported), event bus, feature flags, porting annotations | working |
| `engine:vfs` | Layered virtual filesystem, mod manifest | working (dir layers) |
| `engine:data` | JSON → primitive table compiler, typed tables | interface |
| `engine:input` | `GameAction`, `InputFrame`, `ActionSource`, vanilla button map | working |
| `engine:render` | Render state model, software renderer, `FrameBuffer`, `Camera` | skeleton |
| `engine:audio` | m4a/MP2K-equivalent sequencer + mixer, `AudioSink` | interface |
| `engine:script` | `.fe8s` parser/compiler/interpreter | interface |
| `engine:text` | Localised strings, tags, font/glyph layout | skeleton |
| `engine:save` | Versioned save container, migrations, `.sav` import | skeleton |
| `game` | Game systems (boot/title, chapter flow, map, units, battle, AI, events, menus, world map, prep, save menu) | shell |
| `testing` | Reference-data access, differential tests, `AllocationMeter`, frame benchmark | working |
| `app` | Activity, GL ES 3.0 view + scaling shader, Compose launcher/settings, AudioTrack, input sources | shell |
| `tools:extract` | JVM CLI: ROM → JSON/PNG/Tiled/MIDI (Milestone 2); refuses to write inside the code repo; never shipped | skeleton (`--help`, arg checks) |

Game systems map to the decomp's files via the `system` column of `reference/decomp_functions.tsv`
(core, battle_map, battle_logic, battle_anim, map_anim, ai, events, menus, prep_screen, worldmap, save,
text_face, presentation, audio, link, data) — the same split as the README progress table.

## Frame loop
`GameLoop.frame(now)` is called once per display refresh (GL thread). `FixedStepClock` converts wall
time to whole GBA frames using exact integer maths (2^24 Hz / 280 896 cycles) with a catch-up cap; each
tick: `InputFrame.update` → `core.onTickStart` → game systems → `core.onTickEnd`. Then `present()` hands
the render state to the backend. Logic never reads wall-clock time, so vanilla is deterministic and a
differential test can drive `tick()` directly with the same per-frame inputs as mGBA.

## Rendering
Two backends over one semantic render state ([0008](docs/decisions/0008-render-model.md), proposed):
software (JVM, frame comparison against mGBA dumps; 5-bit channel compare) and OpenGL ES 3.0 (native
240×160 texture scaled by a shader, aspect-correct, nearest filtering). `Camera` is not tied to
240×160 so widescreen mods are possible; vanilla is fixed at 240×160.

## Verification pipeline
```
ROM repo: rom/baserom.gba ─► mgba-harness (headless mGBA 0.10.5) + reference/scripts/*.txt
          ─► reference/out/<script>/{manifest,inputs.bin,<dump>.{ewram,iwram,frame.rgb,...}}
          ─► release assets-<sha> ─► $FE8_ASSETS (tools/setup.sh / CI)
code repo: :testing differential tests replay inputs.bin into the port and compare state/frames
          ─► JUnit XML ─► tools/progress.py ─► README bar + docs/port_map.json
```

## Key decisions
See [docs/decisions/](docs/decisions/README.md): script format, data schemas, mod VFS, event bus,
toolchain, verification policy, decomp choice, render model.
