# HANDOFF

_Updated 2026-11-05 (owner PC: M5-25, 39b, 41c, 42-47, 53-55). Owner decisions: docs/PROJECT_BRIEF.md. Backlog:
docs/TASKS.md. M5 facts and reference runs: docs/plans/M5-notes.md. History: git log._

## NEEDS USER
_(nothing blocked)_

## In progress
- M5-60d: scheduled, until ~11:25Z

## Next task: M5-60d (event-layer unit loads + register load_unit in Fe8Boot), then finish M5-58/59 from `wip/M5-58`
M5-60 core done (scheduled 11-06): `fe8.game.bm.UnitLoader(table, rom, unsealedCharacter, rng, isHardMode)`:
`loadUnit` (LoadUnit: items, REDA end position, non-blue autolevel incl. promoted bonus, RNG) and `loadUnitForEvent`
(LoadUnit_800F704, partial, untagged: `b` guessed 0). Throws Unsupported for blue/boss autolevel
(UnitAutolevelRealistic), genMonster, gorgon eggs, sumFlag, MU walks when not quiet. `UnitLoaderDifferentialTest`:
throne room (r09518→r09521), allies/messenger (ram_trace_prologue), red autolevel (RNG candidates from rng.watch;
r dumps every 30 f). Masks: +0x3C sprite handle, US_HIDDEN, MU-moved x/y. Weak (untagged): UnitCheckStatCaps,
UnitHideIfUnderRoof, GetItemHpBonus/GetUnitMaxHp, CanUnitUseWeapon/Staff, UnitAutolevelWExp.
M5-53..55 done (owner PC): `EventVmDifferentialTest` runs the real `EventEngine` over the whole prologue trace (12618
pairs, 0 mismatches; RNG, tutorial fields, call/active queues and flags compared). Unported commands are test-only
stubs (STUBS table). 172 Event19 pairs are skipped: the game engine idles while a Popup/GotItem child proc lives (no
blocked-by-child model yet). Withheld tags and why: docs/TASKS.md Done/M5. EventEngine_CanStartSkip/StartSkip are
partial (an unpushed alternative port is on owner-PC branch `backup/event-engine-dup`). `EventScrCore` takes the
game's RngService/PlaySt/KeyStatus (no defaults). Wiring still open (M5-58): `BMapMain.hooks.playerPhase`,
`hooks.callEvent` → EventEngine. M5-56 done (scheduled): `RoutedFlagStore`, `ModFlagSymbols`, `ModSaveSections`; M5-56b done: a mod flag name in a flag
arg gets `set_slot 2, handle` before it (`ModFlagSlotExpander`; overwrites slot 2). M5-57 done: `ModScriptEndToEndTest` (mod .fe8s → synthetic range → calls a
ROM script → returns; 0 B/frame). `wip/M5-56`, `wip/M5-60` are merged and can be deleted (scheduled runs get 403 on branch delete). `wip/M5-58` (scheduled 11-05, full jvmTest green, not merged):
Fe8Boot wires `BMapMain.hooks.playerPhase`/`callEvent` → `Fe8Boot.eventEngine` (EventScrCore + `ReleaseEventStubs`
no-ops for unregistered codes), dev New-Game `startSaveMenu` stand-in (WriteNewGameSave slot 0, EVENT_RETURN);
`NewGameToPrologueTest` reaches chapter 0 PlayerPhase, beginning events end. Stand-in `startPressed() = true` skips
ALL events (fix before merge). Blocker: no unit loader → playerPhaseHandleAutoEnd ends the phase (M5-60).

## State
- Boot (H&S, logos, op anim, title) matches mGBA: 27 lag frames, 249 frames pixel-exact, gap f1180 only.
- Sound: m4a sequencer and DirectSound mixer byte-exact vs per-frame RAM traces (`M4aNoteTest`, 3145 frames);
  `CgbSound` verified; `PsgSynth` (GBATEK/mGBA behaviour, not a port) RMS 1249 vs mGBA 1200.
- Owner device tests passed: boot to title, touch buttons, gamepad, GPU backend, audio, ROM import (~4 s) + boot
  from it. Pending: smoke test of the first APK built on the KMP android variants (MANUAL_TEST "Newly testable").
- Verified ports: 350 / 8352 (`tools/progress.py`, 2026-11-05). Scripts: 897/897 EventScr* byte-exact in 46 `.fe8s`.
- Layout: `:gba:*` game-neutral (ADR 0012), `gbafe.*` in `:engine:*`, `fe8.*` in `:game`/`:app`; KMP jvm+android
  (ADR 0013) except `:gba:testkit`, `:testing`, `:tools:extract`.

## Open leads (low priority unless the owner reports a problem)
- m4a tests skip the M5 runs (`M4aRuns.kt`): our sequencer diverges on `ram_trace_newgame` r00975 channel 12
  (new-game menu songs) and on `ram_trace_ch2_scroll` (r01469 track 0 status 0xC0 vs our 0x80; r01104 player 2).
  Investigate before claiming m4a coverage there.
- Test strength: ~40 ProcEngine tags share one Kotlin function with another tag (a break proves the group, not each
  name); OpAnimMergeBGProcInit, sub_80C8608 untagged. Untagged for lack of a trace: SaveBonusContentData,
  SaveRankings, WriteLinkArenaStruct2, VerifySramFast_Core, GameControl_SwitchPostSaveMenu,
  GameCtrl_Check{NewGame,GameComplete}AndBranch, GetPhaseAbleUnitCount exclusion branches, ply_keysh, ply_xcmd,
  GameControl_EnableSoundEffects, EndAllProcChildren, StartOrChangeBgm, Title_IDLE, Title_EndAllProcChildren,
  DrawTitleSprites_Init, SetSecondaryHBlankHandler, OpAnimHBlank1, ColorFadeSetupFrom{ColorToWhite,White},
  sub_80C85FC, sub_80C8684, sub_8009B64, GameControl_PostIntro (partial), m4a fade-in/`TEMPORARY_FADE`.
  `m4aSongNumStartOrChange` is never called by FE8: leave it unclaimed.
