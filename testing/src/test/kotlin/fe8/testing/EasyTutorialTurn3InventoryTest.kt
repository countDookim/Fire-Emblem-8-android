package fe8.testing

import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Inventory (not a port claim, PC-10i-turn3) of the Easy-tutorial turn-2 enemy phase and the
 * turn-3 start in `ram_trace_newgame_easy` (PC-cap3), excluding the O'Neill vs Seth scripted battle
 * `f25148..25750` (PC-10i-battle). Proc rows come from [EnemyPhaseInventoryTest.rows] over the
 * `procs` watch (1-based step index); battle-animation procs are left out (BA-* inventories).
 * Sub-windows: one-enemy-left follow-up event `f24870..25045`, enemy phase banner, AI and phase
 * flips `f25046..25147`, Seth/Eirika dialogue and the select-Eirika box `f25750..26310`, turn-3
 * phase intro `f26311..26391`.
 */
@Timeout(900)
class EasyTutorialTurn3InventoryTest {
    private val inventory = EnemyPhaseInventoryTest()
    private val ref = ReferenceData.fromEnvironment()

    private fun check(expected: List<String>, first: Int, last: Int) {
        val rows = inventory.rows(RUN, first, last).filterNot { row -> HIDDEN.any { it in row } }
        assertEquals(expected.sorted(), rows.sorted(), "$RUN f$first..f$last")
    }

    @Test
    fun `attack event after the kill matches the procs watch f24870 to f25045`() {
        check(ONEILL_ATTACK_ROWS, 24870, 25045)
    }

    @Test
    fun `enemy phase 2 and phase flips match the procs watch f25046 to f25147`() {
        check(ENEMY_PHASE_ROWS, 25046, 25147)
    }

    @Test
    fun `dialogue and select box match the procs watch f25750 to f26310`() {
        check(DIALOGUE_ROWS, 25750, 26310)
    }

    @Test
    fun `turn-3 phase intro matches the procs watch f26311 to f26391`() {
        check(INTRO_ROWS, 26311, 26391)
    }

