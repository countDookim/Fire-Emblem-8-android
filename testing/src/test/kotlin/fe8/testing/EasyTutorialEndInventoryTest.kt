package fe8.testing

import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Inventory (not a port claim, PC-10i-end) of the end of the Easy tutorial in
 * `ram_trace_newgame_easy` (PC-cap3). Proc rows come from [EnemyPhaseInventoryTest.rows] over the
 * `procs` watch (1-based step index). Battle-animation procs (`ekr*`, `efx*`) are left out of the
 * rows (BA-* inventories); `eobjLvup` is kept. Sub-windows: turn-3 select event `f26392..26549`,
 * hint and forced destination `f26550..26687`, Attack-only menu `f26688..26735`, forecast box
 * `f26736..27633`, map battle and level-up `f27634..28823`, Tutorial E and ending scene
 * `f28824..30373`, post-chapter save screen `f30374..30549`, chapter intro `f30550..30757`,
 * Chapter 1 opening `f30758..30900`. Inputs: A f26400 (grid, selects Eirika), A f26550 (grid, not
 * on the forced tile), A f26640 (destination), A f26700 (Attack), A f26730 (weapon), A f27630
 * (target), plus text-page A presses on the 30-frame grid.
 */
@Timeout(900)
class EasyTutorialEndInventoryTest {
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
    fun `Tutorial A select event match the procs watch f26392 to f26549`() {
        check(PHASE_START_ROWS, 26392, 26549)
    }

    @Test
    fun `hint at the wrong tile, forced destination and walk match the procs watch f26550 to f26687`() {
        check(HINT_DEST_ROWS, 26550, 26687)
    }

    @Test
    fun `Attack-only menu and weapon list match the procs watch f26688 to f26735`() {
        check(MENU_ROWS, 26688, 26735)
    }

    @Test
    fun `forecast box match the procs watch f26736 to f27633`() {
        check(FORECAST_ROWS, 26736, 27633)
    }

    @Test
    fun `map battle, critical kill and forced level-up match the procs watch f27634 to f28823`() {
        check(BATTLE_ROWS, 27634, 28823)
    }

    @Test
    fun `Tutorial E box and ending scene match the procs watch f28824 to f30373`() {
        check(ENDING_ROWS, 28824, 30373)
    }

    @Test
    fun `post-chapter save screen match the procs watch f30374 to f30549`() {
        check(SAVE_ROWS, 30374, 30549)
    }

    @Test
    fun `chapter intro match the procs watch f30550 to f30757`() {
        check(INTRO_ROWS, 30550, 30757)
    }

    @Test
    fun `Chapter 1 opening event match the procs watch f30758 to f30900`() {
        check(CH1_ROWS, 30758, 30900)
    }

