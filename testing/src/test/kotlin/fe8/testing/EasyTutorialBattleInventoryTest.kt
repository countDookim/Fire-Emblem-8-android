package fe8.testing

import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Inventory (not a port claim, PC-10i-battle) of the Easy-tutorial attack sequence in
 * `ram_trace_newgame_easy` (PC-cap3). Proc rows come from [EnemyPhaseInventoryTest.rows] over the
 * `procs` watch (1-based step index). Battle-animation procs (`ekr*`, `efx*`, `eobj*`) are left
 * out of the rows: they are covered by the BA-* inventories. Sub-windows: turn-2 player phase and
 * select event `f22214..22447`, Attack-only menu and weapon list `f22448..22565`, forecast box
 * `f22566..23373`, map battle `f23374..24028`, Tutorial8 `f24029..24209`, Seth scripted kill,
 * one-enemy-left and end turn `f24210..24870`, O'Neill vs Seth `f25148..25750`.
 * Inputs: A f22230 (select Eirika), A f22440 (destination = her tile), A f22530 (Attack),
 * A f22560 (Rapier), A f23370 (target), plus text-page A presses on the 30-frame grid.
 */
@Timeout(300)
class EasyTutorialBattleInventoryTest {
    private val inventory = EnemyPhaseInventoryTest()
    private val ref = ReferenceData.fromEnvironment()

    private fun check(expected: List<String>, first: Int, last: Int) {
        val rows = inventory.rows(RUN, first, last).filterNot { row -> HIDDEN.any { it in row } }
        assertEquals(expected.sorted(), rows.sorted(), "$RUN f$first..f$last")
    }

    private fun run(): ReferenceData.Run {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run(RUN)
        assumeTrue(run.hasWatch("procs") && run.hasWatch("red"), "$RUN watches missing")
        return run
    }

    @Test
    fun `turn 2 select event matches the procs watch f22214 to f22447`() {
        check(SELECT_ROWS, 22214, 22447)
    }

    @Test
    fun `attack-only menu and weapon list match the procs watch f22448 to f22565`() {
        check(MENU_ROWS, 22448, 22565)
    }

    @Test
    fun `forecast box matches the procs watch f22566 to f23373`() {
        check(FORECAST_ROWS, 22566, 23373)
    }

    @Test
    fun `map battle and kill match the procs watch f23374 to f24028`() {
        check(BATTLE_ROWS, 23374, 24028)
    }

    @Test
    fun `post-action event matches the procs watch f24029 to f24209`() {
        check(POST_ACTION_ROWS, 24029, 24209)
    }

    @Test
    fun `scripted kill, one enemy left and end turn match the procs watch f24210 to f24870`() {
        check(SCRIPTED_KILL_ROWS, 24210, 24870)
    }

    @Test
    fun `O'Neill vs Seth scripted battle matches the procs watch f25148 to f25750`() {
        check(ONEILL_ROWS, 25148, 25750)
    }