    @Test
    fun `queue, phase, units and flags follow the sequence`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run(RUN)
        assumeTrue(run.hasWatch("procs") && run.hasWatch("red"), "$RUN watches missing")
        val playSt = run.watch("gplayst")
        val blue = run.watch("blue")
        val red = run.watch("red")
        val flags = run.watch("flags")
        fun queue(step: Int) =
            (playSt.u8(step, PLAYST_TUT_TYPE) shr 4) to playSt.u8(step, PLAYST_TUT_COUNTER)
        fun turn(step: Int) =
            playSt.u8(step, PLAYST_TURN) or (playSt.u8(step, PLAYST_TURN + 1) shl 8)
        // the queue is empty from the end of Tutorial9 until the select-Eirika script enqueues
        // ONSELECT (2) / TutorialA (counter 11) at f26308, before the phase intro
        assertEquals(0 to 0, queue(24870))
        assertEquals(0 to 0, queue(26307))
        assertEquals(2 to 11, queue(26308))
        assertEquals(2 to 11, queue(26391))
        // phase: enemy (turn 2) f24867 -> other f25130 -> player (turn 3) before f25750
        assertEquals(0x80, playSt.u8(24870, PLAYST_FACTION))
        assertEquals(2, turn(24870))
        assertEquals(0x80, playSt.u8(25129, PLAYST_FACTION))
        assertEquals(0x40, playSt.u8(25130, PLAYST_FACTION))
        assertEquals(2, turn(25147))
        assertEquals(0, playSt.u8(25750, PLAYST_FACTION))
        assertEquals(3, turn(25750))
        assertEquals(3, turn(26391))
        // nobody acts in the enemy phase: both Soldiers are gone (cleared f24027, f24699), Eirika
        // stays at (4,5) with HP 16; O'Neill (red slot 0) is at (10,8) before his turn-3 event
        // move and (9,6) after it; Seth (blue slot 0) HP 13 -> 11 in the scripted battle
        for (step in listOf(24870, 25147, 25750, 26391)) {
            assertEquals(0, red.u32(step, UNIT_SIZE))
            assertEquals(0, red.u32(step, 2 * UNIT_SIZE))
            assertEquals(16, blue.u8(step, UNIT_SIZE + UNIT_CUR_HP))
            assertEquals(4, blue.u8(step, UNIT_SIZE + UNIT_X))
            assertEquals(5, blue.u8(step, UNIT_SIZE + UNIT_Y))
        }
        assertEquals(10, red.u8(24870, UNIT_X))
        assertEquals(8, red.u8(25147, UNIT_Y))
        assertEquals(9, red.u8(25750, UNIT_X))
        assertEquals(6, red.u8(26391, UNIT_Y))
        assertEquals(13, blue.u8(24870, UNIT_CUR_HP))
        assertEquals(13, blue.u8(25147, UNIT_CUR_HP))
        assertEquals(11, blue.u8(25750, UNIT_CUR_HP))
        assertEquals(11, blue.u8(26391, UNIT_CUR_HP))
        // no flag among 0x000..0x17F changes in f24870..25147 or f25750..26391
        for ((first, last) in listOf(24870 to 25147, 25750 to 26391)) {
            for (step in first + 1..last) {
                for (byte in 0 until FLAG_BYTES) {
                    assertEquals(
                        flags.u8(step - 1, byte),
                        flags.u8(step, byte),
                        "flag byte $byte f$step",
                    )
                }
            }
        }
    }

    private companion object {
        const val RUN = "ram_trace_newgame_easy"
        const val UNIT_SIZE = 0x48
        const val UNIT_X = 0x10
        const val UNIT_Y = 0x11
        const val UNIT_CUR_HP = 0x13
        const val FLAG_BYTES = 0x30
        const val PLAYST_FACTION = 0x0F
        const val PLAYST_TURN = 0x10
        const val PLAYST_TUT_TYPE = 0x4A
        const val PLAYST_TUT_COUNTER = 0x4B
        val HIDDEN = listOf("ekr", "Ekr", "efx", "Efx", "eobj")

        val ONEILL_ATTACK_ROWS = listOf(
            "ProcScr_PalFade <- gProcScr_E_FACE 24875-24887",
            "ProcScr_PalFade <- gProcScr_E_FACE 25026-25034",
            "ProcScr_StdEventEngine <- tree 3 24870-25041",
            "ProcScr_TemporaryLock <- gProcScr_Talk 24875-24883",
            "gMusicProc3Script <- tree 3 24870-24916",
            "gProcScr_0859124C <- gProcScr_E_FACE 24875-25034",
            "gProcScr_E_FACE <- tree 5 24875-25034",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 24875-25034",
            "gProcScr_FaceEndIn8Frames <- tree 3 25026-25034",
            "gProcScr_GameControl <- tree 3 24870-25045",
            "gProcScr_ResetCursorPosition <- gProc_BMapMain 25045-25045",
            "gProcScr_Talk <- tree 3 24870-25023",
            "gProcScr_TalkBubbleOpen <- tree 3 24885-24896",
            "gProcScr_TalkOpen <- gProcScr_Talk 24885-24897",
            "gProcScr_TalkShiftClear <- gProcScr_Talk 24937-24953",
            "gProcScr_TalkShiftClear <- gProcScr_Talk 24961-24977",
            "gProcScr_TalkSkipListener <- tree 3 24871-25023",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 24902-24931",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 24991-25021",
            "gProc_BMapMain <- tree 2 24870-25045",
            "gProc_MapTask <- tree 4 24870-25045",
            "gUnknown_08591DE8 <- ProcScr_StdEventEngine 25026-25037",
            "sProc_BMVSync <- tree 0 24870-25045",
        )
        val ENEMY_PHASE_ROWS = listOf(
            "ProcScr_PhaseIntro <- gProc_BMapMain 25046-25115",
            "gProcScr_BerserkCpOrder <- gProcScr_BerserkCpPhase 25127-25127",
            "gProcScr_BerserkCpOrder <- gProcScr_BerserkCpPhase 25143-25143",
            "gProcScr_BerserkCpPhase <- gProc_BMapMain 25127-25128",
            "gProcScr_BerserkCpPhase <- gProc_BMapMain 25143-25144",
            "gProcScr_CpDecide <- gProcScr_CpOrder 25122-25122",
            "gProcScr_CpOrder <- gProcScr_CpPhase 25122-25124",
            "gProcScr_CpOrder <- gProcScr_CpPhase 25139-25140",
            "gProcScr_CpPhase <- gProc_BMapMain 25122-25125",
            "gProcScr_CpPhase <- gProc_BMapMain 25139-25141",
            "gProcScr_GameControl <- tree 3 25046-25147",
            "gProcScr_PhaseIntroBlendBox <- ProcScr_PhaseIntro 25046-25106",
            "gProcScr_PhaseIntroSquares <- ProcScr_PhaseIntro 25046-25111",
            "gProcScr_PhaseIntroText <- ProcScr_PhaseIntro 25046-25113",
            "gProcScr_PhaseIntroUnk <- gProcScr_PhaseIntroText 25052-25113",
            "gProcScr_UpdateTraps <- gProc_BMapMain 25146-25146",
            "gProc_BMapMain <- tree 2 25046-25147",
            "gProc_MapTask <- tree 4 25046-25147",
            "sProc_BMVSync <- tree 0 25046-25147",
        )
        val DIALOGUE_ROWS = listOf(
            "ProcScr_BoxDialogueDrawTextExt <- tree 3 26188-26226",
            "ProcScr_EventDisplayCursor <- ProcScr_StdEventEngine 25750-25807",
            "ProcScr_EventDisplayCursor <- ProcScr_StdEventEngine 26247-26306",
            "ProcScr_MergeBoxDialogue <- tree 3 26181-26238",
            "ProcScr_PalFade <- gProcScr_E_FACE 25816-25828",
            "ProcScr_PalFade <- gProcScr_E_FACE 25894-25902",
            "ProcScr_PalFade <- gProcScr_E_FACE 25913-25925",
            "ProcScr_PalFade <- gProcScr_E_FACE 26044-26052",
            "ProcScr_PalFade <- gProcScr_E_FACE 26063-26075",
            "ProcScr_PalFade <- gProcScr_E_FACE 26104-26112",
            "ProcScr_PalFade <- gProcScr_E_FACE 26123-26135",
            "ProcScr_PalFade <- gProcScr_E_FACE 26166-26174",
            "ProcScr_StdEventEngine <- tree 3 25750-26310",
            "ProcScr_TalkBoxIdle <- tree 0 26180-26237",
            "ProcScr_TemporaryLock <- gProcScr_Talk 25816-25824",
            "ProcScr_TemporaryLock <- gProcScr_Talk 25894-25910",
            "ProcScr_TemporaryLock <- gProcScr_Talk 25913-25921",
            "ProcScr_TemporaryLock <- gProcScr_Talk 26044-26060",
            "ProcScr_TemporaryLock <- gProcScr_Talk 26063-26071",
            "ProcScr_TemporaryLock <- gProcScr_Talk 26104-26120",
            "ProcScr_TemporaryLock <- gProcScr_Talk 26123-26131",
            "gProcScr_0859124C <- gProcScr_E_FACE 25816-25902",
            "gProcScr_0859124C <- gProcScr_E_FACE 25913-26052",
            "gProcScr_0859124C <- gProcScr_E_FACE 26063-26112",
            "gProcScr_0859124C <- gProcScr_E_FACE 26123-26174",
            "gProcScr_BoxDialogue <- tree 3 26180-26243",
            "gProcScr_E_FACE <- tree 5 25816-25902",
            "gProcScr_E_FACE <- tree 5 25913-26052",
            "gProcScr_E_FACE <- tree 5 26063-26112",
            "gProcScr_E_FACE <- tree 5 26123-26174",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 25816-25902",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 25913-26052",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 26063-26112",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 26123-26174",
            "gProcScr_FaceEndIn8Frames <- tree 3 25894-25902",
            "gProcScr_FaceEndIn8Frames <- tree 3 26044-26052",
            "gProcScr_FaceEndIn8Frames <- tree 3 26104-26112",
            "gProcScr_FaceEndIn8Frames <- tree 3 26166-26174",
            "gProcScr_GameControl <- tree 3 25750-26310",
            "gProcScr_Talk <- tree 3 25810-26163",
            "gProcScr_TalkBubbleOpen <- tree 3 25826-25837",
            "gProcScr_TalkBubbleOpen <- tree 3 25923-25934",
            "gProcScr_TalkBubbleOpen <- tree 3 26073-26084",
            "gProcScr_TalkBubbleOpen <- tree 3 26133-26144",
            "gProcScr_TalkOpen <- gProcScr_Talk 25826-25838",
            "gProcScr_TalkOpen <- gProcScr_Talk 25923-25935",
            "gProcScr_TalkOpen <- gProcScr_Talk 26073-26085",
            "gProcScr_TalkOpen <- gProcScr_Talk 26133-26145",
            "gProcScr_TalkShiftClear <- gProcScr_Talk 25981-25997",
            "gProcScr_TalkSkipListener <- tree 3 25812-26163",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 26191-26221",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 25858-25891",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 25937-25951",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 26029-26041",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 26087-26101",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 26147-26161",
            "gProc_BMapMain <- tree 2 25750-26310",
            "gProc_MapTask <- tree 4 25750-26310",
            "gUnknown_08591DE8 <- ProcScr_StdEventEngine 26166-26177",
            "gUnknown_08A01800 <- tree 3 26181-26187",
            "sProc_BMVSync <- tree 0 25750-26310",
        )
        val INTRO_ROWS = listOf(
            "ProcScr_PhaseIntro <- gProc_BMapMain 26315-26385",
            "gProcScr_GameControl <- tree 3 26311-26391",
            "gProcScr_PhaseIntroBlendBox <- ProcScr_PhaseIntro 26315-26376",
            "gProcScr_PhaseIntroSquares <- ProcScr_PhaseIntro 26315-26381",
            "gProcScr_PhaseIntroText <- ProcScr_PhaseIntro 26315-26383",
            "gProcScr_PhaseIntroUnk <- gProcScr_PhaseIntroText 26322-26383",
            "gProc_BMapMain <- tree 2 26311-26391",
            "gProc_MapTask <- tree 4 26311-26391",
            "sProc_BMVSync <- tree 0 26311-26391",
        )
    }
}
