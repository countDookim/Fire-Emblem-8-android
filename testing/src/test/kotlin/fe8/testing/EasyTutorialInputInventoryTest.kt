package fe8.testing

import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Inventory (not a port claim, PC-10i-input) of the first forced Easy-tutorial sequence in
 * `ram_trace_newgame_easy` (PC-cap3). Proc rows come from [EnemyPhaseInventoryTest.rows] over the
 * `procs` watch (1-based step index, matching `f`/`r` dumps). Sub-windows: tutorial text box
 * (EventScr_Prologue_ExecTut) `f18216..18454`, phase intro `f18455..18540`, forced select
 * (Tutorial0/1) `f18541..18760`, refused off-tile A and hint event (Tutorial1) `f18761..18999`,
 * accepted destination, walk and Wait-only menu `f19000..19080`, item give `f19081..19560`.
 * Inputs: A f18540, RIGHT f18770, A f18800 (off tile), A f19020 (destination), A f19080 (Wait).
 */
@Timeout(300)
class EasyTutorialInputInventoryTest {
    private val inventory = EnemyPhaseInventoryTest()
    private val ref = ReferenceData.fromEnvironment()

    private fun check(expected: List<String>, first: Int, last: Int) {
        assertEquals(
            expected.sorted(),
            inventory.rows(RUN, first, last).sorted(),
            "$RUN f$first..f$last",
        )
    }

    private fun run(): ReferenceData.Run {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run(RUN)
        assumeTrue(run.hasWatch("procs") && run.hasWatch("blue"), "$RUN watches missing")
        return run
    }

    @Test
    fun `tutorial text box and cursor flash match the procs watch f18216 to f18454`() {
        check(TEXT_BOX_ROWS, 18216, 18454)
    }

    @Test
    fun `phase intro and player phase start match the procs watch f18455 to f18540`() {
        check(PHASE_START_ROWS, 18455, 18540)
    }

    @Test
    fun `forced select event matches the procs watch f18541 to f18760`() {
        check(SELECT_ROWS, 18541, 18760)
    }

    @Test
    fun `refused off-tile A and hint event match the procs watch f18761 to f18999`() {
        check(HINT_ROWS, 18761, 18999)
    }

    @Test
    fun `accepted destination, walk and Wait-only menu match the procs watch f19000 to f19080`() {
        check(MOVE_WAIT_ROWS, 19000, 19080)
    }

    @Test
    fun `item give matches the procs watch f19081 to f19560`() {
        check(ITEM_ROWS, 19081, 19560)
    }

