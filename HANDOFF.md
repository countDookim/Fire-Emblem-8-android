# HANDOFF

_Updated 2026-11-15 14:35Z (owner PC: enemies walk, attack and fade (EP-8..9c, EP-8b/8c), map battle start (EP-13), level-up rolls (MC-17), ROM-portrait app icon, game-text CI check, 24 new readability rows done, readability paused 2 days; 950 tests green locally). Read GPT.md / CLAUDE.md / GEMINI.md for session rules.
Decisions: [PROJECT_BRIEF](docs/PROJECT_BRIEF.md). Plan: [ROADMAP](docs/plans/ROADMAP.md). Backlog: [TASKS](docs/TASKS.md).
Reference details: [M5](docs/plans/M5-notes.md), [M6](docs/plans/M6-notes.md)._

## NEEDS USER

Nothing blocks development. Owner device test 0.1.622 (11-12): D-11 and PP-0a confirmed; new reports D-14, D-15 below.
Extensive modding scope, separate vanilla tests and replaceable camera/controls/AI are accepted (ADR 0016).
[M7 decisions](docs/plans/M7-modding.md): release staging (D2), API details (D5), layout/debug/storage/packaging
(D4/D6/D8/D9) and executable runtime (D10) remain open; M6 and design inventories are not blocked.

## In progress

- MC-18a: Claude owner PC, level-up announcement (mapanim_lvup.c), until ~01:00Z (2026-11-16).
- EP-1b: Claude owner PC, start-of-phase display targets (bmtarget.c, bmmap.c), until ~02:00Z (2026-11-16).


- EP-17 (map animation flow A: mapanim.c), owner PC Gemini Flash agent `ep17`, claimed 2026-11-15 21:05Z.
- EP-10b (trap damage display and unit counts: bmusailment.c, uiselecttarget.c, bmunit.c), owner PC Gemini Flash agent `ep10b`, claimed 2026-11-15 22:20Z.


## Current state

- 2026-11-15 GPT/Terra: **MC-18i done**, original level-up display inventory (3 tests); MC-18a..f
  now have exact proc/display windows and dump-density limits. **EP-11g partial:** current-song query
  ported and mutation-checked; real map-song caller compared. No transition/cancellation/save credit;
  three CAP rows retain those gaps. Full local JVM suite: **980 passed, zero failures/errors/skips**
  (12m39s); progress **1256/7732**, ported-without-tag/unknown-verifies both 0. No new device behavior.
  Other AI claims preserved. Game Dokka passed (2m38s); instruction/generated-list/game-text checks passed.

- 2026-11-15 GPT planning: owner-approved extensive modding recorded in ADR 0016 and M7 plan.
  Vanilla stays original; mods can replace data/audio, executable AI/rules, camera and controls without
  rebuilding the APK. Added M7-24..32 design tasks; no runtime/storage choice silently selected.
  Brief, architecture, modding guide and all three assistant instructions aligned. Docs only; no new
  code, captures, port credits, APK or device checks. Existing implementation claims preserved.

- 2026-11-15 GPT planning only (owner-selected Astra high): broad M6-D..J tasks split in
  [TASKS](docs/TASKS.md) and [task scopes](docs/plans/M6-task-scopes.md). Candidate ports require
  inventory confirmation; later milestones have scenario CAP/INV rows, not invented function coverage.
  PC-easy-w closes M6-E with tutorials included. M7 implementation choices are tracked separately.
  No code/captures or device changes;
  planning checks only, no new JVM run or port credits. Other agents' claims remain authoritative.


- 2026-11-15 GPT owner PC: **EP-7b resolved** by original callback-phase capture; the danger-map test
  compares all 150 tiles with no exclusion. **EP-29a partial:** 27 original helper calls checked, but
  only default returns; all four helpers stay Transcribed (0 new port credits), EP-29cap added.
  Private references: `assets-c52dd60`; 50,728 older files byte-identical. Full local `jvmTest`:
  **966 passed, zero failures/skips**; game Dokka, Python/schema and game-text checks passed.
  Used isolated integration worktrees; other agents' unpublished work left untouched. No new device check.