    @Test
    fun `tutorial queue, idle callback, units and flags follow the sequence`() {
        val run = run()
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val playSt = run.watch("gplayst")
        val blue = run.watch("blue")
        val red = run.watch("red")
        val flags = run.watch("flags")
        val procs = run.watch("procs")
        fun dump(step: Int) = ProcArrayDump.ofWatch(procs, step, names)
        fun idle(step: Int) = dump(step).find("gProcScr_PlayerPhase")?.u32(PROC_IDLE_CB) ?: -1
        fun flag(step: Int, id: Int) = (flags.u8(step, id shr 3) shr (id and 7)) and 1
        // gPlaySt tutorial_exec_type (+0x4A bits 4..7) and tutorial_counter (+0x4B): queued
        // TUTORIAL_EVT_TYPE_* / (index of the EventListScr_Prologue_Tutorial entry + 1)
        fun queue(step: Int) = (playSt.u8(step, PLAYST_TUT_TYPE) shr 4) to playSt.u8(step, PLAYST_TUT_COUNTER)
        assertEquals(0 to 0, queue(22136))
        assertEquals(2 to 5, queue(22137)) // ONSELECT, Tutorial4 (TutMessageTurn2 end)
        assertEquals(0 to 0, queue(22231)) // A f22230 ran it
        assertEquals(3 to 6, queue(22404)) // DESTSELECTED, Tutorial5
        assertEquals(0 to 0, queue(22440))
        assertEquals(4 to 7, queue(22441)) // AFTERMOVE, Tutorial6
        assertEquals(0 to 0, queue(22448))
        assertEquals(5 to 8, queue(22524)) // FORECAST, Tutorial7
        assertEquals(0 to 0, queue(22566))
        assertEquals(1 to 9, queue(23366)) // POSTACTION, Tutorial8
        assertEquals(0 to 0, queue(24029))
        assertEquals(6 to 10, queue(24199)) // PLAYERPHASE, Tutorial9
        assertEquals(0 to 0, queue(24210))
        // PlayerPhase idle callback: the select (A f22230) leaves PlayerPhase_RangeDisplayIdle (0x0801CD1D);
        // Tutorial5 (ASMC) swaps in PlayerPhase_RangeDisplayIdle_ForceAPress f22442, then
        // PlayerPhase_WaitForUnitMovement f22446 (same chain as PC-10i-input f19021/f19030)
        assertEquals(0x0801CD1D, idle(22425))
        assertEquals(0x0801CD1D, idle(22438))
        assertEquals(0x0801D819, idle(22442))
        assertEquals(0x0801D64D, idle(22446))
        // Soldier gUnitArrayRed[1] (5,5) HP 8 dies in the map battle (written back f23995, struct cleared f24027);
        // Soldier gUnitArrayRed[2] (9,6) HP 20 dies to the scripted kill (f24647, cleared f24699)
        assertEquals(8, red.u8(23994, UNIT_SIZE + UNIT_CUR_HP))
        assertEquals(0, red.u8(23995, UNIT_SIZE + UNIT_CUR_HP))
        assertEquals(0, red.u32(24027, UNIT_SIZE))
        assertEquals(20, red.u8(24646, 2 * UNIT_SIZE + UNIT_CUR_HP))
        assertEquals(0, red.u8(24647, 2 * UNIT_SIZE + UNIT_CUR_HP))
        assertEquals(0, red.u32(24699, 2 * UNIT_SIZE))
        // Seth moves (4,4) -> (9,5) f24260; Eirika stays (4,5), HP 16
        assertEquals(4, blue.u8(24259, UNIT_X))
        assertEquals(9, blue.u8(24260, UNIT_X))
        assertEquals(16, blue.u8(24700, UNIT_SIZE + UNIT_CUR_HP))
        // temporary flag 0x107 (TMP7): set since f15878, cleared f24859 (the one-enemy-left event consumed it),
        // set again f24867 (phase end); 0x106 pulses at the events (f24029, f24199, f24203, f24699)
        assertEquals(1, flag(24858, 0x107))
        assertEquals(0, flag(24859, 0x107))
        assertEquals(0, flag(24866, 0x107))
        assertEquals(1, flag(24867, 0x107))
        // O'Neill (9,6) after his own move (10,8) -> (9,6) f25174; Seth HP 13 -> 11
        assertEquals(13, blue.u8(25700, UNIT_CUR_HP))
        assertEquals(11, blue.u8(25729, UNIT_CUR_HP))
        assertNotNull(dump(25176).find("ProcScr_ScriptBattleDeamon"))
        assertNull(dump(25731).find("ProcScr_ScriptBattleDeamon"))
        assertNotNull(dump(24262).find("ProcScr_ScriptBattleDeamon"))
        assertNull(dump(24649).find("ProcScr_ScriptBattleDeamon"))
    }