    @Test
    fun `cursor, unit state and flags follow the forced sequence`() {
        val run = run()
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val bm = run.watch("gbmst")
        val blue = run.watch("blue")
        val flags = run.watch("flags")
        val procs = run.watch("procs")
        fun dump(step: Int) = ProcArrayDump.ofWatch(procs, step, names)
        fun cursor(step: Int) = bm.u16(step, BM_CURSOR_X) to bm.u16(step, BM_CURSOR_Y)
        fun flag(step: Int, id: Int) = (flags.u8(step, id shr 3) shr (id and 7)) and 1

        // PhaseIntro ResetCursorPosition moves the cursor (7,5) -> Eirika (9,5) at f18455
        assertEquals(7 to 5, cursor(18454))
        assertEquals(9 to 5, cursor(18455))
        assertEquals(9, blue.u8(18500, UNIT_SIZE + UNIT_X))
        assertEquals(5, blue.u8(18500, UNIT_SIZE + UNIT_Y))
        assertNotNull(dump(18532).find("gProcScr_PlayerPhase"))
        // A f18540 on Eirika: tutorial StdEventEngine f18541..18751
        assertNull(dump(18540).find("ProcScr_StdEventEngine"))
        assertNotNull(dump(18541).find("ProcScr_StdEventEngine"))
        assertNull(dump(18751).find("ProcScr_StdEventEngine"))
        // RIGHT f18770 moves the cursor freely; A f18800 off the forced tile snaps it to (4,5)
        assertEquals(9 to 5, cursor(18769))
        assertEquals(10 to 5, cursor(18770))
        assertEquals(10 to 5, cursor(18799))
        assertEquals(4 to 5, cursor(18800))
        assertNotNull(dump(18800).find("ProcScr_StdEventEngine"))
        assertNull(dump(18991).find("ProcScr_StdEventEngine"))
        // Eirika still at (9,5) until the accepted destination press A f19020
        assertEquals(9, blue.u8(19020, UNIT_SIZE + UNIT_X))
        assertEquals(4, blue.u8(19068, UNIT_SIZE + UNIT_X))
        assertEquals(5, blue.u8(19068, UNIT_SIZE + UNIT_Y))
        // Wait-only menu f19068..19080 (menu proc + one item proc), then done state
        assertNotNull(dump(19068).find("sProc_Menu"))
        assertNotNull(dump(19068).find("sProc_MenuItem"))
        assertNull(dump(19080).find("sProc_Menu"))
        assertEquals(0x1, blue.u32(19068, UNIT_SIZE + UNIT_STATE))
        assertEquals(0x41, blue.u32(19081, UNIT_SIZE + UNIT_STATE))
        // temporary flags 0x105, 0x106 set at f19081 (item give event)
        assertEquals(0, flag(19080, 0x105))
        assertEquals(1, flag(19081, 0x105))
        assertEquals(1, flag(19081, 0x106))
        assertNotNull(dump(19399).find("ProcScr_GotItem"))
        assertNull(dump(19541).find("ProcScr_GotItem"))
        // proc_idleCb (+0x0C) of gProcScr_PlayerPhase: Eirika selected -> PlayerPhase_RangeDisplayIdle
        // (0x0801CD1D); the destination dispatch (f19020 event) swaps in
        // PlayerPhase_RangeDisplayIdle_ForceAPress (0x0801D819, PlayPhaseForcePressAButtonInRangeDisp
        // via Proc_SetRepeatCb) f19021, then PlayerPhase_WaitForUnitMovement (0x0801D64D) f19030
        fun idle(step: Int) = dump(step).find("gProcScr_PlayerPhase")!!.u32(PROC_IDLE_CB)
        assertEquals(0x0801CD1D, idle(18790))
        assertEquals(0x0801CD1D, idle(19019))
        assertEquals(0x0801D819, idle(19021))
        assertEquals(0x0801D64D, idle(19030))
    }