- 2026-11-15 10:26Z – 14:35Z owner PC (Sonnet agents hit the 5-hour limit twice; resumed after account switches):
  **enemies act in game.** Merged EP-8 (perform walk, AI target cursor), EP-9 (perform action: Soldiers attack via
  the real map battle and die to Eirika's counter), EP-9b (equip after a move), EP-9c (BMXFADE map fade, entity map
  refresh), EP-8c (MU end of walk), EP-8b (MU step sounds, fixes BUG-mustepse), MC-17 (level-up growth rolls; display
  is new row MC-18), EP-13 (map battle start: MOVEUNITs face off ~3 frames, rest of the animation is EP-17). Demoted in review: CpPerform_MoveCameraOntoUnit (camera never moves in a capture). APP-icon:
  original vector launcher icon; after ROM import Eirika's portrait (built from the ROM in memory) is the recents
  icon and an "Add to home screen" shortcut (owner chose runtime-only, rule 3). BUG-gametext: ROM message text removed
  from tests/comments/docs, new CI step `tools/check_no_game_text_in_source.py`. READABILITY: the 11-14 "complete" was
  wrong (rows named files sized 11-11); 24 rows added for fe8.game.bm and fe8.game.worldmap, all done. New rows: BUG-deadmovestat (C reads a stat pid through NULL after the
  actor dies), MC-18 (level-up display), LG-0 weather sub-lane. Device checks pending: MANUAL_TEST step 5 (enemy walk,
  attack, fade pause, footsteps), APP-icon step; earlier PP-17, D-14, D-15.
