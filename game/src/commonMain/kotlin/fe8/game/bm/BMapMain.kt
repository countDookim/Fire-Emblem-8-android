package fe8.game.bm

import fe8.game.RomView
import gbafe.core.flags.EventFlags
import gbafe.proc.Proc
import gbafe.proc.ProcBoolFunc
import gbafe.proc.ProcCmd
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.call2
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.goto
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.sleep
import gbafe.proc.ProcScripts.startChild
import gbafe.proc.ProcScripts.startChildBlocking
import gbafe.proc.ProcScripts.whileExists

/**
 * The battle map main proc of `src/bm.c`: `gProc_BMapMain` (ROM 0x0859A1F0, labels 0-11), the
 * phase loop that runs the beginning events, switches phases and starts the player and
 * computer phases, with the functions it calls (`SwitchPhases`, `BmMain_ChangePhase`,
 * `BmMain_StartPhase`, `BmMain_ResumePlayerPhase`, `BmMain_SuspendBeforePhase`,
 * `BmMain_StartIntroFx`, `CallBeginningEvents`, `UndeployEveryone`, `sub_8015434`,
 * `GotoChapterWithoutSave`, `SetEventId_0x84` / `UnsetEventId_0x84`, `BmMain_UpdateTraps`).
 *
 * The script is [script]; [BattleMapStart.Hooks.bmapMainScript] must be set to it (its
 * `newState` is [BattleMapStart.BMapMainState]). [ResetCursorPosition] and [CamMove] are the
 * ported procs it starts. Everything else it starts or calls is a [Hooks] stand-in: with the
 * defaults, the phase procs of the player and the computer idle forever and every other child
 * proc ends at once. Nothing allocates per frame.
 */