    private companion object {
        const val RUN = "ram_trace_newgame_easy"
        const val UNIT_SIZE = 0x48
        const val UNIT_STATE = 0x0C
        const val UNIT_X = 0x10
        const val UNIT_Y = 0x11
        const val PROC_IDLE_CB = 0x0C
        const val BM_CURSOR_X = 0x14
        const val BM_CURSOR_Y = 0x16

        val TEXT_BOX_ROWS = listOf(
            "ProcScr_BoxDialogueDrawTextExt <- tree 3 18238-18366",
            "ProcScr_EventDisplayCursor <- ProcScr_StdEventEngine 18387-18446",
            "ProcScr_MergeBoxDialogue <- tree 3 18231-18378",
            "ProcScr_PalFade <- gProcScr_E_FACE 18216-18224",
            "ProcScr_StdEventEngine <- tree 3 18216-18450",
            "ProcScr_TalkBoxIdle <- tree 0 18230-18377",
            "gProcScr_0859124C <- gProcScr_E_FACE 18216-18224",
            "gProcScr_BoxDialogue <- tree 3 18230-18383",
            "gProcScr_E_FACE <- tree 5 18216-18224",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 18216-18224",
            "gProcScr_FaceEndIn8Frames <- tree 3 18216-18224",
            "gProcScr_GameControl <- tree 3 18216-18454",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 18240-18271",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 18330-18361",
            "gProc_BMapMain <- tree 2 18216-18454",
            "gProc_MapTask <- tree 4 18216-18454",
            "gUnknown_08591DE8 <- ProcScr_StdEventEngine 18216-18227",
            "gUnknown_08A01800 <- tree 3 18231-18237",
            "sProc_BMVSync <- tree 0 18216-18454",
        )
        val PHASE_START_ROWS = listOf(
            "ProcScr_Mu <- tree 5 18539-18540",
            "ProcScr_PhaseIntro <- gProc_BMapMain 18455-18525",
            "gProcScr_GameControl <- tree 3 18455-18540",
            "gProcScr_PhaseIntroBlendBox <- ProcScr_PhaseIntro 18456-18516",
            "gProcScr_PhaseIntroSquares <- ProcScr_PhaseIntro 18456-18521",
            "gProcScr_PhaseIntroText <- ProcScr_PhaseIntro 18456-18523",
            "gProcScr_PhaseIntroUnk <- gProcScr_PhaseIntroText 18462-18523",
            "gProcScr_PlayerPhase <- gProc_BMapMain 18532-18540",
            "gProcScr_ResetCursorPosition <- gProc_BMapMain 18455-18455",
            "gProcScr_SideWindowMaker <- tree 3 18534-18534",
            "gProcScr_TerrainDisplay <- tree 3 18535-18540",
            "gProcScr_UnitDisplay_MinimugBox <- tree 3 18535-18540",
            "gProc_BMapMain <- tree 2 18455-18540",
            "gProc_MapTask <- tree 4 18455-18540",
            "sProc_BMVSync <- tree 0 18455-18540",
        )
        val SELECT_ROWS = listOf(
            "ProcScr_BoxDialogueDrawTextExt <- tree 3 18569-18726",
            "ProcScr_EventDisplayCursor <- ProcScr_StdEventEngine 18543-18745",
            "ProcScr_MergeBoxDialogue <- tree 3 18562-18738",
            "ProcScr_Mu <- tree 5 18541-18760",
            "ProcScr_StdEventEngine <- tree 3 18541-18750",
            "ProcScr_TalkBoxIdle <- tree 0 18561-18737",
            "gProcScr_0859ACE8 <- gProcScr_PlayerPhase 18541-18751",
            "gProcScr_BoxDialogue <- tree 3 18561-18743",
            "gProcScr_GameControl <- tree 3 18541-18760",
            "gProcScr_PlayerPhase <- gProc_BMapMain 18541-18760",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 18571-18601",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 18690-18721",
            "gProc_BMapMain <- tree 2 18541-18760",
            "gProc_MapTask <- tree 4 18541-18760",
            "gUnknown_08A01800 <- tree 3 18562-18568",
            "sProcScr_MoveLimitView <- tree 4 18542-18760",
            "sProcScr_MoveLimitViewChange <- sProcScr_MoveLimitView 18542-18547",
            "sProc_BMVSync <- tree 0 18541-18760",
        )
        val HINT_ROWS = listOf(
            "ProcScr_BoxDialogueDrawTextExt <- tree 3 18818-18966",
            "ProcScr_EventDisplayCursor <- ProcScr_StdEventEngine 18801-18986",
            "ProcScr_MergeBoxDialogue <- tree 3 18811-18978",
            "ProcScr_Mu <- tree 5 18761-18999",
            "ProcScr_StdEventEngine <- tree 3 18800-18990",
            "ProcScr_TalkBoxIdle <- tree 0 18810-18977",
            "gProcScr_BoxDialogue <- tree 3 18810-18983",
            "gProcScr_GameControl <- tree 3 18761-18999",
            "gProcScr_PlayerPhase <- gProc_BMapMain 18761-18999",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 18840-18871",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 18930-18961",
            "gProc_BMapMain <- tree 2 18761-18999",
            "gProc_MapTask <- tree 4 18761-18999",
            "gUnknown_08A01800 <- tree 3 18811-18817",
            "sProcScr_MoveLimitView <- tree 4 18761-18999",
            "sProc_BMVSync <- tree 0 18761-18999",
        )
        val MOVE_WAIT_ROWS = listOf(
            "ProcScr_GreenTextColor <- sProc_Menu 19068-19079",
            "ProcScr_Mu <- tree 5 19000-19080",
            "ProcScr_MuStepSe <- tree 3 19026-19026",
            "ProcScr_MuStepSe <- tree 3 19033-19034",
            "ProcScr_MuStepSe <- tree 3 19041-19042",
            "ProcScr_MuStepSe <- tree 3 19049-19050",
            "ProcScr_MuStepSe <- tree 3 19057-19058",
            "ProcScr_MuStepSe <- tree 3 19065-19066",
            "ProcScr_StdEventEngine <- tree 3 19020-19023",
            "ProcScr_StdEventEngine <- tree 3 19068-19070",
            "gProcScr_GameControl <- tree 3 19000-19080",
            "gProcScr_PlayerPhase <- gProc_BMapMain 19000-19080",
            "gProc_BMapMain <- tree 2 19000-19080",
            "gProc_MapTask <- tree 4 19000-19080",
            "sProcScr_MoveLimitView <- tree 4 19000-19024",
            "sProc_BMVSync <- tree 0 19000-19080",
            "sProc_Menu <- tree 3 19068-19079",
            "sProc_MenuItem <- sProc_Menu 19068-19079",
        )
        val ITEM_ROWS = listOf(
            "ProcScr_BoxDialogueDrawTextExt <- tree 3 19551-19560",
            "ProcScr_EventDisplayCursor <- ProcScr_StdEventEngine 19081-19140",
            "ProcScr_GotItem <- ProcScr_StdEventEngine 19399-19540",
            "ProcScr_MergeBoxDialogue <- tree 3 19544-19560",
            "ProcScr_Mu <- tree 5 19081-19560",
            "ProcScr_PalFade <- gProcScr_E_FACE 19149-19161",
            "ProcScr_PalFade <- gProcScr_E_FACE 19149-19161",
            "ProcScr_PalFade <- gProcScr_E_FACE 19386-19394",
            "ProcScr_PalFade <- gProcScr_E_FACE 19386-19394",
            "ProcScr_Popup <- ProcScr_GotItem 19399-19538",
            "ProcScr_PopupUpdateIcon <- ProcScr_Popup 19426-19538",
            "ProcScr_StdEventEngine <- tree 3 19081-19560",
            "ProcScr_TalkBoxIdle <- tree 0 19543-19560",
            "ProcScr_TemporaryLock <- gProcScr_Talk 19149-19157",
            "ProcScr_TemporaryLock <- gProcScr_Talk 19149-19157",
            "gProcScr_0859124C <- gProcScr_E_FACE 19149-19394",
            "gProcScr_0859124C <- gProcScr_E_FACE 19149-19394",
            "gProcScr_BoxDialogue <- tree 3 19543-19560",
            "gProcScr_E_FACE <- tree 5 19149-19394",
            "gProcScr_E_FACE <- tree 5 19149-19394",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 19149-19394",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 19149-19394",
            "gProcScr_FaceEndIn8Frames <- tree 3 19386-19394",
            "gProcScr_FaceEndIn8Frames <- tree 3 19386-19394",
            "gProcScr_GameControl <- tree 3 19081-19560",
            "gProcScr_PlayerPhase <- gProc_BMapMain 19081-19560",
            "gProcScr_Talk <- tree 3 19143-19383",
            "gProcScr_TalkBubbleOpen <- tree 3 19159-19170",
            "gProcScr_TalkOpen <- gProcScr_Talk 19159-19171",
            "gProcScr_TalkShiftClear <- gProcScr_Talk 19207-19223",
            "gProcScr_TalkShiftClear <- gProcScr_Talk 19231-19247",
            "gProcScr_TalkShiftClear <- gProcScr_Talk 19297-19313",
            "gProcScr_TalkShiftClear <- gProcScr_Talk 19321-19337",
            "gProcScr_TalkSkipListener <- tree 3 19145-19383",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 19560-19560",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 19174-19201",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 19261-19291",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 19351-19381",
            "gProc_BMapMain <- tree 2 19081-19560",
            "gProc_MapTask <- tree 4 19081-19560",
            "gUnknown_08591DE8 <- ProcScr_StdEventEngine 19386-19397",
            "gUnknown_08A01800 <- tree 3 19544-19550",
            "sMusicProc2Script <- ProcScr_Popup 19409-19424",
            "sMusicProc2Script <- ProcScr_Popup 19522-19537",
            "sProc_BMVSync <- tree 0 19081-19560",
        )
    }
}