- M5-32: slots 3/4 block-info checksum 1F1F1F1F in the dump vs ours for invalid kinds (stack garbage; test checks shape).
- PSG: 6 of 857 quarter frames differ from mGBA (ch1 f953/f991/f1009, wave f980): note-specific envelope steps,
  not a phase error (`SEQ_OFFSET_CYCLES` 68198 is best).
- f1180: BG VRAM 0xD800..0xE800 differs from the dump (sync timing of a BG map copy).
- Slow app open (2026-10-30): likely fixed (assets counted on `Dispatchers.IO`); if reported again, measure
  `MainActivity.onCreate`/Compose init and the first Play frame.
- Later: `.fe8s` beyond the prologue need each `ChapterEventGroup`'s LOAD targets; keysplit/drum sub-voicegroups
  are not written as JSON; OBJ mosaic; mosaic on affine BGs; GPU sprites still rasterised on the CPU.

## How-to
- New reference runs (ROM repo; needs cmake, ninja, network for a harness rebuild): add to `harness/gen_scripts.py`
  (`# args: --sram reference/fixtures/X.sav` header for save-based runs; owner saves go to `reference/fixtures/`),
  run it and `harness/make_reference.sh` (rebuilds all runs); check old runs are byte-identical to a fresh untar of
  `dist/reference.tar.xz` (only PROVENANCE.sha1 may differ); pack `tar -C reference --sort=name -cf - out | xz -9 -T0
  > dist/reference.tar.xz`; update its sha1 in `dist/MANIFEST.sha1` (else publish fails); push. The `publish-assets`
  workflow makes release `assets-<sha>` (latest); in the code repo `rm .assets/MANIFEST.sha1 && tools/setup.sh`.
  If new runs break the m4a tests, list them in `M4aRuns.kt`. PSG solo runs: `--audio --psg-mask N`; ours:
  `FE8_PSG_MASK` (with `--rerun`).
- Harness script: `run N`, `hold KEYS N`, `press`, `dump`/`dumpram`/`dumpsram NAME`, `watch NAME ADDR LEN` (≤ 16,
  LEN ≤ 0x10000, hex needs `0x`), `savestate`/`loadstate` (does not restore SRAM). `python3 -I harness/scan_trace.py
  <run dir>` names procs/gPlaySt/SRAM transitions; `gen_scripts.py --scratch-newgame OUT END RAM_EVERY [FROM]`.
- Re-extract: `./gradlew :tools:extract:run --args="--rom $FE8_ASSETS/baserom.gba --out <dir outside repo> --decomp
  <fireemblem8u> --map <rom repo>/decomp/fireemblem8.map --symbols <rom repo>/decomp/fireemblem8.nm.txt"`, then
  `python3 tools/compare_extraction.py <rom repo>/extracted <out>`, copy changed files into the ROM repo's
  `extracted/`, push there (CI fails until you do).
- Shader parity after a shader/`PackedCompositor` change: `FE8_SHADER_DUMP=<dir> ./gradlew :testing:test --tests
  '*FrameCompareTest'`, then `node tools/shader_parity.mjs <dir>`.
- Decomp: `git clone --filter=blob:none --sparse https://github.com/FireEmblemUniverse/fireemblem8u`, checkout
  `04d1edb…`, `git sparse-checkout set --no-cone include src texts asm data sound`.
- Trace timing: a frame that overruns its VBlank is dumped mid-frame; step only on dumps whose successor's key
  struct differs (`titleKeyPath`).

## Rules and caveats
- Aim for a `@VerifiesPort` every run or two. No `java.*`/`javax.*`/`System.*` in `commonMain`; battle rolls only
  via `mechanics.battleRolls`.
- Always `source .assets.env` before Gradle (else Maven Central 429s).
- Pushes race CI's `ci: update progress [skip ci]` commit: `git fetch && git rebase origin/master`, push again.
- CI Dokka fails on ANY warning (every public declaration incl. constructor `val`s needs KDoc): run `./gradlew
  dokkaGenerate -Pfe8.docs=true` (per module on the owner PC) before pushing public API. Keep `configureDokkaLint()`
  on Groovy property access.
- `tools/progress.py` credits a `@VerifiesPort` test to the LAST `class` declared before it: keep helper classes
  below the test class.
- Allocation tests can flake (200 B once in 1000 frames): use `AllocationMeter.measureMin`.
- `FrameCompareTest.applyHBlank` knows only `HBlank_BgVerticalScroll`; dumped RAM is one game step ahead of the
  dumped frame.
- CI `startup_failure` → check the owner's GitHub billing first. Actions storage is small: master's APK goes only to
  the `latest` release (branch APK artifacts 2 days, Dokka 1 day, results 7 days); if storage fills, delete old
  artifacts (`gh api -X DELETE repos/OWNER/REPO/actions/artifacts/ID`).
- `gh release view/download` (GraphQL) is refused; use `gh api repos/.../releases/...` (REST). Delete a `wip/`
  branch once merged.
- Data: `cursor_hand.png` colours `paletteSource: caller`; `effectiveAgainst` holds class numbers; classes inline
  shared terrain tables; `RamSymbols[name]` throws on ambiguous static names.
