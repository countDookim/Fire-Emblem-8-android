package fe8.game.bm

import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcBoolFunc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.call2
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.whileExists
import gbafe.proc.ProcScripts.whileTrue
import gbafe.proc.ProcScripts.yield

/** `struct CpPerformProc` past `PROC_HEADER` (src/cp_perform.c): what the steps keep. */
public class CpPerformState {
    /** `isUnitVisible` (0x31): false when fog hides the walk, so no MU is started. */
    public var isUnitVisible: Boolean = false

    /** `unk_30` (0x30): frames [CpPerform.cpPerformWaitAction] has run. */
    public var unk30: Int = 0

    /** `func` (0x2C): the action step; only `AiDummyAction` is written (always done). */
    public var funcIsDummy: Boolean = false
}

/** `struct UnkProcA` past `PROC_HEADER` (src/cp_perform.c): the AI target cursor. */
public class AiTargetCursorState {
    /** `unk_2C`: map pixel x of the cursor. */
    public var x: Int = 0

    /** `unk_30`: map pixel y of the cursor. */
    public var y: Int = 0

    /** `unk_58`: the `PutMapCursor` kind. */
    public var kind: Int = 0

    /** `unk_64` (s16): frames the cursor has been shown. */
    public var timer: Int = 0
}

/**
 * The AI perform proc of src/cp_perform.c, movement half: `gProcScr_CpPerform` (ROM 0x085A8024)
 * with the camera onto the unit, the unit's walk (an MU along the best path to the decided tile),
 * the camera onto the target and the target cursor `gProcScr_AiTargetCursor` (0x085A8004). The
 * action half (EP-9: a combat or no action) and `CpPerform_EquipBest` (EP-9b, [CpEquip]) are
 * ported too. [start] is the value of [CpDecide.Hooks.startCpPerform].
 *
 * The unit walk reuses the player phase's ported code ([PlayerPhase.unitBeginAction],
 * [PlayerPhase.unitApplyWorkingMovementScript], [PlayerPhase.handlePostActionTraps],
 * [PlayerPhase.runPotentialWaitEvents]), [MovementMap], [generateBestMovementScript] and
 * [CamMove]. Nothing allocates per frame.
 */