    private companion object {
        const val RUN = "ram_trace_newgame_easy"
        const val UNIT_SIZE = 0x48
        const val UNIT_X = 0x10
        const val UNIT_CUR_HP = 0x13
        const val PROC_IDLE_CB = 0x0C
        const val PLAYST_TUT_TYPE = 0x4A
        const val PLAYST_TUT_COUNTER = 0x4B
        val HIDDEN = listOf("ekr", "Ekr", "efx", "Efx", "eobj")

        val SELECT_ROWS = listOf(
            "ProcScr_BoxDialogueDrawTextExt <- tree 3 22259-22383",
            "ProcScr_EventDisplayCursor <- ProcScr_StdEventEngine 22233-22402",
            "ProcScr_GreenTextColor <- gProcScr_GoalDisplay 22223-22230",
            "ProcScr_MergeBoxDialogue <- tree 3 22252-22395",
            "ProcScr_Mu <- tree 5 22228-22447",
            "ProcScr_MuStepSe <- tree 3 22446-22446",
            "ProcScr_StdEventEngine <- tree 3 22231-22407",
            "ProcScr_StdEventEngine <- tree 3 22440-22443",
            "ProcScr_TalkBoxIdle <- tree 0 22251-22394",
            "gProcScr_0859ACE8 <- gProcScr_PlayerPhase 22231-22408",
            "gProcScr_BoxDialogue <- tree 3 22251-22400",
            "gProcScr_GameControl <- tree 3 22214-22447",
            "gProcScr_GoalDisplay <- tree 3 22223-22230",
            "gProcScr_PlayerPhase <- gProc_BMapMain 22220-22447",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 22260-22291",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 22350-22381",
            "gProcScr_TerrainDisplay <- tree 3 22223-22230",
            "gProcScr_UnitDisplay_MinimugBox <- tree 3 22223-22230",
            "gProc_BMapMain <- tree 2 22214-22447",
            "gProc_MapTask <- tree 4 22214-22447",
            "gUnknown_08A01800 <- tree 3 22252-22258",
            "sProcScr_MoveLimitView <- tree 4 22232-22444",
            "sProcScr_MoveLimitViewChange <- sProcScr_MoveLimitView 22232-22237",
            "sProc_BMVSync <- tree 0 22214-22447",
        )
        val MENU_ROWS = listOf(
            "ProcScr_BoxDialogueDrawTextExt <- tree 3 22457-22503",
            "ProcScr_GreenTextColor <- sProc_Menu 22448-22529",
            "ProcScr_GreenTextColor <- sProc_Menu 22531-22559",
            "ProcScr_MergeBoxDialogue <- tree 3 22450-22515",
            "ProcScr_Mu <- tree 5 22448-22565",
            "ProcScr_StdEventEngine <- tree 3 22448-22525",
            "ProcScr_TalkBoxIdle <- tree 0 22449-22514",
            "gProcCmd_MenuItemPanel <- sProc_Menu 22530-22559",
            "gProcScr_0859124C <- gProcScr_E_FACE 22530-22559",
            "gProcScr_BKSEL <- tree 3 22560-22565",
            "gProcScr_BoxDialogue <- tree 3 22449-22520",
            "gProcScr_E_FACE <- tree 5 22530-22559",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 22530-22559",
            "gProcScr_GameControl <- tree 3 22448-22565",
            "gProcScr_PlayerPhase <- gProc_BMapMain 22448-22565",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 22471-22501",
            "gProcScr_TargetSelection <- tree 3 22560-22565",
            "gProc_BMapMain <- tree 2 22448-22565",
            "gProc_MapTask <- tree 4 22448-22565",
            "gUnknown_08A01800 <- tree 3 22450-22456",
            "sProcScr_MoveLimitView <- tree 4 22448-22529",
            "sProcScr_MoveLimitView <- tree 4 22531-22565",
            "sProcScr_MoveLimitViewChange <- sProcScr_MoveLimitView 22448-22453",
            "sProcScr_MoveLimitViewChange <- sProcScr_MoveLimitView 22531-22537",
            "sProc_BMVSync <- tree 0 22448-22565",
            "sProc_Menu <- tree 3 22448-22529",
            "sProc_Menu <- tree 3 22530-22559",
            "sProc_MenuItem <- sProc_Menu 22448-22529",
            "sProc_MenuItem <- sProc_Menu 22530-22559",
        )
        val FORECAST_ROWS = listOf(
            "ProcScr_BoxDialogueDrawTextExt <- tree 3 22577-23345",
            "ProcScr_MergeBoxDialogue <- tree 3 22570-23357",
            "ProcScr_Mu <- tree 5 22566-23373",
            "ProcScr_StdEventEngine <- tree 3 22566-23368",
            "ProcScr_TalkBoxIdle <- tree 0 22569-23356",
            "gProcScr_BKSEL <- tree 3 22566-23369",
            "gProcScr_BoxDialogue <- tree 3 22569-23362",
            "gProcScr_GameControl <- tree 3 22566-23373",
            "gProcScr_PlayerPhase <- gProc_BMapMain 22566-23373",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 22591-22621",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 22710-22741",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 22800-22831",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 22890-22921",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 22980-23011",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 23100-23131",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 23220-23251",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 23310-23341",
            "gProcScr_TargetSelection <- tree 3 22566-23369",
            "gProc_BMapMain <- tree 2 22566-23373",
            "gProc_MapTask <- tree 4 22566-23373",
            "gUnknown_08A01800 <- tree 3 22570-22576",
            "sProcScr_MoveLimitView <- tree 4 22566-23369",
            "sProc_BMVSync <- tree 0 22566-23373",
        )
        val BATTLE_ROWS = listOf(
            "ProcScr_Mu <- tree 5 23374-23388",
            "ProcScr_Mu <- tree 5 23977-24028",
            "ProcScr_Mu <- tree 5 23995-24025",
            "ProcScr_MuDeathFade <- ProcScr_Mu 23995-24025",
            "ProcScr_MuHitFlash <- ProcScr_Mu 23995-24011",
            "ProcScr_PalFade <- ProcScr_Mu 23995-24015",
            "gMusicProc3Script <- tree 3 23407-23440",
            "gProcScr_GameControl <- tree 3 23374-24028",
            "gProcScr_PlayerPhase <- gProc_BMapMain 23374-24028",
            "gProc_BMapMain <- tree 2 23374-24028",
            "gProc_MapTask <- tree 4 23374-24028",
            "sProcScr_CombatAction <- gProcScr_PlayerPhase 23374-24027",
            "sProc_BMVSync <- tree 0 23374-24028",
            "sProc_DelayedBMapDispResume <- tree 3 23977-23978",
        )
        val POST_ACTION_ROWS = listOf(
            "ProcScr_EventDisplayCursor <- ProcScr_StdEventEngine 24029-24088",
            "ProcScr_Mu <- tree 5 24029-24206",
            "ProcScr_PalFade <- gProcScr_E_FACE 24097-24109",
            "ProcScr_PalFade <- gProcScr_E_FACE 24097-24109",
            "ProcScr_PalFade <- gProcScr_E_FACE 24186-24194",
            "ProcScr_PalFade <- gProcScr_E_FACE 24186-24194",
            "ProcScr_StdEventEngine <- tree 3 24029-24201",
            "ProcScr_StdEventEngine <- tree 3 24202-24205",
            "ProcScr_TemporaryLock <- gProcScr_Talk 24097-24105",
            "ProcScr_TemporaryLock <- gProcScr_Talk 24097-24105",
            "gProcScr_0859124C <- gProcScr_E_FACE 24097-24194",
            "gProcScr_0859124C <- gProcScr_E_FACE 24097-24194",
            "gProcScr_E_FACE <- tree 5 24097-24194",
            "gProcScr_E_FACE <- tree 5 24097-24194",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 24097-24194",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 24097-24194",
            "gProcScr_FaceEndIn8Frames <- tree 3 24186-24194",
            "gProcScr_FaceEndIn8Frames <- tree 3 24186-24194",
            "gProcScr_GameControl <- tree 3 24029-24209",
            "gProcScr_PlayerPhase <- gProc_BMapMain 24029-24209",
            "gProcScr_Talk <- tree 3 24091-24183",
            "gProcScr_TalkBubbleOpen <- tree 3 24107-24118",
            "gProcScr_TalkOpen <- gProcScr_Talk 24107-24119",
            "gProcScr_TalkSkipListener <- tree 3 24093-24183",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 24151-24181",
            "gProc_BMapMain <- tree 2 24029-24209",
            "gProc_MapTask <- tree 4 24029-24209",
            "gUnknown_08591DE8 <- ProcScr_StdEventEngine 24186-24197",
            "sProc_BMVSync <- tree 0 24029-24209",
        )
        val SCRIPTED_KILL_ROWS = listOf(
            "ProcScr_EventDisplayCursor <- ProcScr_StdEventEngine 24699-24758",
            "ProcScr_Mu <- tree 5 24210-24259",
            "ProcScr_Mu <- tree 5 24262-24278",
            "ProcScr_Mu <- tree 5 24629-24646",
            "ProcScr_Mu <- tree 5 24666-24697",
            "ProcScr_MuCtrl <- tree 5 24210-24259",
            "ProcScr_MuDeathFade <- ProcScr_Mu 24666-24697",
            "ProcScr_MuHitFlash <- ProcScr_Mu 24666-24683",
            "ProcScr_MuStepSe <- tree 3 24211-24214",
            "ProcScr_MuStepSe <- tree 3 24220-24221",
            "ProcScr_MuStepSe <- tree 3 24231-24232",
            "ProcScr_MuStepSe <- tree 3 24234-24235",
            "ProcScr_MuStepSe <- tree 3 24241-24242",
            "ProcScr_MuStepSe <- tree 3 24252-24253",
            "ProcScr_MuStepSe <- tree 3 24255-24256",
            "ProcScr_PalFade <- ProcScr_Mu 24666-24687",
            "ProcScr_PalFade <- gProcScr_E_FACE 24767-24779",
            "ProcScr_PalFade <- gProcScr_E_FACE 24846-24854",
            "ProcScr_ScriptBattleDeamon <- ProcScr_StdEventEngine 24262-24648",
            "ProcScr_StdEventEngine <- tree 3 24210-24861",
            "ProcScr_StdEventEngine <- tree 3 24867-24870",
            "ProcScr_TemporaryLock <- gProcScr_Talk 24767-24775",
            "gMusicProc3Script <- tree 3 24297-24330",
            "gMusicProc3Script <- tree 3 24867-24870",
            "gProcScr_0859124C <- gProcScr_E_FACE 24767-24854",
            "gProcScr_BerserkCpOrder <- gProcScr_BerserkCpPhase 24864-24864",
            "gProcScr_BerserkCpPhase <- gProc_BMapMain 24864-24865",
            "gProcScr_E_FACE <- tree 5 24767-24854",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 24767-24854",
            "gProcScr_FaceEndIn8Frames <- tree 3 24846-24854",
            "gProcScr_GameControl <- tree 3 24210-24870",
            "gProcScr_PlayerPhase <- gProc_BMapMain 24210-24862",
            "gProcScr_Talk <- tree 3 24761-24843",
            "gProcScr_Talk <- tree 3 24869-24870",
            "gProcScr_TalkBubbleOpen <- tree 3 24777-24788",
            "gProcScr_TalkOpen <- gProcScr_Talk 24777-24789",
            "gProcScr_TalkSkipListener <- tree 3 24763-24843",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 24809-24841",
            "gProc_BMapMain <- tree 2 24210-24870",
            "gProc_MapTask <- tree 4 24210-24870",
            "gUnknown_08591DE8 <- ProcScr_StdEventEngine 24846-24857",
            "sProcScr_BMXFADE <- tree 3 24647-24664",
            "sProc_BMVSync <- tree 0 24210-24870",
            "sProc_DelayedBMapDispResume <- tree 3 24629-24630",
        )
        val ONEILL_ROWS = listOf(
            "ProcScr_BattleEventEngine <- tree 3 25223-25310",
            "ProcScr_EventDisplayCursor <- ProcScr_StdEventEngine 25748-25750",
            "ProcScr_Mu <- tree 5 25148-25173",
            "ProcScr_Mu <- tree 5 25176-25192",
            "ProcScr_Mu <- tree 5 25711-25728",
            "ProcScr_MuCtrl <- tree 5 25148-25173",
            "ProcScr_MuStepSe <- tree 3 25149-25150",
            "ProcScr_MuStepSe <- tree 3 25156-25157",
            "ProcScr_MuStepSe <- tree 3 25164-25165",
            "ProcScr_MuStepSe <- tree 3 25172-25173",
            "ProcScr_PalFade <- gProcScr_E_FACE 25230-25242",
            "ProcScr_PalFade <- gProcScr_E_FACE 25296-25304",
            "ProcScr_ScriptBattleDeamon <- ProcScr_StdEventEngine 25176-25730",
            "ProcScr_StdEventEngine <- tree 3 25148-25750",
            "ProcScr_TemporaryLock <- gProcScr_Talk 25230-25238",
            "gMusicProc3Script <- tree 3 25211-25244",
            "gProcScr_0859124C <- gProcScr_E_FACE 25230-25304",
            "gProcScr_E_FACE <- tree 5 25230-25304",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 25230-25304",
            "gProcScr_FaceEndIn8Frames <- tree 3 25296-25304",
            "gProcScr_GameControl <- tree 3 25148-25750",
            "gProcScr_Talk <- tree 3 25224-25293",
            "gProcScr_TalkBubbleOpen <- tree 3 25240-25251",
            "gProcScr_TalkOpen <- gProcScr_Talk 25240-25252",
            "gProcScr_TalkSkipListener <- tree 3 25226-25293",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 25254-25291",
            "gProc_BMapMain <- tree 2 25148-25750",
            "gProc_MapTask <- tree 4 25148-25750",
            "gUnknown_08591DE8 <- ProcScr_BattleEventEngine 25296-25307",
            "sProcScr_BMXFADE <- tree 3 25729-25746",
            "sProc_BMVSync <- tree 0 25148-25750",
            "sProc_DelayedBMapDispResume <- tree 3 25711-25712",
        )
    }
}
