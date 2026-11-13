package fe8.game.bm

import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcBoolFunc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.call2
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.goto
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.sleep
import gbafe.proc.ProcScripts.startChildBlocking
import gbafe.proc.ProcScripts.whileExists
import gbafe.proc.ProcScripts.whileTrue
import gbafe.proc.ProcScripts.yield

/**
 * The player phase of src/playerphase.c: `gProcScr_PlayerPhase` (0x0859AAD8, transcribed 1:1,
 * 66 commands, checked against the ROM by `PlayerPhaseScriptRomTest`) and the functions the
 * idle part of it runs: [playerPhaseHandleAutoEnd], [handlePlayerCursorMovement] and the
 * cursor part of [playerPhaseMainIdle], with the cursor handling of src/bm.c
 * ([handleMapCursorInput], [handleMoveMapCursor], [handleMoveCameraWithMapCursor]).
 *
 * The unit select of the A button ([playerPhaseMainIdle], [getPlayerSelectKind]), the script
 * commands after label 1 up to the range display idle ([playerPhaseInitUnitMovementSelect],
 * [displayUnitEffectRange], [makeMoveunitForActiveUnit] in the child [selectEventsScript]) and the
 * active unit they set ([active], `UnitBeginAction`) are ported too.
 *
 * Default hooks ([Hooks], no-ops by default; `Fe8Boot` sets `RefreshUnitSprites`,
 * `PutMapCursor` and `StartMapSongBgm`): the L/R/START actions of the idle loop, the side
 * windows, `PlayerPhase_Suspend`, the tutorial event, the cursor sound effect, the attack range,
 * the range squares, the path arrow and every call of the script after the range display idle
 * (movement, action) have defaults that do nothing; `Fe8Boot` connects them (PP-17,
 * `PlayerPhaseWiring`), and [Hooks.unitSelectEnabled] is false by default (the tests that do not
 * wire the select flow). The hooks that stay without a port are listed on [Hooks], each with its
 * plan row (M6-C to M6-I). A `PROC_REPEAT` with a default hook never leaves it. Nothing
 * allocates per frame.
 */
