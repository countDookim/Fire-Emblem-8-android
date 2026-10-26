# Task backlog

Ordered; each task ≤ 20 minutes. Status: `todo` · `wip` (branch `wip/<id>`) · `done` · `blocked` (see HANDOFF NEEDS USER).
Take the first `todo` whose dependencies are `done`. If fewer than 5 `todo` remain, plan the next milestone.

## M1 — Toolchain, CI, skeleton, docs

| ID | Task | Done when | Status |
|---|---|---|---|
| M1-01 | Repo skeleton, Gradle (JVM modules + conditional :app), wrapper | `./gradlew jvmClasses` green in a session | done |
| M1-02 | ROM repo: ROM, mgba-harness, reference scripts/data, publish workflow | release `assets-*` exists with 4 assets | done |
| M1-03 | `tools/setup.sh`, `gen_reference.py`, `progress.py`, `maven_mirror.py` | setup idempotent, timed; progress prints bar | done |
| M1-04 | CI workflow: tests, APK, benchmark, Dokka, progress commit, `latest` release | one green run on master | done |
| M1-05 | Docs: README, ARCHITECTURE, MODDING, ADRs 0001–0008, module READMEs, HANDOFF, MANUAL_TEST | files exist and are linked | done |
| M1-06 | CI reads ROM assets (needs owner secret, see HANDOFF) and differential tests run in CI | CI summary shows `assets available: true`, 0 skipped | done |
| M1-07 | KDoc lint: Dokka `reportUndocumented=true` for JVM modules; fix all warnings; fail CI on undocumented public API | `./gradlew dokkaGenerate -Pfe8.docs=true` has no undocumented warnings | done |
| M1-08 | `progress.py` self-test: tiny fixture (fake Kotlin + JUnit XML) under `tools/tests/`, run in CI | `python3 -m unittest discover tools/tests` passes; skipped/failed tests never count | done |
| M1-09 | Decomp symbol map: ROM-repo workflow that builds fireemblem8u (agbcc + binutils) and publishes `fireemblem8.map` as a release asset; (owner approved 2026-10-25; also publishes ELF `nm` symbols) | release has the map, or NEEDS USER entry | done |
| M1-10 | `gen_reference.py --map`: emit `reference/ram_symbols.tsv` (EWRAM+IWRAM name → address → size) from the map; commit | file committed; `RngDifferentialTest` reads addresses from it instead of constants | done |
| M1-11 | `reference/` README: column docs, regeneration steps, decomp pin | file exists | done |
| M1-12 | Harness: `savestate NAME` / `loadstate NAME` commands + `--frames-every N` for long traces (ROM repo) | new script produces files; release republished | done |

## M2 — Asset and data extraction (outputs go to the ROM repo only)

