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
 * The attack's target selection callbacks of src/bmmenu.c (`gSelectInfo_Attack`,
 * src/menu_def.c) and the cancel proc `gProcScr_0859B630`: [onSwitchIn], [onSelect], [onCancel]
 * and [onEnd] are `AttackMapSelect_*`.
 *
 * The battle forecast (`NewBattleForecast`, `UpdateBattleForecastContents`,
 * `CloseBattleForecast`, the `gProcScr_BKSEL` proc and the R help) is MC-10, the [Hooks].
 */
public class AttackMapSelect(
    private val engine: ProcEngine,
    private val targets: SelectTargets,
    private val active: ActiveUnit,
    private val units: UnitTable,
    private val facing: UnitFacing,
    private val camMove: CamMove,
    /** The BG 2 tilemap [onEnd] clears. */
    private val hardware: BgHardware,
) : TargetSelection.SelectInfo {
    /** Services outside the ported file. */
    public class Hooks {
        /** `EventEngineExists()`: an event is running (the selection then ignores A and B). */
        public var eventEngineExists: () -> Boolean = { false }

        /** `HideMoveRangeGraphics`. */
        public var hideMoveRangeGraphics: () -> Unit = {}

        /** `LockGame`. */
        public var lockGame: () -> Unit = {}

        /** `UnlockGame`. */
        public var unlockGame: () -> Unit = {}

        /**
         * `sub_8022E54` ([UnitActionMenu.unitActionMenuAttackFromProc]): reopens the weapon
         * list.
         */
        public var restartWeaponList: () -> Unit = {}

        /**
         * `NewBattleForecast` (the selection's `onInit`): starts the forecast window proc
         * (`gProcScr_BKSEL`). Stand-in until MC-10: nothing is shown.
         */
        public var newBattleForecast: () -> Unit = {}

        /**
         * `BattleGenerateSimulation(gActiveUnit, unit, -1, -1, itemSlotIndex)` then
         * `UpdateBattleForecastContents()` for the unit with id `unitId` (the ballista and
         * obstacle variants too). Stand-in until MC-10: nothing is shown.
         */
        public var updateForecast: (unitId: Int) -> Unit = {}

        /**
         * `Proc_EndEach(gProcScr_BKSEL)` (A on a target) and `CloseBattleForecast` ([onEnd]).
         * Stand-in until MC-10.
         */
        public var closeForecast: () -> Unit = {}

        /**
         * `Proc_Find(gProcScr_BKSEL)`: whether the forecast window proc still exists (the cancel
         * proc waits for it to end). Stand-in until MC-10: it is gone at once.
         */
        public var forecastExists: () -> Boolean = { false }

        /**
         * `StartBattleForecastHelpBox` (R on a target). Stand-in until MC-10: nothing happens
         * (the selection's `onHelp`).
         */
        public var startForecastHelp: () -> Unit = {}
    }

    /** The hooks, set by the game. */
    public val hooks: Hooks = Hooks()

    /**
     * `gProcScr_0859B630` (0x0859B630): CALL LockGame, CALL [sub8022E38], WHILE_EXISTS
     * `gProcScr_BKSEL`, WHILE_EXISTS `ProcScr_CamMove`, CALL `sub_8022E54`
     * ([Hooks.restartWeaponList]), CALL UnlockGame, END.
     */
    public val cancelScript: ProcScript = ProcScript(
        "gProcScr_0859B630",
        listOf(
            call(ProcFunc { hooks.lockGame() }),
            call(ProcFunc { sub8022E38() }),
            whileTrue(ProcBoolFunc { hooks.forecastExists() }),
            whileExists { camMove.script },
            call(ProcFunc { hooks.restartWeaponList() }),
            call(ProcFunc { hooks.unlockGame() }),
            end(),
        ),
    )

    override fun onInit(proc: Proc) {
        hooks.newBattleForecast()
    }

    /**
     * Ported from src/bmmenu.c: AttackMapSelect_Select
     *
     * A on a target (ignored while an event runs): the action becomes a combat on the target's
     * unit (a wall or trap: its tile and trap type, not verified: no capture has one), the
     * forecast window ends, and the selection ends with the select sound and BG 0 and 1 cleared.
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

        hooks.closeForecast()

        return Menu.MENU_ACT_SKIPCURSOR or Menu.MENU_ACT_END or Menu.MENU_ACT_SND6A or
            Menu.MENU_ACT_CLEAR
    }

    /**
     * Transcribed from src/bmmenu.c: sub_8022E38 (not verified: the Prologue map fits the
     *   screen, so the camera never moves and no mutation changes captured data)
     *
     * Moves the camera back onto the active unit (a CamMove with no parent) when it is off it.
     */
    private fun sub8022E38() {
        camMove.ensureCameraOntoPosition(null, units.xPos(active.unit), units.yPos(active.unit))
    }

    /**
     * Ported from src/bmmenu.c: AttackMapSelect_Cancel
     *
     * B on a target (ignored while an event runs): starts [cancelScript] (tree 3), which brings
     * the camera back and reopens the weapon list, and ends the selection with the cancel sound.
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
     * ([UnitFacing.changeActiveUnitFacing]); a wall or trap target (unit id 0) is stored in the
     * action data and its battle unit set up (not verified: no capture has one); then the forecast
     * is made ([Hooks.updateForecast], MC-10).
     */
    override fun onSwitchIn(proc: Proc, target: Int): Int {
        facing.changeActiveUnitFacing(targets.targetX(target), targets.targetY(target))

        if (targets.targetUid(target) == 0) {
            active.action.xOther = targets.targetX(target) and 0xFF
            active.action.yOther = targets.targetY(target) and 0xFF
            active.action.trapType = targets.targetExtra(target) and 0xFF
        }

        hooks.updateForecast(targets.targetUid(target))

        return 0
    }

    /**
     * Ported from src/bmmenu.c: AttackMapSelect_End
     *
     * Clears BG 2, hides the range squares and closes the forecast ([Hooks.closeForecast]).
     */
    override fun onEnd(proc: Proc) {
        hardware.fill(hardware.mapBuffer(2), 0)
        hardware.enableBgSyncByMask(1 shl 2)

        hooks.hideMoveRangeGraphics()
        hooks.closeForecast()
    }

    override fun onHelp(proc: Proc, target: Int): Int {
        hooks.startForecastHelp()
        return 0
    }

    private companion object {
        const val PROC_TREE_3 = 3
        const val UNIT_ACTION_COMBAT = 2
    }
}
