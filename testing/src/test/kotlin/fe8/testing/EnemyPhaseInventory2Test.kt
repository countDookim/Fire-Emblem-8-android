package fe8.testing

import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Inventory (not a port claim, EP-0b) of the procs alive in four windows of
 * `ram_trace_prologue_clear` (EP-cap2) that [EnemyPhaseInventoryTest] does not cover: the boss's
 * flag-gated turn event (f2964..3722), the boss's move-only enemy phase (f3726..3877), the turn 3
 * player attack on the boss (f4130..4892) and the ending scene's start (f4894..5000). Rows are
 * built as in [EnemyPhaseInventoryTest.rows] from the `procs` watch, clipped to the window.
 * The function lists derived from these rows and from a scratch function-entry trace are in
 * docs/plans/M6C-J-breakdown.md ("EP-0b inventory").
 */
class EnemyPhaseInventory2Test {
    private val inventory = EnemyPhaseInventoryTest()
    private val ref = ReferenceData.fromEnvironment()

    private fun check(expected: List<String>, first: Int, last: Int) {
        assertEquals(
            expected.sorted(),
            inventory.rows(RUN, first, last).sorted(),
            "$RUN f$first..f$last",
        )
    }

    private fun clearRun(): ReferenceData.Run {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run(RUN)
        assumeTrue(run.hasWatch("red"), "$RUN has no red watch")
        return run
    }

    @Test
    fun `boss turn event matches the procs watch f2964 to f3722`() {
        check(BOSS_TURN_EVENT_ROWS, 2964, 3722)
    }

    @Test
    fun `boss move only enemy phase matches the procs watch f3726 to f3877`() {
        check(BOSS_MOVE_ROWS, 3726, 3877)
    }

    @Test
    fun `turn 3 attack on the boss matches the procs watch f4130 to f4892`() {
        check(BOSS_BATTLE_ROWS, 4130, 4892)
    }

    @Test
    fun `ending scene start matches the procs watch f4894 to f5000`() {
        check(ENDING_START_ROWS, 4894, 5000)
    }

    @Test
    fun `the boss turn event clears the boss AI scripts and the phase decision is a move`() {
        val run = clearRun()
        val red = run.watch("red")
        // Red 0 (the boss): ai1 at 0x42, a-script position 0x43, ai2 at 0x44, b-script position 0x45
        fun ai(step: Int) = (0x42..0x45).map { red.u8(step, it) }
        assertEquals(listOf(6, 1, 3, 1), ai(3718))
        assertEquals(listOf(0, 0, 0, 0), ai(3719))
        assertEquals(listOf(0, 0, 0, 0), ai(3803))
        // the decision at f3804: no action (0), unit 0x81, move to (9,6), nothing else set
        assertEquals(listOf(0, 1, 0, 1), ai(3877))
        val decision = run.watch("aidecision")
        assertEquals(MOVE_ONLY_BOSS, decision.step(3804).toList())
        assertEquals(MOVE_ONLY_BOSS, decision.step(3877).toList())
    }

