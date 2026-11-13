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
 * ported procs it starts. Everything else it starts or calls is a [Hooks] default: with the
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
    /** The hooks; `Fe8Boot` replaces the ported ones, the rest keep the defaults. */
    public val hooks: Hooks = Hooks(),
) {
    /** The calls and procs that are not ported yet; every one does nothing by default. */
    public class Hooks {
        /**
         * `WriteSuspendSave(slot)`: writes a suspend save file for the given slot. Default no-op;
         * `Fe8Boot` sets `suspendSaveHook` (`BmSuspendSave`).
         */
        public var writeSuspendSave: (slot: Int) -> Unit = {}

        /**
         * `CallEvent(events, execType)` with the BMapMain [Proc] that asked; may be wired to
         * `EventEngine.callEvent`. Default no-op; `Fe8Boot` sets it to `EventEngine.callEvent`.
         */
        public var callEvent: (events: Int, execType: Int, proc: Proc) -> Unit = { _, _, _ -> }

        /**
         * `RunPhaseSwitchEvents`: true if it started events (then `BmMain_ChangePhase` yields one
         * frame instead of continuing). Default false; `Fe8Boot` sets
         * `EventInfos.runPhaseSwitchEvents`.
         */
        public var runPhaseSwitchEvents: () -> Boolean = { false }

        /**
         * `ClearActiveFactionGrayedStates`: clears `US_UNSELECTABLE`, `US_HAS_MOVED` and
         * `US_HAS_MOVED_AI` of the units of the current faction (bmunit.c:1108); the blue phase
         * also lowers the favour value of the player units. [BMapMain.changePhase] calls it
         * before [BMapMain.switchPhases], so it clears the phase that is ending.
         * `Fe8Boot` sets `PhaseStartEnd.clearActiveFactionGrayedStates`.
         */
        public var clearActiveFactionGrayedStates: () -> Unit = {}

        /**
         * `RefreshUnitSprites`: rebuilds the map sprites of all units. Default no-op;
         * `Fe8Boot` sets the `UnitSpriteHandles.refreshUnitSprites` port.
         */
        public var refreshUnitSprites: () -> Unit = {}

        /**
         * `ProcessTurnSupportExp` (bmreliance.c:177): gives support points to pairs of player
         * units that are adjacent (or one carries the other); skipped on turn 1 and on extra
         * maps. Stand-in until M6-H.
         */
        public var processTurnSupportExp: () -> Unit = {}

        /**
         * `UpdatePrevDeployStates` (bmunit.c:1401): copies `US_NOT_DEPLOYED` into `US_BIT21` and
         * `US_BIT16` into `US_BIT26` for every unit, saves the unit word structs during the prep
         * screen and calls `ResetAllPlayerUnitState`. Stand-in (M6-I).
         */
        public var updatePrevDeployStates: () -> Unit = {}

        /**
         * `TickActiveFactionTurn` (bmunit.c:1138): for the units of the current faction, counts
         * down barrier, torch and status durations (a status with `UNIT_STATUS_RECOVER` does not
         * count down). Runs after [BMapMain.switchPhases], so it ticks the phase that starts.
         * `Fe8Boot` sets `PhaseStartEnd.tickActiveFactionTurn`.
         */
        public var tickActiveFactionTurn: () -> Unit = {}

        /**
         * `DecayTraps` (bmtrick.c:692): counts down torchlight and light rune traps and removes
         * them at zero. Stand-in until M6-H.
         */
        public var decayTraps: () -> Unit = {}

        /**
         * `sub_80155C4` (bm.c, after `GotoChapterWithoutSave`): when flag 3 is set, registers the
         * chapter time and turn count, then computes the chapter rankings, runs
         * `ChapterChangeUnitCleanup` and `StartBattleMap(0)`, keeping `unk4A_1`. Stand-in until
         * M6-E.
         */
        public var sub80155C4: () -> Unit = {}

        /**
         * `RenderBmMap`: redraws the 15x10 tile window of the map into the BG3 tilemap from the
         * camera position. Stand-in until M6-E (resume paths).
         */
        public var renderBmMap: () -> Unit = {}

        /**
         * `StartMapSongBgm`: starts the map music of the current faction's phase
         * (`GetCurrentMapMusicIndex`). Default no-op;
         * `Fe8Boot` sets `MapMusic.startMapSongBgm`.
         */
        public var startMapSongBgm: () -> Unit = {}

        /**
         * `StartMidFadeFromBlack`: starts a fade from black with speed 0x10. Default no-op;
         * `Fe8Boot` sets `screenFade.startMidFadeFromBlack`.
         */
        public var startMidFadeFromBlack: () -> Unit = {}

        /**
         * `WaitForFade`: true once no fade is running (the C breaks the proc then). Default true;
         * `Fe8Boot` sets `!screenFade.fadeExists()`.
         */
        public var waitForFade: () -> Boolean = { true }

        /**
         * `GetBattleMapKind` ([BattleMapKind]). Default: always a story map; `Fe8Boot` sets
         * `BattleMapStart.hooks.getBattleMapKind`.
         */
        public var getBattleMapKind: () -> Int = { BattleMapKind.STORY }

        /**
         * `gProcScr_ChapterIntro` (0x0859B1B0): the chapter intro (title card) shown before the
         * map. Default: ends at once; `Fe8Boot` sets the [ChapterIntro] script on the whole ROM.
         */
        public var chapterIntro: ProcScript = standIn("gProcScr_ChapterIntro")

        /**
         * `gProcScr_ChapterIntroTitleOnly`: the chapter intro with the title card only, shown
         * after [BMapMain.gotoChapterWithoutSave] (label 2). Stand-in until M6-E: ends at once.
         */
        public var chapterIntroTitleOnly: ProcScript = standIn("gProcScr_ChapterIntroTitleOnly")

        /**
         * `ProcScr_PhaseIntro` (0x0859AF80): the phase banner animation at the start of each
         * phase (it ends at once when the faction has no units). Default: ends at once;
         * `Fe8Boot` sets the `PhaseIntro` script.
         */
        public var phaseIntro: ProcScript = standIn("ProcScr_PhaseIntro")

        /**
         * `gProcScr_StatusDecayDisplay` (bmusailment.c:806): shows units whose status wears off
         * after [tickActiveFactionTurn]. Stand-in
         * until M6-D: ends at once.
         */
        public var statusDecayDisplay: ProcScript = standIn("gProcScr_StatusDecayDisplay")

        /**
         * `gProcScr_TerrainHealDisplay` (bmusailment.c:583): shows units healed by the terrain
         * they stand on at the start of the phase. Stand-in until M6-D: ends at once.
         */
        public var terrainHealDisplay: ProcScript = standIn("gProcScr_TerrainHealDisplay")

        /**
         * `gProcScr_PoisonDamageDisplay` (bmusailment.c:649): shows the poison damage taken at
         * the start of the phase. Stand-in until M6-D: ends at once.
         */
        public var poisonDamageDisplay: ProcScript = standIn("gProcScr_PoisonDamageDisplay")

        /**
         * `gProcScr_GorgonEggHatchDisplay` (bmusailment.c:731): hatches Gorgon eggs at the start
         * of the phase. Stand-in until M6-I: ends at once.
         */
        public var gorgonEggHatchDisplay: ProcScript = standIn("gProcScr_GorgonEggHatchDisplay")

        /**
         * `gProcScr_UpdateTraps` (bmtrick.c:17): counts down the traps, computes and displays
         * the trap damage. Stand-in until M6-H: ends at once.
         */
        public var updateTraps: ProcScript = standIn("gProcScr_UpdateTraps")

        /**
         * `gProcScr_BerserkCpPhase` (cp_phase.c:30): AI phase for berserked units (it runs
         * after every phase, including the player phase). Stand-in until M6-D: ends at once.
         */
        public var berserkCpPhase: ProcScript = standIn("gProcScr_BerserkCpPhase")

        /**
         * `gProcScr_PlayerPhase` (0x0859AAD8). Default: never ends; Fe8Boot sets the
         * transcribed [PlayerPhase.script].
         */
        public var playerPhase: ProcScript = idle("gProcScr_PlayerPhase")

        /**
         * `gProcScr_CpPhase`: the AI phase of the RED and GREEN factions. Stand-in until M6-D:
         * never ends.
         */
        public var cpPhase: ProcScript = idle("gProcScr_CpPhase")

        private fun standIn(name: String) = ProcScript(name, listOf(end()))

        private fun idle(name: String) = ProcScript(name, listOf(block(), end()))
    }

    /**
     * `gActionData.suspendPointType` as `BmMain_SuspendBeforePhase` leaves it (the action data is
     * not ported yet); `SUSPEND_POINT_PHASECHANGE` after that call. Stand-in until M6-B.
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
     * [BattleMapStart.BMapMainState]. Labels: 0 chapter intro, 1 beginning events, 11 clear flag
     * 0x84, 3 change phase, 9 phase start displays, 5 run the phase, 2 chapter change
     * ([gotoChapterWithoutSave]), 4/6/7/8 resume entries that redraw the map and fade in, then
     * continue at 5 / resume the player phase then 3 / 3 after berserk / 9; 10 resumes the
     * player phase without a redraw.
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
     * Transcribed from src/bm.c: CallBeginningEvents (not verified: no test checks it against a
     *   trace yet)
     *
     * Calls the beginning scene events of the chapter (the common skirmish beginning on a
     * skirmish map) through [Hooks.callEvent] with exec type 1 and [proc] as the parent proc.
     * Returns false, so `PROC_CALL_2` waits.
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
     * Clears the acted state of the faction that ends its phase, refreshes the sprites, runs
     * [switchPhases] and the phase switch events. Returns false (`PROC_CALL_2` waits) if events
     * started.
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
     * Always true: the C returns false (after `sub_80832D0`) only if `sub_80832D4` is 1, and that
     * returns 0 in the base game (eventinfo.c:352), so that branch is not written. Verified by
     * the phase proc starting right after the displays in the red, green and blue phase start.
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
     * Transcribed from src/bm.c: BmMain_ResumePlayerPhase (not verified: no test checks it against
     *   a trace yet)
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
     * [Hooks.decayTraps] and returns false (verified on the green phase of
     * `ram_trace_prologue_enemy_phase`; `gProcScr_UpdateTraps` itself is a hook).
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
     * Transcribed from src/bm.c: BmMain_StartIntroFx (not verified: no test checks it against a
     *   trace yet)
     *
     * Starts [Hooks.chapterIntro] blocking [proc] except in `CHAPTER_CASTLE_FRELIA` (0x38) and
     * in `CHAPTER_L_5` (0x06) with flag 0x88 set (flag 0x88 has no name in the decomp).
     */
    public fun startIntroFx(proc: Proc) {
        if (playSt.chapterIndex == 0x38) return
        if (playSt.chapterIndex == 0x06 && flags.checkFlag(0x88)) return
        engine.startBlocking(hooks.chapterIntro, proc)
    }

    /**
     * Transcribed from src/bm.c: UndeployEveryone (not verified: no trace separates it from a
     *   no-op)
     *
     * Clears flag 0x84 and, unless `unk4A_1` (a `PlaySt` bit of unknown meaning) is set, clears
     * `US_NOT_DEPLOYED` of every valid unit with id 1..0x3F (player units).
     */
    public fun undeployEveryone() {
        flags.clearFlag(0x84)
        if (playSt.unk4A1 != 0) return
        for (unitId in 1 until FACTION_GREEN) {
            if (!units.isValid(unitId)) continue
            units.setState(unitId, units.state(unitId) and BattleMapStart.US_NOT_DEPLOYED.inv())
        }
    }

    /**
     * Transcribed from src/bm.c: GotoChapterWithoutSave (not verified: no test checks it against a
     *   trace yet)
     *
     * Sets the chapter index, sends the running BMapMain to label 2 (chapter change) and ends
     * the player, computer and berserk phase procs.
     */
    public fun gotoChapterWithoutSave(chapterId: Int) {
        playSt.chapterIndex = chapterId
        engine.goto(engine.find(script)!!, 2)
        engine.endEach(hooks.playerPhase)
        engine.endEach(hooks.cpPhase)
        engine.endEach(hooks.berserkCpPhase)
    }

    /**
     * Transcribed from src/bm.c: SetEventId_0x84 (not verified: no trace separates it from a
     *   no-op); `SetFlag(0x84)`). Sets flag 0x84 (no decomp name); it stays set
     * while the beginning events run.
     */
    public fun setEventId84() {
        flags.setFlag(0x84)
    }

    /**
     * Transcribed from src/bm.c: UnsetEventId_0x84 (not verified: no trace separates it from a
     *   no-op); `ClearFlag(0x84)`). Clears flag 0x84 (no decomp name) after the beginning events.
     */
    public fun unsetEventId84() {
        flags.clearFlag(0x84)
    }

    /** Faction, save, and event script constants. */
    public companion object {
        /** `FACTION_BLUE`: player faction, `gPlaySt.faction` value and unit id base (0x00). */
        public const val FACTION_BLUE: Int = 0x00

        /** `FACTION_GREEN`: ally (NPC) faction (0x40). */
        public const val FACTION_GREEN: Int = 0x40

        /** `FACTION_RED`: enemy faction (0x80). */
        public const val FACTION_RED: Int = 0x80

        /** `SUSPEND_POINT_PHASECHANGE`: suspend save point type at phase switch (9). */
        public const val SUSPEND_POINT_PHASECHANGE: Int = 9

        /** `SAVE_ID_SUSPEND`: suspend save slot index (3). */
        public const val SAVE_ID_SUSPEND: Int = 3

        /** `EventScr_SkirmishCommonBeginning` (ROM 0x089FFC30): skirmish beginning events. */
        public const val EVENT_SKIRMISH_COMMON_BEGINNING: Int = 0x089FFC30
    }
}
