# HANDOFF

_Updated 2026-11-10. Read GPT.md / CLAUDE.md / GEMINI.md for session rules.
Decisions: [PROJECT_BRIEF](docs/PROJECT_BRIEF.md). Backlog: [TASKS](docs/TASKS.md).
Reference details: [M5](docs/plans/M5-notes.md), [M6](docs/plans/M6-notes.md)._

## NEEDS USER

Nothing blocks development. Emulator comparison of D-12 is pending; see [MANUAL_TEST](docs/MANUAL_TEST.md).

## In progress

- D11-wm3 (WorldMap_Init step timing, tag prefix): owner PC, until ~22:30Z
- D-11 subtitle (extend replay past 2357 by callback phases): owner PC, until ~22:30Z

## Current state

- Latest owner test: **local 0.1.488 (`0a73f11`)**: neither reported castle audio stutter reproduced.
  Original save restored afterward; ROM import preserved. Published 0.1.488 (`8c5b7c9`) banner comparison remains partial.
- Published **0.1.528 (`e0187ea`)**: CI **737 passed, zero failures/skips**; fixes the Rapier popup
  icon and stale frame on close. Local full `jvmTest test` also passed 737; both APK builds/content
  guards, game Dokka, 27 Python tests and instruction sync passed.
- **674/8352 functions verified** (+3 scheduled 11-10 night: `RefreshGmNodeLinksExt`, `RefreshGmNodeLinks`, `sub_80B8FD4` (state_0 only; state_3 clear unobserved); +4 scheduled 11-10 eve: `WMLoc_GetChapterId`, `WMLoc_GetNextLocId`, `NewWorldMap`, `WorldMap_CallBeginningEvent`; +2 scheduled 11-10 pm: `Event15_BgmVolume` subcode 0, `GmDataInit`; +12 earlier 11-10: `BMapVSync_InitMapAnimations`, `BMapVSync_Start`, `BMapVSync_OnLoop` in `1cd6709`; `StartBrownTextBox`, `sub_8011A1C`, `sub_8011A48`, `sub_8011AA0`, `sub_8011AF4`, `sub_8011B4C`, `sub_8011B90`, `GetItemType`, `GetItemAttributes` in `de5b055`), `ported-without-tag: 0`; 897 EventScr scripts reproduce ROM bytes at runtime.
- New Game reaches the Prologue opening and turn-1 map; suspend/resume and cursor movement work.
  Player actions, battle animations and the real save/difficulty menus remain unimplemented.
- Layout: game-neutral `:gba:*`, `gbafe.*` in `:engine:*`, FE8 `fe8.*` in `:game`/`:app` (ADRs 0010–0013).
  All APKs require player ROM import (`base/rom/baserom.gba`); no game content is bundled.

## Device status

Close an issue only after reference verification and owner confirmation; preserve stated comparison limits.

| Issue | Latest evidence / status |
|---|---|
| D-07 audio | **Resolved for both reported castle events:** owner heard neither hitch on local 0.1.488 (`0a73f11`); normal trace PCM repetitions fell 4495→0 after the movement frontier change. Earlier cache/reserve changes alone failed. |
| D-12 phase banner | **Appears correct on 0.1.488; emulator comparison pending.** Presence is confirmed, exact fidelity is not. |
| D-11 cinematic | Open: the subtitle monologue and following world-map opening are unwired. |
| D-09 Rapier popup | Open, owner low priority. Headless inventory grant is checked; device inventory is unknown. |
| D-05 interactions | Expected missing gameplay; re-test when player actions are ported. |

D-01–04, D-06, D-08, D-10 and normal portrait mouths are owner-confirmed solved.
Earlier device checks cover boot/import/controls/GPU/suspend-resume. Do not request routine repeats of solved checks.

## Next work

1. **D-11:** standalone subtitle parent/flare scripts remain unwired. Replay covers steps 1214–2354
   with the real archived-palette fade (510/512 live entries match; entries 273/417 are unmodelled).
   `1fee6d4` verifies original fade startup/display disabling on the observed branch. New private
   `38dc714` captures scene setup entering at 2355 and returning at 2357; `82dfb5d` compares the atomic
   Kotlin callback with its original return state. VBlank advances the clock/runs tree 0 inside
   that callback, so clock ticks are not completed normal updates. Next: extend replay using
   callback phases, then verify later lifecycle, normal ending and original Start skips before
   wiring. Monologue 1213–4897; world-map wrapper 4898–9313 is separate. See [M6 notes](docs/plans/M6-notes.md).
