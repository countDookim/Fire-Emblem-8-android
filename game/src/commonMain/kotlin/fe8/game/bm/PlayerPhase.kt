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
 * (movement, action) are stand-ins until M6-B. [Hooks.unitSelectEnabled] is false by default, so
 * the A button does nothing in the app until PP-17 wires the rest of the select flow. A
 * `PROC_REPEAT` with a default hook never leaves it. Nothing allocates per frame.
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
) {
    /** The unit sprite hover controller ([UnitSpriteHover]). */
    public val hover: UnitSpriteHover = UnitSpriteHover(bm, map, units)

    init {
        hooks.resetUnitSpriteHover = ProcFunc { hover.resetUnitSpriteHover() }
        hooks.unitSpriteHoverUpdate = ProcFunc { hover.unitSpriteHoverUpdate() }
        hooks.isUnitSpriteHoverEnabledAt = { x, y -> hover.isUnitSpriteHoverEnabledAt(x, y) }
    }

    /** `PlaySoundEffect` for the cursor sound (a stand-in until M6-B wires the sound wrapper). */
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
        /** Stand-in for `PlayerPhase_Suspend` (writes the suspend save) until M6-B. */
        public var suspend: ProcFunc = ProcFunc {}

        /** `RefreshUnitSprites`. Default no-op; `Fe8Boot` sets the `UnitSpriteHandles` port. */
        public var refreshUnitSprites: ProcFunc = ProcFunc {}

        /** Stand-in for `StartPlayerPhaseStartTutorialEvent` until M6-B. */
        public var startTutorialEvent: ProcFunc = ProcFunc {}

        /** `StartMapSongBgm`. Default no-op; `Fe8Boot` sets `MapMusic.startMapSongBgm`. */
        public var startMapSongBgm: ProcFunc = ProcFunc {}

        /** Stand-in for `StartPlayerPhaseSideWindows` until M6-B. */
        public var startSideWindows: ProcFunc = ProcFunc {}

        /** Stand-in for `EndPlayerPhaseSideWindows` until M6-B. */
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
         * Stand-in until PP-12 for the L button branch of `PlayerPhase_MainIdle`
         * (`TrySwitchViewedUnit` and the sound).
         */
        public var lButton: ProcFunc = ProcFunc {}

        /**
         * Stand-in until PP-12 for the whole R button branch of `PlayerPhase_MainIdle` (the stat
         * screen, `Proc_Goto(proc, 5)`); runs when R is new and the cursor tile holds a unit;
         * returns true if it left `PlayerPhase_MainIdle`.
         */
        public var rButton: ProcBoolFunc = ProcBoolFunc { false }

        /**
         * Stand-in until PP-12 for the START button branch of `PlayerPhase_MainIdle` (the
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

        /** Stand-in for `PlaySoundEffect(SONG_65)`, the cursor move sound, until M6-B. */
        public var cursorSound: SoundEffect = SoundEffect {}

        /**
         * `DoesBMXFADEExist` (bmxfade.c, `Proc_Find(sProcScr_BMXFADE)`): stand-in until M6-B,
         * default false. No capture of the select window has the fade.
         */
        public var doesBmxFadeExist: ProcBoolFunc = ProcBoolFunc { false }

        /** `ShowUnitSprite(unit)` for unit id [unit]; stand-in until PP-12. */
        public var showUnitSprite: (unit: Int) -> Unit = {}

        /**
         * Stand-in until PP-12 for `StartOrphanMenuAdjusted(&gMapMenuDef, ...)` and
         * `sub_80832CC` (the map menu); gets `gBmSt.cursorTarget.x - gBmSt.camera.x`.
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

        /** `PlaySoundEffect`; a stand-in until M6-B wires the sound wrapper. */
        public var playSoundEffect: SoundEffect = SoundEffect {}

        /** Stand-in until M6-B for `MuExists` (`Proc_Find(ProcScr_Mu) != 0`); default false. */
        public var muExists: ProcBoolFunc = ProcBoolFunc { false }

        /** Stand-in until M6-B for `StartMu(unit)` (unit id [unit]). */
        public var startMu: (unit: Int) -> Unit = {}

        /** Stand-in until M6-B for `HideUnitSprite(unit)` (unit id [unit]). */
        public var hideUnitSprite: (unit: Int) -> Unit = {}

        /** Stand-in until M6-B for `SetAutoMuDefaultFacing`. */
        public var setAutoMuDefaultFacing: ProcFunc = ProcFunc {}

        /** Stand-in until M6-B for `TryCallSelectEvents` (select events of the active unit). */
        public var tryCallSelectEvents: ProcFunc = ProcFunc {}

        /**
         * Stand-in until PP-17 for the part of `DisplayUnitEffectRange` that runs while the unit
         * has not moved (`AttackRange.generateUnitCompleteAttackRange` is ported, but
         * `GetUnitWeaponUsabilityBits` and `GenerateMagicSealMap` are not, so `Fe8Boot` cannot
         * wire it yet; the select test backs the weapon-only case with it):
         * `BmMapFill(gBmMapOther, 0)`, `GenerateMagicSealMap` for a unit with magic ranks,
         * `BmMapFill(gBmMapRange, 0)` and the attack or staff range generation for the weapon
         * usability of unit id [unit] (`gBmSt.swapActionRangeCount` selects between both for a
         * unit with both). Returns the `LIMITVIEW_*` flags to display; the default
         * does nothing and keeps `LIMITVIEW_BLUE`.
         */
        public var generateAttackRange: (unit: Int) -> Int = { LIMITVIEW_BLUE }

        /**
         * `DisplayMoveRangeGraphics(flags)`: stand-in until PP-17 wires
         * [MoveLimitView.displayMoveRangeGraphics] (ported and verified, PP-4) into `Fe8Boot`.
         */
        public var displayMoveRangeGraphics: (flags: Int) -> Unit = {}

        /**
         * Stand-in until PP-5 for `PathArrowDisp_Init(mode)`: 0 when the cursor is on the active
         * unit, 1 otherwise.
         */
        public var pathArrowDispInit: (mode: Int) -> Unit = {}

        /** Stand-in for `PlayerPhase_RangeDisplayIdle` (a `PROC_REPEAT`) until PP-7. */
        public var rangeDisplayIdle: ProcFunc = ProcFunc {}

        /** Stand-in for `PlayerPhase_DisplayUnitMovement` until M6-B. */
        public var displayUnitMovement: ProcFunc = ProcFunc {}

        /** Stand-in for `PlayerPhase_WaitForUnitMovement` (a `PROC_REPEAT`) until M6-B. */
        public var waitForUnitMovement: ProcFunc = ProcFunc {}

        /** Stand-in for `PlayerPhase_ApplyUnitMovement` (a `PROC_REPEAT`) until M6-B. */
        public var applyUnitMovement: ProcFunc = ProcFunc {}

        /** Stand-in for `PlayerPhase_PrepareAction` (a `PROC_CALL_2`) until M6-B. */
        public var prepareAction: ProcBoolFunc = ProcBoolFunc { true }

        /** Stand-in for `ApplyUnitAction` (a `PROC_CALL_2`) until M6-B. */
        public var applyUnitAction: ProcBoolFunc = ProcBoolFunc { true }

        /** Stand-in for `HandlePostActionTraps` (a `PROC_CALL_2`) until M6-H. */
        public var handlePostActionTraps: ProcBoolFunc = ProcBoolFunc { true }

        /** Stand-in for `RunPotentialWaitEvents` (a `PROC_CALL_2`) until M6-B. */
        public var runPotentialWaitEvents: ProcBoolFunc = ProcBoolFunc { true }

        /** Stand-in for `EnsureCameraOntoActiveUnitPosition` (a `PROC_CALL_2`) until M6-B. */
        public var ensureCameraOntoActiveUnitPosition: ProcBoolFunc = ProcBoolFunc { true }

        /** Stand-in for `PlayerPhase_FinishAction` until M6-B. */
        public var finishAction: ProcFunc = ProcFunc {}

        /** Stand-in for `PlayerPhase_ResumeRangeDisplay` until M6-B. */
        public var resumeRangeDisplay: ProcFunc = ProcFunc {}

        /** Stand-in for `EndAllMus` until M6-B. */
        public var endAllMus: ProcFunc = ProcFunc {}

        /** Stand-in for `DisplayActiveUnitEffectRange` (label 11) until M6-B. */
        public var displayActiveUnitEffectRange: ProcFunc = ProcFunc {}

        /** Stand-in for `PlayerPhase_DisplayDangerZone` until M6-B. */
        public var displayDangerZone: ProcFunc = ProcFunc {}

        /** Stand-in for `PlayerPhase_ReReadGameSaveGfx` until M6-B. */
        public var reReadGameSaveGfx: ProcFunc = ProcFunc {}

        /** Stand-in for `gProcScr_ADJUSTSFROMXI`, a script that ends at once until M6-B. */
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
     * Transcribed from src/playerphase.c: PlayerPhase_HandleAutoEnd (not verified: no test checks
     *   it against a trace yet)
     *
     * Jumps [proc] to label 3 (end of the phase) if auto end turns is on and no unit of the active
     * faction can act.
     */
    public fun playerPhaseHandleAutoEnd(proc: Proc) {
        if (playSt.disableAutoEndTurns == 0 && units.getPhaseAbleUnitCount(playSt.faction) == 0) {
            engine.goto(proc, 3)
        }
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
     * Stand-ins until PP-12: the L, R and START branches ([Hooks.lButton], [Hooks.rButton],
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
     * or staff range ([Hooks.generateAttackRange], a stand-in until PP-17); then displays the range
     * squares ([Hooks.displayMoveRangeGraphics], a stand-in until PP-4) with the flags
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

        private const val US_HIDDEN = 1 shl 0
        private const val US_HAS_MOVED = 1 shl 6
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