    @Test
    fun `tutorial queue, units, flags and chapter change follow the sequence`() {
        val run = run()
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val playSt = run.watch("gplayst")
        val blue = run.watch("blue")
        val red = run.watch("red")
        val flags = run.watch("flags")
        val procs = run.watch("procs")
        fun dump(step: Int) = ProcArrayDump.ofWatch(procs, step, names)
        fun flag(step: Int, id: Int) = (flags.u8(step, id shr 3) shr (id and 7)) and 1
        // gPlaySt tutorial_exec_type (+0x4A bits 4..7) and tutorial_counter (+0x4B): queued
        // TUTORIAL_EVT_TYPE_* / (index of the EventListScr_Prologue_Tutorial entry + 1)
        fun queue(step: Int) = (playSt.u8(step, PLAYST_TUT_TYPE) shr 4) to playSt.u8(step, PLAYST_TUT_COUNTER)
        assertEquals(0 to 0, queue(26307))
        assertEquals(2 to 11, queue(26308)) // ONSELECT, TutorialA (queued by the turn-3 tutorial call)
        assertEquals(2 to 11, queue(26400))
        assertEquals(0 to 0, queue(26401)) // grid A f26400 on Eirika ran TryCallSelectEvents
        assertEquals(3 to 12, queue(26517)) // DESTSELECTED, TutorialB
        assertEquals(0 to 0, queue(26550)) // A f26550 on the wrong tile: StartDestSelectedEvent, hint branch
        assertEquals(3 to 12, queue(26636)) // re-queued by the fail branch
        assertEquals(0 to 0, queue(26640)) // A f26640 on (8,6): success branch
        assertEquals(4 to 13, queue(26641)) // AFTERMOVE, TutorialC
        assertEquals(5 to 14, queue(26688)) // FORECAST, TutorialD (TutorialC ran and enqueued it)
        assertEquals(0 to 0, queue(26736)) // StartBattleForecastTutorialEvent
        assertEquals(1 to 15, queue(27626)) // POSTACTION, TutorialE
        assertEquals(1 to 15, queue(28823))
        assertEquals(0 to 0, queue(28824)) // RunTutorialEvent(POSTACTION) after the fight
        assertEquals(0 to 0, queue(30900))
        // Eirika = gUnitArrayBlue[1]: (4,5) -> (8,6) at f26688 (next to O'Neill (9,6)), Lv 1 -> 2 at f28790
        assertEquals(4, blue.u8(26687, UNIT_SIZE + UNIT_X))
        assertEquals(5, blue.u8(26687, UNIT_SIZE + UNIT_Y))
        assertEquals(8, blue.u8(26688, UNIT_SIZE + UNIT_X))
        assertEquals(6, blue.u8(26688, UNIT_SIZE + UNIT_Y))
        assertEquals(1, blue.u8(28789, UNIT_SIZE + UNIT_LEVEL))
        assertEquals(42, blue.u8(28789, UNIT_SIZE + UNIT_EXP))
        assertEquals(16, blue.u8(28789, UNIT_SIZE + UNIT_MAX_HP))
        assertEquals(16, blue.u8(28789, UNIT_SIZE + UNIT_CUR_HP))
        assertEquals(2, blue.u8(28790, UNIT_SIZE + UNIT_LEVEL))
        assertEquals(22, blue.u8(28790, UNIT_SIZE + UNIT_EXP))
        assertEquals(17, blue.u8(28790, UNIT_SIZE + UNIT_MAX_HP))
        assertEquals(6, blue.u8(28790, UNIT_SIZE + UNIT_CUR_HP))
        // boss gUnitArrayRed[0]: HP 23 -> 0 (written back f28790), struct cleared f28822
        assertEquals(23, red.u8(28789, UNIT_CUR_HP))
        assertEquals(0, red.u8(28790, UNIT_CUR_HP))
        assertEquals(80, red.u8(28821, 0))
        assertEquals(0, red.u32(28822, 0))
        // event flags: 0x1F set with Tutorial E (f28824), 0x82 set f29035 (inside the Tutorial E event)
        assertEquals(0, flag(28823, 0x1F))
        assertEquals(1, flag(28824, 0x1F))
        assertEquals(0, flag(29034, 0x82))
        assertEquals(1, flag(29035, 0x82))
        // Prologue clear: PlayerPhase/event engine gone f30371, chapterIndex 0 -> 1 f30374, party healed
        assertNotNull(dump(30370).find("gProcScr_PlayerPhase"))
        assertNull(dump(30371).find("gProcScr_PlayerPhase"))
        assertNotNull(dump(30370).find("ProcScr_StdEventEngine"))
        assertNull(dump(30371).find("ProcScr_StdEventEngine"))
        assertEquals(0, playSt.u8(30373, PLAYST_CHAPTER))
        assertEquals(1, playSt.u8(30374, PLAYST_CHAPTER))
        assertEquals(6, blue.u8(30373, UNIT_SIZE + UNIT_CUR_HP))
        assertEquals(17, blue.u8(30374, UNIT_SIZE + UNIT_CUR_HP))
        assertEquals(11, blue.u8(30373, UNIT_CUR_HP))
        assertEquals(30, blue.u8(30374, UNIT_CUR_HP))
        assertNotNull(dump(30374).find("gProcScr_SaveMenuPostChapter"))
        assertNull(dump(30541).find("gProcScr_SaveMenuPostChapter"))
        // faction 0x40 f30543, ChapterIntro f30550..30756, Chapter 1 StdEventEngine f30758
        assertEquals(0, playSt.u8(30542, PLAYST_FACTION))
        assertEquals(0x40, playSt.u8(30543, PLAYST_FACTION))
        assertNull(dump(30549).find("gProcScr_ChapterIntro"))
        assertNotNull(dump(30550).find("gProcScr_ChapterIntro"))
        assertNotNull(dump(30756).find("gProcScr_ChapterIntro"))
        assertNull(dump(30757).find("gProcScr_ChapterIntro"))
        assertNull(dump(30757).find("ProcScr_StdEventEngine"))
        assertNotNull(dump(30758).find("ProcScr_StdEventEngine"))
        // the level-up screen and death fade
        assertNull(dump(28541).find("ProcScr_eobjLvup"))
        assertNotNull(dump(28542).find("ProcScr_eobjLvup"))
        assertNull(dump(28733).find("ProcScr_eobjLvup"))
    }