| ID | Task | Done when | Status |
|---|---|---|---|
| M2-01 | New module `:tools:extract` (JVM CLI): args `--rom --decomp --map --out`; refuses to write inside the code repo | `./gradlew :tools:extract:run --args=--help` works | done |
| M2-02 | LZ77 (BIOS `LZ77UnCompWram`) decompressor in `engine:data` (load-time use) + golden test on a ROM blob | decompressed size/bytes match header + known tileset | done (size + exact stream end on 2 ROM images; byte-level check vs VRAM comes with M3-06) |
| M2-03 | 4bpp/8bpp tile + BGR555 palette → indexed PNG writer (keeps palette indices for vanilla palette behaviour) | round-trip test PNG → tiles identical | done (`TilesPngRomTest`: Img_TriPegasusKnightBG byte-identical) |
| M2-04 | `schemas/items.schema.json` + item table extractor (`struct ItemData`, `src/data_items.c`) → `data/items.json` | schema-valid; 1st/last item spot-checked vs decomp | done (`ItemsRomTest` + `tools/tests/test_schemas.py`; CLI `--symbols`) |
| M2-05 | Classes: schema + extractor (`data_classes.c`) | schema-valid; counts match decomp | done (`ClassesRomTest`, 127 classes; schema test) |
| M2-06 | Characters: schema + extractor (`data_characters.c`) | schema-valid | done (`CharactersRomTest`, 256 rows incl. supports; schema test) |
| M2-07 | Terrain tables (`data_terrains.c`) + item bonus tables | schema-valid | done (`TerrainsRomTest`, 108 tables; item bonuses inlined in items.json) |
| M2-08 | Text: Huffman string decoder (port of `msg.c` decode) + golden test (title screen string) | decoded text matches ROM-rendered string | done (`MsgHuffman`, port of asm/arm.s DecodeString; `MsgHuffmanRomTest`: Iron Sword/Eirika/Seth) |
| M2-09 | Text control codes → readable tags; all strings → `text/en/*.json` | every string round-trips to identical bytes | done (`MessageCodec`, `MessagesRomTest`: 3404 round-trip + MSG_002/212/904 vs texts.txt; schema `text.schema.json`) |
| M2-10 | Font glyphs (`fontgrp`) → PNG atlas + JSON metrics | glyph widths match decomp tables | done (`FontExtractor` → `fonts/{system,talk,special}.{png,json}`; `FontsRomTest` widths + bitmap rows vs `data/fonts/glyphs_*.h`; `font.schema.json`) |
| M2-11 | Portraits (`face.c` data) → PNG + JSON (mouth/eye frames, anchors) | 3 portraits visually checked + counts | done (`PortraitsExtractor`: 172 entries, unassembled tile sheets + chibi/mouth/card PNGs; `PortraitsRomTest` vs `portrait_data.c`; Eirika/Seth/card checked by eye; 96×80 assembly = face renderer, M3) |
| M2-12 | Chapter data (`chapterdata.c`) → `data/chapters.json` | schema-valid | done (`ChaptersExtractor`, 79 chapters, every struct byte; `ChaptersRomTest` + one-off full cross-check vs decomp `src/data/chapter_settings.json`: 0 mismatches) |
| M2-13 | Map layouts + tile config → Tiled `.tmj` + tileset PNG (one chapter) | opens in Tiled (validated by `.tmj` schema), tiles match | done (`MapsExtractor`: prologue `.tmj` + 1024-metatile tileset PNG with terrain properties; `MapsRomTest` + Tiled-structure test; render checked by eye; no CLI step yet → M2-14) |
| M2-14 | All chapter maps converted; map changes (doors, chests) as Tiled layers | count matches chapter table | done (CLI `maps/chapter_NN.tmj` ×79 + shared tilesets; `MapsRomTest`: all 79 convert, changes in bounds; ch. 2 villages/chests checked by eye) |
| M2-15 | Event script converter v0: command table from decomp event macros → `.fe8s` (prologue only) | output parses with M5 grammar draft; diffable | done (`tools/gen_fe8_event_commands.py` → `Fe8EventCommands` (182 header forms); `EventScriptConverter`; CLI `scripts/prologue.fe8s` (32 scripts); `EventScriptRomTest`: all 32 prologue scripts consume exactly their nm size, 0 `raw`, lines match grammar draft regex) |
| M2-16 | Unit placement (`UNIT` defs) → Tiled object layer | prologue units correct | done (`UnitsExtractor`: `UnitDefinition` + REDAs, every field; prologue's 9 `UnitDef_Event_Prologue*` arrays → hidden object layers in `chapter_00.tmj`; `UnitsRomTest` vs `prologue-eventudefs.h`; other chapters need M2-15's LOAD targets) |
| M2-17 | Map sprites (standing/moving) → PNG + anim JSON | counts match class table | done: `MapSpritesExtractor` standing (107 sheets) + moving (127 entries, `moving/NNN.png` + AP frames/anims JSON), `MapSpritesRomTest` |
| M2-18 | m4a songs → MIDI + `voicegroups/*.json` (one song end-to-end) | MIDI note events match song data | done (`gbafe.extract.m4a.M4aSong`: decoder + MIDI writer + `readVoicegroup`; CLI `songs/song001.mid` + `voicegroups/voicegroup000.json`; `SongsRomTest`; one-off compare vs decomp `song001_agbfe3_bgm_opening.mid`: every note time/key/velocity/length + tempo map identical; `voicegroup.schema.json`) |
| M2-19 | All songs + direct-sound samples → MIDI + WAV | counts match song table | done: CLI `songs/` 1000 entries → 594 MIDI + `songs.json`, 84 `voicegroups/`, `samples/` 439 WAV + `samples.json`; one-off compares: all 589 decomp `.mid` identical, all 439 `.aif` identical PCM + rate; `SongsRomTest`; `songs`/`samples` schemas |
| M2-20 | UI graphics (windows, cursors, icons) → PNG + JSON | icon sheet visually checked | done: icons (`IconsExtractor`, 224 icons), window frames (`WindowFramesExtractor`, 4 colors), cursors (`CursorsExtractor`: misc OBJ UI 18×4-tile sheet + 2 palettes, map cursor OAM lists, hand cursor); all checked by eye; `IconsRomTest` |
| M2-21 | Extraction runs in CI; `extracted.tar.xz` in release; `setup.sh` fetches it | session has `$FE8_ASSETS/extracted` | done: ROM-repo `publish-assets` packs `extracted/` (reproducible tar) into the release; `setup.sh` fetches it via REST (`extracted/.release` = tag, skip when unchanged; sparse-clone fallback); code-repo CI runs the extractor and fails on drift vs the committed extraction (`tools/ci_check_extraction.sh`; ROM repo is private, so extraction runs where the ROM token already is) |

