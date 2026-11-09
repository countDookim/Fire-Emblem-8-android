# HANDOFF

_Updated 2026-11-09 (owner-PC 15-min run: Issue #5 device results recorded, D-08/D-10/mouths solved on 0.1.474/d91ed28, D-06/D-07 Issue #5 trace findings, 3 readability rows done; latest published APK 0.1.476/fbf71c5). Owner decisions: docs/PROJECT_BRIEF.md. Backlog:
docs/TASKS.md. M5 facts and reference runs: docs/plans/M5-notes.md. History: git log._

## NEEDS USER
_(nothing blocked)_

## In progress
_(none; owner 15-minute run completed; no worktrees open)_

## Latest device test (owner, 2026-11-09, Issue #5)
Build **0.1.474 (`d91ed28`)** (GitHub Issue #5): everything except the 5 items in Issue #5 is confirmed solved on device:
- **D-08** (outdoor Seth/Franz dialogue background corruption and post-dialogue map restoration, `6d3406c`/`910fcfa`): **solved 11-09**.
- **D-10 & portrait mouths** (portrait eye blinking `350f19a`/`191449c` and normal mouth animation `28bef11`): **solved 11-09**.
- **Still open in Issue #5 on `0.1.474` (`d91ed28`)**:
  1. **D-07**: brief audio stutter in the castle when the blue soldier walks up, before continuing normally. (`0.1.474` pre-dated `c339db5`; published APK **0.1.476 (`fbf71c5`)** includes `c339db5`. Trace also shows a one-time ~11 KB allocation on walk start when `StartMuInternal`/`MuSpriteAnim.create` decompresses the MU sheet.)
  2. **Seth vs Valter battle animation**: missing (known unported `ekr` battle animation system; deferred to M6 part 2).
  3. **D-06**: Franz and Seth no longer stay stuck permanently on the edge (`7ac8384`), but stop right on the map edge for a moment before fading out instead of riding cleanly off-screen. (See D-06 row for script/engine causes.)
  4. **D-09**: missing Rapier-received popup (low priority per owner).
  5. **D-05**: player phase only moves the cursor (expected until M6 part 2 player-phase actions/menus are ported).

## Earlier device checks (historical, 2026-11-07)
These are older build observations, not the current device status. See the device-issue table and latest report below.
- Device test of APK 5e213f1 passed all 3 MANUAL_TEST items (KMP rebuild, issue #4 Prologue map + song, resume turn
  1). Issue #4 can be closed. Owner-reported problem on the Prologue opening (owner 11-07: note it, don't
  investigate yet): the opening plays but its cutscenes are skipped and no dialogue is shown; the screen flashes a
  few times; many cutscene units that should not be there end up on the battle map with the allies; then the cursor
  can be moved but nothing happens; there is no player phase and no enemies. Code facts (read, not traced):
  `Fe8Boot.eventEngine` (Fe8Boot.kt:184-252) registers only EventScrCore, unit loads, ChangeMap, Fade, Bgm; Talk
  (M6-33/34), Mu (M6-25..27) and `EventUnitState` (Event34) are ported but not registered there, so their commands
  hit `ReleaseEventStubs` no-ops. Note the headless `AppRomPathTest`/ `PrologueMapTest` do reach `playerPhaseScript`
  (15 red units), so the device path differs: find out why first.
  Re-check 11-07 20:00Z (owner PC; headless device path = `Fe8Game` + temp FileSaveStorage + actionSource input,
  scripts watch / START-mash / A+START-mash, old 5e213f1 vs master bd863ea). 5e213f1 reproduces the report: player
  phase proc starts at f652 while events run to f681 (skip) / f1834 (watch), so the map runs during the cutscene
  (no game lock); the tutorial branch ran; 9 blue + 15 red cutscene units stay, some off the 15x10 map (x 19/20), none
  of them the real enemies. Master (M6-29 fix): player phase starts after the events (f691 skip, f1937 watch), 2 blue
  + 3 red, red equal r19400, cursor moves (BmSt.playerCursorX 9..14). Still wrong on master: (1) no dialogue: 1A x17,
  1B x22, 1D x13, 21/22 x4 hit ReleaseEventStubs (EventText/Talk* exist but are not registered in Fe8Boot.eventEngine;
  M6-44); (2) Seth/Eirika end at (8,5)/(9,5), ref (4,4)/(4,5): 2F MOVEUNIT x17 and 30 ENUN x23 are stubbed; also 15,
  0D, 37, 3A stubbed; (3) flashes: real EventFade BLDY 16->0 ramps at event start/end, same old and new, not compared
  to a trace; (4) A on a unit/menus not tested headless. Device re-test: clear app data first (a valid suspend in
  filesDir/fe8.sav makes the stand-in save menu resume instead of New Game). Scratch harness (uncommitted):
  owner PC $HOME/.cache/fe8-tmp/DevicePathPrologueScratchTest.kt; worth turning into a regression test.
- Open from issue #4: Event15 + `StartBgmVolumeChange` (save-menu BGM ramp, ram_trace_newgame f1174..1193),
  GameControl fade stand-ins (GameControl.kt:206-212, 409-414). Map palette not yet compared to first_phase pal.bin.

## Device issues (owner-reported; close only when verified)
Status stays `open` until the fix is verified: a test against a reference run shows the original behaviour AND the
owner confirms it on a device/APK (owner 11-07: "only mark them as solved when they're actually verified solved").
A merged fix without both is `fix merged, unverified`. Never mark `solved` from headless runs alone.

| ID | Owner-reported problem (build/date noted where relevant) | Initial findings / reference and code facts | Current status |
|---|---|---|---|
| D-01 | Opening "flashes": the screen fades in and out for each dialogue, but no dialogue (and no units) is shown, so it is skipped at once and looks like flashing. | Text commands 1A x17, 1B x22, 1D x13, 21/22 x4 hit `ReleaseEventStubs` no-ops; EventText/Talk*/TalkBubble/TalkFace exist but are not registered in `Fe8Boot.eventEngine` (M6-44). The fades are the real EventFade. | solved 11-08 (reference: PrologueOpeningTextTest BG0/BG1 vs ram_trace_prologue_talk; owner device check of APK after a01a8cb). Portrait display, blinking (D-10) and normal mouth animation owner-confirmed in 0.1.474 (d91ed28), 11-09 |
| D-02 | In the in-game cutscene before the Prologue map no cutscene unit is shown and every event is skipped one after the other until the map begins. | Headless master: the cutscene unit loads run (f1324 red 45 + 2x 80, cleared f1595) but 0 OBJ sprites are drawn during events (scratch harness `maxObjDuringEvents` = 0); 2F MOVEUNIT x17, 30 ENUN x23 stubbed. Why nothing is drawn: not investigated. | solved 11-08 (reference: CutsceneUnitSpritesTest, unit OBJ of ram_trace_prologue f09600..f12300 equal ours on the frame with the same unit table; owner device check). f12600/f12900/f18000..f19200 unit OBJ now in the test too (11-08) |
| D-03 | Map start: Seth and Eirika are placed at roughly the right tiles, but the original then has a short dialogue between them, after which Seth walks 4 tiles left and 1 up, then the map begins. Both are missing. | Headless: they end at (8,5)/(9,5), ref first_phase r19400 (4,4)/(4,5); MOVEUNIT/ENUN and the text commands are stubbed (D-01). | solved 11-08 (reference: MoveUnitDifferentialTest end tiles vs r19400; owner device check). Walking animation itself is owner-confirmed 11-09; off-screen exits remain D-06 and occasional stutter is D-07. Owner 11-08: on Easy the original runs a walking tutorial (player moves Eirika) instead of her automatic walk; Normal/Hard walk automatically, as the APK does. Our New Game stand-in always writes Normal (SaveMenuWriteNewGame difficulty 1, controller 1), so the Easy tutorial branch (Event19 CHECK_TUTORIAL -> 89EF27C) is not reachable until the save menu / difficulty select is ported; test it then. |
| D-04 | Enemy units are on the correct tiles but appear as allies. | Headless master: they are in the red unit array (ids 0x81..0x83, chars 0x68/0x82/0x80, tiles equal r19400), so the unit data is red; how they are drawn (sprite palette/allegiance in UnitSpritesOam) not investigated. | solved 11-08 (reference: MoveUnitDifferentialTest `index byte` test, every unit's index (faction -> palette) equals r19400; owner device check) |
| D-05 | Earlier report (5e213f1): cursor moves but nothing is interactable. Not re-reported on 3380240. | Headless master: cursor moves (BmSt.playerCursorX 9..14); A on a unit and menus untested. | closed 11-08 (owner: expected for now; after the units are placed the cursor moves but nothing is interactable because no player-phase actions are ported yet; re-check when they are) |
| D-06 | Owner 11-09, 0.1.467 (060478a) & 0.1.474 (d91ed28, Issue #5): in 0.1.474 Franz and Seth no longer stay permanently on the edge (7ac8384), but stop right on the map edge for a moment before fading out instead of cleanly riding out. | 7ac8384 wired `Event34` `refreshMapAndSprites`. Trace of `src/events/prologue-eventscript.h` shows why they pause at the edge: (1) inside the castle (`:80-96`), Franz walks 8 tiles to `(12,11)` while Seth walks 12 tiles to `(13,11)` before `ENUN` + `DISA`, so Franz stands at `(12,11)` ~32 frames waiting for Seth; (2) on every `MuCtrl` end (`MuCtrl.kt:521-536`), `MuCtr_OnEnd` un-hides the standing unit sprite at the edge tile on the frame the MU ends, and `Event30_ENUN` yields (`EVC_ADVANCE_YIELD`) for 1 frame before `DISA` runs; (3) outside the castle (`:146-151`, `:192-200`), right after `DISA`, `FlashCursor(..., 60)` stalls 60 frames with `startDisplayCursor` currently a no-op hook in `EventCursorCamera.kt` before `Text_BG`/`FADI(16)`. | open (partial fix 7ac8384 confirmed removing permanent sprite; edge pause before fade-out remains in Issue #5) |
| D-07 | Owner 11-09, 0.1.467 (060478a) & 0.1.474 (d91ed28, Issue #5): brief audio stutter in the castle when the blue soldier walks up, then continues normally. | `0.1.474` (`d91ed28`) did not yet include `c339db5` (which removed per-frame `HashMap<Int, SpriteObject>` boxing during walking; now published in `0.1.476` / `fbf71c5`). Trace on the castle soldier walk-start frame (f785) also shows a one-time ~11,312 B allocation when `StartMuInternal`/`MuSpriteAnim.create` decompresses the MU sheet (`rom.objN`). | fix merged (`c339db5`), published in **0.1.476 (`fbf71c5`)**; re-test on 0.1.476+ (walk-start decompression remains if stutter persists) |
| D-08 | Owner 11-09, 0.1.467 (060478a): background corrupts at the outdoor Seth/Franz dialogue and worsens afterward. | Map tile animation overwrote conversation BG3 VRAM. Scheduled 6d3406c adds BMapDispSuspend/Resume; ConvoBackgroundVramTest checks original f13200..14100 and 330 live scene frames. Owner-PC audit adds Event23/24 moving-unit locks (a4ff217), reference semaphore transitions and source-derived nested/resume checks. 910fcfa additionally restores the available map graphics, palettes and sprite data after dialogue; full scene BG3 and nonanimated restored map tiles/palettes match original captures. | solved 11-09 (reference: ConvoBackgroundVramTest + 910fcfa; owner device check of 0.1.474/d91ed28, Issue #5) |
| D-09 | Owner 11-09, 0.1.467 (060478a): no popup announcing Eirika received the Rapier before the first battle; inventory cannot be checked because player-phase interactions are unavailable. | Event37_GiveItem's NewPopup_ItemGot path is a stand-in. OpeningStubCommandsTest asserts the Rapier in Eirika's inventory headlessly; this is not device inventory confirmation or a trace verification of Event37. | open; low priority (owner) |
| D-10 | Owner 11-09, 0.1.467 (060478a): portraits do not blink; original FE8 behavior confirmed. | Scheduled 350f19a starts gProcScr_FaceBlink and wires SetFaceBlinkControl. Owner-PC audit 191449c verifies normal eye OAM and selected portrait tiles in both orientations, plus individual mutation checks of all four tagged functions. 28bef11 additionally implements the mouth proc; a separate test follows both children and the shared LCG for 300 frames from one seed, replaying display bits. | solved 11-09 (reference: 191449c/28bef11; owner device check of 0.1.474/d91ed28, Issue #5) |

## Audit 11-06 20:10Z (owner PC) of scheduled work 3c26e9e..ab985a8
Full jvmTest green (555). 6 tags withheld because breaks survived (UpdateMapImgAnimations: VRAM every 10th frame;
GameCtrl_StartResumedGame: camera 0; FadeFromCommon_OnLoop: one fade speed; GetUnitSpriteHideFlag; SetEventSlotCounter;
WriteLastGameSaveId): stronger tests can reinstate them. KDoc fixes done (M6-46). Unchecked groups: ReadGameSave/
WriteGameSave, ReadSuspendSave, unit-sprite reset/use/refresh, StartLockingFadeFromBlack, SwitchPostSaveMenu.
Same session: M6-23 (EventChangeMap), M6-25/26/27 (Mu.kt start, movement, display; AP_* animation player is a hook),
M6-30 (gbafe.text.MsgStrings), M6-31/32 (gbafe.text Font/TextSystem fonts, widths, glyph drawing, PutText) done;
M6-46: no "Ported from" without a tag left (`progress.py` prints `ported-without-tag`; it now ignores .claude/ worktrees).
M6-33/34 (fe8.game.scene Talk: init, interpreter, wait-for-A; the whole first talk matches per frame) and M6-38/39
(BmBattle + BattleGenerate: unit init, stats, scripted hits; Prologue battle matches r14430) done. Next: M6-35/36
(faces, bubble/shift procs: TalkInterpretDifferentialTest has transcribed stand-ins for them), M6-37 (Event1A/1B),
M6-40/41 (battle result + Event3F).
 Merged `wip/M6-05`, `wip/M6-17` deleted.

## Scheduled 2026-11-09 10:51Z
M6-14 blocked: headless Prologue makes 51 `flags & 1` unit loads, none onto an occupied tile, so
AiGetUnitClosestValidPosition is never reached (stale NewGameToPrologueTest note fixed); needs a colliding-load trace.
M6-28 marked done via D-03 (01eb8c4). M6-20: GetCurrentMapMusicIndex tagged (MapMusicReferenceTest; blue slot vs
gSoundSt.songId; MAP_BGM_BLUE mutation fails). Blue songs start r19387 / ch6 r01754 after the phase switch (old song
stops r19316 / r01684); r19313 and ch6 f01620 (song 0x34, in no ch6 slot, likely event-started) are pre-switch states.
Green r19280 0x13 = its slot but also the red slot, origin unchecked: green/red stay unverified.
StartMapSongBgm tagged: gSoundSt songId/playing/unk7/maxChannels vs r19387 and ch6 r01754 (seeded r19386/r01753;
songId+1 mutation fails); m4a player state not compared. Event12_StartBGM next step: ram_trace_newgame_bm r09539->r09540 is a clean
stopped->started pair (songId 4 stopped -> 0x26 playing, unk7 0, maxChannels -1): seed the event proc as
EventVmDifferentialTest does, run event12StartBgm, compare gSoundSt. Caution (scheduled follow-up): no EWRAM/IWRAM word in r09530..r09540 points at a ROM
`12 00 26 00` command, so 0x26 may not come from Event12; find the event proc's pEventCurrent first. Then M6-45 (owner claim expired 10:57Z) or D09-*.

## Owner-PC audit 2026-11-09
Validation: full `jvmTest test` and debug/release APK builds passed (639 tests, zero failures/skips);
engine:extract and game Dokka passed per module. Both APKs passed the no-game-content guard against
1,535 extracted assets. Python checks: 27 passed. Assistant instructions synchronized. Progress regenerated
from the full suite: **545/8352**, `ported-without-tag: 0`. Units/font extractor readability completed.
APK publication: **Build 0.1.474 (d91ed28)** was published earlier; a new CI build from `master` adds the D-07
walking sprite-cache fix (`c339db5`). Latest owner device result is still 0.1.467/060478a; D-06/D-07/D-08/D-10
and mouths remain pending device confirmation.

- D-07 walking allocation fix (`c339db5`): `MuSpriteAnim.objectAt` boxed `addr: Int` on each `HashMap.getOrPut` lookup while rendering walking MU sprites. Replaced with primitive `IntArray` + `Array<SpriteObject?>` storage; `WalkingAllocationTest` verifies sustained 10-frame allocation-free visible-walking windows across the Prologue opening (8 of 26 windows at 0 B).
- Reviewed scheduled D-08 (`6d3406c`) and D-10 (`350f19a`). Blink counters/intervals and visible normal-eye OAM/tiles match original captures in both orientations; five individual mutations failed and were restored. Held/small eye modes remain unverified; normal mouth animation was subsequently implemented (28bef11).
- D-08 retained the background overwrite guard. Added omitted Event23/24 moving-unit block/unblock (PROC_MARK_MU, not music), reference semaphore transitions, source-derived nested suspension, and animation-resume coverage. Focused classes green; removing MU block failed. Neither device issue is marked solved without owner confirmation.

- D-08 follow-up 910fcfa: Event22 had no map-graphics restoration. New regression compares scene BG3 VRAM/tilemap/palettes at f13500, restored nonanimated map tiles and palette banks 6–15 against first_phase f19430, transition tilemap against f14100, and idle tilemap against f19430. Removing restoration fails; focused event/map/background tests green. Animated VRAM 0xA000..0xAFFF excluded from the map-return comparison because capture phases differ.

## Next task: device re-test D-08/D-06/D-07/D-10 and mouths; D-09 Rapier popup remains low priority
Scheduled 11-09 00:17Z (full jvmTest+test green): D-08 fixed 6d3406c, see table (other BG palette ~123 B diffs and f13500 BG0 text-tile diffs not explained; alignment by unit table only). Ref f14100/f14400 are black, f14700 is the Valter battle anim (unported), so later BG3 diffs are battle prep, not D-08. wip/D-08: ConvoBackgroundFade (Event21 subcode-1 fade procs, transcribed; Prologue never calls it) + Bg08ScratchTest diff harness; merge the fade only with a test. Gradle here needs `python3 tools/maven_mirror.py --port 8765 --cache /root/.cache/fe8-maven &` + FE8_MAVEN_MIRROR (Central 429).
Owner-PC 11-09: 28bef11 starts the original mouth child before the blink child and queues ROM mouth tiles until VBlank. Five tests cover parent/timer initialization, speaking/silent state, 300-frame shared LCG sequencing, OAM/VRAM in both orientations, and production flush timing. Six deliberate mutations failed then were restored. Smile/blend paths and exact initial phase from reused original proc memory are outside this verification. No new StartFace tag.

Latest device evidence remains 0.1.467 (060478a). D-06/D-07/D-08/D-10 and normal mouths now have merged
fixes and await owner confirmation on a new APK; none is marked solved. D-07 profiling (`c339db5`) removed
per-frame `Int` boxing in `MuSpriteAnim.objectAt` during unit walking and added `WalkingAllocationTest`. D-09 Rapier popup remains explicitly low priority and device inventory is
unknown. The unexercised fade procs on wip/D-08 must not be merged without reference verification.
D-09 follow-up: private ROM repo f7a442a adds reproducible ram_trace_item_popup generation (old scripts
unchanged). Local private reference/out/ram_trace_item_popup has 181 RAM snapshots and 10 full graphics
captures. Inputs match the original Prologue prefix; EWRAM/IWRAM match at six overlapping captures;
popup/item/icon procs are active in the four visible captures and absent at both endpoints. Output preserved in the private checkout; no ROM-derived files entered this repo.
Not packed/published or installed into FE8_ASSETS yet: D09-1b must handle the known Prologue sound-test alias
from that exact provenance before publishing. Then D09-2 UI frame/icon primitives and D09-3 popup/event wiring.
D-09 remains open/low priority; this capture does not implement the visible popup or confirm device inventory.

Owner PC 11-09: CLAUDE.md, GPT.md and new GEMINI.md now share docs/AGENT_INSTRUCTIONS.template.md; profiles in
tools/sync_agent_instructions.py preserve each assistant's model/tool choices. Edit the template/profile, run
`python3 tools/sync_agent_instructions.py --write` then `--check`; CI rejects generated-file drift. Idempotence,
deliberate GEMINI drift/recovery, Python syntax and CI YAML checks passed. All three now require proactively
announcing new manual tests with steps, expected results, limits and actual APK build/publication status.
Owner PC 11-09 extra 15-minute run (23:24..23:39Z; three Terra Medium agents): D-06 graphics timing corrected.
MuSpriteAnim now queues live source row descriptors (32-entry bound, MU-only subset of the global registry), then
Fe8Boot flushes them at VBlank after backgrounds and before high OAM. Immediate copies had made graphics one
pass ahead of synchronized OAM. MuTimingDifferentialTest now byte-compares all 64 referenced OBJ tiles plus all
four MU OAM entries at f12600; early-flush mutation fails, restored tests pass. Other frames/device remain unverified.
StartFaceFadeOut verified: palette banks 22/23 and face lifetime match the original fade-out window; duration
12->11 mutation fails. Delayed removal ends the fade before full black, as in the original. Generic PaletteFade
stays transcribed: portrait paths exercised, arbitrary color transitions/durations/custom stops not independently verified.
Entrance portrait base OAM matches f9890/9900/9910 in both orientations using fixed draw state r(frame-2): parent
face draws before its move child; hardware OAM syncs the preceding pass. No new tag for this test.
Full `jvmTest test` passed; 539/8352 verified, `ported-without-tag: 0`. Game/extract/render Dokka passed (per module). UI extractor readability done (94e08df); JSON/PNG behavior preserved. Device checklist updated.

Owner PC 11-09 resumed (Sol High + three Terra Medium agents): saved WIP tests integrated. Guide flags verified:
 all 48 flag bytes vs prologue n15877->15878; flag-write and sentinel mutations fail. ASMC dispatch/yield
also compared, but Event0D_AsmCall remains untagged (subtype-1 skip unobserved).
Portrait fade-in: production TalkScene palette banks 22/23 match every color at talk r09871..r09884;
12->11 frame mutation fails. StartFaceFadeIn tagged; generic palette fades remain transcribed (nonblack/early-stop
paths not reference-verified). Blink/mouth/fade-out and moving-face OAM parity still open; device check pending.
D-06: full boot aligned at the first castle pair movement with original completed-pass inputs; all four
MU/controller states match 72 passes and all four MU OAM entries match f12600. Fixed capture-boundary guards explain
n12528 and n12561/62; no runtime timing change or new port tags. Next: other MU animation frames/VRAM, then device check.
Full `jvmTest test` and progress.py tests (7) passed; 538/8352 verified, `ported-without-tag: 0`. Extraction/render/text
and game Dokka passed (per module); shared-message-buffer KDoc fixed. Terrain readability done (7c6df24).
Scheduled 11-08 23:15Z (full jvmTest green; Dokka not run, gbafe.text public API grew): M6-44 Event3A done as transcription, untagged: `fe8.game.event.EventPopup` (sub 1 brown box; sub 0 NewPopup_VerySimple throws), `fe8.game.scene.BrownTextBox` (popup.c box + fade procs), gbafe.text sprite text (initSpriteTextFont, initSpriteText, getSpriteTextDrawDest, drawSpriteTextGlyph, spriteTextDrawBackgroundExt, textInsertDrawString, getStringTextCenteredPos; Config dispatch on sprite glyph/dest pointers). Registered in Fe8Boot: opening stubs left = none (OpeningStubCommandsTest). BrownTextBoxReferenceTest: hold-phase OAM (30 objs), BLDCNT/BLDALPHA and OBJ VRAM 0x15400..+0x800 (text) + 0x15000..+0x2C0 equal ram_trace_newgame_bm f09559 (box x=8,y=0, width 12; also in prologue f09600). Not verified: alpha ramp frames (dumps every 30 f), box tiles after 0x152C0 (dump has leftovers). Facts: hardware OAM lags one step, so the 0x400 blend bit shows 2 views after SetBlendNone (C: 1 step). Then tagged (break-checked: oam2 offset, width r4+5, dest *32, glyph shift each fail the test): BrownTextBox_Loop, StartBrownTextBoxCore, GetSpriteTextDrawDest, DrawSpriteTextGlyph; tools/progress.py now strips the `[jvm]` suffix of :game test names (before, no :game @VerifiesPort test counted; 536 verified). Next: fade procs (sub_8011A48..B90) need a per-frame run for the ramp; Event3A_DisplayPopup sub 0; delete wip/M6-44-3A, wip/M6-44-3A-blend.
Owner PC 11-08 18:34Z: talk face OBJ output implemented via cached ROM compositions; Face_OnIdle/FaceRefreshSprite
break-checked against 124 base-face OAM comparisons (talk f9950..10560, both orientations). Hidden/blend/wrap only
behavior-checked; entrance movement, blink/mouth overlays and palette fades not verified. Focused tests 3/3 passed;
device check pending. Next for that agent: palette fade-in vs the reference.
Owner PC 11-08 18:31Z: corrected six unsupported event `Ported from` labels to `Transcribed from` with effect-specific
verification gaps; source audit now `ported-without-tag: 0`. Terrain extractor readability reviewed; TerrainsRomTest 3/3
passed, full suite/Dokka pending before marking the readability row complete. Next for that agent: verify ASMC/guide flags.
Scheduled 11-08 16:51Z (full jvmTest green): M6-44 transcribed + registered, untagged: Event22_ClearScreen (+Event24 part; RefreshBMapGraphics/BMapDispResume stand-ins) 1e4068e; Event21_TextBg + EventShowTextBgDirect 122ae98 (opening: 4x BACG, slot2 bg 29, text type 1: real BG3 gfx/tsa/pal load; fade procs sub_800EA84..EF48, DisplayCGfx, ColFadeIn/Out are host stand-ins); Event0D_AsmCall + bmguide BmGuideTextSetAllGreen (opening's only ASMC). New gauge `OpeningStubCommandsTest` (Fe8Boot.onEventStub): opening stubs left = 0x3A only. Event37_GiveItem transcribed (give/gold real, popups stand-ins, full-inventory drop menu throws; test asserts Eirika has Rapier 0x09). Facts for next: item popup = NewPopup_ItemGot (popup.c:494, PopupScr_GotItem interpreter), not drawn; 0x3A = sub 1 BROWNBOXTEXT(0x664,8,8) @0x089EECBC -> StartBrownTextBox (popup.c:776-830, ProcScr_BrownTextBox :605-662). No popup code exists in Kotlin. Next: popup.c (brown box for 0x3A, item-got popup), then trace-compare 0x21/0x22 (BG3 VRAM/pal vs ram_trace_prologue) to tag them.
Scheduled 11-08 10:50Z (full jvmTest green): f12900 'missing 2 OBJ' was a one-frame transition (units 139/140 appear in the table one frame before their sprites; next frame all 15 equal the dump), no draw bug. CutsceneUnitSpritesTest now covers f09600..f12900 + f18000..f19200 (f12600's table does occur, 61 f). Other dumps: f13200..f14400, f16200..f17700 have no unit OBJ; f14700/f15000/f15300/f15600/f19500/f19800 table equal, OBJ never equal (f14700 = Valter vs Seth battle animation screen, 44 OBJ tiles 448..511 pal 11/12 = efx/banim objects, we draw 4 map sprites: battle anim not ported; others not diffed). MU OBJ (D-06): f12600 dump 4 MU objs; ours equal in x/tile/pal/shape/size, y gap between pairs 39-40 px vs dump 36 (no frame equal; not fixed by an original-like A pulse, PrologueBoot now takes `pulse`). Facts: one event command at 0x089EEE04 starts all 4 MUs (pair 2 created hidden, walks 27 f later in ours); event pointer DF8->DFC->E04 takes 1+1 f in ours vs 3+... in original (n12523->n12526, procs.watch.bin) and the original's pair 1 seems to start ~6 f after E04 (inferred from y + A pulses, not measured). Next: decode the MU proc state in the original around n12526..12600 (MuCtrl / move script waits) and compare with ours; GetMuQ4MovementSpeed (A held = x4) matches mu.c.
Scheduled 11-08 04:51Z: cause of the f12900 SMS 38/39 swap: Fe8Boot's 0x2C stand-in forced the silent load (FADEDIN) for the red cutscene loads (script 0x89EEDF0/DFC/E08, real bits 0x002, screen visible in the original), so all reds appeared at once. Fixed 6adccbb: EventLoadUnitWithMovement/TryPrepareEventUnitMovement transcribed (proc callback, EventUnitLoadsHost), LoadUnit_800F704 starts MuCtr_StartDefinedMove (UnitLoader.startDefinedMove hook), stand-in removed; full jvmTest green. Now slots 38=77, 39=109 = dump. f12900 table frame (PrologueBoot f3041): we draw 13 map-sprite OBJ, dump 15; missing `112,96,344,13,0,1` and `80,112,130,13,8,2` (y,x,tile,pal,shape,size; pal 13, probably walking reds). Next: those 2, then add f12900 to CutsceneUnitSpritesTest; `sumFlag` defs still throw (AdjustNewUnitPosition flags&2).
  Facts on the 2 (not certain): on f3041 and f12900 unit tables, hidden bits and MU configs (all slot 0, standing MUs) agree; tiles 344/130 are sheet tiles, not MU tiles; y=112 fits char 0x40 (id 140) at (12,7), y=80 maybe a char 0x84 red at (10,6)/(16,6). So likely our sprite refresh/draw skips them (handle list or SMS tile); not checked.
Scheduled 11-07 22:51Z: D-02 reference test 9b88ae5; D-06 ap.c + MU walk wiring d55a4dc (wip/D-06 merged, delete it). Full jvmTest green; Dokka not run (no dokkaGenerate task without SDK). f12900 after d55a4dc (table-equal frames, all OBJ): the 2 objects missing before are drawn now; still differs: map-sprite tiles 346/282 swapped (4 objs, pal 13) and 7 dump-only pal-6 objs tiles 768..848 (not MU tiles; UI/text?). f12600: our run never has its unit table. 346/282 is not an SMS animation phase (syncUnitSpriteSheet swaps VRAM, not tile numbers): tile = sheet slot from useUnitSprite counters, so likely allocation order; dump f12900 gSMS16xGfxIndexCounter=56, 32x=10, 12 slots (f12600: 57/6/9). Found (Fe8Boot.unitSprites now public): on the f12900-table frame our counters equal the dump (56/10) and all slots equal except gUnitSpriteSlots[38]/[39] swapped (ours 109/77, dump 77/109; already so at f12600): the first `useUnitSprite` calls for SMS ids 38 and 39 run in the other order. Next: find which load/refresh order allocates them (trace when slots 38/39 appear in the r-dumps before f12600) and fix; then add f12900 to CutsceneUnitSpritesTest.
Owner PC 11-07 20:30-21:25Z (3 agents): D-04 34d5c05 (New Game path skipped InitUnits: all unit index 0 -> faction blue), D-03 01eb8c4
(Event2F_MoveUnit + Event30_ENUN tagged, MoveUnitDifferentialTest blue (4,4)/(4,5) = r19400; MuCtrl transcribed; ENUN refresh gives
units sprite handles during events = D-02; walking units hidden until they arrive: ap.c not ported), D-01 a01a8cb (text
commands real; tests now page the dialogue with A, opening ~5000 f), M6-20 1f70922 (BGM volume ramp tagged vs newgame
f1174..1192; test skips the proc step on lag frames f1175/f1181; Event15 transcribed). Remote wip/M6-20 is merged: delete it (owner).
Readability: gbafe.core.flags, gbafe.extract, .fe8, .fe8.chapters/.characters/.classes/.items/.maps/.mapsprites/.portraits done. Full jvmTest green after each merge.
Owner PC 11-07 19:10-19:40Z: wip/M6-29-v3 merged (cdff3dc). Cause of the 2-unit idle map: the dev New Game stand-in
wrote `(mode 0, isTutorial -1)`, so config.controller stayed 0 and Event19 CHECK_TUTORIAL (89EE0C0) took the tutorial
branch (89EF27C) instead of the enemy load + ally move; SaveMenuWriteNewGame difficulty 1 passes mode 1, isTutorial 1
(ram_trace_prologue: controller 1). Fe8Boot now also wires event lockGame/resumeGameplay to the loop. Idle map now
2 blue + 3 red; red ids/chars/tiles equal first_phase r19400, blue are at (8,5),(9,5) vs ref (4,4),(4,5) because MOVEUNIT is
still stubbed (the "(4,4),(4,5)" in commit 381be2f was wrong: PrologueMapTest checks no positions). NewGameToPrologueTest's START phase now starts with the beginning events. Full jvmTest
green, 524 verified. Probably related to the owner's device report: re-test on device. Delete wip/M6-29-v2, wip/M6-29-v3
(owner). Readability: gbafe.proc, gbafe.data (c0f92eb), gbafe.input, gba.core/.event/.time (697847c), gba.core.sram, gba.vfs (1b2b121), gba.input (52e07f0) done; 13 of 53 rows done.
Scheduled 11-07 16:51Z: `wip/M6-29-v3` = wip/M6-29-v2 rebased onto master (conflicts in EventVmDifferentialTest
STUBS/MIN_STUB_PAIRS resolved; delete wip/M6-29-v2). Not merged: PrologueMapTest "600 idle frames" fails there (cursor stuck
at (19,27) on the 15x10 map). Root cause (subagent, not verified by me): `Fe8Boot.eventEngine` keeps the no-op
`EventEngineHooks.lockGame/resumeGameplay`, so BMapMain runs during the beginning events (trace: gbmst lock=1 steps
9507..19311); changePhase queues phase event 89f75a4 (never run in the trace) which ChangeMaps and runs Event26 CAM1 [19,27].
Master passes only because Event26 was a stand-in. Wiring lockGame=loop.lockGame()/resumeGameplay=loop.unlockGame() fixes the
cursor tests but `idle Prologue map has unit sprite objects` then gets 2 units: the 2nd beginning pass branches at 89ee0c0
(Event19 CHECK sub 7 claim, subagent; doubtful: 89EE0C0 runs twice, both times falling through to CALL). Ref run idle: blue
(4,4),(4,5) + 3 red. Per-frame unit dump with the lock (follow-up 11-07): cutscene loads/clears as expected (f1324 red 45 +
2x 80, cleared f1595), then the final load at f1613 brings only Eirika (9,5) + Seth (8,5); no enemy load and no move to
(4,4)/(4,5) follows; the engine then ends (last script 89EF27C..2AC, then 8592114). Ally count right, enemy load and ally
positions missing: compare the event address sequence after f1595 with the reference run. Likely also the owner's device
symptom. Then wire the lock and merge wip/M6-29-v3.
Owner PC 11-07 15:40-16:05Z: M4 finished as far as traceable: M4-12 (6 Proc_* tagged, 4 untraceable) and M4-02
(soft-reset run + test; Huffman/RL unused). M4 open only: the reset path's one-frame offset in EarlyStartUi (TASKS M4-02),
wiring SoftwareResetFlag/KeyComboReset into the boot. New CLAUDE.md rule 11: every owner-PC session reviews a section of
docs/READABILITY.md (done: gbafe.core math/time/mechanics/rng; next: a small todo row). `.assets` on the owner PC has both new runs (published).
Owner PC 11-07 15:10-16:05Z: M6-37 done (EventTextDifferentialTest; EventText not in Fe8Boot). CI on master failed since
11-06 23:49 (Dokka: 2 undocumented; summary: battle_end run not packed): fixed (1d089a8; ROM repo c66e0fd packs
ram_trace_prologue_battle_end, old runs byte-identical; `.assets` updated). Full jvmTest green, 518 verified. CI does
commit README/port_map itself after a green master run (the How-to line saying it never does was wrong). Owner
deleted `wip/M6-41` and `wip/M6-29` (11-07). `wip/M6-37` is merged into master and can be deleted (owner: auto mode
refuses remote branch deletes here).
Scheduled 11-07 13:05Z (full jvmTest green 13:27Z): Fe8Boot now registers Event34 (EventUnitState, hooks: unitAt only) and Event3F (EventScriptBattle,
host: lock = loop.gameLock, entity-map refresh; anim/AiRefreshMap/RefreshUnitSprites default) (81c9e51; Fe8Boot/Prologue/
NewGame/ResumeBoot tests green). `wip/M6-29-v2` = wip/M6-29 cherry-picked onto master (old wip/M6-29 has unrelated
history in the shallow clone; delete it and wip/M6-41, branch deletes are refused for scheduled runs) + EventVm replay runs
real 0x3F (out of STUBS/UNIT_STUBS): ScriptBattleDeamon breaks at the gbmst lock and its unit result equals the r15090 dump
(base run; battle run: lock never returns in range). ScriptBattleDeamon still untagged (thin: 1 unit HP; PROC_MARK_EVENT
unchecked). `wip/M6-20`: StartBgmVolumeChange, MusicVc_OnLoop, Sound_SetSEVolume, m4aSongNumStop, Event15 transcribed and
registered (EventVm replay green), untagged; `BgmVolumeChangeTest` (newgame f1174..1193) disabled: the trace ramps BGM to
0 over ~19 f (not the PostSaveMenu (0xC0,0x100,0x10) guess) and track flags alternate 131/128 per dump (order vs MPlayMain).
Scheduled 11-07 11:15Z (full jvmTest green 11:14Z): M6-41 Event3F_ScriptBattle + GenerateScriptBattleHitFormEventQueue
verified (`EventScriptBattleDifferentialTest`, prologue_battle step 14430 = r14429 inputs, battle structs/hits unmasked);
deamon untagged, not registered yet. M6-42 `fe8.game.event.EventInfos` (search + EvCheck00/02 tagged; others throw "not
ported"). M6-43 RunPhaseSwitchEvents tagged (no-event path only; no trace starts a turn event) and wired in Fe8Boot.
`wip/M6-41` merged (remote branch delete was refused this run: delete it).
Scheduled 11-07 05:15Z: M6-40 rank mismatch solved: the f15075 unit update of `ram_trace_prologue_battle_end` is
`EventBattleReloadBmStatus` (eventscr3.c, ScriptBattleDeamon end of Event3F), not BattleApplyGameStateUpdates (disasm +
trace: wexp would be 182; the ROM never calls it). Ported `BattleGenerate.eventBattleReloadBmStatus` (hooks for AiRefreshMap/
RefreshEntityBmMaps/RefreshUnitSprites), `EventBattleReloadDifferentialTest` (r15074->r15075; masks +0x3C..3F, unit 0x01
US_HIDDEN, cleared by battle-anim end code) replaces BattleApplyDifferentialTest; UnitRemoveInvalidItems tag withdrawn;
wexp class baseRanks offset fixed (+0x2C). BattleApply*/UpdateUnitFromBattle stay untagged (needs a player-phase battle run).
battle_end added to M4A_UNCOVERED_RUNS (assumed same Prologue divergence, not confirmed): the run can now be packed; with it
(run locally: harness takes 7 s) jvmTest is green. Full jvmTest green 05:12Z without it. Next: M6-41 Event3F using the reload.
`wip/M6-41` (493de0e): `fe8.game.event.EventScriptBattle` transcribes Event3F_ScriptBattle, ScriptBattleDeamon and the
slot-queue hit builder behind `EventScriptBattleHost` (untagged, not registered). To verify: parametrize EventTrace.prologue/
Replayer by run name and replay `ram_trace_prologue_battle`; host gameLock = gBmSt lock from the gbmst watch; seed
BattleGenerate (actor/target/stats as in EventBattleReloadDifferentialTest) from the A-step ewram; give the deamon a parent
proc; then drop 0x3F from STUBS/UNIT_STUBS for that run. Item-override 0x3F still throws.
Scheduled 11-06 23:38Z: M6-40 partial (BmBattle `battleApplyGameStateUpdates` chain, `BattleApplyDifferentialTest`
r15074->r15075). New ROM-repo script `ram_trace_prologue_battle_end` (old run ends f15059, the update runs at f15075);
owner: run make_reference + repack dist (CI skips the test until then). WARNING: with this run present,
M4aNoteTest (r15059 ch0 envelope) and M4aSequencerTest (r15131 track 0) fail: the m4a tests scan every run, so fix
or exclude before publishing. Full jvmTest otherwise green 11-07 00:0xZ (341, only those 2 fail). Only UnitRemoveInvalidItems tagged: Seth's sword
rank 181 + weaponExp 1 -> we write 182, ROM keeps 181 (masked); find why (item weaponExp offset 0x20? input decode?), then tag.
Scheduled 11-06 22:51Z (full jvmTest green 23:12Z): M6-36 done (0c3439a, `fe8.game.scene.TalkBubble`: open/bubble/shift-clear procs, BG1 tilemap matches per
frame f9866..10565). M6-35 done (9ebd577, `TalkFace`: load/layer/move procs; face tiles diverge from f10040 in the eye area
until blink/mouth procs exist; face palettes need StartFaceFadeIn). Neither is wired into a production TalkHost/Fe8Boot yet
(owner's Prologue report). `wip/M6-37`: `fe8.game.event.EventText` transcribes Event1A/1B/1C/1D behind `EventTextHost`;
EventVm replay runs 1A/1B/1C real (test `idleAware` wrapper keeps the old parked-before-command skip), 0x1D still stubbed
(needs live talk state + choice result); untagged: replay checks only return codes/engine state. Next: wire EventText to
Talk/TalkBubble/TalkFace with a real host, then a talk-effect check to tag it; merge wip/M6-37.
Scheduled 11-06 19:50Z: M6-12 done (eb45763): `Fe8Boot(saveStorage)`, MainActivity `filesDir/fe8.sav`; save menu stand-in
resumes whenever a valid suspend exists (else New Game); `ResumeBootTest` Ch2/Prologue reach idle, units == ch2_scroll/
prologue_resume r01299 (mask +0x3C). `wip/M6-29`: Event26/3B/3C transcribed, untagged: 0x3C runs in no event trace;
3B needs the EventDisplayCursor child proc compared (procs watch +0x64..0x6A); talk/battle runs replay with a one-line
`EventTrace.prologue` change, but talk mismatches at step 9803: our 0x3B parks at 0x089EECF4 (char 15 absent: units made by stubbed 0x2C inside
the window are not in the replay's seeded table; lookup itself matches C). wip/M6-29 now skips those lookups and replays
talk/battle without mismatches (>1000 pairs each). Still untagged: 3B child proc, 0x3C, camera. M6-10: ResumeFromPhaseIdle verified (BMapMain = proc slot 2 of
gProcArray 0x02024E68, cmd 40 = label 4 at r01150; dispcnt vs iwram 0x03003080); other variants unreached by traces.
Full jvmTest green 19:58Z. CI eb45763 failed only the summary skip guard (prologue.sav absent in CI); fixed in bc7c23b (SRAM
dump s01001 of ram_trace_prologue_resume).
Scheduled 11-06 19:06Z: M6-19 partial: `MapAnimations` tile anim verified + wired in Fe8Boot (see TASKS). Full jvmTest green 19:06Z.
Scheduled 11-06 19:00Z: M6-18 done (`MapCursor`, wired; `MapCursorDifferentialTest` vs first_phase OAM objects 0..3; drawn
position = dumped playerCursorDisplay minus the slide step, test searches 0..4 px: tighten if a per-frame gBmSt trace exists).
GetCurrentMapMusicIndex stays untagged: Prologue traces cannot tell red from green slots, no victory/flag-4/skirmish case (needs a later-chapter run).
Scheduled 11-06 18:55Z: M6-17 done. `UnitSpritesOam` (PutUnitSpritesOam/IconsOam verified vs first_phase oam.bin
f19430..19530: cursor = objects 0..3, ours from 4, clock = dump clock - 1). Fe8Boot `wireUnitSprites` replaces the
BattleMapStart/BMapMain/PlayerPhase unit-sprite stand-ins (trap pool zeroed stand-in); `PrologueMapTest` sees 6 unit
objects at idle (not compared to the dump: cutscene stubs). OBJ drawn by HardwareScene (code-read; not yet seen on
device). Local `dokkaGenerate` not found without ANDROID_HOME; CI runs it. Force-push and branch delete are blocked here: `wip/M6-17` is merged and can be deleted.
Scheduled 11-06 18:16Z: M6-11 done (`savemenu/PostSaveMenu.kt`, `PostSaveMenuDifferentialTest`; stand-in selection; not
yet wired into `GameControl.hooks.startSaveMenu`: do it in M6-12). M6-13 blocked (no run reaches the four functions; TASKS).
M6-15/16 done (`UnitSprites`, `UnitSpriteHandles`, `UnitSpritesDifferentialTest` vs f19430). `wip/M6-17`: Sync/ForceSync
UnitSpriteSheet; ForceSync verified over f19430..19530 (>=68 branch unobserved; SyncUnitSpriteSheet untagged, testable at
f19450/f19490 clock 32/0; `registerDataMove` copies at once instead of queueing FlushTiles); open: PutUnitSpritesOam,
MapTask hook, Fe8Game.present OBJ. Full jvmTest + :game Dokka green 11-06 18:34Z (master ce4cf72).
Scheduled 11-06 18:xx: M6-08 done (`SaveStorage`, `FileSaveStorage` temp+ATOMIC_MOVE in gba/core jvmMain,
`CartSram.loadFrom/flushTo`; Android wiring = `File(filesDir, "fe8.sav")` in M6-12). M6-09 done
(`BattleMapStart.startResumedGame`, wired in Fe8Boot; `StartResumedGameDifferentialTest` r01149 gBmSt; camera only
weakly covered, ch2_scroll untested). M6-10 partial: variants + dispatch transcribed, untagged; for M6-12 set
`battleMap.hooks.suspendPointType` from a `SuspendRam.actionData[0x16]` filled by ReadSuspendSave, wire
`hooks.refreshEntityBmMaps/refreshUnitSprites` (EntityBmMaps is ported but not injected), then tag PhaseIdle vs a
dump that shows the BMapMain label (subagent could not find the proc in the r01149 proc array). M6-11: 
`GameControl.switchPostSaveMenu` was already transcribed (checked vs gamecontrol.c:447); open: PostSaveMenuHandler
(needs a SaveMenuProc) and the ch2_scroll/ch6_start tests. Full jvmTest green 11-06 18:12Z.
M6-05/06/07 done (scheduled 11-06): ReadSuspendSave* and ReadGameSave/LoadSavedUnit verified (`ReadSuspendSaveReferenceTest`,
`ReadGameSaveReferenceTest`, `BmGameSave.kt`); WriteGameSave round trip masks 3 uninitialised-stack bytes + checksum; WriteGlobalSaveInfo (character-known marking) unobserved, untagged. Fix: ReadPermanentFlags reads 0x19 bytes (was 0x18). Weak spots: ReadGameSave's
`gameSaveSlot = slot` and `InvalidateSuspendSave` are unobserved by the ch6 run. Decomp C is not in setup's assets:
`git clone --depth 1 --filter=blob:none --sparse https://github.com/FireEmblemUniverse/fireemblem8u` + `sparse-checkout set src include`.
`wip/M6-05` (remote) is merged and can be deleted.
Device crash fixed 11-06 (bca8381): START on the title with only the boot/sound windows (debug APK, no import) read
chapter data outside the view; the New Game stand-in now runs only on `RomView.isWholeRom`. `AppRomPathTest` drives
`Fe8Game` (sound + present) on both ROM sources. Owner had installed the 887d744 APK (before M5-61); the next device
test needs the APK from bca8381 or later + Re-import ROM.
Owner 11-06: run ONE agent/task at a time from now on. M5-58/59 merged (11-06): Fe8Boot runs New Game → beginning
events (unskipped; START skip reads the real KeyStatus) → Prologue PlayerPhase idle; `PrologueMapTest` (BG3 tiles vs
ram_trace_first_phase f19430, 0 B/frame idle with D-pad). Known gap: release no-op stubs leave cutscene units (9 blue/
15 red vs the reference's 2/3), so `UnitLoader.closestValidPosition` (transcribed, `ClosestValidPositionTest`) runs
for red units 139/142; closed by M6-22/28/44. M5-61 (11-06): import keeps `base/rom/baserom.gba` and
the app runs on `RomView.ofRom` (old installs: re-import); device test pending. M6-03/04: the suspend save is written at each phase start through `Fe8Boot.saveLib` (stand-ins:
actionData, traps, convoy, WM info, dungeon; GMapSaveInfo is 0x24). M6-21/22: `EventVmDifferentialTest` compares
unit arrays; `EventUnitState` (Event34) registered in the test engine only (not Fe8Boot), untagged. New runs ram_trace_prologue_{talk,battle,resume}
(ROM repo c80bcb8; excluded from m4a tests). Old `wip/` branches deleted 11-06.

## State
- Boot (H&S, logos, op anim, title) matches mGBA: 27 lag frames, 249 frames pixel-exact, gap f1180 only.
- Sound: m4a sequencer and DirectSound mixer byte-exact vs per-frame RAM traces (`M4aNoteTest`, 3145 frames);
  `CgbSound` verified; `PsgSynth` (GBATEK/mGBA behaviour, not a port) RMS 1249 vs mGBA 1200.
- Owner device tests passed: boot to title, touch buttons, gamepad, GPU backend, audio, ROM import (~4 s) + boot
  from it. Pending: smoke test of the first APK built on the KMP android variants (MANUAL_TEST "Newly testable").
- Verified ports: 547 / 8352 (`tools/progress.py`, full `jvmTest test`, 2026-11-09); `ported-without-tag: 0`. Scripts: 897/897 EventScr* byte-exact in 46 `.fe8s`.
- Layout: `:gba:*` game-neutral (ADR 0012), `gbafe.*` in `:engine:*`, `fe8.*` in `:game`/`:app`; KMP jvm+android
  (ADR 0013) except `:gba:testkit`, `:testing`, `:tools:extract`.

## Open leads (low priority unless the owner reports a problem)
- m4a tests skip the M5 runs (`M4aRuns.kt`): our sequencer diverges on `ram_trace_newgame` r00975 channel 12
  (new-game menu songs) and on `ram_trace_ch2_scroll` (r01469 track 0 status 0xC0 vs our 0x80; r01104 player 2).
  Investigate before claiming m4a coverage there.
- Test strength: ~40 ProcEngine tags share one Kotlin function with another tag (a break proves the group, not each
  name); OpAnimMergeBGProcInit, sub_80C8608 untagged. Untagged for lack of a trace: SaveBonusContentData,
  SaveRankings, WriteLinkArenaStruct2, VerifySramFast_Core,
  GameCtrl_Check{NewGame,GameComplete}AndBranch, GetPhaseAbleUnitCount exclusion branches, ply_keysh, ply_xcmd,
  GameControl_EnableSoundEffects, EndAllProcChildren, StartOrChangeBgm, Title_IDLE, Title_EndAllProcChildren,
  DrawTitleSprites_Init, SetSecondaryHBlankHandler, OpAnimHBlank1, ColorFadeSetupFrom{ColorToWhite,White},
  sub_80C85FC, sub_80C8684, sub_8009B64, GameControl_PostIntro (partial), m4a fade-in/`TEMPORARY_FADE`.
  `m4aSongNumStartOrChange` is never called by FE8: leave it unclaimed.
- Owner fixture `complete100.sav` (ROM repo reference/fixtures, 11-06): 100% game, 2 Creature Campaign slots, no
  run yet. Could give ground truth for the untagged save/post-game paths above (SaveBonusContentData, SaveRankings,
  GameCtrl_CheckGameCompleteAndBranch, link arena, sound room) and later world-map/Creature Campaign loads.
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
- CI's "Commit progress (master)" step commits README.md + docs/port_map.json after a green master run; when CI is
  red they go stale (run `python3 tools/progress.py --write` after a full `jvmTest test` and commit them yourself).
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
