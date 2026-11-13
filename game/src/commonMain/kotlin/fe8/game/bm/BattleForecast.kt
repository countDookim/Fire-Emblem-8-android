package fe8.game.bm

import fe8.game.ui.UiFrame
import gbafe.proc.Proc
import gbafe.proc.ProcBoolFunc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.goto
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.sleep
import gbafe.proc.ProcScripts.whileTrue
import gbafe.render.BgHardware

/**
 * The battle forecast window of src/bksel.c: the proc `gProcScr_BKSEL`, its fields and the
 * numbers it caches (the hit counts and the effectiveness flags). The forecast battle itself is
 * [BattleGenerate.battleGenerateSimulation].
 *
 * Only the standard layout (`battleForecastType` 0) and the path the Prologue capture takes are
 * written. Everything the player sees is a [Hooks] entry (the panel, its labels and palettes
 * are MC-11, the slide and the arrows MC-12); the proc fields, the battle numbers and the
 * slide counters are here. Never run by the capture and not written: the extended layout
 * (`DrawBattleForecastContentsExtended`, `PutBattleForecastTilemaps`), the help box
 * (`StartBattleForecastHelpBox`), the ballista and obstacle forecasts.
 */
public class BattleForecast(
    private val engine: ProcEngine,
    private val battle: BmBattle,
    private val gen: BattleGenerate,
    private val items: ItemInfo,
    private val units: UnitTable,
    private val active: ActiveUnit,
    private val playSt: PlaySt,
    private val bm: BmSt,
    private val map: BmMap,
    private val hardware: BgHardware,
) {
    /** `struct BattleForecastProc` past `PROC_HEADER` (the text structs are drawing). */
    public class State {
        /** `unk_2C` (+0x2C): frames the display has run. */
        public var frameCount: Int = 0

        /** `x` (+0x30, s8). */
        public var x: Int = 0

        /** `y` (+0x31, s8). */
        public var y: Int = 0

        /** `frameKind` (+0x32): 1 standard, 2 extended. */
        public var frameKind: Int = 0

        /** `ready` (+0x33, s8): [forecastInit] ran. */
        public var ready: Int = 0

        /** `needContentUpdate` (+0x34, s8). */
        public var needContentUpdate: Int = 0

        /** `side` (+0x35, s8): -1 is left, +1 is right. */
        public var side: Int = 0

        /** `slide_offset` (+0x36, s8). */
        public var slideOffset: Int = 0

        /** `hitCountA` (+0x50, s8): the actor's hits, a brave weapon counting twice. */
        public var hitCountA: Int = 0

        /** `hitCountB` (+0x51, s8). */
        public var hitCountB: Int = 0

        /** `isEffectiveA` (+0x52, s8). */
        public var isEffectiveA: Int = 0

        /** `isEffectiveB` (+0x53, s8). */
        public var isEffectiveB: Int = 0
    }

    /** Services outside the ported file. */
    public class Hooks {
        /** `EventEngineExists` (`MapEventEngineExists_`): the proc waits while an event runs. */
        public var eventEngineExists: () -> Boolean = { false }

        /**
         * `ResetTextFont`, for the setting that shows no forecast. Stand-in until MC-11: the
         * text is not drawn.
         */
        public var resetTextFont: () -> Unit = {}

        /**
         * The drawing set-up of `BattleForecast_Init`: the frame graphics and palette, the icon
         * and text set-up (`InitBattleForecastIconPaletteBuffer`, `InitBattleForecastLabels`)
         * and BG 1's position. Stand-in until MC-11: nothing is drawn.
         */
        public var initDrawing: () -> Unit = {}

        /**
         * `DrawBattleForecastContentsStandard` (names, numbers, icons) and
         * `PutBattleForecastTilemaps`. Stand-in until MC-11: nothing is drawn.
         */
        public var drawContents: (State) -> Unit = {}

        /**
         * `InitBattleForecastFramePalettes`. Stand-in until MC-11: the frame is not coloured.
         */
        public var initFramePalettes: () -> Unit = {}

        /**
         * The tilemap copy of `BattleForecast_LoopSlideIn` and `_LoopSlideOut`: the panel's BG 0
         * and 1 rows for this step of the slide. Stand-in until MC-12a: nothing is drawn.
         */
        public var drawSlide: (State) -> Unit = {}

        /**
         * The arrows, multipliers and palette flash of `BattleForecast_LoopDisplay`. Stand-in
         * until MC-12b: nothing is drawn.
         */
        public var putDisplay: (State) -> Unit = {}

        /**
         * `UnpackUiFrameBuffered(-1)` of `BattleForecast_OnEnd`. Stand-in until MC-11: the
         * frame graphics are not changed.
         */
        public var unpackUiFrame: () -> Unit = {}

        /**
         * `CheckBattleForecastTutorialEvent` is 1 (the first forecast of the tutorial chapter).
         * Stand-in until MC-12a: no tutorial event runs.
         */
        public var tutorialPending: () -> Boolean = { false }
    }

    /** The hooks, set by the game. */
    public val hooks: Hooks = Hooks()

    private var usesLeft = 0

    /**
     * `gProcScr_BKSEL` (0x0859E520): NAME, end callback [forecastOnEnd], CALL `ClearBg0Bg1`,
     * SLEEP 0, CALL [forecastInit], LABEL 0, WHILE an event runs, CALL [onNewBattle], REPEAT
     * [loopSlideIn], CALL the tutorial event, REPEAT [loopDisplay], REPEAT [loopSlideOut], GOTO
     * 0, LABEL 1, REPEAT [loopSlideOut], END.
     */
    public val script: ProcScript = ProcScript(
        "gProcScr_BKSEL",
        listOf(
            name("BKSEL"),
            setEndCb(ProcFunc { forecastOnEnd() }),
            call(ProcFunc { UiFrame.clearBg0Bg1(hardware) }),
            sleep(0),
            call(ProcFunc { forecastInit(it.state as State) }),
            label(0),
            whileTrue(ProcBoolFunc { hooks.eventEngineExists() }),
            call(ProcFunc { onNewBattle(it.state as State) }),
            repeat(ProcFunc { loopSlideIn(it) }),
            call(ProcFunc { triggerTutorial() }),
            repeat(ProcFunc { loopDisplay(it) }),
            repeat(ProcFunc { loopSlideOut(it) }),
            goto(0),
            label(1),
            repeat(ProcFunc { loopSlideOut(it) }),
            end(),
        ),
        newState = { State() },
    )

    /** `Proc_Find(gProcScr_BKSEL)`. */
    public fun find(): Proc? = engine.find(script)

    /**
     * Ported from src/bksel.c: NewBattleForecast
     *
     * Starts the forecast proc (tree 3) for the chosen layout (0 standard, 1 extended; 2 shows
     * none: only the text font is reset, not verified: the capture uses the standard one) and
     * sets every tile of the movement map to -1. The tutorial event's key mask is not written
     * (no capture has the event).
     */
    public fun newBattleForecast() {
        if (playSt.battleForecastType == 2) {
            hooks.resetTextFont()
            return
        }

        val proc = engine.start(script, PROC_TREE_3)
        val state = proc.state as State
        state.ready = 0

        when (playSt.battleForecastType) {
            0 -> state.frameKind = 1
            1 -> state.frameKind = 2
        }

        map.bmMapFill(map.movement, -1)
    }

    /**
     * Ported from src/bksel.c: BattleForecast_Init
     *
     * Sets up the panel's graphics ([Hooks.initDrawing]) and marks the proc ready for content
     * updates.
     */
    private fun forecastInit(state: State) {
        hooks.initDrawing()
        state.ready = 1
    }

    /**
     * Transcribed from src/bksel.c: BattleForecast_OnEnd (not verified: it only restores the
     *   frame graphics, which are drawing, MC-11)
     *
     * The end callback: [Hooks.unpackUiFrame].
     */
    private fun forecastOnEnd() {
        hooks.unpackUiFrame()
    }

    /**
     * Ported from src/bksel.c: GetBattleForecastPanelSide
     *
     * The side of the screen the panel goes on, opposite the target: 1 (right) when the target
     * is left of the middle column, -1 when right of it, 0 on it.
     */
    public fun getBattleForecastPanelSide(): Int {
        val x = battle.target.unitX * 16 - bm.cameraX

        if (x < MIDDLE_X) return 1

        if (x > MIDDLE_X) return -1

        return 0
    }

    /**
     * Ported from src/bksel.c: BattleForecastHitCountUpdate
     *
     * One strike of [battleUnit]: [hits] grows by one (two for a brave weapon) while the weapon
     * has uses left; [usesLeft] counts them down (not verified: a brave weapon is in no capture).
     * Returns the new hit count.
     */
    private fun battleForecastHitCountUpdate(battleUnit: BattleUnit, hits: Int): Int {
        var hitsCounter = hits
        if (usesLeft > 0) {
            hitsCounter = (hitsCounter + 1) and 0xFF
            usesLeft -= 1

            if (battleUnit.weaponAttributes and ItemInfo.IA_BRAVE != 0) {
                hitsCounter = (hitsCounter + 1) and 0xFF
                usesLeft -= 1
            }
        }
        return hitsCounter
    }

    /**
     * Ported from src/bksel.c: InitBattleForecastBattleStats
     *
     * Counts each side's hits (a follow-up strike for the one that strikes first, as many as its
     * weapon has uses) and whether each side's attack is effective or reverts the weapon
     * triangle (not verified: no capture has an effective attack).
     */
    private fun initBattleForecastBattleStats(state: State) {
        val actor = battle.actor
        val target = battle.target

        usesLeft = items.getItemUses(actor.weaponBefore)

        val followUp = gen.battleGetFollowUpOrder()
        val first = gen.firstStriker

        state.hitCountA = 0
        state.isEffectiveA = 0

        if (actor.weapon != 0 || actor.weaponBroke != 0) {
            state.hitCountA = battleForecastHitCountUpdate(actor, state.hitCountA)

            if (followUp && first === actor) {
                state.hitCountA = battleForecastHitCountUpdate(first, state.hitCountA)
            }

            if (battle.isUnitEffectiveAgainst(actor.bytes, 0, target.bytes, 0)) {
                state.isEffectiveA = 1
            }

            if (battle.isItemEffectiveAgainst(actor.weaponBefore, target.bytes, 0)) {
                state.isEffectiveA = 1
            }

            if (actor.wTriangleHitBonus > 0 &&
                actor.weaponAttributes and ItemInfo.IA_REVERTTRIANGLE != 0
            ) {
                state.isEffectiveA = 1
            }
        }

        state.hitCountB = 0
        state.isEffectiveB = 0
        usesLeft = items.getItemUses(target.weaponBefore)

        if (target.weapon != 0 || target.weaponBroke != 0) {
            state.hitCountB = battleForecastHitCountUpdate(target, state.hitCountB)

            if (followUp && first === target) {
                state.hitCountB = battleForecastHitCountUpdate(first, state.hitCountB)
            }

            if (battle.isUnitEffectiveAgainst(target.bytes, 0, actor.bytes, 0)) {
                state.isEffectiveB = 1
            }

            if (battle.isItemEffectiveAgainst(target.weaponBefore, actor.bytes, 0)) {
                state.isEffectiveB = 1
            }

            if (target.wTriangleHitBonus > 0 &&
                target.weaponAttributes and ItemInfo.IA_REVERTTRIANGLE != 0
            ) {
                state.isEffectiveB = 1
            }
        }
    }

    /**
     * Partially ported from src/bksel.c: DrawBattleForecastContents
     *
     * The field part (MC-10): clears the frame count and the update flag and counts the hits for
     * the standard layout; the drawing is [Hooks.drawContents] (MC-11). The extended layout is
     * never run.
     */
    private fun drawBattleForecastContents(state: State) {
        state.frameCount = 0
        state.needContentUpdate = 0

        when (state.frameKind) {
            1 -> {
                initBattleForecastBattleStats(state)
                hooks.drawContents(state)
            }
        }
    }

    /**
     * Partially ported from src/bksel.c: BattleForecast_OnNewBattle
     *
     * The proc's fields for a new forecast: contents, the side (the panel slides in from it),
     * the slide counter and the position. The frame palettes are [Hooks.initFramePalettes].
     */
    private fun onNewBattle(state: State) {
        drawBattleForecastContents(state)

        state.side = getBattleForecastPanelSide()
        state.slideOffset = 0

        state.x = if (state.side < 0) 0 else 20

        state.y = 0

        hooks.initFramePalettes()
    }

    /**
     * Partially ported from src/bksel.c: BattleForecast_LoopSlideIn
     *
     * Counts the four slide steps (the tile copy is [Hooks.drawSlide]) and ends the step.
     */
    private fun loopSlideIn(proc: Proc) {
        val state = proc.state as State
        hooks.drawSlide(state)

        state.slideOffset++

        if (state.slideOffset and 0xFF == SLIDE_STEPS) {
            state.slideOffset = 0
            engine.breakProc(proc)
        }
    }

    /**
     * Partially ported from src/bksel.c: BattleForecast_LoopSlideOut
     *
     * Counts the four slide steps (the tile copy is [Hooks.drawSlide]) and ends the step.
     */
    private fun loopSlideOut(proc: Proc) {
        loopSlideIn(proc)
    }

    /**
     * Partially ported from src/bksel.c: BattleForecast_LoopDisplay
     *
     * Every frame: counts it, and when the contents need an update (not verified: the capture
     * never changes the target) redraws them, or ends the step when the panel must change side.
     * The arrows and flashes are [Hooks.putDisplay].
     */
    private fun loopDisplay(proc: Proc) {
        val state = proc.state as State
        state.frameCount++

        if (state.needContentUpdate != 0) {
            val side = getBattleForecastPanelSide()

            if (side != 0 && side != state.side) {
                engine.breakProc(proc)
                return
            }

            drawBattleForecastContents(state)
            hooks.initFramePalettes()
        }

        if (state.frameKind == 1) {
            hooks.putDisplay(state)
        }
    }

    /**
     * Transcribed from src/bksel.c: TriggerBattleForcastToturialEvent (not verified: the capture
     *   starts no tutorial event)
     *
     * Starts the forecast tutorial event when it is due ([Hooks.tutorialPending]); the event
     * itself is not written.
     */
    private fun triggerTutorial() {
        hooks.tutorialPending()
    }

    /**
     * `BattleGenerateSimulation(gActiveUnit, unit, -1, -1, gActionData.itemSlotIndex)` then
     * [updateBattleForecastContents], the forecast part of `AttackMapSelect_SwitchIn` for the
     * unit [unitId] (the ballista and obstacle forecasts are not written: no capture has them).
     */
    public fun simulateAndUpdate(unitId: Int) {
        gen.battleGenerateSimulation(
            units.bytes, units.unitOffset(active.unit), units.unitOffset(unitId), -1, -1,
            active.action.itemSlotIndex,
        )
        updateBattleForecastContents()
    }

    /**
     * Transcribed from src/bksel.c: UpdateBattleForecastContents (not verified: the capture's one
     *   call comes before the proc is ready, and the contents are redrawn at the start anyway)
     *
     * Asks a ready forecast to redraw its contents.
     */
    public fun updateBattleForecastContents() {
        val proc = find() ?: return
        val state = proc.state as State

        if (state.ready != 0) state.needContentUpdate = 1
    }

    /**
     * Ported from src/bksel.c: CloseBattleForecast
     *
     * Ends the forecast: a proc that is not ready yet is ended at once (not verified: the
     * capture closes it when ready) with BG 0 and 1 cleared; a ready one slides out
     * (`Proc_Goto` label 1).
     */
    public fun closeBattleForecast() {
        val proc = find() ?: return
        val state = proc.state as State

        if (state.ready == 0) {
            UiFrame.clearBg0Bg1(hardware)
            engine.end(proc)
            return
        }
        engine.goto(proc, 1)
    }

    /** `Proc_EndEach(gProcScr_BKSEL)`: the forecast ends at once (A on a target). */
    public fun endForecast() {
        engine.endEach(script)
    }

    private companion object {
        const val PROC_TREE_3 = 3
        const val MIDDLE_X = 0x70
        const val SLIDE_STEPS = 4
    }
}
