package fe8.game.bm

import fe8.game.ui.Menu
import gbafe.proc.Proc
import gbafe.proc.ProcBoolFunc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.whileExists
import gbafe.proc.ProcScripts.whileTrue
import gbafe.render.BgHardware

/**
 * The target selection the player is in after choosing Attack and a weapon: the callbacks of
 * `gSelectInfo_Attack` (src/menu_def.c; [onInit] is `NewBattleForecast`, [onSwitchIn], [onSelect],
 * [onCancel] and [onEnd] are `AttackMapSelect_*` of src/bmmenu.c, [onHelp] is
 * `StartBattleForecastHelpBox`) and the proc `gProcScr_0859B630` ([cancelScript]) that backs out
 * to the weapon list.
 *
 * The battle forecast window is [BattleForecast]; the R help box is a hook ([Hooks]).
 */
public class AttackMapSelect(
    private val engine: ProcEngine,
    private val targets: SelectTargets,
    private val active: ActiveUnit,
    private val units: UnitTable,
    private val facing: UnitFacing,
    private val camMove: CamMove,
    /** The forecast window ([BattleForecast]). */
    private val forecast: BattleForecast,
    /** The BG 2 tilemap [onEnd] clears. */
    private val hardware: BgHardware,
) : TargetSelection.SelectInfo {
    /** Services outside the ported file. */
    public class Hooks {
        /** `EventEngineExists()`: an event is running (the selection then ignores A and B). */
        public var eventEngineExists: () -> Boolean = { false }

        /** `HideMoveRangeGraphics`: removes the blue/red range squares from the map. */
        public var hideMoveRangeGraphics: () -> Unit = {}

        /** `LockGame`: blocks player input while [cancelScript] runs. */
        public var lockGame: () -> Unit = {}

        /** `UnlockGame`: gives player input back at the end of [cancelScript]. */
        public var unlockGame: () -> Unit = {}

        /**
         * `sub_8022E54` (`UnitActionMenu_Attack(NULL, NULL)`,
         * [UnitActionMenu.unitActionMenuAttackFromProc]): reopens the weapon list.
         */
        public var restartWeaponList: () -> Unit = {}

        /**
         * `StartBattleForecastHelpBox` (R on a target: the help box of the forecast's arrows and
         * marks). Stand-in until IS-2 (help boxes): nothing happens (the selection's `onHelp`).
         */
        public var startForecastHelp: () -> Unit = {}
    }

    /** The hooks, set by the game. */
    public val hooks: Hooks = Hooks()

    /**
     * `gProcScr_0859B630` (0x0859B630), started by [onCancel]: CALL LockGame, CALL
     * [ensureCameraOnActiveUnit], WHILE_EXISTS `gProcScr_BKSEL` (the forecast slides out),
     * WHILE_EXISTS `ProcScr_CamMove`, CALL `sub_8022E54` ([Hooks.restartWeaponList]), CALL
     * UnlockGame, END.
     */
    public val cancelScript: ProcScript = ProcScript(
        "gProcScr_0859B630",
        listOf(
            call(ProcFunc { hooks.lockGame() }),
            call(ProcFunc { ensureCameraOnActiveUnit() }),
            whileTrue(ProcBoolFunc { forecast.find() != null }),
            whileExists { camMove.script },
            call(ProcFunc { hooks.restartWeaponList() }),
            call(ProcFunc { hooks.unlockGame() }),
            end(),
        ),
    )

    /** `NewBattleForecast`: opens the forecast window when the selection starts. */
    override fun onInit(proc: Proc) {
        forecast.newBattleForecast()
    }

    /**
     * Ported from src/bmmenu.c: AttackMapSelect_Select
     *
     * A on a target (ignored while an event runs): the pending action becomes a combat
     * (`UNIT_ACTION_COMBAT`) on the target's unit; for a wall or snag (unit id 0) its tile and
     * trap type are stored instead (not verified: no capture has one). The forecast window ends
     * at once, and the selection ends with the select sound (`MENU_ACT_SND6A`) and the menu
     * cleared.
     */
    override fun onSelect(proc: Proc, target: Int): Int {
        if (hooks.eventEngineExists()) return 0

        active.action.unitActionType = UNIT_ACTION_COMBAT
        active.action.targetIndex = targets.targetUid(target) and 0xFF

        if (targets.targetUid(target) == 0) {
            active.action.xOther = targets.targetX(target) and 0xFF
            active.action.yOther = targets.targetY(target) and 0xFF
            active.action.trapType = targets.targetExtra(target) and 0xFF
        }

        forecast.endForecast()

        return Menu.MENU_ACT_SKIPCURSOR or Menu.MENU_ACT_END or Menu.MENU_ACT_SND6A or
            Menu.MENU_ACT_CLEAR
    }

    /**
     * Transcribed from src/bmmenu.c: sub_8022E38 (not verified: the Prologue map fits the
     *   screen, so the camera never moves and no mutation changes captured data)
     *
     * Moves the camera back onto the active unit (`EnsureCameraOntoPosition` with no parent proc)
     * when it is off it, as the selection's cancel starts: the target cursor may have scrolled
     * the camera away.
     */
    private fun ensureCameraOnActiveUnit() {
        camMove.ensureCameraOntoPosition(null, units.xPos(active.unit), units.yPos(active.unit))
    }

    /**
     * Ported from src/bmmenu.c: AttackMapSelect_Cancel
     *
     * B on a target (ignored while an event runs): starts [cancelScript] (`PROC_TREE_3`), which
     * brings the camera back and reopens the weapon list, and ends the selection with the cancel
     * sound (`MENU_ACT_SND6B`).
     */
    override fun onCancel(proc: Proc, target: Int): Int {
        if (hooks.eventEngineExists()) return 0

        engine.start(cancelScript, PROC_TREE_3)

        return Menu.MENU_ACT_SKIPCURSOR or Menu.MENU_ACT_END or Menu.MENU_ACT_SND6B
    }

    /**
     * Ported from src/bmmenu.c: AttackMapSelect_SwitchIn
     *
     * The cursor arrives on a target: the active unit turns towards it
     * ([UnitFacing.changeActiveUnitFacing]); a wall or snag target (unit id 0) has its tile and
     * trap type stored in the action data (not verified: no capture has one; the C also calls
     * `InitObstacleBattleUnit` here, which is not written); then the forecast is simulated and
     * shown ([BattleForecast.simulateAndUpdate]). The C's ballista branch
     * (`BattleGenerateBallistaSimulation`) is not written.
     */
    override fun onSwitchIn(proc: Proc, target: Int): Int {
        facing.changeActiveUnitFacing(targets.targetX(target), targets.targetY(target))

        if (targets.targetUid(target) == 0) {
            active.action.xOther = targets.targetX(target) and 0xFF
            active.action.yOther = targets.targetY(target) and 0xFF
            active.action.trapType = targets.targetExtra(target) and 0xFF
        }

        forecast.simulateAndUpdate(targets.targetUid(target))

        return 0
    }

    /**
     * Ported from src/bmmenu.c: AttackMapSelect_End
     *
     * The selection's end: clears BG 2 (the range squares' layer), hides the range squares and
     * closes the forecast ([BattleForecast.closeBattleForecast]).
     */
    override fun onEnd(proc: Proc) {
        hardware.fill(hardware.mapBuffer(2), 0)
        hardware.enableBgSyncByMask(BG2_SYNC_BIT)

        hooks.hideMoveRangeGraphics()
        forecast.closeBattleForecast()
    }

    /** `StartBattleForecastHelpBox` through [Hooks.startForecastHelp]. */
    override fun onHelp(proc: Proc, target: Int): Int {
        hooks.startForecastHelp()
        return 0
    }

    private companion object {
        const val PROC_TREE_3 = 3
        const val UNIT_ACTION_COMBAT = 2

        /** `BG2_SYNC_BIT` (include/hardware.h). */
        const val BG2_SYNC_BIT = 1 shl 2
    }
}