    private companion object {
        const val RUN = "ram_trace_newgame_easy"
        const val UNIT_SIZE = 0x48
        const val UNIT_LEVEL = 0x08
        const val UNIT_EXP = 0x09
        const val UNIT_X = 0x10
        const val UNIT_Y = 0x11
        const val UNIT_MAX_HP = 0x12
        const val UNIT_CUR_HP = 0x13
        const val PLAYST_CHAPTER = 0x0E
        const val PLAYST_FACTION = 0x0F
        const val PLAYST_TUT_TYPE = 0x4A
        const val PLAYST_TUT_COUNTER = 0x4B
        val HIDDEN = listOf("ekr", "Ekr", "efx", "Efx")

        val PHASE_START_ROWS = listOf(
            "ProcScr_BoxDialogueDrawTextExt <- tree 3 26430-26496",
            "ProcScr_EventDisplayCursor <- ProcScr_StdEventEngine 26404-26515",
            "ProcScr_GreenTextColor <- gProcScr_GoalDisplay 26395-26400",
            "ProcScr_MergeBoxDialogue <- tree 3 26423-26508",
            "ProcScr_Mu <- tree 5 26401-26549",
            "ProcScr_StdEventEngine <- tree 3 26401-26520",
            "ProcScr_TalkBoxIdle <- tree 0 26422-26507",
            "gProcScr_0859ACE8 <- gProcScr_PlayerPhase 26401-26521",
            "gProcScr_BoxDialogue <- tree 3 26422-26513",
            "gProcScr_GameControl <- tree 3 26392-26549",
            "gProcScr_GoalDisplay <- tree 3 26395-26400",
            "gProcScr_PlayerPhase <- gProc_BMapMain 26392-26549",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 26461-26491",
            "gProcScr_TerrainDisplay <- tree 3 26395-26400",
            "gProcScr_UnitDisplay_MinimugBox <- tree 3 26395-26400",
            "gProc_BMapMain <- tree 2 26392-26549",
            "gProc_MapTask <- tree 4 26392-26549",
            "gUnknown_08A01800 <- tree 3 26423-26429",
            "sProcScr_MoveLimitView <- tree 4 26403-26549",
            "sProcScr_MoveLimitViewChange <- sProcScr_MoveLimitView 26403-26408",
            "sProc_BMVSync <- tree 0 26392-26549",
        )

        val HINT_DEST_ROWS = listOf(
            "ProcScr_BoxDialogueDrawTextExt <- tree 3 26568-26614",
            "ProcScr_EventDisplayCursor <- ProcScr_StdEventEngine 26551-26634",
            "ProcScr_MergeBoxDialogue <- tree 3 26561-26626",
            "ProcScr_Mu <- tree 5 26550-26687",
            "ProcScr_MuStepSe <- tree 3 26646-26646",
            "ProcScr_MuStepSe <- tree 3 26653-26654",
            "ProcScr_MuStepSe <- tree 3 26661-26662",
            "ProcScr_MuStepSe <- tree 3 26669-26670",
            "ProcScr_MuStepSe <- tree 3 26677-26678",
            "ProcScr_MuStepSe <- tree 3 26685-26686",
            "ProcScr_StdEventEngine <- tree 3 26550-26638",
            "ProcScr_StdEventEngine <- tree 3 26640-26643",
            "ProcScr_TalkBoxIdle <- tree 0 26560-26625",
            "gProcScr_BoxDialogue <- tree 3 26560-26631",
            "gProcScr_GameControl <- tree 3 26550-26687",
            "gProcScr_PlayerPhase <- gProc_BMapMain 26550-26687",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 26581-26611",
            "gProc_BMapMain <- tree 2 26550-26687",
            "gProc_MapTask <- tree 4 26550-26687",
            "gUnknown_08A01800 <- tree 3 26561-26567",
            "sProcScr_MoveLimitView <- tree 4 26550-26644",
            "sProc_BMVSync <- tree 0 26550-26687",
        )

        val MENU_ROWS = listOf(
            "ProcScr_GreenTextColor <- sProc_Menu 26688-26699",
            "ProcScr_GreenTextColor <- sProc_Menu 26701-26729",
            "ProcScr_Mu <- tree 5 26688-26735",
            "ProcScr_StdEventEngine <- tree 3 26688-26690",
            "gProcCmd_MenuItemPanel <- sProc_Menu 26700-26729",
            "gProcScr_0859124C <- gProcScr_E_FACE 26700-26729",
            "gProcScr_BKSEL <- tree 3 26730-26735",
            "gProcScr_E_FACE <- tree 5 26700-26729",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 26700-26729",
            "gProcScr_GameControl <- tree 3 26688-26735",
            "gProcScr_PlayerPhase <- gProc_BMapMain 26688-26735",
            "gProcScr_TargetSelection <- tree 3 26730-26735",
            "gProc_BMapMain <- tree 2 26688-26735",
            "gProc_MapTask <- tree 4 26688-26735",
            "sProcScr_MoveLimitView <- tree 4 26688-26699",
            "sProcScr_MoveLimitView <- tree 4 26701-26735",
            "sProcScr_MoveLimitViewChange <- sProcScr_MoveLimitView 26688-26694",
            "sProcScr_MoveLimitViewChange <- sProcScr_MoveLimitView 26701-26707",
            "sProc_BMVSync <- tree 0 26688-26735",
            "sProc_Menu <- tree 3 26688-26699",
            "sProc_Menu <- tree 3 26700-26729",
            "sProc_MenuItem <- sProc_Menu 26688-26699",
            "sProc_MenuItem <- sProc_Menu 26700-26729",
        )

        val FORECAST_ROWS = listOf(
            "ProcScr_BoxDialogueDrawTextExt <- tree 3 26747-27605",
            "ProcScr_MergeBoxDialogue <- tree 3 26740-27617",
            "ProcScr_Mu <- tree 5 26736-27633",
            "ProcScr_StdEventEngine <- tree 3 26736-27628",
            "ProcScr_TalkBoxIdle <- tree 0 26739-27616",
            "gProcScr_BKSEL <- tree 3 26736-27629",
            "gProcScr_BoxDialogue <- tree 3 26739-27622",
            "gProcScr_GameControl <- tree 3 26736-27633",
            "gProcScr_PlayerPhase <- gProc_BMapMain 26736-27633",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 26761-26791",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 26850-26881",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 26940-26971",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 27060-27091",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 27150-27181",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 27240-27271",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 27360-27391",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 27480-27511",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 27570-27601",
            "gProcScr_TargetSelection <- tree 3 26736-27629",
            "gProc_BMapMain <- tree 2 26736-27633",
            "gProc_MapTask <- tree 4 26736-27633",
            "gUnknown_08A01800 <- tree 3 26740-26746",
            "sProcScr_MoveLimitView <- tree 4 26736-27629",
            "sProc_BMVSync <- tree 0 26736-27633",
        )

        val BATTLE_ROWS = listOf(
            "ProcScr_BattleEventEngine <- tree 3 28039-28100",
            "ProcScr_Mu <- tree 5 27634-27648",
            "ProcScr_Mu <- tree 5 28772-28823",
            "ProcScr_Mu <- tree 5 28790-28820",
            "ProcScr_MuDeathFade <- ProcScr_Mu 28790-28820",
            "ProcScr_MuHitFlash <- ProcScr_Mu 28790-28806",
            "ProcScr_PalFade <- ProcScr_Mu 28790-28810",
            "ProcScr_PalFade <- gProcScr_E_FACE 28046-28058",
            "ProcScr_PalFade <- gProcScr_E_FACE 28086-28094",
            "ProcScr_TemporaryLock <- gProcScr_Talk 28046-28054",
            "ProcScr_eobjLvup <- tree 3 28542-28732",
            "ProcScr_eobjLvup <- tree 3 28562-28732",
            "ProcScr_eobjLvup <- tree 3 28582-28732",
            "ProcScr_eobjLvup <- tree 3 28602-28732",
            "gMusicProc3Script <- tree 3 27667-27700",
            "gProcScr_0859124C <- gProcScr_E_FACE 28046-28094",
            "gProcScr_E_FACE <- tree 5 28046-28094",
            "gProcScr_E_FACE <- tree 5 28472-28743",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 28046-28094",
            "gProcScr_FaceEndIn8Frames <- tree 3 28086-28094",
            "gProcScr_GameControl <- tree 3 27634-28823",
            "gProcScr_PlayerPhase <- gProc_BMapMain 27634-28823",
            "gProcScr_Talk <- tree 3 28040-28083",
            "gProcScr_TalkBubbleOpen <- tree 3 28056-28067",
            "gProcScr_TalkOpen <- gProcScr_Talk 28056-28068",
            "gProcScr_TalkSkipListener <- tree 3 28042-28083",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 28070-28081",
            "gProc_BMapMain <- tree 2 27634-28823",
            "gProc_MapTask <- tree 4 27634-28823",
            "gUnknown_08591DE8 <- ProcScr_BattleEventEngine 28086-28097",
            "sProcScr_CombatAction <- gProcScr_PlayerPhase 27634-28822",
            "sProc_BMVSync <- tree 0 27634-28823",
            "sProc_DelayedBMapDispResume <- tree 3 28772-28773",
        )

        val ENDING_ROWS = listOf(
            "ProcScr_BoxDialogueDrawTextExt <- tree 3 28833-29014",
            "ProcScr_FadeFromBlack <- ProcScr_StdEventEngine 29061-29076",
            "ProcScr_FadeToBlack <- ProcScr_StdEventEngine 29040-29055",
            "ProcScr_FadeToBlack <- ProcScr_StdEventEngine 30336-30351",
            "ProcScr_MergeBoxDialogue <- tree 3 28826-29026",
            "ProcScr_Mu <- tree 5 28824-30370",
            "ProcScr_PalFade <- gProcScr_E_FACE 29084-29096",
            "ProcScr_PalFade <- gProcScr_E_FACE 29084-29096",
            "ProcScr_StdEventEngine <- tree 3 28824-29037",
            "ProcScr_StdEventEngine <- tree 3 29038-30370",
            "ProcScr_TalkBoxIdle <- tree 0 28825-29025",
            "ProcScr_TemporaryLock <- gProcScr_Talk 29084-29092",
            "ProcScr_TemporaryLock <- gProcScr_Talk 29084-29092",
            "gMusicProc3Script <- tree 3 29039-29089",
            "gProcScr_0859124C <- gProcScr_E_FACE 29084-30352",
            "gProcScr_0859124C <- gProcScr_E_FACE 29084-30352",
            "gProcScr_BoxDialogue <- tree 3 28825-29031",
            "gProcScr_E_FACE <- tree 5 29084-30352",
            "gProcScr_E_FACE <- tree 5 29084-30352",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 29084-30352",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 29084-30352",
            "gProcScr_GameControl <- tree 3 28824-30373",
            "gProcScr_PlayerPhase <- gProc_BMapMain 28824-30370",
            "gProcScr_Talk <- tree 3 29078-30333",
            "gProcScr_TalkBubbleOpen <- tree 3 29094-29105",
            "gProcScr_TalkBubbleOpen <- tree 3 29134-29145",
            "gProcScr_TalkBubbleOpen <- tree 3 29314-29325",
            "gProcScr_TalkBubbleOpen <- tree 3 29374-29385",
            "gProcScr_TalkBubbleOpen <- tree 3 29944-29955",
            "gProcScr_TalkBubbleOpen <- tree 3 30005-30016",
            "gProcScr_TalkOpen <- gProcScr_Talk 29094-29106",
            "gProcScr_TalkOpen <- gProcScr_Talk 29134-29146",
            "gProcScr_TalkOpen <- gProcScr_Talk 29314-29326",
            "gProcScr_TalkOpen <- gProcScr_Talk 29374-29386",
            "gProcScr_TalkOpen <- gProcScr_Talk 29944-29956",
            "gProcScr_TalkOpen <- gProcScr_Talk 30005-30017",
            "gProcScr_TalkPause <- gProcScr_Talk 29239-29256",
            "gProcScr_TalkShiftClear <- gProcScr_Talk 29258-29274",
            "gProcScr_TalkShiftClear <- gProcScr_Talk 29527-29543",
            "gProcScr_TalkShiftClear <- gProcScr_Talk 29551-29567",
            "gProcScr_TalkShiftClear <- gProcScr_Talk 29617-29633",
            "gProcScr_TalkShiftClear <- gProcScr_Talk 29671-29687",
            "gProcScr_TalkShiftClear <- gProcScr_Talk 29767-29783",
            "gProcScr_TalkShiftClear <- gProcScr_Talk 29791-29807",
            "gProcScr_TalkShiftClear <- gProcScr_Talk 29857-29873",
            "gProcScr_TalkShiftClear <- gProcScr_Talk 29881-29897",
            "gProcScr_TalkShiftClear <- gProcScr_Talk 30097-30113",
            "gProcScr_TalkShiftClear <- gProcScr_Talk 30157-30173",
            "gProcScr_TalkShiftClear <- gProcScr_Talk 30181-30197",
            "gProcScr_TalkShiftClear <- gProcScr_Talk 30247-30263",
            "gProcScr_TalkShiftClear <- gProcScr_Talk 30271-30287",
            "gProcScr_TalkSkipListener <- tree 3 29080-30333",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 28861-28891",
            "gProcScr_TalkWaitForInput <- ProcScr_BoxDialogueDrawTextExt 28980-29011",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 29108-29131",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 29161-29191",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 29281-29311",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 29359-29371",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 29431-29461",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 29491-29521",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 29581-29611",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 29731-29761",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 29839-29851",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 29911-29941",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 29989-30001",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 30061-30091",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 30121-30151",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 30211-30241",
            "gProcScr_TalkWaitForInput <- gProcScr_Talk 30301-30331",
            "gProc_BMapMain <- tree 2 28824-30370",
            "gProc_MapTask <- tree 4 28824-30360",
            "gProc_MapTask <- tree 4 30361-30370",
            "gUnknown_08A01800 <- tree 3 28826-28832",
            "sProc_BMVSync <- tree 0 28824-30370",
        )

        val SAVE_ROWS = listOf(
            "ProcScr_ApProc <- tree 3 30386-30494",
            "ProcScr_BmFadeIN <- tree 4 30388-30419",
            "ProcScr_BmFadeOUT <- tree 4 30523-30538",
            "ProcScr_savedraw <- gProcScr_SaveMenuPostChapter " + "30386-30540",
            "gProcScr_GameControl <- tree 3 30374-30549",
            "gProcScr_SaveDrawCursor <- ProcScr_savedraw 30386-30540",
            "gProcScr_SaveMenuPostChapter <- gProcScr_GameControl 30374-30540",
            "gProc_BMapMain <- tree 2 30548-30549",
            "gProc_MapTask <- tree 4 30548-30549",
            "sProc_BMVSync <- tree 0 30548-30549",
        )

        val INTRO_ROWS = listOf(
            "ProcScr_ChapterIntro_Bg1And3Scroll <- gProcScr_ChapterIntro 30553-30740",
            "ProcScr_ChapterIntro_KeyListen <- gProcScr_ChapterIntro 30613-30659",
            "gProcScr_ChapterIntro <- gProc_BMapMain 30550-30756",
            "gProcScr_GameControl <- tree 3 30550-30757",
            "gProc_BMapMain <- tree 2 30550-30757",
            "gProc_MapTask <- tree 4 30550-30757",
            "sProc_BMVSync <- tree 0 30550-30757",
        )

        val CH1_ROWS = listOf(
            "ProcScr_EventDisplayCursor <- ProcScr_StdEventEngine 30876-30900",
            "ProcScr_Mu <- tree 5 30759-30800",
            "ProcScr_Mu <- tree 5 30759-30805",
            "ProcScr_Mu <- tree 5 30760-30810",
            "ProcScr_Mu <- tree 5 30760-30813",
            "ProcScr_Mu <- tree 5 30802-30813",
            "ProcScr_Mu <- tree 5 30807-30825",
            "ProcScr_Mu <- tree 5 30812-30812",
            "ProcScr_MuCtrl <- tree 5 30759-30800",
            "ProcScr_MuCtrl <- tree 5 30759-30805",
            "ProcScr_MuCtrl <- tree 5 30760-30810",
            "ProcScr_MuCtrl <- tree 5 30760-30813",
            "ProcScr_MuCtrl <- tree 5 30802-30813",
            "ProcScr_MuCtrl <- tree 5 30807-30825",
            "ProcScr_MuCtrl <- tree 5 30812-30812",
            "ProcScr_MuStepSe <- tree 3 30761-30762",
            "ProcScr_MuStepSe <- tree 3 30776-30777",
            "ProcScr_MuStepSe <- tree 3 30785-30788",
            "ProcScr_MuStepSe <- tree 3 30792-30795",
            "ProcScr_MuStepSe <- tree 3 30801-30805",
            "ProcScr_MuStepSe <- tree 3 30809-30813",
            "ProcScr_MuStepSe <- tree 3 30818-30819",
            "ProcScr_StdEventEngine <- tree 3 30758-30900",
            "gProcScr_GameControl <- tree 3 30758-30900",
            "gProc_BMapMain <- tree 2 30758-30900",
            "gProc_MapTask <- tree 4 30758-30900",
            "sProc_BMVSync <- tree 0 30758-30900",
        )    }
}
