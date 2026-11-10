# M6 notes: text/Talk, MU, battle, suspend/resume

Facts for the open M6 tasks (docs/TASKS.md); checked against decomp 04d1edb, the nm file (every address below) and the
`procs`/`gplayst`/`gbmst` per-step watches (`scan_trace.py --watch-changes procs --watch-max 100000`). Ground truth is
only mGBA dumps or ROM golden values (ADR 0006). Conventions as in M5-notes.md. Frames are absolute (1 watch step =
1 frame in the split-hold runs).

## Reference runs (ROM repo `reference/scripts/<run>.txt`; headers give exact frames)

| Run | Content | Used by |
|---|---|---|
| `ram_trace_prologue_talk` | NEWGAME to f10640; dumpram every frame f9850..10640; full dumps (VRAM/OAM/pal/frame) every 10 f f9850..10640 AND every frame f10575..10635; watches gplayst, gbmst, rng, evglobals, flags, procs, actiondata | M6-27, M6-30, text/font/face/MU tasks |
| `ram_trace_prologue_battle` | NEWGAME to f15060; dumpram every frame f14400..15060 (not f14440: see below); full dumps every 20 f; same watches | M6-38 and the battle tasks |
| `ram_trace_prologue_resume` | `prologue.sav` (chapter-start suspend, turn 1): A f1002, A f1104, RIGHT 40 f (f1301), DOWN 40 f (f1341); dumpram every frame f1000..1420, full dumps f1200 + every 10 f f1300..1420; dumpsram f1001, f1200, f1420; same watches | M6-09, M6-10, M6-12 |

Existing runs are unchanged (byte-identical; only PROVENANCE.sha1 differs).

## Frames (procs.watch of ram_trace_prologue = of the new runs; all agree)

- gProcScr_Talk (0x08591358): f9865..10564, f10697..12034, f12131..12244, f12787..12874, f13213..13564,
  f13776..13894, f14119..14374; f14480..14614 inside the battle (BattleEventEngine f14479..14631). Earlier (WM): f5106..9285.
- gProcScr_TalkOpen (0x08591540): 13-frame windows (TalkBubbleOpen alongside), first f9917..9930, then f9965, f10114,
  f10174, f10385; next Talk f10733, f10804, ... TalkFaceMove f9881..9915; TalkShiftClear f10051..10068, f10261, f10327, f10501.
- ProcScr_Mu/MuCtrl (0x089A2C48 / 0x089A2DB0): f9674..9782, f10579..10621 + f10623..10633 (the MU walk; MuStepSe
  every 8 f from f10580), f12049..12067, f12259.. (several MUs overlap f12259..12723), f13038.., f13608..13675,
  f13937..14018, f14418..14428, f14430..14447, f15057..15075.
- Battle: ProcScr_ekrBattleStarting + gProc_ekrBattleDeamon f14431, gProc_ekrBattle f14467..15035, ProcScr_ekrBattleEnding
  f15035..15075.
- gBattleActor/Target/Stats/HitArray (0x0203A4EC/56C/4D4/5EC) are all zero at f14400..14429 and filled at f14430 (one
  frame before ekrBattleStarting), constant to f15060. The per-frame window therefore starts at f14400 (the first
  plan said f14440, which would miss InitBattleUnit).
- Resume (prologue.sav): menu A f1002 lands (slot list, HelpPromptSpr f1017); A f1104 lands (BmFadeOUT f1105); gPlaySt
  loaded f1140 (chapter 0, turn 1, mode 1; GameCtrl nextAction 4 f1142); BMapMain/MapTask/BMVSync f1149; FadeFromBlack
  f1151..1167; PlayerPhase f1169; gBmSt cursor (5,5) f1144, camera (0,0) throughout (map fits the screen); RIGHT moves it
  from f1301 ((6,5), then every 4 f after a 12 f repeat delay to (13,5) f1337), DOWN from f1341 (13,6) .. (13,9) f1361.
  SRAM differs f1001 vs f1200 (3117 bytes) and is identical f1200..f1420. inputs.bin: A f1002-1003, A f1104-1105,
  RIGHT f1301.., DOWN f1341...

## RAM (nm-checked)

