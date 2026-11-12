# Task backlog

Open rows only. Milestones, task rules and the reasons: [ROADMAP](plans/ROADMAP.md) (owner-approved 2026-11-11).

- Kinds: **CAP** capture (ROM repo) · **INV** inventory test, no port claim · **PORT** one behaviour in one
  capture window, ≤ ~8 functions · **WIRE** connect into the game + headless test + MANUAL_TEST.
- Port only functions the capture window runs. `Ported from` + break-checked `@VerifiesPort` (bare function
  names), else `Transcribed from … (not verified: why)`.
- Claim before starting (CLAUDE.md rule 10); take the first `todo` whose deps are done, in either lane.
- Status ≤ 15 words; details go to the lane's notes file. Delete a finished row and add its ID to Done.
- Fewer than 5 open rows in a lane: break the next ROADMAP milestone into rows.

## Lane A: opening wired (M6-A, D-11)

Notes: [D11-worldmap](plans/D11-worldmap.md) (§8 status), [M6-notes](plans/M6-notes.md) (monologue). Captures:
`ram_trace_prologue` (steps 1213–9313), `ram_trace_newgame_bm` (f9314–9900), the three Start-skip runs.

| ID | Kind | Task | Acceptance | Deps | Status |
|---|---|---|---|---|---|
| D11-wm8b | PORT | Text opcodes through the host, 5100–5500 | WmText and Talk proc fields per step | — | in progress (owner PC) |
| D11-wm8c | PORT | `WM_MOVECAM2` scrolls and nation highlights through the VM, 5436–8110 | UpdateExt/Border1 fields per step | — | in progress (owner PC) |
| D11-wm8d | PORT | Portrait opcodes C4/C5 through the VM, 5764–7780 | Face procs per step; BG2 and palette at dumps | — | in progress (owner PC) |
| D11-wm8e | PORT | Unit/dot opcodes 9E–AC through the VM, 8100–9280 | GmapUnit/AutoMu/WmPlaceDot fields per step; OAM at f08100–f09000 | — | todo |
| D11-wm8f | PORT | Fade-out, second event run (9289–9292), `EndWM`, `ResetFaces` restore | r09300/r09330 | D11-wm8b–e | todo |
| D11-wm9 | WIRE | `hooks.worldMapWrapperScript`/`endWorldMap` in `Fe8Boot` | Headless New Game reaches `StartBattleMap`; 0 B/frame; MANUAL_TEST | D11-wm8f | todo |

## Lane B: player phase (M6-B)

Notes: [M6B-player-phase](plans/M6B-player-phase.md) (captures, key frames, proc inventory). Start state: `prologue.sav` (turn-1 suspend).

| ID | Kind | Task | Acceptance | Deps | Status |
|---|---|---|---|---|---|
| PP-11 | PORT | `PlayerPhase_PrepareAction`/`FinishAction`, Wait action, `HandleAutoEnd`; post-action event check (StdEventEngine f1356–1358) | Unit state bits after the action | — | todo; WIP `wip/PP-11`, findings in notes |
| CAP-status1 | CAP | Run where the cursor rests ≥ 64 frames on a unit with a status, crosses unit→unit in one corner, and (option) `unitDisplayType` 1 | Then verify `PutUnitMapUiStatus`, `MMB_CheckForUnit`, `sub_808C234`, Burst display | — | todo |
| PP-17 | WIRE | Player-phase hooks in `Fe8Boot`; replay cap1 inputs headless | Same unit position/state as cap1; 0 B/frame; MANUAL_TEST | PP-11, PP-13 | todo |

## Other open rows

| ID | Kind | Task | Acceptance | Deps | Status |
|---|---|---|---|---|---|
| CAP-hard1 | CAP | Hard-mode New Game run reaching autolevelled loads (unblocks old M6-13) | Run published; then tag `UnitAutolevelRealistic`, `UnitApplyBonusLevels`, `UnitAutolevelPenalty` | — | todo |
| CAP-collide1 | CAP | Run where a unit load lands on an occupied tile (unblocks old M6-14) | Run published; then verify `AiGetUnitClosestValidPosition`, `AdjustNewUnitPosition` | — | todo |
| D12-2 | CAP+PORT | Enemy-phase intro capture with per-scanline blend; extend `PhaseIntroDifferentialTest` | Enemy name graphics/palette and per-line blend equal the capture | — | todo; do with M6-D |
| TEST-2 | INV | `WalkingAllocationTest` borderline on the owner PC: 1–2 of 26 allocation-free windows (needs 3), 4 of 6 local runs on 11-11 failed; CI passes | Find the allocating call (allocation profiler on the walking window) or show it is GC/JIT noise; fix or adjust with the reason | — | todo |
| D09-3 | PORT | Remaining item-popup branches (gold, full inventory, red/root, unit-name/number) | Each branch against a capture that reaches it, else not written | — | low priority; Rapier popup confirmed on device 11-11 |

Owner-PC sessions also continue [READABILITY](READABILITY.md) (rule 11).

## Done

Row details, including what each task left transcribed: [archive](plans/TASKS-archive-2026-11-11.md) and `git log`.

- **M1–M5** complete. **M6 part 1** (Prologue opening to turn 1, suspend/resume): M6-01..12, 15..19, 21..28,
  30..39, 41..43, 45, 46 done; M6-20, 29, 40, 44 done as far as captures allow.