    private companion object {
        const val RUN = "ram_trace_prologue_clear"
        val MOVE_ONLY_BOSS = listOf(0, 0x81, 9, 6, 0, 0, 0, 0, 0, 0, 1, 0).map { it.toByte() }

        val BOSS_TURN_EVENT_ROWS = listOf(
            "ProcScr_PalFade <- gProcScr_E_FACE 2972-2984",
            "ProcScr_PalFade <- gProcScr_E_FACE 3706-3714",
            "ProcScr_StdEventEngine <- tree 3 2964-3721",
            "ProcScr_TemporaryLock <- gProcScr_Talk 2972-2980",
            "gMusicProc3Script <- tree 3 2964-3012",
            "gProcScr_0859124C <- gProcScr_E_FACE 2972-3714",
            "gProcScr_E_FACE <- tree 5 2972-3714",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 2972-3714",
            "gProcScr_FaceEndIn8Frames <- tree 3 3706-3714",
            "gProcScr_GameControl <- tree 3 2964-3722",
            "gProcScr_Talk <- tree 3 2966-3703",
            "gProcScr_TalkBubbleOpen <- tree 3 2982-2993",
            "gProcScr_TalkOpen <- gProcScr_Talk 2982-2994",
            "gProcScr_TalkShiftClear <- gProcScr_Talk 3327-3343",
            "gProcScr_TalkShiftClear <- gProcScr_Talk 3496-3512",
            "gProcScr_TalkSkipListener <- tree 3 2968-3703",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 3259-3321",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 3685-3701",
            "gProc_BMapMain <- tree 2 2964-3722",
            "gProc_MapTask <- tree 4 2964-3722",
            "gUnknown_08591DE8 <- ProcScr_StdEventEngine 3706-3717",
            "sProc_BMVSync <- tree 0 2964-3722",
        )

        val BOSS_MOVE_ROWS = listOf(
            "ProcScr_Mu <- tree 5 3804-3831",
            "ProcScr_MuStepSe <- tree 3 3805-3805",
            "ProcScr_MuStepSe <- tree 3 3812-3813",
            "ProcScr_MuStepSe <- tree 3 3820-3821",
            "ProcScr_MuStepSe <- tree 3 3828-3829",
            "ProcScr_PhaseIntro <- gProc_BMapMain 3726-3796",
            "ProcScr_StdEventEngine <- tree 3 3877-3877",
            "gProcScr_BerserkCpOrder <- gProcScr_BerserkCpPhase 3856-3856",
            "gProcScr_BerserkCpOrder <- gProcScr_BerserkCpPhase 3872-3872",
            "gProcScr_BerserkCpPhase <- gProc_BMapMain 3856-3857",
            "gProcScr_BerserkCpPhase <- gProc_BMapMain 3872-3873",
            "gProcScr_CpDecide <- gProcScr_CpOrder 3803-3851",
            "gProcScr_CpOrder <- gProcScr_CpPhase 3803-3853",
            "gProcScr_CpOrder <- gProcScr_CpPhase 3868-3869",
            "gProcScr_CpPerform <- gProcScr_CpDecide 3804-3831",
            "gProcScr_CpPhase <- gProc_BMapMain 3803-3854",
            "gProcScr_CpPhase <- gProc_BMapMain 3868-3870",
            "gProcScr_GameControl <- tree 3 3726-3877",
            "gProcScr_PhaseIntroBlendBox <- ProcScr_PhaseIntro 3726-3787",
            "gProcScr_PhaseIntroSquares <- ProcScr_PhaseIntro 3726-3792",
            "gProcScr_PhaseIntroText <- ProcScr_PhaseIntro 3726-3794",
            "gProcScr_PhaseIntroUnk <- gProcScr_PhaseIntroText 3733-3794",
            "gProcScr_UpdateTraps <- gProc_BMapMain 3875-3875",
            "gProc_BMapMain <- tree 2 3726-3877",
            "gProc_MapTask <- tree 4 3726-3877",
            "sProcScr_BMXFADE <- tree 3 3832-3847",
            "sProc_BMVSync <- tree 0 3726-3877",
        )

        val BOSS_BATTLE_ROWS = listOf(
            "ProcScr_ApProc <- tree 3 4550-4577",
            "ProcScr_BattleEventEngine <- tree 3 4278-4460",
            "ProcScr_BattleEventEngine <- tree 3 4636-4740",
            "ProcScr_CallDelayedArg <- tree 3 4485-4506",
            "ProcScr_CallDelayedArg <- tree 3 4544-4565",
            "ProcScr_CallDelayedArg <- tree 3 4590-4611",
            "ProcScr_GreenTextColor <- sProc_Menu 4181-4209",
            "ProcScr_GreenTextColor <- sProc_Menu 4211-4239",
            "ProcScr_MapAnimBattle <- tree 3 4275-4890",
            "ProcScr_MapAnimDefaultItemEffect <- ProcScr_MapAnimBattle 4485-4535",
            "ProcScr_MapAnimDefaultItemEffect <- ProcScr_MapAnimBattle 4544-4581",
            "ProcScr_MapAnimDefaultItemEffect <- ProcScr_MapAnimBattle 4590-4627",
            "ProcScr_MapAnimExpBar <- ProcScr_MapAnimBattle 4776-4878",
            "ProcScr_MapBattleInfoBox <- tree 3 4468-4635",
            "ProcScr_Mu <- tree 5 4130-4892",
            "ProcScr_Mu <- tree 5 4275-4772",
            "ProcScr_MuDeathFade <- ProcScr_Mu 4742-4772",
            "ProcScr_MuHitFlash <- ProcScr_Mu 4491-4507",
            "ProcScr_MuHitFlash <- ProcScr_Mu 4596-4612",
            "ProcScr_MuHitFlash <- ProcScr_Mu 4742-4758",
            "ProcScr_MuStepSe <- tree 3 4131-4131",
            "ProcScr_MuStepSe <- tree 3 4133-4134",
            "ProcScr_MuStepSe <- tree 3 4140-4141",
            "ProcScr_MuStepSe <- tree 3 4151-4152",
            "ProcScr_MuStepSe <- tree 3 4154-4155",
            "ProcScr_MuStepSe <- tree 3 4161-4162",
            "ProcScr_MuStepSe <- tree 3 4172-4173",
            "ProcScr_MuStepSe <- tree 3 4175-4176",
            "ProcScr_PalFade <- ProcScr_Mu 4491-4511",
            "ProcScr_PalFade <- ProcScr_Mu 4596-4616",
            "ProcScr_PalFade <- ProcScr_Mu 4742-4762",
            "ProcScr_PalFade <- gProcScr_E_FACE 4285-4297",
            "ProcScr_PalFade <- gProcScr_E_FACE 4446-4454",
            "ProcScr_PalFade <- gProcScr_E_FACE 4642-4654",
            "ProcScr_PalFade <- gProcScr_E_FACE 4726-4734",
            "ProcScr_TemporaryLock <- gProcScr_Talk 4285-4293",
            "ProcScr_TemporaryLock <- gProcScr_Talk 4642-4650",
            "gProcCmd_MenuItemPanel <- sProc_Menu 4210-4239",
            "gProcScr_0859124C <- gProcScr_E_FACE 4210-4239",
            "gProcScr_0859124C <- gProcScr_E_FACE 4285-4454",
            "gProcScr_0859124C <- gProcScr_E_FACE 4642-4734",
            "gProcScr_BKSEL <- tree 3 4240-4269",
            "gProcScr_E_FACE <- tree 5 4210-4239",
            "gProcScr_E_FACE <- tree 5 4285-4454",
            "gProcScr_E_FACE <- tree 5 4642-4734",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 4210-4239",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 4285-4454",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 4642-4734",
            "gProcScr_FaceEndIn8Frames <- tree 3 4446-4454",
            "gProcScr_FaceEndIn8Frames <- tree 3 4726-4734",
            "gProcScr_GameControl <- tree 3 4130-4892",
            "gProcScr_PlayerPhase <- gProc_BMapMain 4130-4892",
            "gProcScr_Talk <- tree 3 4279-4443",
            "gProcScr_Talk <- tree 3 4636-4723",
            "gProcScr_TalkBubbleOpen <- tree 3 4295-4306",
            "gProcScr_TalkBubbleOpen <- tree 3 4652-4663",
            "gProcScr_TalkOpen <- gProcScr_Talk 4295-4307",
            "gProcScr_TalkOpen <- gProcScr_Talk 4652-4664",
            "gProcScr_TalkSkipListener <- tree 3 4281-4443",
            "gProcScr_TalkSkipListener <- tree 3 4638-4723",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 4432-4441",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 4709-4721",
            "gProcScr_TargetSelection <- tree 3 4240-4269",
            "gProc_BMapMain <- tree 2 4130-4892",
            "gProc_MapTask <- tree 4 4130-4892",
            "gUnknown_08591DE8 <- ProcScr_BattleEventEngine 4446-4457",
            "gUnknown_08591DE8 <- ProcScr_BattleEventEngine 4726-4737",
            "sProcScr_CombatAction <- gProcScr_PlayerPhase 4274-4892",
            "sProcScr_MoveLimitView <- tree 4 4181-4209",
            "sProcScr_MoveLimitView <- tree 4 4212-4269",
            "sProcScr_MoveLimitViewChange <- sProcScr_MoveLimitView 4181-4187",
            "sProcScr_MoveLimitViewChange <- sProcScr_MoveLimitView 4212-4217",
            "sProc_BMVSync <- tree 0 4130-4892",
            "sProc_Menu <- tree 3 4181-4209",
            "sProc_Menu <- tree 3 4210-4239",
            "sProc_MenuItem <- sProc_Menu 4181-4209",
            "sProc_MenuItem <- sProc_Menu 4181-4209",
            "sProc_MenuItem <- sProc_Menu 4181-4209",
            "sProc_MenuItem <- sProc_Menu 4210-4239",
            "sProc_MenuItem <- sProc_Menu 4210-4239",
        )

        val ENDING_START_ROWS = listOf(
            "ProcScr_FadeFromBlack <- ProcScr_StdEventEngine 4916-4931",
            "ProcScr_FadeToBlack <- ProcScr_StdEventEngine 4895-4910",
            "ProcScr_Mu <- tree 5 4894-5000",
            "ProcScr_PalFade <- gProcScr_E_FACE 4939-4951",
            "ProcScr_PalFade <- gProcScr_E_FACE 4939-4951",
            "ProcScr_StdEventEngine <- tree 3 4894-5000",
            "ProcScr_TemporaryLock <- gProcScr_Talk 4939-4947",
            "ProcScr_TemporaryLock <- gProcScr_Talk 4939-4947",
            "gMusicProc3Script <- tree 3 4894-4944",
            "gProcScr_0859124C <- gProcScr_E_FACE 4939-5000",
            "gProcScr_0859124C <- gProcScr_E_FACE 4939-5000",
            "gProcScr_E_FACE <- tree 5 4939-5000",
            "gProcScr_E_FACE <- tree 5 4939-5000",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 4939-5000",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 4939-5000",
            "gProcScr_GameControl <- tree 3 4894-5000",
            "gProcScr_PlayerPhase <- gProc_BMapMain 4894-5000",
            "gProcScr_Talk <- tree 3 4933-5000",
            "gProcScr_TalkBubbleOpen <- tree 3 4949-4960",
            "gProcScr_TalkOpen <- gProcScr_Talk 4949-4961",
            "gProcScr_TalkSkipListener <- tree 3 4935-5000",
            "gProc_BMapMain <- tree 2 4894-5000",
            "gProc_MapTask <- tree 4 4894-5000",
            "sProc_BMVSync <- tree 0 4894-5000",
        )
    }
}