public class PlayerPhase(
    /** The proc engine the player phase proc runs on. */
    private val engine: ProcEngine,
    /** `gBmSt`. */
    private val bm: BmSt,
    /** `gPlaySt`. */
    private val playSt: PlaySt,
    /** `gBmMapSize`, `gBmMapMovement` and the other map pools. */
    private val map: BmMap,
    /** The unit arrays (`GetPhaseAbleUnitCount`). */
    private val units: UnitTable,
    /** `ProcScr_CamMove`, waited for by the script. */
    private val camMove: CamMove,
    /** `RefreshEntityBmMaps`. */
    private val entityBmMaps: EntityBmMaps,
    /** `RenderBmMap`. */
    private val render: BmMapRender,
    /** `gKeyStatusPtr`. */
    private val keys: KeyStatus,
    /** `EventEngineExists` (`EventEngine.eventEngineExists`). */
    private val eventEngineExists: () -> Boolean,
    /** The hooks for the parts that are not ported; `Fe8Boot` replaces some. */
    public val hooks: Hooks = Hooks(),
    /** `gActiveUnit`, `gActiveUnitId`, `gActiveUnitMoveOrigin` and `gActionData`. */
    public val active: ActiveUnit = ActiveUnit(),
    /**
     * The unit movement map of the select ([MovementMap.generateUnitMovementMapExt]); null (as in
     * `Fe8Boot` until PP-17) skips that call.
     */
    private val movementMap: MovementMap? = null,
    /**
     * The path arrow data ([PathArrow], PP-5); null (as in `Fe8Boot` until PP-17) skips it.
     * Drawing it is [PathArrow.drawPathArrow] (PP-6).
     */
    private val pathArrow: PathArrow? = null,
) {
    /** `gWorkingMovementScript` (the path arrow's, shared with the facing command). */
    public val workingMovementScript: IntArray
        get() = checkNotNull(pathArrow) { "no PathArrow" }.workingMovementScript

    /** The unit sprite hover controller ([UnitSpriteHover]). */
    public val hover: UnitSpriteHover = UnitSpriteHover(bm, map, units)

    init {
        hooks.resetUnitSpriteHover = ProcFunc { hover.resetUnitSpriteHover() }
        hooks.unitSpriteHoverUpdate = ProcFunc { hover.unitSpriteHoverUpdate() }
        hooks.isUnitSpriteHoverEnabledAt = { x, y -> hover.isUnitSpriteHoverEnabledAt(x, y) }
        hooks.rangeDisplayIdle = ProcFunc { playerPhaseRangeDisplayIdle(it) }
        if (pathArrow != null) {
            hooks.pathArrowDispInit = { pathArrow.init(it) }
            hooks.displayUnitMovement = ProcFunc { playerPhaseDisplayUnitMovement() }
        }
        hooks.waitForUnitMovement = ProcFunc { playerPhaseWaitForUnitMovement(it) }
        hooks.applyUnitMovement = ProcFunc { playerPhaseApplyUnitMovement(it) }
        hooks.prepareAction = ProcBoolFunc { playerPhasePrepareAction(it) }
        hooks.applyUnitAction = ProcBoolFunc { applyUnitActionWait() }
        hooks.handlePostActionTraps = ProcBoolFunc { handlePostActionTraps(it) }
        hooks.runPotentialWaitEvents = ProcBoolFunc { runPotentialWaitEvents() }
        hooks.ensureCameraOntoActiveUnitPosition =
            ProcBoolFunc { ensureCameraOntoActiveUnitPosition(it) }
        hooks.finishAction = ProcFunc { playerPhaseFinishAction(it) }
    }

    /** `PlaySoundEffect` (`Fe8Boot` passes the game's sound wrapper). */
    public fun interface SoundEffect {
        /** Plays song [song]. */
        public fun play(song: Int)
    }

    /** `PutMapCursor` (the cursor sprite). */
    public fun interface PutMapCursor {
        /** Puts the cursor sprite at pixel ([x], [y]) with sprite [kind] (0 normal, 3 hover). */
        public fun put(x: Int, y: Int, kind: Int)
    }

    /**
     * The default hooks. Every one defaults to doing nothing (the `PROC_CALL_2` ones to true, go
     * on; `doesBmxFadeExist` to false); a replacement for a `PROC_REPEAT` must end the wait
     * itself.
     */
    public class Hooks {
        /** `PlayerPhase_Suspend` (writes the suspend save); set by `PlayerPhaseWiring`. */
        public var suspend: ProcFunc = ProcFunc {}

        /** `RefreshUnitSprites`. Default no-op; `Fe8Boot` sets the `UnitSpriteHandles` port. */
        public var refreshUnitSprites: ProcFunc = ProcFunc {}

        /** Stand-in until M6-E for `StartPlayerPhaseStartTutorialEvent` (Easy-mode tutorial). */
        public var startTutorialEvent: ProcFunc = ProcFunc {}

        /** `StartMapSongBgm`. Default no-op; `Fe8Boot` sets `MapMusic.startMapSongBgm`. */
        public var startMapSongBgm: ProcFunc = ProcFunc {}

        /** `StartPlayerPhaseSideWindows`; set by `PlayerPhaseWiring`. */
        public var startSideWindows: ProcFunc = ProcFunc {}

        /** `EndPlayerPhaseSideWindows`; set by `PlayerPhaseWiring`. */
        public var endSideWindows: ProcFunc = ProcFunc {}

        /**
         * `ResetUnitSpriteHover` (src/bmudisp.c). Replaced by [PlayerPhase] to call
         * [UnitSpriteHover.resetUnitSpriteHover].
         */
        public var resetUnitSpriteHover: ProcFunc = ProcFunc {}

        /**
         * Whether the A button of `PlayerPhase_MainIdle` selects (false: the whole A branch is
         * skipped). Default false so the app is unchanged until PP-17 wires the select flow;
         * `PlayerPhaseSelectDifferentialTest` sets it.
         */
        public var unitSelectEnabled: Boolean = false

        /**
         * Stand-in until M6-F for the L button branch of `PlayerPhase_MainIdle`
         * (`TrySwitchViewedUnit` and the sound).
         */
        public var lButton: ProcFunc = ProcFunc {}

        /**
         * Stand-in until M6-F for the whole R button branch of `PlayerPhase_MainIdle` (the stat
         * screen, `Proc_Goto(proc, 5)`); runs when R is new and the cursor tile holds a unit;
         * returns true if it left `PlayerPhase_MainIdle`.
         */
        public var rButton: ProcBoolFunc = ProcBoolFunc { false }

        /**
         * Stand-in until M6-F for the START button branch of `PlayerPhase_MainIdle` (the
         * minimap, `Proc_Goto(proc, 9)`); runs when START is new and SELECT is not held and the A
         * branch did not run; returns true if it left `PlayerPhase_MainIdle`.
         */
        public var startButton: ProcBoolFunc = ProcBoolFunc { false }

        /**
         * `UnitSpriteHoverUpdate` (src/bmudisp.c). Replaced by [PlayerPhase] to call
         * [UnitSpriteHover.unitSpriteHoverUpdate].
         */
        public var unitSpriteHoverUpdate: ProcFunc = ProcFunc {}

        /**
         * `IsUnitSpriteHoverEnabledAt` (src/bmudisp.c). Replaced by [PlayerPhase] to call
         * [UnitSpriteHover.isUnitSpriteHoverEnabledAt].
         */
        public var isUnitSpriteHoverEnabledAt: (x: Int, y: Int) -> Boolean = { _, _ -> false }

        /**
         * `PutMapCursor` (the cursor sprite). Default no-op; `Fe8Boot` sets `mapCursor.put`.
         */
        public var putMapCursor: PutMapCursor = PutMapCursor { _, _, _ -> }

        /** `PlaySoundEffect(SONG_65)`, the cursor move sound; set by `PlayerPhaseWiring`. */
        public var cursorSound: SoundEffect = SoundEffect {}

        /**
         * `DoesBMXFADEExist` (bmxfade.c, `Proc_Find(sProcScr_BMXFADE)`): stand-in until M6-H
         * (the fog fade of chapters with vision), default false. No capture of the select window
         * has it.
         */
        public var doesBmxFadeExist: ProcBoolFunc = ProcBoolFunc { false }

        /** `ShowUnitSprite(unit)` for unit id [unit]; set by `PlayerPhaseWiring`. */
        public var showUnitSprite: (unit: Int) -> Unit = {}

        /**
         * `StartOrphanMenuAdjusted(&gMapMenuDef, ...)` and `sub_80832CC` (the map menu; set by
         * `PlayerPhaseWiring`); gets `gBmSt.cursorTarget.x - gBmSt.camera.x`.
         */
        public var startMapMenu: (xOffset: Int) -> Unit = {}

        /**
         * Stand-in until M6-E for `PidStatsAddActAmt(pid, 1)` (the per-character action count of
         * the save statistics); gets the character id.
         */
        public var pidStatsAddActAmt: (pid: Int) -> Unit = {}

        /**
         * Stand-in until M6-I for `CanCharacterBePrepMoved(pid)` (only asked on the preparation
         * screen); default false.
         */
        public var canCharacterBePrepMoved: (pid: Int) -> Boolean = { false }

        /**
         * Stand-in until M6-C for `sub_802C334` (zeroes `sUnknown_0203A60C`, a scratch struct of
         * the battle code), called by `UnitBeginAction`.
         */
        public var resetBattleScratch: () -> Unit = {}

        /** `PlaySoundEffect`; set by `PlayerPhaseWiring`. */
        public var playSoundEffect: SoundEffect = SoundEffect {}

        /** `MuExists` (`Proc_Find(ProcScr_Mu) != 0`); default false, `Mu.muExists` in the game. */
        public var muExists: ProcBoolFunc = ProcBoolFunc { false }

        /** `StartMu(unit)` (unit id [unit]); the hover's `startMu` in the game. */
        public var startMu: (unit: Int) -> Unit = {}

        /** `HideUnitSprite(unit)` (unit id [unit]); set by `PlayerPhaseWiring`. */
        public var hideUnitSprite: (unit: Int) -> Unit = {}

        /** `SetAutoMuDefaultFacing`; set by `PlayerPhaseWiring`. */
        public var setAutoMuDefaultFacing: ProcFunc = ProcFunc {}

        /** Stand-in until M6-E for `TryCallSelectEvents` (select events of the active unit). */
        public var tryCallSelectEvents: ProcFunc = ProcFunc {}

        /**
         * The part of `DisplayUnitEffectRange` that runs while the unit has not moved.
         * `Fe8Boot` sets it (`PlayerPhaseWiring`) with the weapon case of
         * `AttackRange.generateUnitCompleteAttackRange`; the staff range and
         * `GenerateMagicSealMap` are a stand-in until M6-C. The original is:
         * `BmMapFill(gBmMapOther, 0)`, `GenerateMagicSealMap` for a unit with magic ranks,
         * `BmMapFill(gBmMapRange, 0)` and the attack or staff range generation for the weapon
         * usability of unit id [unit] (`gBmSt.swapActionRangeCount` selects between both for a
         * unit with both). Returns the `LIMITVIEW_*` flags to display; the default
         * does nothing and keeps `LIMITVIEW_BLUE`.
         */
        public var generateAttackRange: (unit: Int) -> Int = { LIMITVIEW_BLUE }

        /**
         * `DisplayMoveRangeGraphics(flags)`: `Fe8Boot` sets
         * [MoveLimitView.displayMoveRangeGraphics] (PP-4).
         */
        public var displayMoveRangeGraphics: (flags: Int) -> Unit = {}

        /**
         * `PathArrowDisp_Init(mode)`: 0 when the cursor is on the active unit, 1 otherwise. With a
         * [PathArrow] given to [PlayerPhase] it calls [PathArrow.init] (data only, the graphics
         * are PP-6); `Fe8Boot` gives it a [PathArrow].
         */
        public var pathArrowDispInit: (mode: Int) -> Unit = {}

        /**
         * `PlayerPhase_RangeDisplayIdle` (a `PROC_REPEAT`). Replaced by [PlayerPhase] to call
         * [playerPhaseRangeDisplayIdle].
         */
        public var rangeDisplayIdle: ProcFunc = ProcFunc {}

        /** `StartDestSelectedEvent` (events); stand-in until M6-E, false (no event). */
        public var startDestSelectedEvent: () -> Boolean = { false }

        /**
         * `GetUnitWeaponUsabilityBits(unit)` for unit id [unit]; default 0. `Fe8Boot` answers
         * the weapon bit only (the staff bit is a stand-in until M6-C).
         */
        public var getUnitWeaponUsabilityBits: (unit: Int) -> Int = { 0 }

        /** `GetCombinedEnemyWeaponUsabilityBits`; stand-in until M6-C, default 0. */
        public var getCombinedEnemyWeaponUsabilityBits: () -> Int = { 0 }

        /**
         * `HideMoveRangeGraphics`: `Fe8Boot` sets [MoveLimitView.hideMoveRangeGraphics] (PP-4).
         */
        public var hideMoveRangeGraphics: ProcFunc = ProcFunc {}

        /** `CanShowUnitStatScreen(unit)` for unit id [unit]; stand-in until M6-F. */
        public var canShowUnitStatScreen: (unit: Int) -> Boolean = { false }

        /**
         * Stand-in until M6-F for `SetStatScreenConfig` and `StartStatScreen(unit, proc)` (unit
         * id [unit]).
         */
        public var startStatScreen: (unit: Int, proc: Proc) -> Unit = { _, _ -> }

        /** `GetTrapAt(x, y)->type` of src/bmtrick.c; stand-in until M6-H, -1 for no trap. */
        public var trapTypeAt: (x: Int, y: Int) -> Int = { _, _ -> -1 }

        /**
         * `PlayerPhase_DisplayUnitMovement`; [PlayerPhase] replaces it with the port when it has
         * a [PathArrow], else it does nothing.
         */
        public var displayUnitMovement: ProcFunc = ProcFunc {}

        /** `PlayerPhase_WaitForUnitMovement` (a `PROC_REPEAT`); replaced by the port. */
        public var waitForUnitMovement: ProcFunc = ProcFunc {}

        /** `PlayerPhase_ApplyUnitMovement` (a `PROC_REPEAT`); replaced by the port. */
        public var applyUnitMovement: ProcFunc = ProcFunc {}

        /**
         * `SetAutoMuMoveScript(gWorkingMovementScript)` ([Mu.setAutoMuMoveScript]); gets the
         * script. Default no-op until `Fe8Boot` wires the select (PP-17).
         */
        public var setAutoMuMoveScript: (script: IntArray) -> Unit = {}

        /**
         * `MuExistsActive` ([Mu.muExistsActive]). The default (true) never ends the wait of
         * [playerPhaseWaitForUnitMovement]; `Fe8Boot` sets [Mu.muExistsActive].
         */
        public var muExistsActive: () -> Boolean = { true }

        /**
         * Stand-in until M6-E for `StartAfterUnitMovedEvent` (src/eventinfo.c; the tutorial and
         * the chapter's after-move events): true when an event started.
         */
        public var startAfterUnitMovedEvent: () -> Boolean = { false }

        /**
         * `StartSemiCenteredOrphanMenu(&gUnitActionMenuDef, x, 1, 0x16)` (the unit action menu;
         * set by `PlayerPhaseWiring`); gets `gBmSt.cursorTarget.x - gBmSt.camera.x`.
         */
        public var startUnitActionMenu: (xOffset: Int) -> Unit = {}

        /** `PlayerPhase_PrepareAction` (a `PROC_CALL_2`); [PlayerPhase] sets the port. */
        public var prepareAction: ProcBoolFunc = ProcBoolFunc { true }

        /** `ApplyUnitAction` (a `PROC_CALL_2`); [PlayerPhase] sets the Wait branch. */
        public var applyUnitAction: ProcBoolFunc = ProcBoolFunc { true }

        /** `HandlePostActionTraps` (a `PROC_CALL_2`); replaced by [PlayerPhase] with the port. */
        public var handlePostActionTraps: ProcBoolFunc = ProcBoolFunc { true }

        /** `RunPotentialWaitEvents` (a `PROC_CALL_2`); replaced by [PlayerPhase] with the port. */
        public var runPotentialWaitEvents: ProcBoolFunc = ProcBoolFunc { true }

        /** `EnsureCameraOntoActiveUnitPosition` (a `PROC_CALL_2`); replaced by the port. */
        public var ensureCameraOntoActiveUnitPosition: ProcBoolFunc = ProcBoolFunc { true }

        /** `PlayerPhase_FinishAction`; replaced by [PlayerPhase] with the port. */
        public var finishAction: ProcFunc = ProcFunc {}

        /** `WriteSuspendSave(saveId)`; set by `PlayerPhaseWiring` to the boot's suspend save. */
        public var writeSuspendSave: (saveId: Int) -> Unit = {}

        /**
         * `CheckForWaitEvents` for the active unit; wire to `EventInfos.checkForWaitEvents` with
         * its tile (PP-17). Default: nothing to run.
         */
        public var checkForWaitEvents: () -> Boolean = { false }

        /** `RunWaitEvents` for the active unit; wire to `EventInfos.runWaitEvents` (PP-17). */
        public var runWaitEvents: () -> Unit = {}

        /** `ShouldCallEndEvent`; wire to `EventInfos.shouldCallEndEvent` (PP-17). */
        public var shouldCallEndEvent: () -> Boolean = { false }

        /** `MaybeCallEndEvent_`; stand-in until M6-E (the end event). */
        public var maybeCallEndEvent: () -> Unit = {}

        /** `GetPickTrapType(unit)` (bmtrap.c) for unit id [unit]; 0 is none. M6-H stand-in. */
        public var getPickTrapType: (unit: Int) -> Int = { 0 }

        /** `ExecTrap(proc, unit, 0)` for unit id [unit]; stand-in until M6-H, true (go on). */
        public var execTrap: (proc: Proc, unit: Int) -> Boolean = { _, _ -> true }

        /** `GetBattleAnimPreconfType() == PLAY_ANIMCONF_OFF`; stand-in until M6-G, false. */
        public var battleAnimPreconfOff: () -> Boolean = { false }

        /** `PidStatsAddSquaresMoved(charId, amount)`; stand-in until M6-E (the records). */
        public var pidStatsAddSquaresMoved: (charId: Int, amount: Int) -> Unit = { _, _ -> }

        /** `CanUnitMove` for the active unit (a canto move); stand-in until M6-H, false. */
        public var canUnitMove: () -> Boolean = { false }

        /** `RenderBmMapOnBg2` (fog chapters only); stand-in until M6-H. */
        public var renderBmMapOnBg2: ProcFunc = ProcFunc {}

        /** `NewBMXFADE(flag)` (fog chapters only); stand-in until M6-H. */
        public var newBmxFade: (flag: Int) -> Unit = {}

        /** Stand-in until M6-E for `PlayerPhase_ResumeRangeDisplay` (resume during an action). */
        public var resumeRangeDisplay: ProcFunc = ProcFunc {}

        /** `EndAllMus`; set by `PlayerPhaseWiring`. */
        public var endAllMus: ProcFunc = ProcFunc {}

        /** Stand-in until M6-D for `DisplayActiveUnitEffectRange` (label 11, an enemy's range). */
        public var displayActiveUnitEffectRange: ProcFunc = ProcFunc {}

        /** Stand-in until M6-D for `PlayerPhase_DisplayDangerZone` (the enemies' reach). */
        public var displayDangerZone: ProcFunc = ProcFunc {}

        /** Stand-in until M6-F for `PlayerPhase_ReReadGameSaveGfx` (after the stat screen). */
        public var reReadGameSaveGfx: ProcFunc = ProcFunc {}

        /** Stand-in until M6-F for `gProcScr_ADJUSTSFROMXI`, a script that ends at once. */
        public var adjustsFromXiScript: ProcScript =
            ProcScript("gProcScr_ADJUSTSFROMXI", listOf(end()))
    }

    /**
     * `gProcScr_PlayerPhase` (0x0859AAD8). Labels 0 (phase start), 9 (idle), 1 (unit selected),
     * 2, 7 (action), 4, 5, 10, 6, 8, 11, 12 and 3 (end of the phase) as in the C.
     */
    public val script: ProcScript = ProcScript(
        "gProcScr_PlayerPhase",
        listOf(
            name("E_PLAYERPHASE"),
            mark(PROC_MARK_MAIN),
            yield(),
            label(0),
            call(ProcFunc { hooks.suspend.invoke(it) }),
            call(ProcFunc { entityBmMaps.refreshEntityBmMaps() }),
            call(ProcFunc { render.renderBmMap() }),
            call(ProcFunc { hooks.refreshUnitSprites.invoke(it) }),
            call(ProcFunc { hooks.startTutorialEvent.invoke(it) }),
            whileTrue(ProcBoolFunc { eventEngineExists() }),
            call(ProcFunc { playerPhaseHandleAutoEnd(it) }),
            call(ProcFunc { hooks.startMapSongBgm.invoke(it) }),
            label(9),
            call(ProcFunc { hooks.startSideWindows.invoke(it) }),
            call(ProcFunc { hooks.resetUnitSpriteHover.invoke(it) }),
            repeat(ProcFunc { playerPhaseMainIdle(it) }),
            label(1),
            call(ProcFunc { hooks.endSideWindows.invoke(it) }),
            whileTrue(ProcBoolFunc { hooks.doesBmxFadeExist.invoke(it) }),
            call(ProcFunc { units.setAllUnitNotBackSprite() }),
            call(ProcFunc { hooks.refreshUnitSprites.invoke(it) }),
            startChildBlocking { selectEventsScript },
            call(ProcFunc { playerPhaseInitUnitMovementSelect() }),
            sleep(1),
            repeat(ProcFunc { hooks.rangeDisplayIdle.invoke(it) }),
            call(ProcFunc { hooks.displayUnitMovement.invoke(it) }),
            repeat(ProcFunc { hooks.waitForUnitMovement.invoke(it) }),
            label(2),
            repeat(ProcFunc { hooks.applyUnitMovement.invoke(it) }),
            label(7),
            whileExists { camMove.script },
            call2(ProcBoolFunc { hooks.prepareAction.invoke(it) }),
            call2(ProcBoolFunc { hooks.applyUnitAction.invoke(it) }),
            call2(ProcBoolFunc { hooks.handlePostActionTraps.invoke(it) }),
            call2(ProcBoolFunc { hooks.runPotentialWaitEvents.invoke(it) }),
            call2(ProcBoolFunc { hooks.ensureCameraOntoActiveUnitPosition.invoke(it) }),
            call(ProcFunc { hooks.finishAction.invoke(it) }),
            goto(0),
            label(4),
            whileTrue(ProcBoolFunc { hooks.doesBmxFadeExist.invoke(it) }),
            goto(1),
            label(5),
            call(ProcFunc { hooks.reReadGameSaveGfx.invoke(it) }),
            label(10),
            startChildBlocking { hooks.adjustsFromXiScript },
            goto(9),
            label(6),
            call(ProcFunc { hooks.resumeRangeDisplay.invoke(it) }),
            goto(1),
            label(8),
            yield(),
            call(ProcFunc { hooks.endAllMus.invoke(it) }),
            goto(0),
            label(11),
            call(ProcFunc { hooks.endSideWindows.invoke(it) }),
            whileTrue(ProcBoolFunc { hooks.doesBmxFadeExist.invoke(it) }),
            call(ProcFunc { hooks.displayActiveUnitEffectRange.invoke(it) }),
            repeat(ProcFunc { hooks.rangeDisplayIdle.invoke(it) }),
            goto(9),
            label(12),
            call(ProcFunc { hooks.displayDangerZone.invoke(it) }),
            repeat(ProcFunc { hooks.rangeDisplayIdle.invoke(it) }),
            goto(9),
            label(3),
            whileTrue(ProcBoolFunc { hooks.doesBmxFadeExist.invoke(it) }),
            end(),
        ),
    )

    /**
     * `gProcScr_0859ACE8` (0x0859ACE8, transcribed 1:1, checked against the ROM by
     * `PlayerPhaseScriptRomTest`): the select events child of label 1. Makes the MU of the
     * active unit, runs the select events and waits for them.
     */
    public val selectEventsScript: ProcScript = ProcScript(
        "gProcScr_0859ACE8",
        listOf(
            call(ProcFunc { makeMoveunitForActiveUnit(it) }),
            call(ProcFunc { hooks.tryCallSelectEvents.invoke(it) }),
            whileTrue(ProcBoolFunc { eventEngineExists() }),
            end(),
        ),
    )

    private fun s16(v: Int): Int = v.toShort().toInt()

    private fun s8(v: Int): Int = v.toByte().toInt()

    /**
     * Ported from src/playerphase.c: PlayerPhase_HandleAutoEnd
     *
     * Jumps [proc] to label 3 (end of the phase) if auto end turns is on and no unit of the active
     * faction can act. Verified for the "a unit can act" path (the capture's label 0 after the
     * action); the jump is not exercised.
     */
    public fun playerPhaseHandleAutoEnd(proc: Proc) {
        if (playSt.disableAutoEndTurns == 0 && units.getPhaseAbleUnitCount(playSt.faction) == 0) {
            engine.goto(proc, 3)
        }
    }

    /**
     * Ported from src/playerphase.c: EnsureCameraOntoActiveUnitPosition
     *
     * The `PROC_CALL_2` after the action: true (go on) when no camera move had to start for the
     * active unit's tile.
     */
    public fun ensureCameraOntoActiveUnitPosition(proc: Proc): Boolean =
        !camMove.ensureCameraOntoPosition(proc, units.xPos(active.unit), units.yPos(active.unit))

    /**
     * Ported from src/playerphase.c: PlayerPhase_PrepareAction
     *
     * Verified for the Wait action (the camera check on the subject, the item switch and the
     * `unitActionType != WAIT` test that skips the suspend save). Every other branch is
     * transcribed (not verified: no capture has it): no action chosen (back to move, or a forced
     * wait after a trade or take), the trade, supply, take, give and ballista actions that go
     * back to the move (`PlayerPhase_CancelAction`), the promotion items and Solar and Lunar
     * Braces that return before the suspend save, and the suspend save of the other actions.
     *
     * Returns true (go on) when no camera move had to start for the subject.
     */
    public fun playerPhasePrepareAction(proc: Proc): Boolean {
        val subject = active.action.subjectIndex
        val cameraReturn =
            !camMove.ensureCameraOntoPosition(proc, units.xPos(subject), units.yPos(subject))
        when (active.action.unitActionType) {
            0 -> {
                if (bm.takenAction != 0) {
                    active.action.unitActionType = UNIT_ACTION_FORCE_WAIT
                } else {
                    playerPhaseBackToMove(proc)
                    return true
                }
            }
            UNIT_ACTION_TRADED -> {
                bm.takenAction = bm.takenAction or BM_TAKEN_ACTION_TRADE
                playerPhaseCancelAction(proc)
                return true
            }
            UNIT_ACTION_TRADED_SUPPLY -> {
                bm.takenAction = bm.takenAction or BM_TAKEN_ACTION_SUPPLY
                playerPhaseCancelAction(proc)
                return true
            }
            UNIT_ACTION_TAKE, UNIT_ACTION_GIVE -> {
                bm.takenAction = bm.takenAction or BM_TAKEN_ACTION_TAKE
                playerPhaseCancelAction(proc)
                return true
            }
            UNIT_ACTION_RIDE_BALLISTA, UNIT_ACTION_EXIT_BALLISTA -> {
                bm.takenAction = bm.takenAction or BM_TAKEN_ACTION_BALLISTA
                playerPhaseCancelAction(proc)
                return true
            }
            UNIT_ACTION_TRADED_1D -> {
                playerPhaseCancelAction(proc)
                return true
            }
        }
        val item = units.item(subject, active.action.itemSlotIndex) and 0xFF
        when (item) {
            ITEM_HEAVENSEAL, ITEM_HEROCREST, ITEM_KNIGHTCREST, ITEM_ORIONSBOLT, ITEM_ELYSIANWHIP,
            ITEM_GUIDINGRING, ITEM_MASTERSEAL, ITEM_OCEANSEAL, ITEM_LUNARBRACE, ITEM_SOLARBRACE,
            ITEM_UNK_C1,
            -> return cameraReturn
        }
        if (active.action.unitActionType != UNIT_ACTION_WAIT_TYPE && bm.justResumed == 0) {
            active.action.suspendPointType = SUSPEND_POINT_DURINGACTION
            hooks.writeSuspendSave(SAVE_ID_SUSPEND)
        }
        return cameraReturn
    }

    /**
     * Transcribed from src/playerphase.c: PlayerPhase_CancelAction (not verified: no capture
     *   reaches it; only the trade, supply, take, give and ballista actions of
     *   [playerPhasePrepareAction] do)
     *
     * Forgets the chosen action and goes back to label 2, the unit menu.
     */
    public fun playerPhaseCancelAction(proc: Proc) {
        active.action.unitActionType = 0
        engine.goto(proc, 2)
    }

    /**
     * Ported from src/bmmind.c: ApplyUnitAction
     *
     * The Wait branch only (verified for Wait; every other action type is not written and fails
     * loudly). Stand-in until MC-13: a combat action (A on an attack target) ends the unit's
     * turn like Wait, without the battle.
     *
     * Makes the subject the active unit; Wait and Trapped set `US_HAS_MOVED` and go on (true).
     * The Nightmare check of a combat action is part of the combat branch (not written).
     */
    public fun applyUnitActionWait(): Boolean {
        active.unit = active.action.subjectIndex
        check(
            active.action.unitActionType == UNIT_ACTION_WAIT_TYPE ||
                active.action.unitActionType == UNIT_ACTION_TRAPPED ||
                active.action.unitActionType == UNIT_ACTION_COMBAT,
        ) {
            "ApplyUnitAction: action ${active.action.unitActionType} is not written"
        }
        units.setState(active.unit, units.state(active.unit) or US_HAS_MOVED)
        return true
    }

    /**
     * Ported from src/bmtrap.c: HandlePostActionTraps
     *
     * Runs after the action: nothing to do (true) when the unit stands on no trap it triggers.
     * Verified for that result only, with [Hooks.getPickTrapType] returning none; the dead and
     * canto early returns and the rest (the unit stands on a trap) are transcribed (not
     * verified: no capture has a dead unit, a canto unit or a trap here): marks the action as
     * Wait, writes the suspend save,
     * refreshes the unit sprites when battle animations are off and runs the trap
     * ([Hooks.execTrap], PP-17 and the trap port).
     */
    public fun handlePostActionTraps(proc: Proc): Boolean {
        val unit = active.unit
        if (units.currentHp(unit) <= 0) return true
        if ((units.cAttributes(unit) and CA_CANTO) != 0 &&
            (units.state(unit) and US_CANTOING) == 0
        ) {
            when (active.action.unitActionType) {
                UNIT_ACTION_WAIT_TYPE, UNIT_ACTION_COMBAT, UNIT_ACTION_STAFF -> Unit
                else -> return true
            }
        }
        if (hooks.getPickTrapType(unit) == 0) return true
        active.action.suspendPointType = 1
        active.action.unitActionType = UNIT_ACTION_WAIT_TYPE
        hooks.writeSuspendSave(SAVE_ID_SUSPEND)
        if (hooks.battleAnimPreconfOff()) hooks.refreshUnitSprites.invoke(proc)
        return hooks.execTrap(proc, unit)
    }

    /**
     * Ported from src/playerphase.c: RunPotentialWaitEvents
     *
     * The `PROC_CALL_2` after the action: when the post-action event check
     * ([Hooks.checkForWaitEvents]) finds something, starts it ([Hooks.runWaitEvents]) and
     * ends the frame (false; the started event engine locks the game until it ends), else true.
     */
    public fun runPotentialWaitEvents(): Boolean {
        if (hooks.checkForWaitEvents()) {
            hooks.runWaitEvents()
            return false
        }
        return true
    }

    /**
     * Ported from src/playerphase.c: PlayerPhase_FinishAction
     *
     * After the action: puts the active unit on its destination ([moveActiveUnit]), rebuilds the
     * entity maps and the map, puts the cursor on the unit, then ends the MUs. With fog
     * (`chapterVisionRange != 0`) the map is also redrawn on BG2 and faded
     * (not verified: the Prologue has no fog), a canto unit starts its canto
     * ([tryMakeCantoUnit]) and a won chapter shows the end event and jumps to label 8
     * (not verified: the win flag is clear in the capture). Putting the cursor on the unit is
     * not verified either: it already stands there.
     */
    public fun playerPhaseFinishAction(proc: Proc) {
        if (playSt.chapterVisionRange != 0) {
            hooks.renderBmMapOnBg2.invoke(proc)
            moveActiveUnit(active.action.xMove, active.action.yMove)
            entityBmMaps.refreshEntityBmMaps()
            render.renderBmMap()
            hooks.newBmxFade(0)
            hooks.refreshUnitSprites.invoke(proc)
        } else {
            moveActiveUnit(active.action.xMove, active.action.yMove)
            entityBmMaps.refreshEntityBmMaps()
            render.renderBmMap()
        }
        val unit = active.unit
        Camera.setCursorMapPosition(bm, units.xPos(unit), units.yPos(unit))
        playSt.xCursor = bm.playerCursorX
        playSt.yCursor = bm.playerCursorY
        if (tryMakeCantoUnit(proc)) {
            hooks.hideUnitSprite(unit)
            return
        }
        if (hooks.shouldCallEndEvent()) {
            hooks.endAllMus.invoke(proc)
            entityBmMaps.refreshEntityBmMaps()
            render.renderBmMap()
            hooks.refreshUnitSprites.invoke(proc)
            hooks.maybeCallEndEvent()
            engine.goto(proc, 8)
            return
        }
        hooks.endAllMus.invoke(proc)
    }

    /**
     * Ported from src/bmunit.c: MoveActiveUnit
     *
     * Verified for the Wait capture (the unit stays on its tile, no rescue, alive).
     * Puts the active unit on ([x], [y]), makes it unselectable, records the squares moved,
     * shows it again if alive and finalizes the move.
     */
    private fun moveActiveUnit(x: Int, y: Int) {
        val unit = active.unit
        units.setPosition(unit, x, y)
        units.setState(unit, units.state(unit) or US_UNSELECTABLE)
        hooks.pidStatsAddSquaresMoved(units.charId(unit), active.action.moveCount)
        if (units.currentHp(unit) != 0) {
            units.setState(unit, units.state(unit) and US_HIDDEN.inv())
        }
        unitFinalizeMovement(unit)
    }

    /**
     * Transcribed from src/playerphase.c: TryMakeCantoUnit (not verified: the capture's unit has
     *   no canto, and a bail at the first test is not observable: `US_HAS_MOVED` is set, so the
     *   second test bails too)
     *
     * Starts the canto move of an active unit that can canto, has not moved and did not wait,
     * fight or use a staff, and has movement left: returns true after jumping to label 4 (fog)
     * or 1. Returns false in every other case.
     */
    public fun tryMakeCantoUnit(proc: Proc): Boolean {
        val unit = active.unit
        if ((units.cAttributes(unit) and CA_CANTO) == 0) return false
        if ((units.state(unit) and (US_DEAD or US_HAS_MOVED or US_BIT16)) != 0) return false
        when (active.action.unitActionType) {
            UNIT_ACTION_WAIT_TYPE, UNIT_ACTION_COMBAT, UNIT_ACTION_STAFF -> return false
        }
        if (units.movement(unit) <= active.action.moveCount) return false
        if (!hooks.canUnitMove()) return false
        map.bmMapFill(map.range, 0)
        unitBeginCantoAction(unit)
        units.setState(unit, (units.state(unit) or US_HAS_MOVED) and US_UNSELECTABLE.inv())
        hooks.endAllMus.invoke(proc)
        hooks.startMu(unit)
        hooks.setAutoMuDefaultFacing.invoke(proc)
        engine.goto(proc, if (playSt.chapterVisionRange != 0) 4 else 1)
        return true
    }

    /**
     * Ported from src/playerphase.c: HandlePlayerCursorMovement
     *
     * Moves the cursor from the key state: with B held and the display cursor on an 8 pixel
     * boundary, 8 pixels per frame on a new press (`newKeys2`); otherwise 4 pixels per frame on
     * auto-repeat. While the display cursor is between tiles, the A, B, START, R and L presses
     * are cleared from `newKeys`.
     */
    public fun handlePlayerCursorMovement() {
        if ((keys.heldKeys and KEY_B) != 0 && (bm.playerCursorDisplayX and 7) == 0 &&
            (bm.playerCursorDisplayY and 7) == 0
        ) {
            handleMapCursorInput(keys.newKeys2)
            handleMoveMapCursor(8)
            handleMoveCameraWithMapCursor(8)
        } else {
            handleMapCursorInput(keys.repeatedKeys)
            handleMoveMapCursor(4)
            handleMoveCameraWithMapCursor(4)
        }
        if (((bm.playerCursorDisplayX or bm.playerCursorDisplayY) and 0xF) != 0) {
            keys.newKeys = keys.newKeys and
                (KEY_A or KEY_B or KeyStatus.START_BUTTON or KEY_R or KEY_L).inv()
        }
    }

    /**
     * Partially ported from src/playerphase.c: PlayerPhase_MainIdle. Ported: the cursor
     * movement and the A button (see [Hooks.unitSelectEnabled]) with [getPlayerSelectKind].
     * The unit sprite hover ([hover], through the hooks PlayerPhase sets) runs at the end.
     * Stand-ins until M6-F: the L, R and START branches ([Hooks.lButton], [Hooks.rButton],
     * [Hooks.startButton]); `DoesBMXFADEExist` is a hook too, and the cursor sprite is
     * [Hooks.putMapCursor].
     *
     * With A new on the cursor tile: no unit or a unit that cannot act opens the map menu
     * (transcribed, not verified: no select run has it) and jumps to label 9; a unit to control
     * begins its action ([unitBeginAction]) and breaks to label 1; a unit that cannot be
     * controlled (enemy, asleep, berserk) begins its action and jumps to label 11 (not
     * verified); kind 4 (preparation screen) falls through to the START branch.
     */
    public fun playerPhaseMainIdle(proc: Proc) {
        handlePlayerCursorMovement()

        if ((keys.newKeys and KEY_L) != 0) {
            hooks.lButton.invoke(proc)
        } else if (!hooks.doesBmxFadeExist.invoke(proc)) {
            val cursorUnit = map.unit[bm.playerCursorY, bm.playerCursorX]
            if ((keys.newKeys and KEY_R) != 0 && cursorUnit != 0) {
                if (hooks.rButton.invoke(proc)) return
            }
            var handled = false
            if ((keys.newKeys and KEY_A) != 0 && hooks.unitSelectEnabled) {
                val unit = if (units.unitOffset(cursorUnit) >= 0) cursorUnit else 0
                when (getPlayerSelectKind(unit)) {
                    SELECT_NOUNIT, SELECT_TURNENDED -> {
                        hooks.endSideWindows.invoke(proc)
                        playSt.xCursor = bm.playerCursorX
                        playSt.yCursor = bm.playerCursorY
                        if (unit != 0) {
                            hooks.endAllMus.invoke(proc)
                            hooks.showUnitSprite(unit)
                        }
                        hooks.startMapMenu(bm.cursorTargetX - bm.cameraX)
                        engine.goto(proc, 9)
                        return
                    }
                    SELECT_CONTROL -> {
                        unitBeginAction(unit)
                        hooks.pidStatsAddActAmt(units.charId(active.unit))
                        engine.breakProc(proc)
                        handled = true
                    }
                    SELECT_NOCONTROL -> {
                        unitBeginAction(unit)
                        bm.swapActionRangeCount = 0
                        engine.goto(proc, 11)
                        handled = true
                    }
                }
            }
            if (!handled && (keys.newKeys and KEY_START) != 0 &&
                (keys.heldKeys and KEY_SELECT) == 0
            ) {
                if (hooks.startButton.invoke(proc)) return
            }
        }

        hooks.unitSpriteHoverUpdate.invoke(proc)
        val hover = hooks.isUnitSpriteHoverEnabledAt(bm.playerCursorX, bm.playerCursorY)
        val kind = if (hover) 3 else 0
        hooks.putMapCursor.put(bm.playerCursorDisplayX, bm.playerCursorDisplayY, kind)
    }

    /**
     * Ported from src/playerphase.c: GetPlayerSelectKind
     *
     * What A on [unit] (a unit id, 0 for none) selects: `PLAYER_SELECT_NOUNIT` (0) for none,
     * `PLAYER_SELECT_TURNENDED` (1) for a unit of the phase's faction that cannot be selected
     * (it has acted), `PLAYER_SELECT_CONTROL` (2) for one the player can move,
     * `PLAYER_SELECT_NOCONTROL` (3) for another faction's unit or an asleep or berserk one, and 4
     * on the preparation screen for a character that cannot be moved there. The preparation
     * screen branch (`BM_FLAG_PREPSCREEN`, [Hooks.canCharacterBePrepMoved]) is transcribed (not
     * verified: no run is in preparations).
     */
    public fun getPlayerSelectKind(unit: Int): Int {
        var faction = playSt.faction
        if (unit == 0) return SELECT_NOUNIT
        if ((bm.gameStateBits and BM_FLAG_PREPSCREEN) != 0) {
            if (!hooks.canCharacterBePrepMoved(units.charId(unit))) return SELECT_4
            faction = 0
        }
        if ((units.index(unit) and 0xC0) != faction) return SELECT_NOCONTROL
        if ((units.state(unit) and UnitTable.US_UNSELECTABLE) != 0) return SELECT_TURNENDED
        if ((units.cAttributes(unit) and UnitTable.CA_UNSELECTABLE) != 0) return SELECT_TURNENDED
        val status = units.statusIndex(unit)
        if (status != UnitTable.STATUS_SLEEP && status != UnitTable.STATUS_BERSERK) {
            return SELECT_CONTROL
        }
        return SELECT_NOCONTROL
    }

    /**
     * Ported from src/bmunit.c: UnitBeginAction
     *
     * Makes [unit] (a unit id) the active unit: remembers where it stands, clears the action
     * data (subject = its `index`, no action, no movement), clears `taken_action`, sets `unk3F`
     * to 0xFF, resets the battle scratch struct ([Hooks.resetBattleScratch]), hides the unit
     * (`US_HIDDEN`) and clears its `gBmMapUnit` cell.
     */
    public fun unitBeginAction(unit: Int) {
        active.unit = unit
        active.unitId = units.index(unit) and 0xFF
        active.moveOriginX = units.xPos(unit)
        active.moveOriginY = units.yPos(unit)
        active.action.subjectIndex = units.index(unit) and 0xFF
        active.action.unitActionType = 0
        active.action.moveCount = 0
        bm.takenAction = 0
        bm.unk3F = s8(0xFF)
        hooks.resetBattleScratch()
        units.setState(unit, units.state(unit) or US_HIDDEN)
        map.unit[units.yPos(unit), units.xPos(unit)] = 0
    }

    /**
     * Ported from src/playerphase.c: PlayerPhase_InitUnitMovementSelect
     *
     * Sets `BM_FLAG_1` (the movement range shows), displays the active unit's range and starts
     * the path arrow: mode 0 with the select sound (SONG_69) when the cursor is on the unit,
     * mode 1 otherwise (not verified: the cursor is on the unit after the A press).
     */
    public fun playerPhaseInitUnitMovementSelect() {
        bm.gameStateBits = bm.gameStateBits or BM_FLAG_1
        displayUnitEffectRange(active.unit)
        if (units.xPos(active.unit) == bm.playerCursorX &&
            units.yPos(active.unit) == bm.playerCursorY
        ) {
            hooks.pathArrowDispInit(0)
            hooks.playSoundEffect.play(SONG_69)
            return
        }
        hooks.pathArrowDispInit(1)
    }

    /**
     * Partially ported from src/playerphase.c: DisplayUnitEffectRange
     *
     * Floods `gBmMapMovement` from the active unit with `UNIT_MOV - moveCount`
     * ([MovementMap.generateUnitMovementMapExt]; the C ignores its parameter [unit] and uses
     * `gActiveUnit`), and, while the unit has not moved (`US_HAS_MOVED`), generates the attack
     * or staff range ([Hooks.generateAttackRange], set by `Fe8Boot`); then displays the range
     * squares ([Hooks.displayMoveRangeGraphics], PP-4) with the flags
     * (`LIMITVIEW_BLUE`, or the range's). A unit with `US_HAS_MOVED` set displays
     * `LIMITVIEW_BLUE` only (transcribed, not verified: the window has the unit unmoved).
     */
    @Suppress("UNUSED_PARAMETER")
    public fun displayUnitEffectRange(unit: Int) {
        var flags = LIMITVIEW_BLUE
        movementMap?.generateUnitMovementMapExt(
            active.unit, units.movement(active.unit) - active.action.moveCount,
        )
        if ((units.state(active.unit) and US_HAS_MOVED) == 0) {
            flags = hooks.generateAttackRange(active.unit)
        }
        hooks.displayMoveRangeGraphics(flags)
    }

    /**
     * Partially ported from src/playerphase.c: MakeMoveunitForActiveUnit
     *
     * The first command of [selectEventsScript]: when no MU exists and the active unit is of the
     * phase's faction and neither asleep nor berserk, starts its MU and hides its sprite
     * ([Hooks.startMu], [Hooks.hideUnitSprite]; not verified: the window has the hover's MU
     * already); then `SetAutoMuDefaultFacing` ([Hooks.setAutoMuDefaultFacing]).
     */
    public fun makeMoveunitForActiveUnit(proc: Proc) {
        val unit = active.unit
        if (!hooks.muExists.invoke(proc)) {
            if ((units.index(unit) and 0xC0) == playSt.faction) {
                val status = units.statusIndex(unit)
                if (status != UnitTable.STATUS_SLEEP && status != UnitTable.STATUS_BERSERK) {
                    hooks.startMu(unit)
                    hooks.hideUnitSprite(unit)
                }
            }
        }
        hooks.setAutoMuDefaultFacing.invoke(proc)
    }

    /**
     * Ported from src/playerphase.c: PlayerPhase_RangeDisplayIdle
     *
     * The idle loop while the movement range shows: moves the cursor, then picks one action from
     * the keys. A with an active unit: not moved and not controllable (`GetPlayerSelectKind`
     * is not 2) swaps the ranges or cancels, else [canMoveActiveUnitTo] the cursor tile moves
     * (keeps the camera on the origin, hides the range squares, breaks the proc) or fails (sound
     * 0x6C). B cancels ([cancelSelect]) unless the unit has moved (fail). R and L are
     * transcribed (not verified: no capture presses them in the range display), as are A without
     * an active unit, the destination event and the range swap (SONG 0x6C on a failed A is
     * not verified either: no capture presses A off the range). Unless the proc left, the path
     * arrow follows the cursor for a controllable unit ([pathArrow], [PathArrow.drawUpdatedPathArrow]) and
     * the cursor sprite is drawn.
     */
    public fun playerPhaseRangeDisplayIdle(proc: Proc) {
        var action = ACT_NONE
        handlePlayerCursorMovement()

        var checkB = true
        if ((keys.newKeys and KEY_A) != 0) {
            checkB = false
            val unit = active.unit
            if (unit == 0) {
                action = if (hooks.getCombinedEnemyWeaponUsabilityBits() == 3) {
                    ACT_SWAP_RANGES
                } else {
                    ACT_CANCEL
                }
            } else if (hooks.startDestSelectedEvent()) {
                action = ACT_EVENT
            } else if (getPlayerSelectKind(unit) != SELECT_CONTROL &&
                (units.state(unit) and US_HAS_MOVED) == 0
            ) {
                action = if (hooks.getUnitWeaponUsabilityBits(unit) == 3) {
                    ACT_SWAP_RANGES
                } else {
                    ACT_CANCEL
                }
            } else if (!canMoveActiveUnitTo(bm.playerCursorX, bm.playerCursorY)) {
                action = ACT_FAIL
            } else {
                action = ACT_MOVE
                checkB = true
            }
        }
        if (checkB) {
            if ((keys.newKeys and KEY_B) != 0) {
                val moved = (units.state(active.unit) and US_HAS_MOVED) != 0
                action = if (moved) ACT_FAIL else ACT_CANCEL
            } else if ((keys.newKeys and KEY_R) != 0) {
                action = ACT_INFOSCREEN
            } else if ((keys.newKeys and KEY_L) != 0) {
                action = ACT_RESET_CURSOR
            }
        }
        // Note: the C jumps into the B check from ACT_MOVE (`goto else_stmt`), where B or R or L
        // can overwrite the action; A and B in one frame is not a capture case.

        when (action) {
            ACT_FAIL -> hooks.playSoundEffect.play(SONG_6C)
            ACT_MOVE -> {
                camMove.ensureCameraOntoPosition(proc, active.moveOriginX, active.moveOriginY)
                hooks.hideMoveRangeGraphics.invoke(proc)
                engine.breakProc(proc)
                return
            }
            ACT_CANCEL -> {
                cancelSelect(proc)
                return
            }
            ACT_INFOSCREEN -> {
                if (eventEngineExists()) {
                    // break out of the switch: the proc stays
                } else {
                    var uid = map.unit[bm.playerCursorY, bm.playerCursorX]
                    if (active.moveOriginX == bm.playerCursorX &&
                        active.moveOriginY == bm.playerCursorY
                    ) {
                        uid = units.index(active.unit)
                    }
                    if (uid != 0 && hooks.canShowUnitStatScreen(uid)) {
                        hooks.endAllMus.invoke(proc)
                        hooks.startStatScreen(uid, proc)
                        engine.goto(proc, 6)
                        return
                    }
                }
            }
            ACT_RESET_CURSOR -> if (active.unit != 0) {
                camMove.ensureCameraOntoPosition(proc, active.moveOriginX, active.moveOriginY)
                Camera.setCursorMapPosition(bm, active.moveOriginX, active.moveOriginY)
                hooks.playSoundEffect.play(SONG_CANCEL)
            }
            ACT_EVENT -> {}
            ACT_SWAP_RANGES -> {
                bm.swapActionRangeCount = (bm.swapActionRangeCount + 1) and 0xFF
                hooks.hideMoveRangeGraphics.invoke(proc)
                engine.goto(proc, if ((bm.gameStateBits and BM_FLAG_3) != 0) 12 else 11)
            }
        }

        if (getPlayerSelectKind(active.unit) == SELECT_CONTROL) {
            pathArrow?.drawUpdatedPathArrow()
        }
        hooks.putMapCursor.put(bm.playerCursorDisplayX, bm.playerCursorDisplayY, 1)
    }

    /**
     * The `ACT_CANCEL` case of `PlayerPhase_RangeDisplayIdle` (src/playerphase.c), B on the
     * range: ends the MUs, shows the unit again, puts the cursor back on the origin (player
     * units), clears `BM_FLAG_3`, hides the range squares, refreshes the maps and sprites,
     * plays the cancel sound and jumps to label 9.
     */
    private fun cancelSelect(proc: Proc) {
        if (active.unit != 0) {
            hooks.endAllMus.invoke(proc)
            units.setState(active.unit, units.state(active.unit) and US_HIDDEN.inv())
            if ((units.index(active.unit) and 0xC0) == 0) {
                camMove.ensureCameraOntoPosition(proc, active.moveOriginX, active.moveOriginY)
                Camera.setCursorMapPosition(bm, active.moveOriginX, active.moveOriginY)
            }
        }
        bm.gameStateBits = bm.gameStateBits and BM_FLAG_3.inv()
        hooks.hideMoveRangeGraphics.invoke(proc)
        entityBmMaps.refreshEntityBmMaps()
        hooks.refreshUnitSprites.invoke(proc)
        hooks.playSoundEffect.play(SONG_CANCEL)
        engine.goto(proc, 9)
    }

    /**
     * Ported from src/playerphase.c: CanMoveActiveUnitTo
     *
     * Whether the active unit can end its move on ([x], [y]): the tile is free of units and in
     * the movement range, and, for a unit in a ballista, is its origin or has no ballista trap
     * (the ballista part is transcribed, not verified: no capture has one).
     */
    public fun canMoveActiveUnitTo(x: Int, y: Int): Boolean {
        if (map.unit[y, x] != 0) return false
        if (map.movement[y, x] >= MAP_MOVEMENT_MAX) return false
        if ((units.state(active.unit) and UnitLoader.US_IN_BALLISTA) == 0) return true
        val trap = hooks.trapTypeAt(x, y)
        if (x == active.moveOriginX && y == active.moveOriginY) return true
        if (trap < 0) return true
        return trap != TRAP_BALLISTA
    }

    /**
     * Ported from src/playerphase.c: PlayerPhase_DisplayUnitMovement
     *
     * Turns the path into the movement script ([PathArrow.getMovementScriptFromPath]), applies it
     * to the active unit's data ([unitApplyWorkingMovementScript]: sets the destination in the
     * action data) and gives it to the MU ([Hooks.setAutoMuMoveScript]).
     */
    public fun playerPhaseDisplayUnitMovement() {
        val arrow = checkNotNull(pathArrow) { "PlayerPhase_DisplayUnitMovement needs a PathArrow" }
        val unit = active.unit
        arrow.getMovementScriptFromPath()
        unitApplyWorkingMovementScript(
            unit, units.xPos(unit), units.yPos(unit), arrow.workingMovementScript,
        )
        hooks.setAutoMuMoveScript(arrow.workingMovementScript)
    }

    /**
     * Ported from src/playerphase.c: PlayerPhase_WaitForUnitMovement
     *
     * Goes on once no MU moves any more ([Hooks.muExistsActive]).
     */
    public fun playerPhaseWaitForUnitMovement(proc: Proc) {
        if (!hooks.muExistsActive()) engine.breakProc(proc)
    }

    /**
     * Ported from src/playerphase.c: PlayerPhase_ApplyUnitMovement
     *
     * Puts the active unit on the destination of the action data, finalizes the movement and, for
     * a unit that has not moved and not acted (`taken_action`), stores the cost of the move in
     * `moveCount`. Then `StartAfterUnitMovedEvent` ([Hooks.startAfterUnitMovedEvent], a
     * stand-in until M6-E) and the unit action menu ([Hooks.startUnitActionMenu]) unless the
     * move was trapped; breaks (both paths of the C end that way). `ResetTextFont` is done by the
     * wiring of [Hooks.startUnitActionMenu].
     */
    public fun playerPhaseApplyUnitMovement(proc: Proc) {
        val unit = active.unit
        val a = active.action
        units.setPosition(unit, a.xMove, a.yMove)
        unitFinalizeMovement(unit)
        if ((units.state(unit) and US_HAS_MOVED) == 0 && a.unitActionType == 0 &&
            bm.takenAction == 0
        ) {
            a.moveCount = map.movement[a.yMove, a.xMove]
        }
        hooks.startAfterUnitMovedEvent()
        if (a.unitActionType != UNIT_ACTION_TRAPPED) {
            hooks.startUnitActionMenu(bm.cursorTargetX - bm.cameraX)
        }
        engine.breakProc(proc)
    }

    /**
     * Transcribed from src/bmidoten.c: UnitApplyWorkingMovementScript (not verified: the trap
     * and hidden-unit branches, no capture walks onto one)
     *
     * Follows [script] from ([x0], [y0]), leaving the last tile in the action's xMove and yMove. A
     * walker that is no thief, flyer or assassin stops at a hidden trap (script cut, action
     * `UNIT_ACTION_TRAPPED`); a hidden unit on the way turns the step into a bump.
     */
    private fun unitApplyWorkingMovementScript(unit: Int, x0: Int, y0: Int, script: IntArray) {
        var x = x0
        var y = y0
        var it = 0
        val a = active.action
        while (true) {
            a.xMove = x
            a.yMove = y
            when (script[it]) {
                Mu.MOVE_CMD_MOVE_UP -> y--
                Mu.MOVE_CMD_MOVE_DOWN -> y++
                Mu.MOVE_CMD_MOVE_LEFT -> x--
                Mu.MOVE_CMD_MOVE_RIGHT -> x++
            }
            if ((units.cAttributes(unit) and (CA_THIEF or CA_FLYER or CA_ASSASSIN)) == 0) {
                if ((map.hidden[y, x] and HIDDEN_BIT_TRAP) != 0) {
                    script[++it] = Mu.MOVE_CMD_HALT
                    a.unitActionType = UNIT_ACTION_TRAPPED
                    a.xMove = x
                    a.yMove = y
                    return
                }
            }
            if ((map.hidden[y, x] and HIDDEN_BIT_UNIT) != 0) {
                script[it++] = Mu.MOVE_CMD_BUMP
                script[it] = Mu.MOVE_CMD_HALT
                a.unitActionType = UNIT_ACTION_TRAPPED
                return
            }
            if (script[it] == Mu.MOVE_CMD_HALT) break
            it++
        }
    }

    /**
     * Ported from src/playerphase.c: PlayerPhase_BackToMove
     *
     * B on the unit menu: puts the active unit back on its move origin, finalizes the movement
     * (a rescuer's rescuee follows; the ballista case is not ported), shows it, refreshes the
     * maps, redraws, begins the action again ([unitBeginAction], or the canto action when the
     * unit has moved: transcribed, not verified, no capture cancels after a canto), swaps the MU
     * for a fresh one ([Hooks.hideUnitSprite], [Hooks.endAllMus], [Hooks.startMu]) and jumps to
     * label 1.
     */
    public fun playerPhaseBackToMove(proc: Proc) {
        val unit = active.unit
        units.setPosition(unit, active.moveOriginX, active.moveOriginY)
        unitFinalizeMovement(unit)
        units.setState(unit, units.state(unit) and US_HIDDEN.inv())
        entityBmMaps.refreshEntityBmMaps()
        render.renderBmMap()
        hooks.refreshUnitSprites.invoke(proc)
        if ((units.state(unit) and US_HAS_MOVED) == 0) {
            unitBeginAction(unit)
        } else {
            unitBeginCantoAction(unit)
        }
        hooks.hideUnitSprite(unit)
        hooks.endAllMus.invoke(proc)
        hooks.startMu(unit)
        engine.goto(proc, 1)
    }

    /**
     * Transcribed from src/bmunit.c: UnitBeginCantoAction (not verified: no capture begins a
     *   canto action)
     *
     * As [unitBeginAction] without the subject and the move count.
     */
    private fun unitBeginCantoAction(unit: Int) {
        active.unit = unit
        active.unitId = units.index(unit) and 0xFF
        active.moveOriginX = units.xPos(unit)
        active.moveOriginY = units.yPos(unit)
        active.action.unitActionType = 0
        bm.takenAction = 0
        hooks.resetBattleScratch()
        units.setState(unit, units.state(unit) or US_HIDDEN)
        map.unit[units.yPos(unit), units.xPos(unit)] = 0
    }

    /**
     * Transcribed from src/bmunit.c: UnitFinalizeMovement (not verified: rescuer and ballista,
     * no capture has either)
     */
    private fun unitFinalizeMovement(unit: Int) {
        val state = units.state(unit)
        if ((state and US_RESCUING) != 0) {
            val rescuee = units.bytes[units.unitOffset(unit) + 0x1B].toInt() and 0xFF
            units.setPosition(rescuee, units.xPos(unit), units.yPos(unit))
        }
        if ((state and UnitLoader.US_IN_BALLISTA) != 0) {
            throw UnsupportedOperationException("UnitFinalizeMovement: ballista not ported")
        }
    }

    /**
     * Ported from src/bm.c: HandleMapCursorInput
     *
     * Moves the cursor one tile by the D-pad bits of [dirKeys] (opposite directions cancel):
     * updates `playerCursor`, `cursorPrevious` and `cursorTarget` per axis where the new position
     * is on the map. With the movement range shown (`gameStateBits` bit 1) a step off the
     * reachable area only counts when the D-pad is a new press. The cursor sound plays on a move,
     * every other call (bit 2 toggles).
     */
    public fun handleMapCursorInput(dirKeys: Int) {
        val dir = (dirKeys shr 4) and 0xF
        val dx = DIR_X[dir]
        val dy = DIR_Y[dir]
        val newX = bm.playerCursorX + dx
        val newY = bm.playerCursorY + dy

        if ((bm.gameStateBits and (1 shl 1)) != 0) {
            if (map.movement[bm.playerCursorY, bm.playerCursorX] < MAP_MOVEMENT_MAX) {
                if (map.movement[newY, newX] >= MAP_MOVEMENT_MAX) {
                    if ((dirKeys and DPAD_ANY) != (keys.newKeys and DPAD_ANY)) return
                }
            }
        }
        if (newX >= 0 && newX < map.sizeX) {
            bm.cursorTargetX = s16(bm.cursorTargetX + dx * 16)
            bm.cursorPreviousX = bm.playerCursorX
            bm.playerCursorX = newX
        }
        if (newY >= 0 && newY < map.sizeY) {
            bm.cursorTargetY = s16(bm.cursorTargetY + dy * 16)
            bm.cursorPreviousY = bm.playerCursorY
            bm.playerCursorY = newY
        }
        if ((bm.gameStateBits and (1 shl 2)) == 0) {
            if (bm.playerCursorX == bm.cursorPreviousX && bm.playerCursorY == bm.cursorPreviousY) {
                return
            }
            hooks.cursorSound.play(SONG_65)
            bm.gameStateBits = bm.gameStateBits or (1 shl 2)
        } else {
            bm.gameStateBits = bm.gameStateBits and (1 shl 2).inv()
        }
    }

    /**
     * Ported from src/bm.c: HandleMoveMapCursor
     *
     * Moves the display cursor [step] pixels per axis towards the cursor target.
     */
    public fun handleMoveMapCursor(step: Int) {
        if (bm.playerCursorDisplayX < bm.cursorTargetX) {
            bm.playerCursorDisplayX = s16(bm.playerCursorDisplayX + step)
        }
        if (bm.playerCursorDisplayX > bm.cursorTargetX) {
            bm.playerCursorDisplayX = s16(bm.playerCursorDisplayX - step)
        }
        if (bm.playerCursorDisplayY < bm.cursorTargetY) {
            bm.playerCursorDisplayY = s16(bm.playerCursorDisplayY + step)
        }
        if (bm.playerCursorDisplayY > bm.cursorTargetY) {
            bm.playerCursorDisplayY = s16(bm.playerCursorDisplayY - step)
        }
    }

    /**
     * Ported from src/bm.c: HandleMoveCameraWithMapCursor
     *
     * Scrolls the camera [step] pixels per axis when the display cursor is outside the scroll
     * margins (clamping at 0 and `cameraMax`), remembering the direction and the offset into the
     * tile in `unk32`/`unk34`/`unk36`/`unk37`; when nothing scrolled it finishes the move to the
     * next 16 pixel boundary.
     *
     * Verified on the scroll and clamp paths; the tile-finish branch and the exact `cameraMax`
     * boundary of the x clamp are not reached by any reference run (they need a direction
     * reversal or a camera stop mid-tile).
     */
    public fun handleMoveCameraWithMapCursor(step: Int) {
        var isUpdated = false
        val xc = bm.playerCursorDisplayX
        val yc = bm.playerCursorDisplayY

        if (bm.cameraX + Camera.MARGIN_LEFT > xc) {
            if (xc - Camera.MARGIN_LEFT < 0) {
                bm.cameraX = 0
            } else {
                isUpdated = true
                bm.cameraX = s16(bm.cameraX - step)
                bm.unk36 = s8(-step)
                bm.unk32 = s16(bm.cameraX and 0xF)
            }
        }
        if (bm.cameraX + Camera.MARGIN_RIGHT < xc) {
            if (xc - Camera.MARGIN_RIGHT > bm.cameraMaxX) {
                bm.cameraX = bm.cameraMaxX
            } else {
                isUpdated = true
                bm.cameraX = s16(bm.cameraX + step)
                bm.unk36 = s8(step)
                bm.unk32 = s16(bm.cameraX and 0xF)
            }
        }
        if (bm.cameraY + Camera.MARGIN_TOP > yc) {
            if (yc - Camera.MARGIN_TOP < 0) {
                bm.cameraY = 0
            } else {
                isUpdated = true
                bm.cameraY = s16(bm.cameraY - step)
                bm.unk37 = s8(-step)
                bm.unk34 = s16(bm.cameraY and 0xF)
            }
        }
        if (bm.cameraY + Camera.MARGIN_BOTTOM < yc) {
            if (yc - Camera.MARGIN_BOTTOM > bm.cameraMaxY) {
                bm.cameraY = bm.cameraMaxY
            } else {
                isUpdated = true
                bm.cameraY = s16(bm.cameraY + step)
                bm.unk37 = s8(step)
                bm.unk34 = s16(bm.cameraY and 0xF)
            }
        }
        if (!isUpdated) {
            if (bm.unk32 != 0) {
                bm.unk32 = s16((bm.unk32 + bm.unk36) and 0xF)
                bm.cameraX = s16(bm.cameraX + bm.unk36)
            }
            if (bm.unk34 != 0) {
                bm.unk34 = s16((bm.unk34 + bm.unk37) and 0xF)
                bm.cameraY = s16(bm.cameraY + bm.unk37)
            }
        }
    }

    /** Constants. */
    public companion object {
        /** `PROC_MARK_MAIN`. */
        public const val PROC_MARK_MAIN: Int = 2

        /** `MAP_MOVEMENT_MAX` (bmmap.h). */
        public const val MAP_MOVEMENT_MAX: Int = 120

        /** `SONG_65`, the cursor move sound. */
        public const val SONG_65: Int = 0x65

        /** `PLAYER_SELECT_NOUNIT`. */
        public const val SELECT_NOUNIT: Int = 0

        /** `PLAYER_SELECT_TURNENDED`. */
        public const val SELECT_TURNENDED: Int = 1

        /** `PLAYER_SELECT_CONTROL`. */
        public const val SELECT_CONTROL: Int = 2

        /** `PLAYER_SELECT_NOCONTROL`. */
        public const val SELECT_NOCONTROL: Int = 3

        /** `PLAYER_SELECT_4` (a character that cannot be moved on the preparation screen). */
        public const val SELECT_4: Int = 4

        /** `LIMITVIEW_BLUE`. */
        public const val LIMITVIEW_BLUE: Int = 1 shl 0

        /** `LIMITVIEW_RED`. */
        public const val LIMITVIEW_RED: Int = 1 shl 1

        /** `LIMITVIEW_GREEN`. */
        public const val LIMITVIEW_GREEN: Int = 1 shl 2

        /** `SONG_69`, the sound of selecting a unit. */
        public const val SONG_69: Int = 0x69

        /** `BM_FLAG_1`: the movement range shows. */
        public const val BM_FLAG_1: Int = 1 shl 1

        /** `BM_FLAG_PREPSCREEN`. */
        public const val BM_FLAG_PREPSCREEN: Int = 1 shl 4

        /** `SONG_6C`, the sound of a refused action. */
        public const val SONG_6C: Int = 0x6C

        /** `SONG_SE_SYS_WINDOW_CANSEL1`. */
        public const val SONG_CANCEL: Int = 0x6B

        /** `BM_FLAG_3`. */
        public const val BM_FLAG_3: Int = 1 shl 3

        private const val TRAP_BALLISTA = 1
        private const val ACT_NONE = -1
        private const val ACT_FAIL = 0
        private const val ACT_MOVE = 1
        private const val ACT_CANCEL = 2
        private const val ACT_INFOSCREEN = 3
        private const val ACT_RESET_CURSOR = 4
        private const val ACT_EVENT = 5
        private const val ACT_SWAP_RANGES = 6
        private const val US_HIDDEN = 1 shl 0
        private const val UNIT_ACTION_WAIT_TYPE = 1
        private const val UNIT_ACTION_TRAPPED = 0x1E
        private const val HIDDEN_BIT_UNIT = 1 shl 0
        private const val HIDDEN_BIT_TRAP = 1 shl 1
        private const val CA_THIEF = 1 shl 3
        private const val CA_FLYER = (1 shl 11) or (1 shl 12)
        private const val CA_ASSASSIN = 1 shl 25
        private const val US_HAS_MOVED = 1 shl 6
        private const val US_CANTOING = US_HAS_MOVED
        private const val US_UNSELECTABLE = 1 shl 1
        private const val US_DEAD = 1 shl 2
        private const val US_BIT16 = 1 shl 16
        private const val CA_CANTO = 1 shl 1
        private const val UNIT_ACTION_COMBAT = 0x02
        private const val UNIT_ACTION_STAFF = 0x03
        private const val UNIT_ACTION_TAKE = 0x0B
        private const val UNIT_ACTION_GIVE = 0x0C
        private const val UNIT_ACTION_TRADED = 0x1B
        private const val UNIT_ACTION_TRADED_SUPPLY = 0x1C
        private const val UNIT_ACTION_TRADED_1D = 0x1D
        private const val UNIT_ACTION_FORCE_WAIT = 0x1F
        private const val UNIT_ACTION_RIDE_BALLISTA = 0x21
        private const val UNIT_ACTION_EXIT_BALLISTA = 0x22
        private const val BM_TAKEN_ACTION_TAKE = 1 shl 0
        private const val BM_TAKEN_ACTION_TRADE = 1 shl 1
        private const val BM_TAKEN_ACTION_SUPPLY = 1 shl 2
        private const val BM_TAKEN_ACTION_BALLISTA = 1 shl 3
        private const val SUSPEND_POINT_DURINGACTION = 1
        private const val SAVE_ID_SUSPEND = 3
        private const val ITEM_HEROCREST = 0x64
        private const val ITEM_KNIGHTCREST = 0x65
        private const val ITEM_ORIONSBOLT = 0x66
        private const val ITEM_ELYSIANWHIP = 0x67
        private const val ITEM_GUIDINGRING = 0x68
        private const val ITEM_MASTERSEAL = 0x88
        private const val ITEM_HEAVENSEAL = 0x8A
        private const val ITEM_OCEANSEAL = 0x97
        private const val ITEM_LUNARBRACE = 0x98
        private const val ITEM_SOLARBRACE = 0x99
        private const val ITEM_UNK_C1 = 0xC1
        private const val US_RESCUING = 1 shl 4
        private const val KEY_SELECT = 1 shl 2
        private const val KEY_START = KeyStatus.START_BUTTON
        private const val KEY_A = 1 shl 0
        private const val KEY_B = 1 shl 1
        private const val KEY_R = 1 shl 8
        private const val KEY_L = 1 shl 9
        private const val DPAD_ANY = 0xF0

        /** `sDirKeysToOffsetLut` x offsets by the D-pad nibble (right, left, up, down). */
        private val DIR_X = intArrayOf(0, 1, -1, 0, 0, 1, -1, 0, 0, 1, -1, 0, 0, 0, 0, 0)

        /** `sDirKeysToOffsetLut` y offsets by the D-pad nibble. */
        private val DIR_Y = intArrayOf(0, 0, 0, 0, -1, -1, -1, 0, 1, 1, 1, 0, 0, 0, 0, 0)
    }
}
