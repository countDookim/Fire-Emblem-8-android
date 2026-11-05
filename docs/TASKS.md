# Task backlog

Tasks ≤ 20 min. Status `todo` / `started` (details in HANDOFF) / `done` / `blocked`. Claim before starting (CLAUDE.md
rule 10), then take the first `todo` whose deps are `done`. If fewer than 5 `todo` remain, plan the next milestone.

**M5** (core loop, saves, flag registry, script interpreter; planned 2026-11-04). Goal: New Game → Prologue map → first
player phase with D-pad scrolling, on verified ports. Facts, frames, addresses and the reference-run table:
docs/plans/M5-notes.md. Deferred to M6: save load/Continue, suspend/resume, Unit model + LOAD commands, trigger
scanning, WM handlers, text/talk, battles, map sprites, cursor sprite, map BGM, BMVSync animations.

Every port task: `Ported from <file>: <Func>` KDoc; `@VerifiesPort` only for what a test checks against a dump or ROM
bytes, each tag break-checked (break the port, see the test fail); anything else gets "Transcribed from … (not
verified: why)". Seed from dump k, step, compare with dump k+1; use sentinels in buffers the port must write.

## Open

| ID | Task | Acceptance | Deps | Status |
|---|---|---|---|---|
| M5-56 | Infra: wire FlagRegistry/VarRegistry (EventScrCore's flag handlers, ScriptAssembler, gbafe.save container): flag handlers route ids ≥ MOD_BASE to the registry; assembler resolves mod flag/var names; registry state saved in a container `mod:<id>` section | test: a mod flag set by a script survives a container save/load; vanilla flag bytes unchanged | M5-20, M5-22, M5-55 | done |
| M5-56b | Infra: let scripts address mod flags by literal. Mod handles are >= 0x8000, a negative s16 flag arg means "id in gEventSlots[2]" in the vanilla VM, so `set_flag MyMod_X` only works with slot 2 preloaded (ModFlagSaveTest does this). Fix in the assembler (expand a mod-flag operand to a slot-2 load + the command) without changing the VM; parser also rejects `.` in names (use `_`) | test: a script with `set_flag MyMod_X` and no manual slot setup sets the mod flag; vanilla scripts byte-exact | M5-56 | todo |
| M5-57 | Infra: mod-script end-to-end test: a `.fe8s` file loaded at runtime, assembled into the synthetic range, run on the VM, calling a vanilla script and returning | jvmTest passes; 0 B per frame while running | M5-56 | todo |
| M5-58 | Wire `:game` (Fe8Boot already wires StartBattleMap → BMapMain; still to wire: `BMapMain.hooks.playerPhase` = `PlayerPhase.script`, `hooks.callEvent` → `EventEngine.callEvent`): after the title, EXEC_SAVEMENU uses a dev New-Game stand-in (WriteNewGameSave(0, …) on CartSram, nextAction EVENT_RETURN); monologue/world map/chapter intro/phase intro stand-ins end at once; CallBeginningEvents runs the VM in skip mode with release no-op stubs; units from UnitTable; Fe8Game.present renders battle-map BGs | headless JVM run from boot + NEWGAME inputs reaches PlayerPhase_MainIdle on chapter 0 | M5-32, M5-47, M5-55 | todo |
| M5-59 | Tests + manual test for M5-58: BG3 vs the reference (camera seeded from reference, or map tiles only); AllocationTest 600 idle frames with cursor input; MANUAL_TEST "New Game shows the Prologue map; D-pad scrolls it" | BG3 matches; measureMin == 0 B/frame | M5-58 | todo |
| M4-02 | Intro screens `opanim-healthsafetyscreen.c` (`fe8.game.opanim.OpAnimHs`, `EarlyStartUi`): `IsSoftwareReset`, Huffman/RL in `Decompress` | `@VerifiesPort` vs RAM traces / ROM data that exercise them | — | todo (needs a trace or ROM data using them) |
| M4-12 | Verify the rest of `gbafe.proc.ProcEngine` (`src/proc.c`): `Proc_Init`, `Proc_ForAll`, `Proc_ForEach*`, `Proc_SetMark`, `Proc_{Block,Unblock,End}EachMarked`, `Proc_SetEndCb`, `Proc_SetRepeatCb` | `@VerifiesPort` vs RAM traces | — | todo (needs traces that exercise them) |

## Done (details in git log)

- **M1** toolchain, CI, skeleton, docs (M1-01..12). **M2** extraction into moddable formats, ROM repo only
  (M2-01..21). **M3** rendering, input, audio, override layer (M3-01..16; GPU backend, AudioTrack, m4a + mixer + PSG
  verified, owner device/listen tests). **M4** boot to title verified vs mGBA (M4-01, 03..11; ROM import, `:gba:*`,
  KMP, op anim, title, `:app` boot).
- **M5** done: 01-55, 39b, 41b, 41c. Unverified for lack of data: StartBattleMap (deploy loop, fog/skirmish
  branches: needs a per-frame trace of a real chapter start with units and fog), LoadAndVerfySuspendSave (extra-map
  branch), EraseInvalidSaveData (0x40603 conversion, TODO),
  StoreAdjustedCameraPositions, sub_8015D84, CameraMove_8015EDC, IsCameraNotWatchingPosition (need a scripted
  camera / unit menu after scrolling), GetEnemyStartCursorPosition (needs an enemy-phase reset),
  RenderBmMapColumn's y ring wrap, RefreshEntityBmMaps (trap passes are hooks), SetEventId_0x84/UnsetEventId_0x84/UndeployEveryone
  (no trace separates them), PlayerPhase_MainIdle actions (stand-ins), HandleMoveCameraWithMapCursor's tile-finish branch (needs a direction
  reversal mid-scroll), Event03 sub 1, Event06 operators, Event10 (args 2-4), Event19 (10 of 11 subs),
  Event04/0B/11/00/0F (not in the prologue), CallEvent/EnqueueEventCall/CallNextQueuedEvent/EventEngine_OnEnd (call
  queue empty in the prologue), Clear*/Check*/Reset* flags (no non-zero state cleared; needs a later chapter),
  172 Event19 pairs blocked on a Popup/GotItem child (no blocked-by-child model yet), DisplayMovementViewTile (not
  ported). Left transcribed for lack of ground truth: InvalidateGameSave (needs
  ReadSuspendSavePlaySt), WritePermanentFlags, Write/SetBonusContentClaimFlags, WriteSupplyItems, ClearWorldMapStuff
  (zero-only copies), RenderBmMapOnBg2 (never called), GetBattleMapKind; slots 3/4 block-info magic/checksum in
  M5-32 come from the dump (uninitialised stack in the original).
