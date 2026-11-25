package fe8.testing

import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Inventory (not a port claim, PC-10i-turn) of the Easy-tutorial turn-1 enemy phase through the
 * turn-2 start in `ram_trace_newgame_easy` (PC-cap3). Proc rows come from
 * [EnemyPhaseInventoryTest.rows] over the `procs` watch (1-based step index); battle-animation
 * procs (`ekr*`, `efx*`, `eobj*`) are left out (BA-* inventories). Sub-windows: durability box and
 * end turn `f19560..19900`, spawn and turn-1 message event `f19901..20731`, enemy phase banner, AI
 * and the Soldier attack `f20732..21459`, turn-2 start event `f21460..22140`, turn-2 phase intro
 * `f22141..22213`.
 */
@Timeout(900)
class EasyTutorialTurnInventoryTest {
    private val inventory = EnemyPhaseInventoryTest()
    private val ref = ReferenceData.fromEnvironment()

    private fun check(expected: List<String>, first: Int, last: Int) {
        val rows = inventory.rows(RUN, first, last).filterNot { row -> HIDDEN.any { it in row } }
        assertEquals(expected.sorted(), rows.sorted(), "$RUN f$first..f$last")
    }

    @Test
    fun `durability box and end turn match the procs watch f19560 to f19900`() {
        check(BOX_ROWS, 19560, 19900)
    }

    @Test
    fun `spawn and turn-1 message event match the procs watch f19901 to f20731`() {
        check(SPAWN_ROWS, 19901, 20731)
    }

    @Test
    fun `enemy phase and the Soldier attack match the procs watch f20732 to f21459`() {
        check(CP_ROWS, 20732, 21459)
    }

    @Test
    fun `turn-2 start event matches the procs watch f21460 to f22140`() {
        check(TURN2_ROWS, 21460, 22140)
    }

    @Test
    fun `turn-2 phase intro matches the procs watch f22141 to f22213`() {
        check(INTRO_ROWS, 22141, 22213)
    }

