# HANDOFF

_Updated: 2026-10-26 23:20 UTC (scheduled run, started 22:50)._

## Owner decisions 2026-10-25 (read docs/decisions/0009)
- The app is a 1:1 copy. Modding = extension points only; ship no touch gameplay, QoL flags or alternate
  formulas. Only touch input shipped: on-screen GBA buttons (task M3-15).
- Every ported system adds strategy interfaces to `gbafe.core.mechanics.Mechanics` (vanilla impl only,
  alternatives only in tests). Battle must use `mechanics.battleRolls` for hit/crit/skill rolls, never
  `rng.roll1/roll2` directly.

- Reuse by future FE6/FE7 ports (docs/decisions/0010): packages are now `gbafe.*` (engine, extract) vs
  `fe8.*` (game, app, testing, `gbafe.extract.fe8`); data formats `gbafe.<table>` + `"game": "fe8"`.
  New shared systems go in `gbafe.<system>` modules; FE8-only ones (world map, skirmishes...) in `fe8`.
  `ExtractCli` now dispatches on `--game` (default fe8) to `gbafe.extract.fe8.runFe8Steps` (Fe8Steps.kt).

## NEEDS USER
_(nothing blocked)_

## Status
- This run: **M3-06 done**. Menu frames (m0962/m1024/m1086/m1087) were off for two reasons, both fixed:
  (1) the menus run `HBlank_BgVerticalScroll` (BG2HOFS rewritten per line, the rune "fog" wave) -> ported as
  `gbafe.render.BgVerticalScroll` + `gbafe.render.HBlankLines` (replays a frame's HBlank handler, vcount 159..227
  then 0..158, into `LineTable` overrides); (2) mGBA 0.10.5 (32-bit colour build) blends on 8-bit channels
  (`c<<3|c>>2`), up to +1 at 5 bits vs hardware -> `SoftwareRenderer` takes a `ColorMath` strategy
  (`ColorMath.Hardware` default = product), frame compares use test-side `fe8.testing.MgbaColorMath`.
  Health & safety from **extracted assets**: `gbafe.data.Tsa` (TSA rows stored bottom-up), generic
  `gbafe.extract.ScreenExtractor` (`gbafe.screen` json + tile-set PNG), FE8 step writes `ui/health_safety.*`
  (ROM repo `c6f4189`, release published); `HealthSafetyScreenTest` pixel-exact.
- New verified ports (+10 -> 19): `HBlank_BgVerticalScroll`, `GetBgVerticalScrollBuffer`, `FlipBgVerticalScroll`,
  `BG_SetPosition`, `SetBlendConfig` (`LcdControl`), `SaveDraw_ScrollFogBG` (`fe8.game.savemenu.SaveDraw`),
  `OpAnimHS_BrightenPalette`, `GameIntroHealthSafetyWaitButton`, `GameIntroHealthSafetyFadeIN/OUT` (`fe8.game.opanim.OpAnimHs`). Differential tests
  vs mGBA RAM: `SaveDrawDifferentialTest` (menu dumps), `OpAnimHsDifferentialTest` (`ram_trace_boot`).
  Unverified helpers added: `LcdControl.setBlendTargetA/B`, `setDispEnable` (no Ported-from KDoc yet).
- CI: `tools/ci_check_extraction.sh` now compares PNGs by content (`tools/compare_extraction.py`): the session
  JDK's zlib writes different deflate bytes than CI's for identical pixels (all 228 PNGs differed bytewise).
- CI: green through aec1f96; later runs were in progress at wrap-up (a run cancelled by a newer push shows its
  test step as failed: not a real failure). Check the latest run first.
- Session setup: ~114 s. Branch: `master`. No `wip/` branches. Progress: 19 / 8352 verified.

## Run rules (owner, 2026-10-26)
- Budget **~45 min** (guideline; a little over is fine to finish a nearly done task). Work until ~minute 38, never end
  early: when a task finishes, start the next (on `wip/<task-id>` if it may not finish). Wrap up by ~45.
- Run `tools/setup.sh` in the background with a log and poll it. Never fetch full CI job logs (step conclusions
  first, then `tail_lines` <= 80 of the failing job).
- Interleave verifiable ports with infrastructure: a `@VerifiesPort` lands every run or two.

## Next task: M3-07 GPU backend (or more verifiable ports first)
- M3-07 GPU backend (`:app`, manual test) can now show a real scene: the health & safety screen from
  `ui/health_safety.*` (see `HealthSafetyScreenTest` for the assets -> `RenderState` steps).
- Verifiable port candidates (interleave rule): the rest of `opanim-healthsafetyscreen.c` (Nintendo / IS
  fade steps + `GameEarlyStartDelay`, which also call `TryTickHSScreenExit`, `PrepareHealthAndSafetyScreen`) against `ram_trace_boot` (the
  intro proc is `ProcScr_GameEarlyStartUI` 0x08AA7680; `proc_scrCur` points PAST the running command, so the
  active `PROC_REPEAT` function is at `scrCur - 4`); `StartBgVerticalScroll` / `SetBgVerticalScrollPosition`
  (menu entry); `SetBlendTargetA/B` need a dump where they run (first frame of a step).
- `gSinLookup` is now extracted as `data/trig.json` (`gbafe.trig`, schema `schemas/trig.schema.json`; ROM repo
  `f92fe18`), but nothing loads it yet: add a `gbafe.data` loader before `SaveDraw` runs in the app (tests still
  read the table from the ROM). New `schemas/screen.schema.json` for `gbafe.screen`.