- 2026-11-13 20:16Z – 2026-11-14 23:08Z owner PC (paused ~22:10Z–21:40Z next day on the usage limit): **M6-D AI
  decision chain ported; full-Prologue and level-up captures; readability done.** Merged EP-2b (AI unit flags),
  EP-3a (CpDecide loop, suspend point), EP-3b (no-move flag, danger map), EP-4 (script interpreter, commands 05/06/0E),
  EP-5 (attack search), EP-6a (combat score, damage-dealt fallback), EP-7 (movement/range maps, class rank). In-game,
  End turn now runs the real AI: both Soldiers choose the captured attack, but nothing is performed (EP-8 perform is
  null), so enemies still do nothing visible and turn 2 starts. Demoted to Transcribed in review (evidence was
  hand-derived or a self-made assertion): GetSpecialItemFuncIndex, sub_8040C5C, AiIsUnitEnemy,
  AiGetOpponentLowHpScoreComponent. Open mismatch EP-7b: Red 0's f1612 danger map lacks the second player unit's
  damage on 35 tiles (those tiles are skipped in the test; a mid-frame snapshot was ruled out). Captures (ROM repo):
  EP-cap2 `ram_trace_prologue_clear` (`assets-93b13fa`: boss turn event, boss move-only phase, Seth kills the boss,
  ending scene start; no level-up) and MC-cap3 `ram_trace_prologue_levelup` (`assets-8051ed8`: Eirika levels at
  f6760; also two more boss attack phases). EP-0b inventoried the new windows into 45 PORT rows (EP-1b..EP-36, PC-2a;
  EP-0b's boss-move row renamed EP-7d to avoid the EP-7b CAP). Readability: every READABILITY row is now done/audited
  (BmSaveLib 2, BMapMain, opanim x5, events extractor, bm unreviewed row x8, fe8.game x5, UnitTable). Behaviour
  mismatches the reviews found are TASKS rows: BUG-evtmenu, BUG-muadjust, BUG-mustepse, BUG-itemefftgt, BUG-wminit,
  BUG-wpnbits, BUG-blueleader, INV-iconstride. Next: EP-8 (perform: move, cursor), EP-9 (perform: action), then EP-7d
  (boss move), MC-17 (level-up, capture ready), EP-6b. Device check still pending: PP-17, D-14, D-15, MANUAL_TEST
  step 3 (no new manual-test behaviour this session).

- 2026-11-13 owner PC (17:56–20:08Z): **M6-C done except MC-17; M6-D started.** Merged MC-8b (hit checks verified),
  MC-8c (triangle attack transcribed; was always false), MC-w1/MC-w2 (headless replays of the attack and kill captures
  through Fe8Boot match every unit byte and the RNG; attack flow 0 B/frame), TEST-2/TEST-4 (MU walk and waiting event
  loads no longer allocate; TEST-5 menu callback boxing and TEST-6 one-off walk allocations open). EP-cap1 published
  (ROM `assets-2500bf5`, `ram_trace_prologue_enemy_phase`: both Soldiers attack Eirika and die, boss idle, no
  level-up). EP-0 inventory (traced; EP rows rewritten, 2/3/6 split). EP-1 (phase start/end) and EP-2a (AiPhaseInit,
  CpOrder; Fe8Boot runs them, CpDecide a stand-in until EP-3a). Next: EP-2b (claim released), EP-3a.. ; MC-17 needs
  EP-cap2. Readability: fe8.game.ui all rows; fe8.game.bm PhaseIntro/MapAnimations, sprite group, state group, camera
  group, BmMap group, ChapterData/ChapterMapGfx; save BmGameSave group and BmSaveLib 1-520 audited. Left: BmSaveLib
  521-1080, bm BMapMain/BattleMapKind, bm UnitTable, bm 3863 (never reviewed), opanim, fe8.game, extract events.
  Merge scripts: `|| exit 1` on every step (set -e did not stop them). Device check pending: PP-17, D-14, D-15, M6-C
  attack flow (MANUAL_TEST step 3, APK 0.1.667+).

- 2026-11-13 owner PC (to ~10:00Z): **M6-C almost complete.** Merged MC-3a, 3b, 4a, 4b, 5, 6, 9, 10, 11, 12a, 12b,
  13, 14, 15, 16 (with MC-1/2/7/8a from 11-12): Attack -> weapon list (rows, panel, face) -> target cursor -> forecast
  (slides in, arrows, x2) -> battle resolves at once (no map anim, M6-H) -> exp, write-back, dead unit removed.
  Left in M6-C: MC-8b (hit checks), MC-17 (level-up; needs EP-cap2), MC-w (WIRE + headless replay). Stand-ins:
  level-up bookkeeping only (MC-17), dying rescuer no-op (C14-1). New bug rows: BUG-gold, BUG-changemap.
  Readability: all fe8.game.worldmap, fe8.game.event, fe8.app, gba.* (core, input, vfs, data, extract, testkit),
  gbafe.script, small engine/extract rows, savemenu, fe8.testing, title, OpSubtitle, save suspend group audited.
  Left: gbafe.extract.fe8.events, fe8.game, fe8.game.bm (3 rows), opanim (OpAnim/Hs/EarlyStartUi), save (BmSaveLib;
  BmGameSave group), fe8.game.ui. Local full suite is now `./gradlew jvmTest --continue` in the main checkout
  (~6 min; TEST-SPEEDUP Change 4). Device check pending: PP-17, D-14, D-15, M6-C attack flow (MANUAL_TEST step 3).

- 2026-11-12/13 owner PC (17:30–23:10Z): **M6-C started.** MC-1, MC-2, MC-7, MC-8a, MC-9 and MC-3a merged (attack
  target list, battle rounds and hits, forecast simulation, Attack opens the weapon list; 896 tests green, Dokka
  clean). Next: MC-3b, MC-4, MC-5, MC-6, MC-8b, MC-10. Readability: gba.render, gbafe.render and 4 of 8 worldmap rows
  audited; WorldMapLineFade darken constant fixed. Next readability: worldmap Scroll, Screen, Unit, Mu (one file each,
  owner 11-13: small tasks). Device check pending: PP-17, D-14, D-15, MC-3a (MANUAL_TEST).

- 2026-11-12 owner PC (evening): **M6-B code-complete.** PP-17 merged (player phase wired in Fe8Boot: select, range,
  arrow, move, unit menu, Wait, cancels, map menu End, side windows, Burst; enemy AI an M6-D stand-in that ends at once,
  End turn reaches turn 2); crash fixes for a windowed ROM and a unit-less slide-in; cursor-move allocations removed.
  Earlier today: PP-11, CAP-status1 (ROM `assets-5c99df4`), PP-18. Device check pending (MANUAL_TEST PP-17).
  Readability pass (rule 11) not done (owner: Lane B only).

- 2026-11-12 owner PC: **Lane A (M6-A) done.** New Game → monologue (Start skips) → world map (event script, text, camera, highlights, portraits, units, gradient) → "Prologue" title card → castle → player phase, all wired through Fe8Boot; 872 tests green, Dokka clean, 1034/7732 verified (13.3%). Device check of the whole opening pending (MANUAL_TEST D11-wire-b / D11-wm9 entries). Lane A rows in TASKS are empty; next lane-A-like work comes from the device check.

- Owner PC 11-11 (15:23–16:50Z): PP-5..8, PP-10, PP-12, PP-13, PP-16, D11-ci3c, D13-cap1, D13-1, D13-2 merged; 4 readability audits;
  854 tests, 1 local failure (TEST-2, passes in CI); 944/7732 verified. WIP: `wip/PP-11`; D11-wm8a analysis in D11-worldmap §8a.
- ROM repo has no Actions minutes until ~2026-12-01 (owner, 11-11): publish new captures locally with
  `gh release create assets-<sha> --target <full sha> --latest` and publish.yml's file list (done for `assets-bd66e15`, `assets-8a14ce3`). Every ROM-repo commit message
  must contain `[skip ci]`: a push touching `dist/` otherwise starts publish.yml (owner 11-12: no ROM Actions).
  Both ROM workflows (publish-assets, decomp-map) are disabled since 11-12; re-enable with `gh workflow enable` after ~12-01.
- Latest owner test: **local 0.1.488 (`0a73f11`)**: neither reported castle audio stutter reproduced.
  Original save restored afterward; ROM import preserved. Published 0.1.488 (`8c5b7c9`) banner comparison remains partial.
- Published **0.1.598 (`8605040`)**: CI **830 passed, zero failures/skips**; debug/release APK content
  guards and benchmark passed. CI retains only this build's results/Dokka artifacts and rolling APK release.
  Rapier popup icon/cleanup fixes remain included (first in 0.1.528).
- Current source: CI 598 reports **895 verified of 7,732 in scope (11.5%)**; 620 link/debug functions
  are out of scope (8,352 total). `ported-without-tag: 0`, `unknown-verifies-names: 0`.
- New Game reaches the Prologue opening and turn-1 map; suspend/resume and cursor movement work.
  Player actions, battle animations and the real save/difficulty menus remain unimplemented.
- Layout: game-neutral `:gba:*`, `gbafe.*` in `:engine:*`, FE8 `fe8.*` in `:game`/`:app` (ADRs 0010–0013).
  All APKs require player ROM import (`base/rom/baserom.gba`); no game content is bundled.

## Device status

Close an issue only after reference verification and owner confirmation; preserve stated comparison limits.

| Issue | Latest evidence / status |
|---|---|
| D-07 audio | **Resolved for both reported castle events:** owner heard neither hitch on local 0.1.488 (`0a73f11`); normal trace PCM repetitions fell 4495→0 after the movement frontier change. Earlier cache/reserve changes alone failed. |
| D-12 phase banner | **Player banner confirmed correct by the owner on 0.1.488 (11-11)**; reference: `PhaseIntroDifferentialTest`. Enemy/green banners open (D12-2, with M6-D). |
| D-11 cinematic | **Confirmed by the owner on 0.1.622 (11-12):** monologue, Magvel world-map exposition and Start in the monologue (skip to the exposition with a fade-in) are correct. Follow-up D-14. |
| D-14 WM skip fade | **Fixed, device check pending (`d349bd9`):** Start during the world-map exposition while a portrait was shown left a stale face proc that threw, freezing the game dark; the skip now also cuts to black at once as the original. `WorldMapSkipBootTest`. |
| D-15 class reel | **Stand-in, device check pending (`d349bd9`):** title idle no longer hangs on black; the class reel (needs battle animations, M6-G) is skipped and the opening animation follows, as after a played reel. `TitleIdleBootTest`. |
| D-09 Rapier popup | **Resolved:** owner confirmed the popup correct on 0.1.571 (11-11); reference: `WiredItemPopupTest`/`GotItemPopupDifferentialTest`. Other popup branches stay D09-3 (low priority). |
| D-13 Start in cutscene | **Open (owner, 0.1.590, 11-11):** Start during the Prologue in-game cutscene fast-forwards visibly instead of skipping. Triage: `EventEngine_StartSkip` sets the skip bit but its effects (`StartLockingFadeToBlack(0x40)`, `sub_800D488`, `Proc_BlockEachMarked(5)`) sit behind the no-op `startSkipEffects` hook. Rows D13-cap1, D13-1. |
| PP-0a hover cursor | **Confirmed by the owner on 0.1.622 (11-12):** the cursor grows/shrinks over units and player units play their hover animation. |
| D-05 interactions | Expected missing gameplay; re-test when player actions are ported. |

D-01–04, D-06, D-08, D-10 and normal portrait mouths are owner-confirmed solved.
Earlier device checks cover boot/import/controls/GPU/suspend-resume. Do not request routine repeats of solved checks.

## Next work

Plan re-done 2026-11-11 (owner-approved): [ROADMAP](docs/plans/ROADMAP.md) has the milestones and task rules;
[TASKS](docs/TASKS.md) has the open rows. Tasks are capture-first: CAP → INV → PORT (≤ ~8 functions the capture
window runs) → WIRE. Two lanes run in parallel; take the first `todo` whose deps are done in either.

1. **Lane A (M6-D → M6-E):** finish unclaimed, dependency-ready EP rows; skip live claims above.
   `MC-18i` is done; take MC-18a..f using its evidence limits and a precise coding brief. `PC-cap1` captures the
   ending/save/Chapter 1 route; `PC-cap2` the lord-death path. `PC-9i` checks existing menu coverage.
   Source scopes and proposed test names: [M6 task scopes](docs/plans/M6-task-scopes.md).
2. **Lane B (M6-F):** `IS-cap1` → `IS-0`/`IS-2i` → the observed stat-screen/help function groups.
   Unit list, status, options, guide and minimap each have their own CAP/INV. M6-G..J likewise use
   scenario-sized discovery tasks; their inventories must add exact PORT dependencies before WIRE.
   Do not code a `blocked` shortlist merely because its source functions are named. Normal completion
   is `PC-w`; Easy tutorials are required by `PC-easy-w`, the M6-E close. M6-B is code-complete.
3. **Readability paused by the owner 2026-11-15 ~15:30Z for two days (until ~2026-11-17): porting only.** Then resume
   one row per session; 4 rows open (added 11-15 for new files).
   **Readability (rule 11, changed 11-11):** comments must be meaningful and checked against the decomp
   ([READABILITY](docs/READABILITY.md)). Rows done before 2026-11-15 are audited; 24 rows added 2026-11-15 for
   fe8.game.bm and fe8.game.worldmap files that had none (the 11-14 "complete" missed them). New or heavily
   changed files get a new row (Use Sonnet, not Haiku).
4. **Alignment done 2026-11-11:** the progress bar counts in-scope functions only; generated lists
   [UNVERIFIED](docs/plans/UNVERIFIED.md) (written but unverified code) and [STANDINS](docs/plans/STANDINS.md)
   (placeholders by milestone) are checked in CI: regenerate both with `--write` after changing such comments.
5. **Agent notes (11-11):** brief agents to keep scripts in their own scratchpad subdirectory (one agent ran
   another's `edit1.py`). `WalkingAllocationTest` failed intermittently under heavy load once (PP-1 run); it passed
   3/3 afterwards. Re-run it if it fails.
6. **TEST-1 done 11-11:** `:testing` runs in 3 forks; AllocationMeter classes run in `serialTest` (auto-detected).
   Full run ~3.3 min (was 4.5). Revert notes: [TEST-SPEEDUP](docs/plans/TEST-SPEEDUP.md).
7. **M7:** design inventories M7-24..32 precede implementation. Scope is accepted; present concrete
   runtime/API and remaining packaging/storage proposals for owner review before dependent coding.

`@VerifiesPort` takes bare function names; `"src/x.c: F"` silently counts nothing.
Node table checks use pinned runtime source: `tools/setup_decomp.sh` or `FE8U_DECOMP` (read-only).

## Verification limits worth retaining

- D-07: before/after Android traces, profiling limits and ADB setup are in [D07-tracing](docs/plans/D07-tracing.md).
  The frontier preserves weighted movement-pool results and passes existing original movement tests.
  Other long ticks remain; neither trace isolates per-function CPU cost or precisely timestamps the two reported events.
- D-12: `PhaseIntroDifferentialTest` checks chapter-6 player-banner state/buffers/graphics plus complete OBJ
  attributes at six captures f1700..1750 (`a95588a`). `aefe6d8` corrects VCount-to-HBlank line translation with a
  source-derived render-line test; original per-line blending, sound hooks and enemy/green branches remain unverified.
  The replacement uses the primary HBlank slot; coexistence with another handler is not established.
- M4a tests exclude divergent runs in `M4aRuns.kt`; exclusions are not verified coverage. M6-13 needs an autolevel
  trace; M6-14 needs a colliding unit-load trace. Generic palette fades and non-normal portrait modes remain partial.

## Working notes

- CI storage: keep only the latest successful master build (rule 15); `ci.yml` replaces older Actions
  artifacts after results, Dokka and the rolling APK release are available. Branch builds upload no artifacts.
  Initial cleanup: 727→14.9 MiB, two artifacts for CI 598; local Python checks: 53 passed.
- Claim before coding; skip live claims. Use `git fetch` + `git rebase --rebase-merges origin/master`.
  CI may add a progress commit, so fetch/rebase again if a push races it. Commit completed steps; unfinished work uses `wip/`.
- Owner PC: `source .assets.env`, export `ANDROID_HOME=$HOME/android-sdk`; use `$HOME/.cache/fe8-tmp` for logs/temp.
  Maven mirror: `tools/maven_mirror.py` on port 8765 (`/google` before `/central`).
  Serialize Gradle with `.gradle/agent-checks.lock`; use two workers / 2 GiB heap and run Dokka per module
  (`:game:dokkaGenerate -Pfe8.docs=true`; fails on undocumented public members, incl. companions).
- Full checks: `./gradlew jvmTest test`. Focused verification uses `:testing:test --tests <class>` (no `:testing:jvmTest`).
  Filtered runs replace result XML: regenerate progress only after the full suite. Put helper classes/enums below
  tagged tests: the progress parser uses the last preceding class declaration. CI normally commits README/port map;
  regenerate/commit them locally when needed. Keep `ported-without-tag: 0` and mutation-check every new port tag.
- Refreshing an asset archive/manifest alone does not install new runs: unpack/copy them into
  `FE8_ASSETS/reference/out` too; otherwise newly added tests fail on missing files.
- ROM bytes/text/glyphs/dumps belong only in the private ROM repo; tests read FE8_ASSETS. New captures must preserve
  old runs, update packed assets and manifest SHA1, then publish/refresh assets; see the private repo README and harness.
  An overrun dump may be mid-frame; account for RAM/OAM sync timing. GPU/shader changes also need shader parity checks.
- Keep assistant instructions synchronized through `docs/AGENT_INSTRUCTIONS.template.md` and
  `tools/sync_agent_instructions.py`. Continue [READABILITY](docs/READABILITY.md) reviews on owner-PC sessions.
- Completed history and older investigation details: `git log`; pre-compression notes: `git show 8c5b7c9:HANDOFF.md`.