## M3 — Rendering, input, audio, override layer

| ID | Task | Done when | Status |
|---|---|---|---|
| M3-01 | Finalise ADR 0008 (render model): HBlank effects + texture-pack/palette interaction | ADR accepted | done: `LineTable` (per-line overrides, data texture on GPU); indexed vs RGBA replacements (`paletteVariants`) |
| M3-02 | Render state types: `TileLayer`, `SpriteBatch`, `PaletteBank`, `Effects`, `LineTable` (primitive arrays) | compiles; allocation test | done: `gbafe.render.RenderState` (`TileLayer`, `SpriteBatch`, `PaletteBank`, `Effects`, `LineTable`); `RenderStateTest` (0 bytes/frame) |
| M3-03 | Software compositor: text-mode BG layers + priorities | renders a test scene; unit tests | todo |
| M3-04 | Software compositor: sprites (regular + affine), OBJ priority vs BG | tests | todo |
| M3-05 | Blend/brightness/windows/mosaic | tests | todo |
| M3-06 | Frame-compare utility: ARGB vs mGBA dump (5-bit), diff PNG written on failure | first frame-compare test (health & safety screen from extracted assets) passes | todo |
| M3-07 | GPU backend v1 in `:app`: tile layers + sprites via shaders, same render state | APK shows extracted test scene (manual test) | todo |
| M3-08 | Asset override layer: resolver for replacement/hi-res textures by path + scale (mod groundwork; base game ships originals only) | test with a 2× override | todo |
| M3-09 | Input: port key repeat/new-press logic (`hardware.c` key status) onto `InputFrame` + differential test (key status RAM) | `@VerifiesPort` passes | todo |
| M3-10 | Android `ActionSource`s: hardware keys + gamepad mapping | manual test | todo |
| M3-11 | UI element contract: screen bounds + actions for touch hit-testing (no game-logic change; groundwork for mods, 0009) | interface + doc in MODDING | todo |
| M3-15 | On-screen GBA button overlay `ActionSource` in `:app` (D-pad, A, B, L, R, Start, Select; emits exactly GBA keys, multi-touch, no game-logic change) | manual test on device | todo |
| M3-12 | Low-latency `AudioTrack` sink (`PERFORMANCE_MODE_LOW_LATENCY`, mixer thread, no per-buffer allocs) | APK plays a test tone (manual test) | todo |
| M3-13 | m4a sequencer core (tempo, tracks, note on/off) from `m4a.c`/MIDI | unit tests vs MIDI timing | todo |
| M3-14 | m4a mixer: direct-sound + PSG channels; compare against mGBA audio dump (harness: add audio capture) | RMS diff below threshold | todo |