- Frame-compare notes: `FrameCompareTest.applyHBlank` only knows `HBlank_BgVerticalScroll` and fails on any other
  handler; dumped RAM is one game step ahead (the menu already flipped its scroll buffer: the test un-flips).
- M3-10 Android ActionSources, M3-12..15 audio/overlay. Open renderer items: OBJ mosaic, mosaic on affine BGs.
- Follow-ups noted, not tasks yet: unit placement / `.fe8s` for chapters other than the prologue need the event
  converter to follow `LOAD1/LOAD2` targets from each chapter's `ChapterEventGroup` (`include/chapterdata.h`);
  keysplit/drum sub-voicegroups are scanned for samples but not written as their own `voicegroups/*.json`.
- Re-extracting after an extractor change: run the CLI (see the M2-21 lines in Known bugs), copy the output into the
  ROM repo's `extracted/` (only the changed/new files: PNG bytes differ by zlib build), commit + push there
  (publish-assets makes a new release); CI fails until you do.
- New reference runs: add to the ROM repo's `harness/gen_scripts.py`, run it + `harness/make_reference.sh` (seconds;
  `bin/mgba-harness` is committed), check old runs are byte-identical, `tar -C reference -cJf dist/reference.tar.xz
  out`, update the sha1 in `dist/MANIFEST.sha1`, push (publish workflow releases it). Quick screen exploration: run
  the harness on a scratch script and convert `*.frame.rgb` (240×160 RGB888) to PNG to look at it.

## Known bugs / caveats
- `RenderStateTest.filling a frame allocates nothing` failed once locally right after a full build and passed on 4
  reruns (allocation measurement flake, JIT timing); if it recurs in CI, raise the warm-up rather than skip it.
- `tools/progress.py` attributes a `@VerifiesPort` test to the LAST `class` declared before it in the file: keep
  helper classes below the test class (or top-level after it), or the port is not counted.
- Local `master` in a fresh session can be an old unrelated history (seen this run): the checkout came up detached
  at origin/master; use `git checkout -B master origin/master` style recovery (rename the stale branch first).
- Extractor CLI: `./gradlew :tools:extract:run --args="--rom $FE8_ASSETS/baserom.gba --out <dir outside repo> --decomp
  <fireemblem8u checkout> --map <rom repo>/decomp/fireemblem8.map --symbols <rom repo>/decomp/fireemblem8.nm.txt"`.
- `gh release view/download <tag>` (GraphQL) is refused in Claude sessions; use `gh api repos/.../releases/...`
  (REST), as `setup.sh` 3b does. `gh release download` without a tag still works.
- Hand cursor colours in `cursor_hand.png` are a stand-in: the game draws it with whatever OBJ palette 0 the calling
  screen loaded (`paletteSource: caller` in cursors.json).
- CI's Dokka step fails on ANY warning (every public declaration, incl. constructor `val`s, needs KDoc). Before
  pushing new public API run `./gradlew dokkaGenerate -Pfe8.docs=true` (after `tools/setup.sh` + `source .assets.env`).
- Decomp checkout for this work: sparse set must include `texts` (face names in messages.json) and `sound`
  (song/sample ground truth): `git sparse-checkout set --no-cone include src texts asm data sound`.
- `.fe8s` v0 prints args as raw numbers (u16, or u32 for ROM pointers/`set_slot`); symbolic constants and a
  real parser are M5. `move_unit_modify` etc. = the macro's 1-bit `modify` parameter baked into the sub-command.
- The decomp **source** is not in the session assets (only `decomp/fireemblem8.{map,nm.txt}` in the ROM
  repo). Extraction with names needs a checkout: `git clone --filter=blob:none --sparse
  https://github.com/FireEmblemUniverse/fireemblem8u` then `git checkout 04d1edb…` + `git sparse-checkout
  set --no-cone include src texts asm data` (~10 s, works through the session proxy). Tests hard-code the few
  names/addresses they need, so CI does not need it.
- Classes inline copies of shared terrain tables; terrains.json also lists them by symbol. Decide with the
  data loader (later M2) whether classes should reference tables by name.
- `effectiveAgainst` in items.json holds class numbers until items and classes are cross-linked.
- Always `source .assets.env` in the same shell before Gradle: without `FE8_MAVEN_MIRROR` set, Dokka
  resolution goes straight to Maven Central and fails with 429s.
- Long Gradle runs (Dokka with `-Pfe8.docs=true`) can exceed the 2-min tool timeout on a cold daemon: run
  them in the background, or repeat (second run is warm).
- Maven Central through the session proxy returns spurious 404/429; `tools/setup.sh` starts
  `tools/maven_mirror.py` (retries). CI is unaffected.
- `GameSurfaceView` is untested on a real device (no gameplay yet; manual test only).
- Static C names can repeat across files; `RamSymbols[name]` throws if ambiguous — use unique names.
- ROM data symbols in `decomp/fireemblem8.nm.txt` (ROM repo) mostly have no size (asm labels): use the
  next symbol's address as the span, as `Lz77RomTest` does.
- Dokka's Kotlin types are not on the build classpath outside docs builds; `configureDokkaLint()` in
  `build.gradle.kts` configures it via Groovy property access — keep it that way.