    @Test
    fun `queue, phase, spawned units and flags follow the sequence`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run(RUN)
        assumeTrue(run.hasWatch("procs") && run.hasWatch("red"), "$RUN watches missing")
        val playSt = run.watch("gplayst")
        val blue = run.watch("blue")
        val red = run.watch("red")
        val flags = run.watch("flags")
        fun flag(step: Int, id: Int) = (flags.u8(step, id shr 3) shr (id and 7)) and 1
        fun queue(step: Int) =
            (playSt.u8(step, PLAYST_TUT_TYPE) shr 4) to playSt.u8(step, PLAYST_TUT_COUNTER)
        fun turn(step: Int) =
            playSt.u8(step, PLAYST_TURN) or (playSt.u8(step, PLAYST_TURN + 1) shl 8)
        // the tutorial queue is empty after the select/dest/Wait/Give chain until
        // Tutorial4
        assertEquals(0 to 0, queue(19560))
        assertEquals(0 to 0, queue(22136))
        assertEquals(2 to 5, queue(22137)) // ONSELECT, Tutorial4 (end of the turn-2 message event)
        // phase: player -> enemy f19901 -> other f21460 -> player f21478 (turn 2)
        assertEquals(0, playSt.u8(19900, PLAYST_FACTION))
        assertEquals(0x80, playSt.u8(19901, PLAYST_FACTION))
        assertEquals(0x40, playSt.u8(21460, PLAYST_FACTION))
        assertEquals(0, playSt.u8(21478, PLAYST_FACTION))
        assertEquals(1, turn(21477))
        assertEquals(2, turn(21478))
        // the enemy-phase event creates the three red units (no spawn in the Normal Prologue):
        // slot 0 (14,8) HP 23 f19901, slot 1 (14,7) HP 22 f19901, slot 2 (14,7) HP 20 f19902
        for ((slot, hp) in listOf(0 to 23, 1 to 22, 2 to 20)) {
            assertEquals(0, red.u32(19900, slot * UNIT_SIZE))
            val at = if (slot == 2) 19902 else 19901
            assertEquals(hp, red.u8(at, slot * UNIT_SIZE + UNIT_CUR_HP))
            assertEquals(14, red.u8(at, slot * UNIT_SIZE + UNIT_X))
        }
        // event MOVEs: slot 0 -> (10,8) f19936; slot 1 -> (8,6) f19960; slot 2 -> (9,6) f19967
        assertEquals(10, red.u8(19936, UNIT_X))
        assertEquals(8, red.u8(19960, UNIT_SIZE + UNIT_X))
        assertEquals(6, red.u8(19960, UNIT_SIZE + UNIT_Y))
        assertEquals(9, red.u8(19967, 2 * UNIT_SIZE + UNIT_X))
        assertEquals(6, red.u8(19967, 2 * UNIT_SIZE + UNIT_Y))
        // AI: only Soldier 1 acts (walk to (5,5) f20852, attacks Eirika, HP 22 -> 8 f21427 from her
        // counter); slots 0 and 2 stay at (10,8) and (9,6); Eirika (4,5) and Seth keep their HP
        assertEquals(5, red.u8(20852, UNIT_SIZE + UNIT_X))
        assertEquals(5, red.u8(20852, UNIT_SIZE + UNIT_Y))
        assertEquals(22, red.u8(21426, UNIT_SIZE + UNIT_CUR_HP))
        assertEquals(8, red.u8(21427, UNIT_SIZE + UNIT_CUR_HP))
        assertEquals(10, red.u8(22214, UNIT_X))
        assertEquals(9, red.u8(22214, 2 * UNIT_SIZE + UNIT_X))
        assertEquals(16, blue.u8(19560, UNIT_SIZE + UNIT_CUR_HP))
        assertEquals(16, blue.u8(22214, UNIT_SIZE + UNIT_CUR_HP))
        assertEquals(4, blue.u8(22214, UNIT_X))
        // flags: 0x106 (TMP6) cleared f19890, 0x103 f20479, 0x105 f21429;
        // 0x01 -> 0 and 0x77 set f22076
        assertEquals(1, flag(19889, 0x106))
        assertEquals(0, flag(19890, 0x106))
        assertEquals(1, flag(20478, 0x103))
        assertEquals(0, flag(20479, 0x103))
        assertEquals(1, flag(21428, 0x105))
        assertEquals(0, flag(21429, 0x105))
        assertEquals(1, flag(22075, 0x01))
        assertEquals(0, flag(22076, 0x01))
        assertEquals(0, flag(22075, 0x77))
        assertEquals(1, flag(22076, 0x77))
        // set once each: 0x52 and 0x64 f19886 (box event), 0x4F and 0x50 f20725
        // (end of the message event)
        assertEquals(0, flag(19885, 0x52))
        assertEquals(1, flag(19886, 0x52))
        assertEquals(0, flag(19885, 0x64))
        assertEquals(1, flag(19886, 0x64))
        assertEquals(0, flag(20724, 0x4F))
        assertEquals(1, flag(20725, 0x4F))
        assertEquals(0, flag(20724, 0x50))
        assertEquals(1, flag(20725, 0x50))
    }

    private companion object {
        const val RUN = "ram_trace_newgame_easy"
        const val UNIT_SIZE = 0x48
        const val UNIT_X = 0x10
        const val UNIT_Y = 0x11
        const val UNIT_CUR_HP = 0x13
        const val PLAYST_FACTION = 0x0F
        const val PLAYST_TURN = 0x10
        const val PLAYST_TUT_TYPE = 0x4A
        const val PLAYST_TUT_COUNTER = 0x4B
        val HIDDEN = listOf("ekr", "Ekr", "efx", "Efx", "eobj")

        val BOX_ROWS = listOf(
            "ProcScr_BoxDialogueDrawTextExt <- tree 3 19560-19865",
            "ProcScr_MergeBoxDialogue <- tree 3 19560-19877",
            "ProcScr_Mu <- tree 5 19560-19893",
            "ProcScr_StdEventEngine <- tree 3 19560-19888",
            "ProcScr_StdEventEngine <- tree 3 19889-19892",
            "ProcScr_TalkBoxIdle <- tree 0 19560-19876",
            "gProcScr_BerserkCpOrder <- gProcScr_BerserkCpPhase 19898-19898",
            "gProcScr_BerserkCpPhase <- gProc_BMapMain 19898-19899",
            "gProcScr_BoxDialogue <- tree 3 19560-19882",
            "gProcScr_GameControl <- tree 3 19560-19900",
            "gProcScr_PlayerPhase <- gProc_BMapMain 19560-19896",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 19560-19591",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 19650-19681",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 19740-19771",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 19830-19861",
            "gProc_BMapMain <- tree 2 19560-19900",
            "gProc_MapTask <- tree 4 19560-19900",
            "sProc_BMVSync <- tree 0 19560-19900",
        )
        val SPAWN_ROWS = listOf(
            "ProcScr_BoxDialogueDrawTextExt <- tree 3 20488-20704",
            "ProcScr_EventDisplayCursor <- ProcScr_StdEventEngine 19969-20028",
            "ProcScr_MergeBoxDialogue <- tree 3 20481-20716",
            "ProcScr_Mu <- tree 5 19901-19935",
            "ProcScr_Mu <- tree 5 19901-19959",
            "ProcScr_Mu <- tree 5 19902-19966",
            "ProcScr_MuCtrl <- tree 5 19901-19935",
            "ProcScr_MuCtrl <- tree 5 19901-19959",
            "ProcScr_MuCtrl <- tree 5 19902-19966",
            "ProcScr_MuStepSe <- tree 3 19903-19904",
            "ProcScr_MuStepSe <- tree 3 19910-19913",
            "ProcScr_MuStepSe <- tree 3 19918-19921",
            "ProcScr_MuStepSe <- tree 3 19926-19929",
            "ProcScr_MuStepSe <- tree 3 19934-19935",
            "ProcScr_MuStepSe <- tree 3 19936-19937",
            "ProcScr_MuStepSe <- tree 3 19943-19944",
            "ProcScr_MuStepSe <- tree 3 19951-19952",
            "ProcScr_MuStepSe <- tree 3 19959-19960",
            "ProcScr_PalFade <- gProcScr_E_FACE 20038-20050",
            "ProcScr_PalFade <- gProcScr_E_FACE 20104-20112",
            "ProcScr_PalFade <- gProcScr_E_FACE 20123-20135",
            "ProcScr_PalFade <- gProcScr_E_FACE 20123-20135",
            "ProcScr_PalFade <- gProcScr_E_FACE 20466-20474",
            "ProcScr_PalFade <- gProcScr_E_FACE 20466-20474",
            "ProcScr_StdEventEngine <- tree 3 19901-20727",
            "ProcScr_TalkBoxIdle <- tree 0 20480-20715",
            "ProcScr_TemporaryLock <- gProcScr_Talk 20038-20046",
            "ProcScr_TemporaryLock <- gProcScr_Talk 20104-20120",
            "ProcScr_TemporaryLock <- gProcScr_Talk 20123-20131",
            "ProcScr_TemporaryLock <- gProcScr_Talk 20123-20131",
            "gMusicProc3Script <- tree 3 20030-20079",
            "gProcScr_0859124C <- gProcScr_E_FACE 20038-20112",
            "gProcScr_0859124C <- gProcScr_E_FACE 20123-20474",
            "gProcScr_0859124C <- gProcScr_E_FACE 20123-20474",
            "gProcScr_BoxDialogue <- tree 3 20480-20721",
            "gProcScr_E_FACE <- tree 5 20038-20112",
            "gProcScr_E_FACE <- tree 5 20123-20474",
            "gProcScr_E_FACE <- tree 5 20123-20474",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 20038-20112",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 20123-20474",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 20123-20474",
            "gProcScr_FaceEndIn8Frames <- tree 3 20104-20112",
            "gProcScr_FaceEndIn8Frames <- tree 3 20466-20474",
            "gProcScr_FaceEndIn8Frames <- tree 3 20466-20474",
            "gProcScr_GameControl <- tree 3 19901-20731",
            "gProcScr_Talk <- tree 3 20032-20463",
            "gProcScr_TalkBubbleOpen <- tree 3 20048-20059",
            "gProcScr_TalkBubbleOpen <- tree 3 20133-20144",
            "gProcScr_TalkBubbleOpen <- tree 3 20254-20265",
            "gProcScr_TalkOpen <- gProcScr_Talk 20048-20060",
            "gProcScr_TalkOpen <- gProcScr_Talk 20133-20145",
            "gProcScr_TalkOpen <- gProcScr_Talk 20254-20266",
            "gProcScr_TalkShiftClear <- gProcScr_Talk 20341-20357",
            "gProcScr_TalkShiftClear <- gProcScr_Talk 20407-20423",
            "gProcScr_TalkSkipListener <- tree 3 20034-20463",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 20490-20521",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 20580-20611",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 20670-20701",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 20063-20101",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 20162-20191",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 20221-20251",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 20281-20311",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 20371-20401",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 20431-20461",
            "gProc_BMapMain <- tree 2 19901-20731",
            "gProc_MapTask <- tree 4 19901-20731",
            "gUnknown_08591DE8 <- ProcScr_StdEventEngine 20466-20477",
            "gUnknown_08A01800 <- tree 3 20481-20487",
            "sProc_BMVSync <- tree 0 19901-20731",
        )
        val CP_ROWS = listOf(
            "ProcScr_Mu <- tree 5 20811-20866",
            "ProcScr_Mu <- tree 5 21409-21432",
            "ProcScr_MuStepSe <- tree 3 20812-20812",
            "ProcScr_MuStepSe <- tree 3 20819-20820",
            "ProcScr_MuStepSe <- tree 3 20827-20828",
            "ProcScr_MuStepSe <- tree 3 20835-20836",
            "ProcScr_MuStepSe <- tree 3 20843-20844",
            "ProcScr_PhaseIntro <- gProc_BMapMain 20732-20802",
            "ProcScr_StdEventEngine <- tree 3 21429-21431",
            "gMusicProc3Script <- tree 3 20886-20919",
            "gProcScr_AiTargetCursor <- gProcScr_CpPerform 20845-20850",
            "gProcScr_BerserkCpOrder <- gProcScr_BerserkCpPhase 21457-21457",
            "gProcScr_BerserkCpPhase <- gProc_BMapMain 21457-21458",
            "gProcScr_CpDecide <- gProcScr_CpOrder 20809-21452",
            "gProcScr_CpOrder <- gProcScr_CpPhase 20809-21454",
            "gProcScr_CpPerform <- gProcScr_CpDecide 20811-21432",
            "gProcScr_CpPhase <- gProc_BMapMain 20809-21455",
            "gProcScr_GameControl <- tree 3 20732-21459",
            "gProcScr_PhaseIntroBlendBox <- ProcScr_PhaseIntro 20732-20793",
            "gProcScr_PhaseIntroSquares <- ProcScr_PhaseIntro 20732-20798",
            "gProcScr_PhaseIntroText <- ProcScr_PhaseIntro 20732-20800",
            "gProcScr_PhaseIntroUnk <- gProcScr_PhaseIntroText 20739-20800",
            "gProc_BMapMain <- tree 2 20732-21459",
            "gProc_MapTask <- tree 4 20732-21459",
            "sProcScr_BMXFADE <- tree 3 21433-21448",
            "sProcScr_CombatAction <- gProcScr_CpPerform 20852-21427",
            "sProc_BMVSync <- tree 0 20732-21459",
            "sProc_DelayedBMapDispResume <- tree 3 21409-21410",
        )
        val TURN2_ROWS = listOf(
            "ProcScr_BoxDialogueDrawTextExt <- tree 3 21748-22055",
            "ProcScr_EventDisplayCursor <- ProcScr_StdEventEngine 21478-21537",
            "ProcScr_EventDisplayCursor <- ProcScr_StdEventEngine 22076-22135",
            "ProcScr_MergeBoxDialogue <- tree 3 21741-22067",
            "ProcScr_PalFade <- gProcScr_E_FACE 21546-21558",
            "ProcScr_PalFade <- gProcScr_E_FACE 21546-21558",
            "ProcScr_PalFade <- gProcScr_E_FACE 21726-21734",
            "ProcScr_PalFade <- gProcScr_E_FACE 21726-21734",
            "ProcScr_StdEventEngine <- tree 3 21478-22139",
            "ProcScr_TalkBoxIdle <- tree 0 21740-22066",
            "ProcScr_TemporaryLock <- gProcScr_Talk 21546-21554",
            "ProcScr_TemporaryLock <- gProcScr_Talk 21546-21554",
            "gProcScr_0859124C <- gProcScr_E_FACE 21546-21734",
            "gProcScr_0859124C <- gProcScr_E_FACE 21546-21734",
            "gProcScr_BerserkCpOrder <- gProcScr_BerserkCpPhase 21473-21473",
            "gProcScr_BerserkCpPhase <- gProc_BMapMain 21473-21474",
            "gProcScr_BoxDialogue <- tree 3 21740-22072",
            "gProcScr_CpOrder <- gProcScr_CpPhase 21469-21470",
            "gProcScr_CpPhase <- gProc_BMapMain 21469-21471",
            "gProcScr_E_FACE <- tree 5 21546-21734",
            "gProcScr_E_FACE <- tree 5 21546-21734",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 21546-21734",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 21546-21734",
            "gProcScr_FaceEndIn8Frames <- tree 3 21726-21734",
            "gProcScr_FaceEndIn8Frames <- tree 3 21726-21734",
            "gProcScr_GameControl <- tree 3 21460-22140",
            "gProcScr_Talk <- tree 3 21540-21723",
            "gProcScr_TalkBubbleOpen <- tree 3 21556-21567",
            "gProcScr_TalkBubbleOpen <- tree 3 21604-21615",
            "gProcScr_TalkOpen <- gProcScr_Talk 21556-21568",
            "gProcScr_TalkOpen <- gProcScr_Talk 21604-21616",
            "gProcScr_TalkShiftClear <- gProcScr_Talk 21667-21683",
            "gProcScr_TalkSkipListener <- tree 3 21542-21723",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 21751-21781",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 21840-21871",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 21930-21961",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 22020-22051",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 21571-21601",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 21631-21661",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 21691-21721",
            "gProcScr_UpdateTraps <- gProc_BMapMain 21476-21476",
            "gProc_BMapMain <- tree 2 21460-22140",
            "gProc_MapTask <- tree 4 21460-22140",
            "gUnknown_08591DE8 <- ProcScr_StdEventEngine 21726-21737",
            "gUnknown_08A01800 <- tree 3 21741-21747",
            "sProc_BMVSync <- tree 0 21460-22140",
        )
        val INTRO_ROWS = listOf(
            "ProcScr_PhaseIntro <- gProc_BMapMain 22143-22213",
            "gProcScr_GameControl <- tree 3 22141-22213",
            "gProcScr_PhaseIntroBlendBox <- ProcScr_PhaseIntro 22144-22204",
            "gProcScr_PhaseIntroSquares <- ProcScr_PhaseIntro 22144-22209",
            "gProcScr_PhaseIntroText <- ProcScr_PhaseIntro 22144-22211",
            "gProcScr_PhaseIntroUnk <- gProcScr_PhaseIntroText 22150-22211",
            "gProcScr_ResetCursorPosition <- gProc_BMapMain 22143-22143",
            "gProc_BMapMain <- tree 2 22141-22213",
            "gProc_MapTask <- tree 4 22141-22213",
            "sProc_BMVSync <- tree 0 22141-22213",
        )
    }
}