public class CpPerform(
    private val engine: ProcEngine,
    private val bm: BmSt,
    private val playSt: PlaySt,
    private val units: UnitTable,
    /** `EquipUnitItemSlot`. */
    private val items: ItemInfo,
    /** `RefreshEntityBmMaps`. */
    private val entityMaps: EntityBmMaps,
    /** `gActiveUnit` and `gActionData`. */
    private val active: ActiveUnit,
    /** `gAiDecision` ([CpDecide.decision]). */
    private val decision: AiDecision,
    /** `gBmMapMovement`, `gBmMapFog`, `gBmMapSize`. */
    private val map: BmMap,
    private val moves: MovementMap,
    private val camMove: CamMove,
    private val mu: Mu,
    /** The player phase whose ported unit functions the walk reuses. */
    private val phase: PlayerPhase,
    /** `gKeyStatusPtr`. */
    private val keys: KeyStatus,
    /** The tie break of `GenerateBestMovementScript`. */
    private val rng: RngService,
    /** `gWorkingMovementScript`. */
    private val workingMovementScript: IntArray,
    /** `CpPerform_EquipBest` and its helpers. */
    private val equip: CpEquip,
) {
    /** Services outside this file; a null hook does nothing. */
    public class Hooks {
        /** `Proc_Find(gMusicProc3Script) != NULL`; null counts as false. */
        public var musicProc3Exists: AiTry? = null

        /** `StartMapSongBgm` ([MapMusic.startMapSongBgm]). */
        public var startMapSongBgm: AiStep? = null

        /** `HideUnitSprite(unit)` ([UnitSpriteHandles.hideUnitSprite]) for a unit id. */
        public var hideUnitSprite: AiIntStep? = null

        /** `StartMu(unit)` for a unit id. */
        public var startMu: AiIntStep? = null

        /** `PutMapCursor(x, y, kind)` ([MapCursor.put]). */
        public var putMapCursor: PlayerPhase.PutMapCursor? = null

        /**
         * Stand-in for `RideBallista(gActiveUnit)` (bmunit.c; EP-12: the ballista branch of
         * `CpPerform_MoveCameraOntoTarget` is never entered). Gets the unit id.
         */
        public var rideBallista: AiIntStep? = null

        /** `EndAllMus` ([Mu.endAllMus]). */
        public var endAllMus: AiStep? = null

        /** `UpdateAllPhaseHealingAIStatus` ([CpUtility.updateAllPhaseHealingAiStatus]). */
        public var updateAllPhaseHealingAIStatus: AiStep? = null

        /** `RenderBmMapOnBg2` ([BmMapRender.renderBmMapOnBg2]). */
        public var renderBmMapOnBg2: AiStep? = null

        /** `RenderBmMap` ([BmMapRender.renderBmMap]). */
        public var renderBmMap: AiStep? = null

        /** `NewBMXFADE(flag)` ([BmxFade.newBmxFade]). */
        public var newBmxFade: AiIntStep? = null

        /** `ShowUnitSprite(unit)` ([UnitSpriteHandles.showUnitSprite]) for a unit id. */
        public var showUnitSprite: AiIntStep? = null

        /** `RefreshUnitSprites` (display; EP-w). */
        public var refreshUnitSprites: AiStep? = null
    }

    /** Services outside this file. */
    public val hooks: Hooks = Hooks()

    private val updateMapMusicFn = ProcFunc { cpPerformUpdateMapMusic() }
    private val moveCameraOntoUnitFn = ProcFunc { cpPerformMoveCameraOntoUnit(it) }
    private val beginUnitMovementFn = ProcFunc { cpPerformBeginUnitMovement(it) }
    private val moveCameraOntoTargetFn = ProcFunc { cpPerformMoveCameraOntoTarget(it) }
    private val performActionFn = ProcFunc { cpPerformPerformAction(it) }
    private val waitActionFn = ProcFunc { cpPerformWaitAction(it) }
    private val cleanupFn = ProcFunc { cpPerformCleanup(it) }
    private val equipBestFn = ProcFunc { equip.cpPerformEquipBest() }
    private val targetCursorFn = ProcFunc { aiTargetCursorMain(it) }
    private val muActiveFn = ProcBoolFunc { mu.muExistsActive() }
    private val postActionTrapsFn = ProcBoolFunc { phase.handlePostActionTraps(it) }
    private val waitEventsFn = ProcBoolFunc { phase.runPotentialWaitEvents() }

    /** `gProcScr_AiTargetCursor`. */
    public val targetCursorScript: ProcScript = ProcScript(
        "gProcScr_AiTargetCursor",
        listOf(
            yield(),
            whileExists { camMove.script },
            repeat(targetCursorFn),
            end(),
        ),
        ::AiTargetCursorState,
    )

    /** `gProcScr_CpPerform`. */
    public val script: ProcScript = ProcScript(
        "gProcScr_CpPerform",
        listOf(
            name("E_CPPERFORM"),
            call(updateMapMusicFn),
            call(moveCameraOntoUnitFn),
            yield(),
            call(beginUnitMovementFn),
            whileTrue(muActiveFn),
            call(moveCameraOntoTargetFn),
            yield(),
            call(performActionFn),
            repeat(waitActionFn),
            call2(postActionTrapsFn),
            call2(waitEventsFn),
            call(cleanupFn),
            call(equipBestFn),
            label(1),
            end(),
        ),
        ::CpPerformState,
    )

    /** `Proc_StartBlocking(gProcScr_CpPerform, parent)`: [CpDecide.Hooks.startCpPerform]. */
    public val start: AiProcStep = AiProcStep { engine.startBlocking(script, it) }

    /**
     * Transcribed from src/cp_perform.c: CpPerform_UpdateMapMusic (not verified: the music
     * restart shows in no captured value, the sound registers are not watched)
     *
     * Restarts the map music unless a delayed song proc exists.
     */
    public fun cpPerformUpdateMapMusic() {
        if (hooks.musicProc3Exists?.run() != true) hooks.startMapSongBgm?.run()
    }

    /**
     * Transcribed from src/cp_perform.c: CpPerform_MoveCameraOntoUnit (not verified: the camera
     * call shows in no captured value; the Prologue map fits the screen, so `gBmSt.camera` is
     * (0, 0) in every window and a camera move onto another tile survives the tests)
     *
     * Marks the unit's walk visible and starts the camera move onto the active unit
     * ([CamMove.ensureCameraOntoPosition]). In fog on the red phase the unit is invisible, and
     * the camera stays, when neither its tile nor its destination is lit; a pillage then moves
     * the camera to the destination. Seen in the captures: the visible flag (without it no MU
     * walks). Not seen: the camera call and the fog branch (no fog).
     */
    public fun cpPerformMoveCameraOntoUnit(proc: Proc) {
        val state = proc.state as CpPerformState
        state.isUnitVisible = true
        val unit = active.unit
        val x = units.xPos(unit)
        val y = units.yPos(unit)
        if (playSt.chapterVisionRange != 0 && playSt.faction == FACTION_RED) {
            if (map.fog[y, x] != 0 || map.fog[decision.yMove, decision.xMove] != 0) {
                camMove.ensureCameraOntoPosition(proc, x, y)
            } else {
                state.isUnitVisible = false
                if (decision.actionId == AI_ACTION_PILLAGE) {
                    camMove.ensureCameraOntoPosition(proc, decision.xMove, decision.yMove)
                }
            }
        } else {
            camMove.ensureCameraOntoPosition(proc, x, y)
        }
    }

    /**
     * Ported from src/cp_perform.c: CpPerform_BeginUnitMovement
     *
     * Begins the active unit's action, hides its sprite, floods its movement map, builds the
     * best movement script to the decided tile ([generateBestMovementScript]) and applies it to
     * the action data ([PlayerPhase.unitApplyWorkingMovementScript]: the destination, or the
     * tile of a trap), which becomes the decision's move tile. A visible walk starts the MU with
     * the script. Verified: the action reset, the movement map, the script and the destination
     * written back, the MU walk. Not seen: the trap and hidden-unit cases of the script, the
     * sprite hide ([Hooks.hideUnitSprite]) and the default facing
     * ([Mu.setAutoMuDefaultFacing]; the script sets the facing before the first frame is
     * shown), a walk that is not visible (no fog).
     */
    public fun cpPerformBeginUnitMovement(proc: Proc) {
        val state = proc.state as CpPerformState
        val unit = active.unit
        phase.unitBeginAction(unit)
        hooks.hideUnitSprite?.run(unit)
        moves.generateUnitMovementMap(unit)
        generateBestMovementScript(
            map.movement, map.sizeX, map.sizeY, rng, decision.xMove, decision.yMove,
            workingMovementScript,
        )
        phase.unitApplyWorkingMovementScript(
            unit, units.xPos(unit), units.yPos(unit), workingMovementScript,
        )
        decision.xMove = active.action.xMove
        decision.yMove = active.action.yMove
        if (state.isUnitVisible) {
            hooks.startMu?.run(unit)
            mu.setAutoMuDefaultFacing()
            mu.setAutoMuMoveScript(workingMovementScript)
        }
    }

    /**
     * Ported from src/cp_perform.c: CpPerform_MoveCameraOntoTarget
     *
     * Finds the tile of the action's target (none for a move only, an escape, pillage, item use,
     * ballista, Dread Fighter or pick action, and none for a trapped unit), moves the camera onto
     * it and starts the target cursor there. Verified: an attack on a unit (cursor at the
     * target's tile, kind 2) and a move without action (no cursor). Not seen: the camera call
     * (see [cpPerformMoveCameraOntoUnit]), the trapped return, the other actions, and the
     * ballista start of an attack with item slot -1 (stand-in [Hooks.rideBallista], EP-12).
     */
    public fun cpPerformMoveCameraOntoTarget(proc: Proc) {
        if (active.action.unitActionType == UNIT_ACTION_TRAPPED) return
        var x = 0
        var y = 0
        when (decision.actionId) {
            AI_ACTION_COMBAT -> {
                if (decision.targetId == 0) {
                    x = decision.xTarget
                    y = decision.yTarget
                } else {
                    x = units.xPos(decision.targetId)
                    y = units.yPos(decision.targetId)
                }
                val unit = active.unit
                if (decision.itemSlot.toByte().toInt() == -1 &&
                    units.state(unit) and US_IN_BALLISTA == 0
                ) {
                    hooks.endAllMus?.run()
                    units.setPosition(unit, decision.xMove, decision.yMove)
                    hooks.rideBallista?.run(unit)
                    hooks.startMu?.run(unit)
                    mu.setAutoMuDefaultFacing()
                }
            }
            AI_ACTION_STEAL, AI_ACTION_REFRESH -> {
                x = units.xPos(decision.targetId)
                y = units.yPos(decision.targetId)
            }
            AI_ACTION_TALK -> {
                x = units.xPos(decision.yTarget)
                y = units.yPos(decision.yTarget)
            }
            AI_ACTION_STAFF -> {
                if (decision.targetId == 0) return
                x = units.xPos(decision.targetId)
                y = units.yPos(decision.targetId)
            }
            else -> return
        }
        camMove.ensureCameraOntoPosition(proc, x, y)
        startAiTargetCursor(x * 16, y * 16, 2, proc)
    }

    /**
     * Ported from src/cp_perform.c: CpPerform_PerformAction
     *
     * Chooses the action step by `gAiDecision.actionId`: a trapped unit and no action are
     * [aiDummyAction]; a combat action also starts the attack ([aiStartCombatAction]). Verified:
     * both. The other 14 actions are not written and fail loudly (EP-12 and later).
     */
    public fun cpPerformPerformAction(proc: Proc) {
        val state = proc.state as CpPerformState
        state.unk30 = 0
        if (active.action.unitActionType == UNIT_ACTION_TRAPPED) {
            state.funcIsDummy = true
            return
        }
        when (decision.actionId) {
            AI_ACTION_NONE -> state.funcIsDummy = true
            AI_ACTION_COMBAT -> {
                state.funcIsDummy = true
                aiStartCombatAction(proc)
            }
            else -> error("CpPerform_PerformAction: action ${decision.actionId} is not written")
        }
    }

    /**
     * Ported from src/cp_perform.c: AiStartCombatAction
     *
     * Records the attack of the active unit on the decided target in `gActionData`, puts the
     * unit on its move tile, equips the decided weapon (or marks a ballista attack) and applies
     * the action ([PlayerPhase.applyUnitAction], which starts the combat proc blocking [proc]).
     * A target without a unit (a wall or trap, `GetTrapAt`) is not written and fails loudly.
     */
    public fun aiStartCombatAction(proc: Proc) {
        val a = active.action
        val unit = active.unit
        a.subjectIndex = active.unitId
        a.unitActionType = UNIT_ACTION_COMBAT
        a.targetIndex = decision.targetId
        units.setPosition(unit, decision.xMove, decision.yMove)
        check(decision.targetId != 0) { "AiStartCombatAction: GetTrapAt is not written" }
        if (decision.itemSlot.toByte().toInt() != BmBattle.BU_ISLOT_AUTO) {
            items.equipUnitItemSlot(units.bytes, units.unitOffset(unit), decision.itemSlot)
            a.itemSlotIndex = 0
        } else {
            a.itemSlotIndex = BmBattle.BU_ISLOT_BALLISTA
        }
        phase.applyUnitAction(proc)
    }

    /**
     * Ported from src/cp_perform.c: CpPerform_WaitAction
     *
     * The repeat step after the action starts: counts the frame, ends the wait when the action
     * step is done ([aiDummyAction]: at once) and puts the active unit on the decided tile.
     * Verified: the end of the wait. Not seen: the counter and the position write (the unit
     * already stands there and [aiRefreshMap] moves it again).
     */
    public fun cpPerformWaitAction(proc: Proc) {
        val state = proc.state as CpPerformState
        state.unk30++
        if (state.funcIsDummy && aiDummyAction()) engine.breakProc(proc)
        units.setPosition(active.unit, decision.xMove, decision.yMove)
    }

    /**
     * Ported from src/cp_perform.c: AiDummyAction
     *
     * The action step that has nothing to wait for: done (true).
     */
    public fun aiDummyAction(): Boolean = true

    /**
     * Ported from src/cp_perform.c: CpPerform_Cleanup
     *
     * Updates the healing flags, refreshes the map ([aiRefreshMap]) and, for a unit that has no
     * character any more (killed in the action), is hidden, dead or `US_BIT16`, skips
     * `CpPerform_EquipBest` (label 1). Verified: the map refresh and both sides of the skip (the
     * live boss runs the equip, whose danger query rewrites `gBmMapOther`; the Soldiers killed
     * in their attack skip it). Not seen: the healing update (no healer in the Prologue's enemy
     * phases).
     */
    public fun cpPerformCleanup(proc: Proc) {
        hooks.updateAllPhaseHealingAIStatus?.run()
        aiRefreshMap()
        val unit = active.unit
        if (!units.isValid(unit) || units.state(unit) and (US_HIDDEN or US_DEAD or US_BIT16) != 0) {
            engine.goto(proc, 1)
        }
    }

    /**
     * Ported from src/cp_perform.c: AiRefreshMap
     *
     * Makes the action's subject the active unit, puts the cursor on its move tile, moves the unit
     * there ([PlayerPhase.moveActiveUnit]), rebuilds the entity maps and the map, ends the walk's
     * MUs and shows the unit sprite. The fade (`NewBMXFADE(1)`, [BmxFade]) and the display calls
     * are hooks. Verified: the cursor and the unit's move (position, `US_UNSELECTABLE`, hidden bit),
     * for a surviving unit and for one killed in its attack. Not seen: the entity-map refreshes,
     * `EndAllMus`, `ShowUnitSprite` and the display calls (they show in no value this test reads).
     */
    public fun aiRefreshMap() {
        active.unit = active.action.subjectIndex
        Camera.setCursorMapPosition(bm, decision.xMove, decision.yMove)
        hooks.renderBmMapOnBg2?.run()
        phase.moveActiveUnit(decision.xMove, decision.yMove)
        entityMaps.refreshEntityBmMaps()
        hooks.renderBmMap?.run()
        hooks.newBmxFade?.run(1)
        hooks.endAllMus?.run()
        entityMaps.refreshEntityBmMaps()
        hooks.showUnitSprite?.run(active.unit)
        hooks.refreshUnitSprites?.run()
    }

    /**
     * Ported from src/cp_perform.c: StartAiTargetCursor
     *
     * Starts [targetCursorScript] blocking [parent] with the cursor at pixel ([x], [y]), sprite
     * kind [kind] and a zero timer.
     */
    public fun startAiTargetCursor(x: Int, y: Int, kind: Int, parent: Proc) {
        val proc = engine.startBlocking(targetCursorScript, parent) ?: return
        val state = proc.state as AiTargetCursorState
        state.x = x
        state.y = y
        state.kind = kind
        state.timer = 0
    }

    /**
     * Ported from src/cp_perform.c: AiTargetCursor_Main
     *
     * Puts the map cursor; ends the proc when A or START is held or the timer passed 45, then
     * counts the frame. Verified: the cursor's position and kind, the timer, the end after 47
     * frames. Not seen: A or START held (no key is pressed in the captures).
     */
    public fun aiTargetCursorMain(proc: Proc) {
        val state = proc.state as AiTargetCursorState
        hooks.putMapCursor?.put(state.x, state.y, state.kind)
        if (keys.heldKeys and (A_BUTTON or KeyStatus.START_BUTTON) != 0 || state.timer > 45) {
            engine.breakProc(proc)
        }
        state.timer++
    }

    private companion object {
        const val FACTION_RED = 0x80
        const val AI_ACTION_NONE = 0
        const val AI_ACTION_COMBAT = 1
        const val UNIT_ACTION_COMBAT = 2
        const val US_HIDDEN = 1
        const val US_DEAD = 1 shl 2
        const val US_BIT16 = 1 shl 16
        const val AI_ACTION_STEAL = 3
        const val AI_ACTION_PILLAGE = 4
        const val AI_ACTION_STAFF = 5
        const val AI_ACTION_REFRESH = 7
        const val AI_ACTION_TALK = 8
        const val UNIT_ACTION_TRAPPED = 0x1E
        const val US_IN_BALLISTA = 1 shl 11
        const val A_BUTTON = 0x1
    }
}