public class BMapMain(
    private val engine: ProcEngine,
    private val playSt: PlaySt,
    private val units: UnitTable,
    private val flags: EventFlags,
    private val chapterData: ChapterData,
    private val rom: RomView,
    private val resetCursor: ResetCursorPosition,
    private val camMove: CamMove,
    /** The stand-ins. */
    public val hooks: Hooks = Hooks(),
) {
    /** The calls and procs that are not ported yet; every one does nothing by default. */
    public class Hooks {
        /** `WriteSuspendSave(slot)`. Stand-in. */
        public var writeSuspendSave: (slot: Int) -> Unit = {}

        /**
         * `CallEvent(events, execType)` with the BMapMain [Proc] that asked; may be wired to
         * `EventEngine.callEvent`. Stand-in.
         */
        public var callEvent: (events: Int, execType: Int, proc: Proc) -> Unit = { _, _, _ -> }

        /**
         * `RunPhaseSwitchEvents`: true if it started events (then `BmMain_ChangePhase` yields one
         * frame instead of continuing). Stand-in.
         */
        public var runPhaseSwitchEvents: () -> Boolean = { false }

        /** `ClearActiveFactionGrayedStates`. Stand-in. */
        public var clearActiveFactionGrayedStates: () -> Unit = {}

        /** `RefreshUnitSprites`. Stand-in. */
        public var refreshUnitSprites: () -> Unit = {}

        /** `ProcessTurnSupportExp`. Stand-in. */
        public var processTurnSupportExp: () -> Unit = {}

        /** `UpdatePrevDeployStates`. Stand-in. */
        public var updatePrevDeployStates: () -> Unit = {}

        /** `TickActiveFactionTurn`. Stand-in. */
        public var tickActiveFactionTurn: () -> Unit = {}

        /** `DecayTraps` (second half of `BmMain_UpdateTraps`). Stand-in. */
        public var decayTraps: () -> Unit = {}

        /** `sub_80155C4` (chapter change cleanup and `StartBattleMap(0)`). Stand-in. */
        public var sub80155C4: () -> Unit = {}

        /** `RenderBmMap`. Stand-in. */
        public var renderBmMap: () -> Unit = {}

        /** `StartMapSongBgm`. Stand-in. */
        public var startMapSongBgm: () -> Unit = {}

        /** `StartMidFadeFromBlack`. Stand-in. */
        public var startMidFadeFromBlack: () -> Unit = {}

        /** `WaitForFade` as "the fade is over" (the repeat breaks then). Stand-in. */
        public var waitForFade: () -> Boolean = { true }

        /** `GetBattleMapKind` ([BattleMapKind]). Stand-in: always a story map. */
        public var getBattleMapKind: () -> Int = { BattleMapKind.STORY }

        /** `gProcScr_ChapterIntro` (0x0859B1B0). Stand-in: ends at once. */
        public var chapterIntro: ProcScript = standIn("gProcScr_ChapterIntro")

        /** `gProcScr_ChapterIntroTitleOnly`. Stand-in: ends at once. */
        public var chapterIntroTitleOnly: ProcScript = standIn("gProcScr_ChapterIntroTitleOnly")

        /** `ProcScr_PhaseIntro` (0x0859AF80). Stand-in: ends at once. */
        public var phaseIntro: ProcScript = standIn("ProcScr_PhaseIntro")

        /** `gProcScr_StatusDecayDisplay`. Stand-in: ends at once. */
        public var statusDecayDisplay: ProcScript = standIn("gProcScr_StatusDecayDisplay")

        /** `gProcScr_TerrainHealDisplay`. Stand-in: ends at once. */
        public var terrainHealDisplay: ProcScript = standIn("gProcScr_TerrainHealDisplay")

        /** `gProcScr_PoisonDamageDisplay`. Stand-in: ends at once. */
        public var poisonDamageDisplay: ProcScript = standIn("gProcScr_PoisonDamageDisplay")

        /** `gProcScr_GorgonEggHatchDisplay`. Stand-in: ends at once. */
        public var gorgonEggHatchDisplay: ProcScript = standIn("gProcScr_GorgonEggHatchDisplay")

        /** `gProcScr_UpdateTraps`. Stand-in: ends at once. */
        public var updateTraps: ProcScript = standIn("gProcScr_UpdateTraps")

        /** `gProcScr_BerserkCpPhase`. Stand-in: ends at once. */
        public var berserkCpPhase: ProcScript = standIn("gProcScr_BerserkCpPhase")

        /**
         * `gProcScr_PlayerPhase` (0x0859AAD8; task M5-47 provides the real script). Stand-in:
         * never ends.
         */
        public var playerPhase: ProcScript = idle("gProcScr_PlayerPhase")

        /** `gProcScr_CpPhase`. Stand-in: never ends. */
        public var cpPhase: ProcScript = idle("gProcScr_CpPhase")

        private fun standIn(name: String) = ProcScript(name, listOf(end()))

        private fun idle(name: String) = ProcScript(name, listOf(block(), end()))
    }

    /**
     * `gActionData.suspendPointType` as `BmMain_SuspendBeforePhase` leaves it (the action data is
     * not ported yet); `SUSPEND_POINT_PHASECHANGE` after that call. Stand-in.
     */
    public var suspendPointType: Int = 0

    private val startIntroFxFn = ProcFunc { startIntroFx(it) }
    private val setEventId84Fn = ProcFunc { setEventId84() }
    private val updatePrevDeployStatesFn = ProcFunc { hooks.updatePrevDeployStates() }
    private val callBeginningEventsFn = ProcBoolFunc { callBeginningEvents(it) }
    private val undeployEveryoneFn = ProcFunc { undeployEveryone() }
    private val unsetEventId84Fn = ProcFunc { unsetEventId84() }
    private val changePhaseFn = ProcBoolFunc { changePhase() }
    private val suspendBeforePhaseFn = ProcFunc { suspendBeforePhase() }
    private val tickFn = ProcFunc { hooks.tickActiveFactionTurn() }
    private val sub8015434Fn = ProcBoolFunc { sub8015434() }
    private val startPhaseFn = ProcFunc { startPhase(it) }
    private val updateTrapsFn = ProcBoolFunc { updateTraps(it) }
    private val sub80155C4Fn = ProcFunc { hooks.sub80155C4() }
    private val renderBmMapFn = ProcFunc { hooks.renderBmMap() }
    private val startMapSongBgmFn = ProcFunc { hooks.startMapSongBgm() }
    private val startMidFadeFn = ProcFunc { hooks.startMidFadeFromBlack() }
    private val waitForFadeFn = ProcFunc { if (hooks.waitForFade()) engine.breakProc(it) }
    private val resumePlayerPhaseFn = ProcFunc { resumePlayerPhase(it) }

    private val resetScript = { resetCursor.script }
    private val camMoveScript = { camMove.script }
    private val chapterIntroTitleScript = { hooks.chapterIntroTitleOnly }
    private val phaseIntroScript = { hooks.phaseIntro }
    private val statusDecayScript = { hooks.statusDecayDisplay }
    private val terrainHealScript = { hooks.terrainHealDisplay }
    private val poisonScript = { hooks.poisonDamageDisplay }
    private val gorgonScript = { hooks.gorgonEggHatchDisplay }
    private val berserkScript = { hooks.berserkCpPhase }

    /**
     * `gProc_BMapMain` (bm.c:41, ROM 0x0859A1F0, 73 commands). The state is
     * [BattleMapStart.BMapMainState]. Labels: 0 intro, 1 beginning events, 11 flag clear, 3 phase
     * change, 9 phase start, 5 phase run, 2 chapter change, 4/6/7/8 fade-in entries (map
     * redraw), 10 resumed berserk.
     */
    public val script: ProcScript = ProcScript(
        "gProc_BMapMain",
        listOf(
            sleep(0),
            name("E_BMAPMAIN"),
            ProcCmd(0x15), // PROC_15
            mark(BattleMapStart.PROC_MARK_MAIN),
            sleep(0),
            label(0),
            call(startIntroFxFn),
            sleep(0),
            label(1),
            call(setEventId84Fn),
            call(updatePrevDeployStatesFn),
            call2(callBeginningEventsFn),
            call(undeployEveryoneFn),
            label(11),
            call(unsetEventId84Fn),
            label(3),
            call2(changePhaseFn),
            call(suspendBeforePhaseFn),
            label(9),
            startChild(resetScript),
            startChildBlocking(phaseIntroScript),
            whileExists(camMoveScript),
            call(tickFn),
            startChildBlocking(statusDecayScript),
            startChildBlocking(terrainHealScript),
            startChildBlocking(poisonScript),
            startChildBlocking(gorgonScript),
            startChildBlocking(resetScript),
            call2(sub8015434Fn),
            label(5),
            repeat(startPhaseFn),
            startChildBlocking(berserkScript),
            call2(updateTrapsFn),
            goto(3),
            label(2),
            call(sub80155C4Fn),
            sleep(0),
            startChildBlocking(chapterIntroTitleScript),
            sleep(0),
            goto(1),
            label(4),
            call(renderBmMapFn),
            call(startMapSongBgmFn),
            call(startMidFadeFn),
            repeat(waitForFadeFn),
            goto(5),
            label(6),
            call(renderBmMapFn),
            call(startMapSongBgmFn),
            call(startMidFadeFn),
            repeat(waitForFadeFn),
            repeat(resumePlayerPhaseFn),
            startChildBlocking(berserkScript),
            goto(3),
            label(10),
            sleep(0),
            repeat(resumePlayerPhaseFn),
            startChildBlocking(berserkScript),
            goto(3),
            label(8),
            call(renderBmMapFn),
            call(startMapSongBgmFn),
            call(startMidFadeFn),
            repeat(waitForFadeFn),
            goto(9),
            label(7),
            call(renderBmMapFn),
            call(startMapSongBgmFn),
            call(startMidFadeFn),
            repeat(waitForFadeFn),
            startChildBlocking(berserkScript),
            goto(3),
            end(),
        ),
        newState = { BattleMapStart.BMapMainState() },
    )

    /**
     * Ported from src/bm.c: SwitchPhases
     *
     * BLUE to RED, RED to GREEN, GREEN to BLUE with the turn number + 1 (capped at 999) and
     * [Hooks.processTurnSupportExp].
     */
    public fun switchPhases() {
        when (playSt.faction) {
            FACTION_BLUE -> playSt.faction = FACTION_RED
            FACTION_RED -> playSt.faction = FACTION_GREEN
            FACTION_GREEN -> {
                playSt.faction = FACTION_BLUE
                if (playSt.chapterTurnNumber < 999) playSt.chapterTurnNumber++
                hooks.processTurnSupportExp()
            }
        }
    }

    /**
     * Ported from src/bm.c: CallBeginningEvents
     *
     * Calls the beginning scene events of the chapter (the common skirmish beginning on a
     * skirmish map) through [Hooks.callEvent] with exec type 1. Returns false (`PROC_CALL_2`
     * yields one frame).
     */
    public fun callBeginningEvents(proc: Proc): Boolean {
        val events = if (hooks.getBattleMapKind() != BattleMapKind.SKIRMISH) {
            beginningSceneEvents()
        } else {
            EVENT_SKIRMISH_COMMON_BEGINNING
        }
        hooks.callEvent(events, 1, proc)
        return false
    }

    /**
     * The `beginningSceneEvents` pointer (+0x48) of the `ChapterEventGroup` of the current
     * chapter (`GetChapterEventDataPointer(chapterIndex)`).
     */
    public fun beginningSceneEvents(): Int =
        rom.u32(chapterData.getChapterEventDataPointer(playSt.chapterIndex) + 0x48)

    /**
     * Ported from src/bm.c: BmMain_ChangePhase
     *
     * Clears the grays, refreshes the sprites, runs [switchPhases] and the phase switch events.
     * Returns false (yield) if the events started.
     */
    public fun changePhase(): Boolean {
        hooks.clearActiveFactionGrayedStates()
        hooks.refreshUnitSprites()
        switchPhases()
        return !hooks.runPhaseSwitchEvents()
    }

    /**
     * Ported from src/bm.c: sub_8015434
     *
     * Always true: `sub_80832D4` returns 0 in the game.
     */
    public fun sub8015434(): Boolean = true

    /**
     * Ported from src/bm.c: BmMain_StartPhase
     *
     * Starts the player phase (BLUE) or the computer phase (RED, GREEN) blocking [proc], then
     * breaks the repeat of [proc].
     */
    public fun startPhase(proc: Proc) {
        when (playSt.faction) {
            FACTION_BLUE -> engine.startBlocking(hooks.playerPhase, proc)
            FACTION_RED, FACTION_GREEN -> engine.startBlocking(hooks.cpPhase, proc)
        }
        engine.breakProc(proc)
    }

    /**
     * Ported from src/bm.c: BmMain_ResumePlayerPhase
     *
     * Starts the player phase blocking [proc], jumps it to its label 7, breaks [proc].
     */
    public fun resumePlayerPhase(proc: Proc) {
        engine.goto(engine.startBlocking(hooks.playerPhase, proc)!!, 7)
        engine.breakProc(proc)
    }

    /**
     * Ported from src/bm.c: BmMain_UpdateTraps
     *
     * Not the green phase: true. Otherwise starts [Hooks.updateTraps] blocking [proc], runs
     * [Hooks.decayTraps] and returns false.
     */
    public fun updateTraps(proc: Proc): Boolean {
        if (playSt.faction != FACTION_GREEN) return true
        engine.startBlocking(hooks.updateTraps, proc)
        hooks.decayTraps()
        return false
    }

    /**
     * Ported from src/bm.c: BmMain_SuspendBeforePhase
     *
     * Sets [suspendPointType] to `SUSPEND_POINT_PHASECHANGE` and calls
     * [Hooks.writeSuspendSave] with `SAVE_ID_SUSPEND`.
     */
    public fun suspendBeforePhase() {
        suspendPointType = SUSPEND_POINT_PHASECHANGE
        hooks.writeSuspendSave(SAVE_ID_SUSPEND)
    }

    /**
     * Ported from src/bm.c: BmMain_StartIntroFx
     *
     * Starts [Hooks.chapterIntro] blocking [proc] except in chapter 0x38 and in Ch6 with flag
     * 0x88 set.
     */
    public fun startIntroFx(proc: Proc) {
        if (playSt.chapterIndex == 0x38) return
        if (playSt.chapterIndex == 0x06 && flags.checkFlag(0x88)) return
        engine.startBlocking(hooks.chapterIntro, proc)
    }

    /**
     * Ported from src/bm.c: UndeployEveryone
     *
     * Clears flag 0x84 and, unless `unk4A_1` is set, `US_NOT_DEPLOYED` of every valid blue unit.
     */
    public fun undeployEveryone() {
        flags.clearFlag(0x84)
        if (playSt.unk4A1 != 0) return
        for (id in 1 until FACTION_GREEN) {
            if (!units.isValid(id)) continue
            units.setState(id, units.state(id) and BattleMapStart.US_NOT_DEPLOYED.inv())
        }
    }

    /**
     * Ported from src/bm.c: GotoChapterWithoutSave
     *
     * Sets the chapter, sends the running BMapMain to label 2 and ends the phase procs.
     */
    public fun gotoChapterWithoutSave(chapterId: Int) {
        playSt.chapterIndex = chapterId
        engine.goto(engine.find(script)!!, 2)
        engine.endEach(hooks.playerPhase)
        engine.endEach(hooks.cpPhase)
        engine.endEach(hooks.berserkCpPhase)
    }

    /** Ported from src/bm.c: SetEventId_0x84 (`SetFlag(0x84)`). */
    public fun setEventId84() {
        flags.setFlag(0x84)
    }

    /** Ported from src/bm.c: UnsetEventId_0x84 (`ClearFlag(0x84)`). */
    public fun unsetEventId84() {
        flags.clearFlag(0x84)
    }

    /** Constants. */
    public companion object {
        /** `FACTION_BLUE`. */
        public const val FACTION_BLUE: Int = 0x00

        /** `FACTION_GREEN`. */
        public const val FACTION_GREEN: Int = 0x40

        /** `FACTION_RED`. */
        public const val FACTION_RED: Int = 0x80

        /** `SUSPEND_POINT_PHASECHANGE`. */
        public const val SUSPEND_POINT_PHASECHANGE: Int = 9

        /** `SAVE_ID_SUSPEND`. */
        public const val SAVE_ID_SUSPEND: Int = 3

        /** `EventScr_SkirmishCommonBeginning` (ROM 0x089FFC30). */
        public const val EVENT_SKIRMISH_COMMON_BEGINNING: Int = 0x089FFC30
    }
}