- **D-series:** D11-wm3f (route proc verified per step; drawing/transition transcribed: no open paths in the Prologue; GmapRm route-flag hook unconnected → D11-wm9), D11-wire-b (`c86e437`; monologue wired in Fe8Boot, skip via boot verified; device check pending; m4a sequencer still allocates 48–144 B every ~144 ticks with sound on; post-monologue BGM fades → D11-wm9), D11-wm8a (WM event host 4907..5100 through the VM; 13 functions; AF/B0 arg fix; line-fade Init, Event13 transcribed), D11-wm4d (border blend/OAM/palette + unit-fade palettes per step; 14 functions; border/pulse End and unit OAM transcribed), D11-wire-a (skip sound `Sound_FadeOutBGM(1)` verified at 1505/2375/4405; `fadeOutSe` stand-in, no visible effect), D11-cap1 (`ram_trace_prologue_wm_lcd`, ROM `8a14ce3`, release published locally; per-step LCD/pal/OAM 5500–8450), D13-2 (`351feac`; skip verified to the fade-in f10069; unit-load/move opcodes test stand-ins; device check pending), D13-1 (`2eee36a`; fade + talk block on Start; device check pending), D11-ci3c (`5146b83`; main path to f9872; `ChapterIntro_End` still transcribed), D13-cap1 (`ram_trace_prologue_cutscene_skip`, ROM `bd66e15`), D11-ci3b (fog, decal, Bg2Scroll, timer; to f9708), D11-wm3e (`ec9883b`; show transition only, hide not captured; wiring in D11-wm9), D11-ci3 (`2c121c6`; light effects), D11-ci2 (`7f92921`; title hooks `getPlayChapterId`/`getNextUnclearedNode` stand-ins until M6-I), D11-ci4 (`e3ef72b`), D11-wm3d (`1f883a6`), D11-cap2 (`ram_trace_newgame_intro`, ROM `eb263d1`), D11-wm3c, D11-ci1 (`89d837d`), D11-wm3b (`f6b08f2`), D11-ci0 (`ChapterIntroProcInventoryTest`), D07-audio, D07-trace, D09-1, D09-1b, D09-2, D11-slides, D11-fades, D11-init, D11-flare,
  D11-skip, D11-proc, D11-worldmap, D11-wm1, D11-wm2, D12-1. D11-wm3..wm7 (+ wm4a–c, wm6b–d): standalone
  ports verified as far as the Prologue runs them; the rest is in lane A.
- M4-02, M4-12, M5-56, 56b, 57..61.
- **Lane B:** PP-13 (`8c0fbaf`; berserk CP phase/order, phase-switch event, banner start; CpDecide M6-D stand-in), PP-6 (`eb02acd`; arrow sprites + graphics; mode 1 unverified; PutSprite still untagged), PP-8 (`6d7d6b7`; move walk; GenerateBestMovementScript cross-checked only; MuStepSe not done), PP-12 (`da3702a`; map menu availability, Guide draw, End; other item effects not written; bmguide.c hooks; Fe8Boot wiring in PP-17), PP-16 (`e76a49d`; goal window + green text; slide-out and two-line goals transcribed), PP-7 (`b951640`; RangeDisplayIdle/CanMoveActiveUnitTo/BackToMove; CancelAction not run; unit-menu B unported), PP-5 (`ce24e79`; re-route branch and `ResetPathArrow` transcribed; best-path hook until PP-8), PP-10 (`12f5003`; Attack/Item availability, EffectWait; target count hook until bmtarget.c, menu not started by PlayerPhase yet), PP-4 (range squares; hook in PlayerPhase until PP-17; menu-started stretch of attack run unverified), PP-15 (`6dcb288`+; unit mini-box, HP bar; status/Burst stand-ins → CAP-status1), PP-3 (`unitSelectEnabled` off until PP-17; `MainIdle`/`DisplayUnitEffectRange` partial), PP-14 (`5f3a81b`; terrain window; unit/goal windows hooks, their config gating moves into `InitPlayerPhaseInterface` with PP-15/16), PP-0a (hover cursor, `fe2f778` + fix `c18263a`; owner device check pending), PP-2 (`caa2f1f`; ballista branch, `MapSetInRange`, single-slot reach transcribed), PP-9 (`ff84b6f`; `DisplayFrozenUiHand` still a hook), PP-1 (`8da6b33`; `GenerateUnitMovementMap` itself not run in the window), PP-0 (`PlayerPhaseProcInventoryTest`, `8036f44`), PP-cap1 (`ram_trace_prologue_select_move`, ROM `c3ad5d4`), PP-cap2 (`ram_trace_prologue_cancel_end`, ROM `45f35cf`), PP-cap3 (`ram_trace_prologue_attack`, ROM `07a6ca9`, animations on).
- **Tests:** TEST-1 (`2103612`, `a0d69c4`, `407a17f`; full run 4.5 → 3.3 min, 806 tests; [TEST-SPEEDUP](plans/TEST-SPEEDUP.md)).
- **App:** APP-1 (launcher Delete save, `e39c730`; owner-confirmed on 0.1.590).
- **Alignment:** PROG-1 (`b459cf3`), UNV-1, ALN-1 (`3f85702`), ALN-2, STAND-1, STAND-2, M7-0 ([M7-modding](plans/M7-modding.md): 23 proposed rows; D1 decided = option A, ADR 0015; D2–D9 open).