sTalkStateCore 0x03000048 (0x84, IWRAM), sMsgString 0x0202A6AC (0x1000; at f10200 holds the control-coded text "Your
Majesty, I bear bad news."), gDefaultFont 0x02028E58 (0x18), gSMSGfxBuffer 0x02034010 (0x6000), gSMSSyncFlag 0x0203A4CC,
gBattleStats/Actor/Target/HitArray 0x0203A4D4 (0x18) / 0x0203A4EC (0x80) / 0x0203A56C (0x80) / 0x0203A5EC (0x1C),
gPlaySt 0x0202BCF0. ROM: ProcScr_StdEventEngine 0x08591AC0, BattleEventEngine 0x08591AF8, gProc_BMapMain 0x0859A1F0,
sProc_BMVSync 0x0859D8B8, gProc_MapTask 0x0859D908, gProcScr_ChapterIntro 0x0859B1B0, ProcScr_PhaseIntro 0x0859AF80,
gProcScr_PlayerPhase 0x0859AAD8.

## Flow

- Resume: PostSaveMenuHandler (savemenu.c:1276) → ReadSuspendSave(3) (:1306) → SetNextGameActionId(GAME_ACTION_4) →
  GameControl label 8 (gamecontrol.c:461) → GameCtrl_StartResumedGame. Restart: ReadGameSave(slot) → nextAction slot+1 → EXEC_BM.
- A turn-1 suspend resumes straight into PlayerPhase (no PhaseIntro, no BMapMain label 0/1 beginning scene).
- gSMSGfxBuffer/gSMSSyncFlag are not changing during the MU walk in the dumps (gSMSSyncFlag 0, buffer non-zero bytes
  constant 3906): compare MU frames through OAM/OBJ VRAM of the full dumps, not through those buffers.

## Gaps

- No per-frame run of a menu/unit menu on the resumed map; no battle forecast. Add a run when a task needs it.


## D-11 remaining subtitle verification (2026-11-09)

`OpSubtitle` is standalone and transcribed. Its continuous proc replay covers steps 1214–2354,
seeding the original reused transition timer once and running the real generic archived-palette
fade. Original callback snapshots now establish that scene setup (`sub_80C50A0`) starts at step
2355 and returns at 2357: the intervening frame snapshots are inside that call.
`OpSubtitleBoundaryDifferentialTest` compares an atomic Kotlin call with the original return
state (BG0/BG1 maps, palette E/F and timers), not full parent lifecycle timing. `OnVBlank` advances
gGameClock and runs tree 0 during the long callback, so clock increments alone do not count
completed normal proc updates. Extend replay using callback phases/completed updates; do not
insert arbitrary delays to fit video-frame snapshots.

Done 2026-11-09 (`8b8c5b0`, `2e0e4ff`, `36bfd2c`): the runtime dependency `src/sysutil.c` NewFadeIn/FadeInExists, FadeInOut_Init and
FadeIn_Loop, backed by `src/bmlib.c` ArchiveCurrentPalettes/WriteFadedPaletteFromArchive. This is
distinct from ColorFade/FadeCore. It starts on tree 4 and uses a 32-bank archived palette with
independent RGB coefficients and a bank mask. Existing prologue proc watches and r01230/r01260
palette/archive snapshots support a seeded mid-fade replay. The new `ram_trace_fade_start`
callback capture verifies initialization/display disabling on the observed branch (see below). Unseen white/mask branches,
normal slow-fade termination and D11-skip inputs still need independent reference checks before wiring.

## Owner-PC verification, 2026-11-10

D-09 wired-path regression (`WiredItemPopupTest`) runs the real Prologue event through Fe8Boot,
GotItemPopup, PopupScript, BGM volume children and shared text/hardware. It compares the visible
icon OAM, palette RAM, glyph/icon VRAM and text tilemap with the item-popup capture, then checks
the final ten inventory bytes against the original after closure. This exposed the omitted icon
OAM callback and missing BG sync on cleanup; both failed before their respective fixes. The icon
sprite is cached at popup construction, and a primitive OAM writer avoids per-frame boxing.
The test checks the warmed icon callback for allocations, both BGM blocking periods and cleanup
in actual VRAM/OAM. It uses silent audio and corresponding visible/end states: audible jingle
fidelity and full-scene frame timing still require independent verification/device confirmation.

D-11 early fade capture (`ram_trace_fade_start`, private `ea2aafd`) records real Thumb callback
entry/return snapshots in step 1215, preserving original input and overlapping RAM. The startup
test now checks display disabling, assigned proc fields, resetting a nonzero reused timer, all
512 archived colors and the first faded palette. `FadeInOut_DisableGfx` is mutation checked for
the observed mask-not-0xFFFF branch; only BG0/OBJ were initially enabled. The 0xFFFF branch,
initially enabled BG1–BG3, white fades and other reused-slot masks remain unverified. The
read-only harness hook leaves emulated cycles unchanged; legacy capture files were preserved.
