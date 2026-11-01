# Task backlog

Tasks ≤ 20 min. Status `todo`/`wip` (branch `wip/<id>`)/`done`/`blocked`. Take the first `todo` whose deps are
`done`; if fewer than 5 `todo` remain, plan the next milestone.

**Priority:** boot with sound on device done (owner, 2026-11-01; M3-07, M3-12 done). Next: CGB/PSG sound
(M3-13/M3-14 `CgbSound`), then M4-03/04/06/07 (ROM import, public release only).

## M1 — Toolchain, CI, skeleton, docs

Done (details in git log): M1-01 Repo skeleton, Gradle; M1-02 ROM repo; M1-03 tools/setup.sh, gen_reference.py, progress.py, maven_mirror.; M1-04 CI workflow; M1-05 Docs; M1-06 CI reads ROM assets; M1-07 KDoc lint; M1-08 progress.py self-test; M1-09 Decomp symbol map; M1-10 gen_reference.py --map; M1-11 reference/ README; M1-12 Harness.

## M2 — Asset and data extraction (outputs go to the ROM repo only)

Done (details in git log): M2-01 New module :tools:extract; M2-02 LZ77; M2-03 4bpp/8bpp tile; M2-04 schemas/items.schema.json; M2-05 Classes; M2-06 Characters; M2-07 Terrain tables; M2-08 Text; M2-09 Text control codes → readable tags; M2-10 Font glyphs; M2-11 Portraits; M2-12 Chapter data; M2-13 Map layouts; M2-14 All chapter maps converted; M2-15 Event script converter v0; M2-16 Unit placement; M2-17 Map sprites; M2-18 m4a songs → MIDI + voicegroups/*.json; M2-19 All songs + direct-sound samples → MIDI + WAV; M2-20 UI graphics; M2-21 Extraction runs in CI.

## M3 — Rendering, input, audio, override layer

| ID | Task | Done when | Status |
|---|---|---|---|
| M3-07 | GPU backend v1 in `:app`: tile layers + sprites via shaders, same render state | APK shows extracted test scene (manual test) | done (owner manual test 2026-11-01) |
| M3-12 | Low-latency `AudioTrack` sink (`PERFORMANCE_MODE_LOW_LATENCY`, mixer thread, no per-buffer allocs) | APK plays a test tone (manual test) | done (owner manual test 2026-11-01) |
| M3-13 | m4a sequencer core (tempo, tracks, note on/off) from `m4a.c`/MIDI | unit tests vs MIDI timing | done: sequencer verified against per-frame mGBA RAM traces (stronger than MIDI timing): `M4aSequencerTest`, `M4aNoteTest` |
| M3-14 | m4a mixer: direct-sound + PSG channels; compare against mGBA audio dump (harness: add audio capture) | RMS diff below threshold | wip: DirectSound mixer PCM byte-exact vs RAM traces (3145 frames); sound data in the app and boot sound done (`Fe8BootSoundTest`, owner listen test 2026-11-01); `CgbSound` verified (channel state); `PsgSynth` v1 (unit-tested, level approximate); todo: sweep, levels vs mGBA audio |

Done (details in git log): M3-01 Finalise ADR 0008; M3-02 Render state types; M3-09 Input; M3-03 Software compositor; M3-04 Software compositor; M3-05 Blend/brightness/windows/mosaic; M3-06 Frame-compare utility; M3-16 Port gLCDControlBuffer + FlushLCDControl; M3-08 Asset override layer; M3-11 UI element contract; M3-10 Android ActionSources; M3-15 On-screen GBA button overlay ActionSource in :app.

## M4 — Boot to title screen, verified against mGBA

| ID | Task | Acceptance | Status |
|---|---|---|---|
| M4-03 | Extraction as a library + manifest (0011): move the extraction core out of the CLI into a module `:app` can use (no `java.awt`/`javax.imageio`/decomp reads); generate a committed manifest of ROM offsets/sizes/ids/names from the map + decomp (`tools/gen_extract_manifest.py` or a CLI mode) and make extraction run from ROM + manifest only | manifest-only extraction reproduces the ROM repo's `extracted/` (CI drift check) | todo |
| M4-04 | First-launch ROM import in `:app`: document picker, SHA1 check (FE8 U only, clear error otherwise), on-device extraction into `filesDir/base/` with progress, re-extract on extractor format change, VFS `base` layer points there; ROM not kept | manual test on device; extraction time logged (target < ~30 s mid-range phone) | todo |
| M4-06 | Game-neutral GBA port library (owner 2026-10-27): move what any GBA port reuses into `:gba:*` / `gba.*` below `gbafe.*`; layering test (`gba` uses nothing above). Candidates: core (GameLoop, clock, events, `@VerifiesPort`), vfs, data (Lz77, tiles, JSON), render (RenderState, SoftwareRenderer, HBlankLines, GPU), input (+ M3-15 overlay), audio (m4a), extract (PNG/JSON/symbols/MIDI), testkit (ReferenceData, RamSymbols, AllocationMeter); doc for reusing the mGBA harness. FE-only stays: procs, RNG, BattleRolls, KeyStatus, Tsa, text, scripts. Do with M4-03; check `:app` imports by grep | jvmTest, Dokka, CI green; ADR 0012 | todo |
| M4-07 | Kotlin Multiplatform (owner 2026-10-27): `:gba:*`, `:engine:*`, `:game` become KMP with `commonMain`; targets **jvm + android only** (others on owner request); platform code via `expect`/`actual` (today only `engine/vfs` uses `java.io`); `:testing`, `:tools:extract` stay JVM, but the M4-03 extraction core goes in `commonMain`; CI check: no `java.*`/`javax.*` in `commonMain`; KMP plugin must resolve via the session Maven mirror. With/after M4-06 | jvmTest, Dokka, CI green; `:app` builds; ADR records targets | todo |
| M4-02 | Intro screens `opanim-healthsafetyscreen.c` (`fe8.game.opanim.OpAnimHs`, `EarlyStartUi`) | step functions `@VerifiesPort` vs RAM traces; screens pixel-exact | partly done: all step functions verified; logo assets `ui/intro_nintendo`, `ui/intro_is` (`IntroLogosScreenTest`, pixel-exact incl. fades); screen loads (`OpAnimHs.prepareHealthSafety`/`prepareNintendofx` on `gbafe.render.BgHardware`, `gbafe.data.Decompress`) verified vs RAM/VRAM, and the whole proc from its start vs `ram_trace_boot` (`EarlyStartUiBootDifferentialTest`). `Sound_FadeOutBGM` wired (`SoundWrapper`); boot driver `Fe8Boot` with lag frames (27, as mGBA). Todo: `IsSoftwareReset`, Huffman/RL in `Decompress` |
| M4-10 | Title screen: port the title proc(s) after the op anim (`src/titlescreen.c` or what follows the op anim's end ~f2950 in `ram_trace_opanim4`; plan its subtasks first) | step functions `@VerifiesPort` vs RAM traces; title frame-compare vs `boot_to_title` | done: the whole `gProcScr_TitleScreen` (+ skip listener, `DrawTitleSprites`, fades via new `gbafe.render.ColorFade`/`FadeCore`) verified frame by frame by `TitleScreenDifferentialTest` (mode 1 vs `ram_trace_opanim4`+`ram_trace_title1` to the idle title f3600; mode 0 fade-in vs `ram_trace_title_fade`); `GameControl` starts the real title (main test checks it to f3000). (e) done: idle-title frames drawn from our VRAM/palette/OAM/LCD state match mGBA (`OamBuffer`, production `gbafe.render.HardwareScene`). Key-press paths (skip -> label 3 restart, A/Start -> fade out) verified vs `key_status_title`, `ram_trace_title_start`, `ram_trace_title_skip` (2026-10-30) |

Leftovers of done tasks: proc.c `Proc_ForEach*`, marks/blocking, `Proc_FindAfter*`, `CountProcs`.

Done (details in git log): M4-01 Proc scheduler src/proc.c; M4-05 CI guard; M4-08 Op anim cutscene opanim-main.c/opanimfx.c; M4-09 Test-build assets; M4-11 Wire boot in :app.