2. **D11 world map** (steps 4898–9313, [plan](docs/plans/D11-worldmap.md)): wm1/wm2 done; GmData wired at boot and
   post-save; `WorldMapNodes` (fe8.game.worldmap) replaces the no-node stand-in on whole ROMs. wm3 partial:
   `WorldMapMain.newWorldMap` (timer 16 path) and `callBeginningEvent` (Prologue branch; callees are
   `BeginningHooks` lambdas, unwired) verified; wrapper script transcribed, unverified/unwired
   (`ArchivedPaletteFade.script` = `ProcScr_BmFadeIN` now public). Next: ProcScr_WorldMapMain/WorldMap_Init
   prefix, real BeginningHooks (`Events_WM_Beginning`, gmapEventId), wiring.
   11-10 night: `WorldMapPath.kt` (gWMPathData, GmNodeLinks; RefreshGmNodeLinks verified on ch6/ch2 runs — prologue
   table stays zero); ResetGmStoryNode/GetNextUnclearedNode/DeployEveryUnit transcribed (no trace distinguishes them);
   `mainScriptPrefix` + `MainHooks` transcribed; untagged test matches sleep(4)/(1) gaps, but original main sits at
   script pos 3 (WorldMap_Init) for steps 4899–4901 vs ours one step — investigate Init yielding before tagging.
   WMLoc trace check covers node 0/default mode only; `WmNodeDataReferenceTest` full-table checks need
   `FE8U_DECOMP` (decomp checkout) and skip in CI.
3. **D-12:** owner compares the banner with an emulator; D12-2 adds enemy-phase and original scanline captures.
4. **D-09:** item popup is wired through Event37, GotItemPopup and PopupScript. `539016e` fixes the
   omitted icon OAM callback and missing BG sync on close. The real boot-path test checks visible
   icon/palette/glyph/text state, blocking volume children, cleanup and final inventory against
   `ram_trace_item_popup`; silent audio and state alignment do not establish full-scene/audio timing.
   Next: owner checks the popup (low priority). Red/root/full-inventory and negative-clock paths,
   unit-name/number/wtype instructions and gold popups remain unverified or unwired.

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

- Claim before coding; skip live claims. Use `git fetch` + `git rebase --rebase-merges origin/master`.
  CI may add a progress commit, so fetch/rebase again if a push races it. Commit completed steps; unfinished work uses `wip/`.
- Owner PC: `source .assets.env`, export `ANDROID_HOME=$HOME/android-sdk`; use `$HOME/.cache/fe8-tmp` for logs/temp.
  Maven mirror: `tools/maven_mirror.py` on port 8765 (`/google` before `/central`).
  Serialize Gradle with `.gradle/agent-checks.lock`; use two workers / 2 GiB heap and run Dokka per module
  (`:game:dokkaGenerate -Pfe8.docs=true`; fails on undocumented public members, incl. companions). CI runs 530/531
  failed on Dokka only; fixed in `2151d50` (CI pending at handoff).
- Full checks: `./gradlew jvmTest test`. Focused verification uses `:testing:test --tests <class>` (no `:testing:jvmTest`).
  Filtered runs replace result XML: regenerate progress only after the full suite. Put helper classes/enums below
  tagged tests: the progress parser uses the last preceding class declaration. CI normally commits README/port map;
  regenerate/commit them locally when needed. Keep `ported-without-tag: 0` and mutation-check every new port tag.
- ROM bytes/text/glyphs/dumps belong only in the private ROM repo; tests read FE8_ASSETS. New captures must preserve
  old runs, update packed assets and manifest SHA1, then publish/refresh assets; see the private repo README and harness.
  An overrun dump may be mid-frame; account for RAM/OAM sync timing. GPU/shader changes also need shader parity checks.
- Keep assistant instructions synchronized through `docs/AGENT_INSTRUCTIONS.template.md` and
  `tools/sync_agent_instructions.py`. Continue [READABILITY](docs/READABILITY.md) reviews on owner-PC sessions.
- Completed history and older investigation details: `git log`; pre-compression notes: `git show 8c5b7c9:HANDOFF.md`.
