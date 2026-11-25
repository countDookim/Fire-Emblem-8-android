package fe8.testing

import gba.testkit.ReferenceData
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Inventory (not a port claim, PC-11i) of the title main menu and its two save loads in the
 * PC-11cap runs. `ram_trace_title_continue` (fixture: slot 1 plus the suspend save plus two other
 * full slots; 4 menu items) restarts the chapter from slot 1: DOWN `f1002`, A `f1033` (scroll to
 * the slot select), A `f1094` (confirm box), LEFT `f1155`, A `f1176` (`SaveMenu_NewGame`), fade
 * out `f1177..1211`, `PostSaveMenuHandler` `f1214` -> `ReadGameSave(0)`, `nextAction` 1 `f1215`,
 * `gProc_BMapMain` `f1223`, `gProcScr_ChapterIntro` `f1225..1830`, the beginning event `f1831`.
 * `ram_trace_title_resume` (same fixture) resumes the suspend save: A `f1002` (scroll), A `f1063`
 * (`SaveMenu_NewGame`, no confirm box), fade out `f1064..1097`, `PostSaveMenuHandler` `f1099` ->
 * `ReadSuspendSave(3)`, `nextAction` 4 `f1102`, `gProc_BMapMain` `f1109`, `gProcScr_PlayerPhase`
 * `f1129`. `ram_trace_title_menu_items` (slot 1 plus suspend only; 6 items) walks the cursor down
 * `f1002/1032/1062/1092/1122` and up `f1152..1272`. Proc rows are built with
 * [EnemyPhaseInventoryTest.rows] from the `procs` watch (1-based step index); the save menu's own
 * fields are read from its `ProcScr_SaveMenu` record (`struct SaveMenuProc`, `include/savemenu.h`).
 * The function lists of the PORT rows PC-11a..PC-11g are in docs/TASKS.md.
 */
class TitleMenuInventoryTest {
    private val inventory = EnemyPhaseInventoryTest()
    private val ref = ReferenceData.fromEnvironment()

    private fun check(expected: List<String>, run: String, first: Int, last: Int) {
        assertEquals(
            expected.sorted(),
            inventory.rows(run, first, last).sorted(),
            "$run f$first..f$last",
        )
    }

    private fun run(name: String): ReferenceData.Run {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run(name)
        assumeTrue(run.hasWatch("procs"), "$name watches missing")
        return run
    }

    /** Decomp symbols, read once per test (the test class is instantiated per test). */
    private val names: Map<Int, String> by lazy {
        ProcArrayDump.romNames().also { assumeTrue(it.isNotEmpty(), "decomp nm file not present") }
    }

    private fun dump(run: ReferenceData.Run, step: Int) =
        ProcArrayDump.ofWatch(run.watch("procs"), step, names)

    private fun menu(run: ReferenceData.Run, step: Int) =
        dump(run, step).find("ProcScr_SaveMenu")!!

    @Test
    @Timeout(300)
    fun `continue run menu setup matches the procs watch f900 to f960`() {
        check(CONTINUE_MENU_SETTLE, CONTINUE, 900, 960)
    }

    @Test
    @Timeout(300)
    fun `continue run slot select scroll matches the procs watch f1001 to f1100`() {
        check(CONTINUE_SLOT_SELECT, CONTINUE, 1001, 1100)
    }

    @Test
    @Timeout(300)
    fun `continue run confirm box and fade out match the procs watch f1101 to f1213`() {
        check(CONTINUE_CONFIRM_BOX, CONTINUE, 1101, 1213)
    }

    @Test
    @Timeout(300)
    fun `continue run slot load and map start match the procs watch f1214 to f1230`() {
        check(CONTINUE_LOAD, CONTINUE, 1214, 1230)
    }

    @Test
    @Timeout(300)
    fun `continue run chapter intro to beginning event matches the procs watch f1826 to f1840`() {
        check(CONTINUE_EVENT_START, CONTINUE, 1826, 1840)
    }

    @Test
    @Timeout(300)
    fun `resume run slot select scroll matches the procs watch f1002 to f1062`() {
        check(RESUME_SLOT_SELECT, RESUME, 1002, 1062)
    }

    @Test
    @Timeout(300)
    fun `resume run fade out, suspend load and map start match the procs watch f1063 to f1110`() {
        check(RESUME_FADE_OUT_LOAD, RESUME, 1063, 1110)
    }

    @Test
    @Timeout(300)
    fun `resume run map fade in and player phase match the procs watch f1111 to f1135`() {
        check(RESUME_PLAYER_PHASE, RESUME, 1111, 1135)
    }

    @Test
    @Timeout(300)
    fun `cursor walk run only keeps the save menu procs from f900 to f1320`() {
        check(ITEMS_CURSOR_WALK, ITEMS, 900, 1320)
    }

    @Test
    @Timeout(300)
    fun `continue and resume runs share every proc until the first A at f1001`() {
        assertEquals(
            inventory.rows(CONTINUE, 900, 1001).sorted(),
            inventory.rows(RESUME, 900, 1001).sorted(),
        )
    }

    @Test
    @Timeout(300)
    fun `continue run save menu fields follow main menu, slot scroll, confirm box and fade out`() {
        val run = run(CONTINUE)
        fun m(step: Int) = menu(run, step)
        // StartSaveMenu: main_sel_bitfile 0x100 (not a menu option), unk_44 0x100 (suspend shown)
        assertEquals(0x100, m(921).u16(MAIN_SEL_BITFILE))
        assertEquals(0xFF, m(927).u8(SUS_SLOT_CUR)) // SaveMenu_InitScreen
        assertEquals(0x100, m(927).u16(UNK_44))
        // ProcScr_SaveMenu init: slot chapter titles, then InitSaveMenuChoice (3 full slots)
        // SaveMenuInitSaveSlotData(0..2) fill chapter_idx f928/f930/f932, slot 3 (suspend) f936
        assertEquals(listOf(1, 0, 0), chapterIdx(m(928)))
        assertEquals(listOf(1, 7, 0), chapterIdx(m(930)))
        assertEquals(listOf(1, 7, 6), chapterIdx(m(932)))
        assertEquals(0xFF, m(935).u8(SUS_SLOT_CUR))
        assertEquals(0, m(936).u8(SUS_SLOT_CUR)) // the suspend save belongs to slot index 0
        // InitSaveMenuChoice spans f937..f943 (the IsExtra*Enabled checks read SRAM)
        assertEquals(0, m(937).u8(MAIN_OPTIONS))
        assertEquals(0x0B, m(938).u8(MAIN_OPTIONS)) // resume | restart | erase
        assertEquals(3, m(938).u8(MENU_COUNT))
        assertEquals(0x03, m(939).u8(EXTRA_OPTIONS))
        assertEquals(PL_MAIN_LOOP, m(943).u8(JUMP_LABEL))
        assertEquals(0x2B, m(943).u8(MAIN_OPTIONS)) // resume, restart, erase, extras
        assertEquals(4, m(943).u8(MENU_COUNT))
        assertEquals(0x03, m(943).u8(EXTRA_OPTIONS))
        assertEquals(MAIN_MENU_OPTION_RESUME, m(943).u16(MAIN_SEL_BITFILE))
        assertEquals(0, m(1001).u8(MAIN_SELECT))
        assertEquals(1, m(1002).u8(MAIN_SELECT)) // DOWN
        assertEquals(MAIN_MENU_OPTION_RESUME, m(1032).u16(MAIN_SEL_BITFILE))
        assertEquals(MAIN_MENU_OPTION_RESTART, m(1033).u16(MAIN_SEL_BITFILE)) // A
        // SaveMenuScrollSlot: scroll_cnt 1..14 from f1035, unk_2f ends at 0xDC
        assertEquals(PL_SCROLL_SLOT, m(1035).u8(JUMP_LABEL))
        assertEquals(1, m(1035).u8(SCROLL_CNT))
        assertEquals(0x1F, m(1035).u8(SCROLL_X))
        assertEquals(0x0E, m(1048).u8(SCROLL_CNT))
        assertEquals(0xDC, m(1048).u8(SCROLL_X))
        assertEquals(PL_SAVE_SLOT_SEL, m(1050).u8(JUMP_LABEL))
        // slot select: A f1094 opens the confirm box (cursor_config 2 = Cancel)
        assertEquals(0, m(1093).u8(CURSOR_CONFIG))
        assertEquals(2, m(1094).u8(CURSOR_CONFIG))
        assertEquals(1, m(1094).u8(HELP_BOX_ENABLED))
        assertEquals(1, m(1155).u8(CURSOR_CONFIG)) // LEFT: Start
        // A f1176: ExecSaveMenuMiscOption with cursor_config 1 -> unk_44 0xF0, SaveMenu_NewGame
        assertEquals(0, m(1176).u8(CURSOR_CONFIG))
        assertEquals(0, m(1176).u8(HELP_BOX_ENABLED))
        assertEquals(0x100, m(1175).u16(UNK_44))
        assertEquals(0xE0, m(1176).u16(UNK_44))
        assertEquals(0, m(1193).u16(UNK_44))
        assertEquals(0, m(1214).u8(SUS_SLOT)) // slot 1 (index 0) is what PostSaveMenuHandler loads
        assertEquals(MAIN_MENU_OPTION_RESTART, m(1214).u16(MAIN_SEL_BITFILE))
        assertNull(dump(run, 1216).find("ProcScr_SaveMenu"))
    }

    @Test
    @Timeout(300)
    fun `resume run save menu fields follow main menu, slot scroll and fade out`() {
        val run = run(RESUME)
        fun m(step: Int) = menu(run, step)
        assertEquals(0x2B, m(943).u8(MAIN_OPTIONS))
        assertEquals(MAIN_MENU_OPTION_RESUME, m(1002).u16(MAIN_SEL_BITFILE)) // A on item 0
        assertEquals(0, m(1002).u8(MAIN_SELECT))
        assertEquals(PL_SCROLL_SLOT, m(1003).u8(JUMP_LABEL))
        assertEquals(1, m(1003).u8(SCROLL_CNT))
        assertEquals(0x0E, m(1016).u8(SCROLL_CNT))
        assertEquals(PL_SAVE_SLOT_SEL, m(1018).u8(JUMP_LABEL))
        // A f1063 goes straight to SaveMenu_NewGame: no confirm box (cursor_config stays 0),
        // unk_44 keeps 0x100 (only the slot restart path sets 0xF0)
        for (step in 1018..1099) {
            assertEquals(0, m(step).u8(CURSOR_CONFIG), "cursor_config f$step")
            assertEquals(0, m(step).u8(HELP_BOX_ENABLED), "hb_en f$step")
            assertEquals(0x100, m(step).u16(UNK_44), "unk_44 f$step")
        }
        assertEquals(MAIN_MENU_OPTION_RESUME, m(1099).u16(MAIN_SEL_BITFILE))
        assertNull(dump(run, 1103).find("ProcScr_SaveMenu"))
        assertNull(dump(run, 1103).find("gProcScr_HelpPromptSpr"))
    }

    @Test
    @Timeout(300)
    fun `cursor walk run lists six main menu options and moves main_select by one per press`() {
        val run = run(ITEMS)
        fun m(step: Int) = menu(run, step)
        assertEquals(listOf(1, 0xFF, 0xFF), chapterIdx(m(932))) // two erased slots
        assertEquals(listOf(1, 0xFF, 0xFF), chapterIdx(m(1320)))
        assertEquals(0x3F, m(943).u8(MAIN_OPTIONS)) // every option: one slot full, suspend valid
        assertEquals(6, m(943).u8(MENU_COUNT))
        assertEquals(0x03, m(943).u8(EXTRA_OPTIONS))
        // DOWN f1002/1032/1062/1092/1122, UP f1152/1182/1212/1242/1272
        val pressed = listOf(1002, 1032, 1062, 1092, 1122, 1152, 1182, 1212, 1242, 1272)
        val expected = listOf(1, 2, 3, 4, 5, 4, 3, 2, 1, 0)
        assertEquals(0, m(1001).u8(MAIN_SELECT))
        for ((i, step) in pressed.withIndex()) {
            assertEquals(expected[i], m(step).u8(MAIN_SELECT), "main_select f$step")
            val before = if (i == 0) 0 else expected[i - 1]
            assertEquals(before, m(step - 1).u8(MAIN_SELECT), "main_select before f$step")
        }
        // nothing is selected: the bitfile stays MAIN_MENU_OPTION_RESUME, no scroll, no jump
        for (step in 943..1320) {
            assertEquals(MAIN_MENU_OPTION_RESUME, m(step).u16(MAIN_SEL_BITFILE), "bit f$step")
            assertEquals(PL_MAIN_LOOP, m(step).u8(JUMP_LABEL), "jump f$step")
            assertEquals(0, m(step).u8(SCROLL_CNT), "scroll f$step")
        }
    }

    @Test
    @Timeout(300)
    fun `tracecalls run in menu order`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        fun calls(name: String): List<String> {
            val file = File(ref.root, "$name/calls.txt")
            assumeTrue(file.isFile, "$name has no calls.txt")
            return file.readLines().filter { it.isNotBlank() }.map {
                val f = it.split(" ")
                "${f[0].removePrefix("call_")} ${f[1]}"
            }
        }
        assertEquals(
            listOf(
                "SaveMenu_NewGame_1_entry 1176", "SaveMenu_NewGame_1_exit 1176",
                "PostSaveMenuHandler_1_entry 1214", "ReadGameSave_1_entry 1214",
                "ReadGameSave_1_exit 1215", "PostSaveMenuHandler_1_exit 1215",
            ),
            calls(CONTINUE),
        )
        assertEquals(
            listOf(
                "SaveMenu_NewGame_1_entry 1063", "SaveMenu_NewGame_1_exit 1063",
                "PostSaveMenuHandler_1_entry 1099", "ReadSuspendSave_1_entry 1099",
                "ReadSuspendSave_1_exit 1102", "PostSaveMenuHandler_1_exit 1102",
            ),
            calls(RESUME),
        )
        val items = File(ref.root, "$ITEMS/calls.txt")
        assertEquals(0, if (items.isFile) items.readLines().count { it.isNotBlank() } else 0)
    }

    @Test
    @Timeout(300)
    fun `slot load sets gameSaveSlot, chapter state and nextAction 1, suspend load nextAction 4`() {
        val cont = run(CONTINUE)
        val resume = run(RESUME)
        fun action(run: ReferenceData.Run, step: Int) =
            dump(run, step).find("gProcScr_GameControl")!!.u8(GC_NEXT_ACTION)
        // ReadGameSave(0) f1214..1215: gPlaySt becomes the saved slot (chapter 1, turn 3)
        val playSt = cont.watch("gplayst")
        assertEquals(0, playSt.u8(1213, PLAYST_CHAPTER_INDEX))
        assertEquals(1, playSt.u8(1214, PLAYST_CHAPTER_INDEX))
        assertEquals(3, playSt.u16(1214, PLAYST_TURN))
        assertEquals(0, playSt.u8(1214, PLAYST_GAME_SAVE_SLOT))
        assertEquals(0, action(cont, 1214))
        assertEquals(1, action(cont, 1215)) // GAME_ACTION 1 = slot index 0 + 1
        // GameControl_SwitchPostSaveMenu -> map: chapter start state (turn 0, phase 0x40)
        assertEquals(0x40, playSt.u8(1218, PLAYST_FACTION))
        assertEquals(0, playSt.u16(1218, PLAYST_TURN))
        assertNotNull(dump(cont, 1223).find("gProc_BMapMain"))
        assertNotNull(dump(cont, 1225).find("gProcScr_ChapterIntro"))
        assertNotNull(dump(cont, 1831).find("ProcScr_StdEventEngine"))
        // ReadSuspendSave(3) f1099..1102: suspended state, chapter 1 turn 1, GAME_ACTION_4
        val suspend = resume.watch("gplayst")
        assertEquals(0, suspend.u8(1099, PLAYST_CHAPTER_INDEX))
        assertEquals(1, suspend.u8(1100, PLAYST_CHAPTER_INDEX))
        assertEquals(1, suspend.u16(1100, PLAYST_TURN))
        assertEquals(0, action(resume, 1101))
        assertEquals(4, action(resume, 1102))
        assertNotNull(dump(resume, 1109).find("gProc_BMapMain"))
        assertNull(dump(resume, 1128).find("gProcScr_PlayerPhase"))
        assertNotNull(dump(resume, 1129).find("gProcScr_PlayerPhase"))
    }

    @Test
    @Timeout(300)
    fun `loads write only the SRAM header block, the resumed map rewrites the suspend block`() {
        val cont = run(CONTINUE)
        val resume = run(RESUME)
        assumeTrue(cont.hasSram && resume.hasSram, "SRAM dumps missing")
        fun changed(run: ReferenceData.Run, a: String, b: String): List<Int> {
            val x = run.sram(a)
            val y = run.sram(b)
            return x.indices.filter { x[it] != y[it] }
        }
        // ReadGameSave (f1214): InvalidateSuspendSave rewrites the two suspend block descriptors
        // (table entries 3 and 4 at 0x94 and 0xA4, 29 changed bytes); the data stays
        val slotLoad = changed(cont, "s01213", "s01214")
        assertEquals(29, slotLoad.size)
        assertEquals(0x94..0xB3, slotLoad.min()..slotLoad.max())
        assertEquals(0, changed(cont, "s01214", "s01215").size)
        assertEquals(0, changed(cont, "s01215", "s01216").size)
        // ReadSuspendSave writes no SRAM; BmMain_SuspendBeforePhase rewrites the suspend block
        assertEquals(0, changed(resume, "s01099", "s01128").size)
        val rewrite = changed(resume, "s01128", "s01129")
        assertEquals(0xD4, rewrite.min())
        assertEquals(0x1816, rewrite.max())
        assertEquals(2932, rewrite.size)
        assertEquals(listOf(0x63, 0xA0, 0xA1, 0xA2, 0xA3), changed(resume, "s01130", "s01131"))
    }

    private fun chapterIdx(menu: ProcArrayDump.Proc) =
        listOf(menu.u8(CHAPTER_IDX), menu.u8(CHAPTER_IDX + 1), menu.u8(CHAPTER_IDX + 2))

    private companion object {
        const val CONTINUE = "ram_trace_title_continue"
        const val RESUME = "ram_trace_title_resume"
        const val ITEMS = "ram_trace_title_menu_items"

        // struct SaveMenuProc (include/savemenu.h)
        const val SCROLL_CNT = 0x29
        const val MAIN_SELECT = 0x2B
        const val SUS_SLOT = 0x2C
        const val JUMP_LABEL = 0x2E
        const val SCROLL_X = 0x2F
        const val MAIN_OPTIONS = 0x30
        const val MENU_COUNT = 0x31
        const val EXTRA_OPTIONS = 0x32
        const val CURSOR_CONFIG = 0x36
        const val CHAPTER_IDX = 0x37
        const val HELP_BOX_ENABLED = 0x3E
        const val SUS_SLOT_CUR = 0x3F
        const val MAIN_SEL_BITFILE = 0x42
        const val UNK_44 = 0x44
        const val MAIN_MENU_OPTION_RESUME = 0x01
        const val MAIN_MENU_OPTION_RESTART = 0x02
        const val PL_MAIN_LOOP = 2
        const val PL_SCROLL_SLOT = 3
        const val PL_SAVE_SLOT_SEL = 5

        // struct PlaySt and GameCtrlProc
        const val PLAYST_GAME_SAVE_SLOT = 0x0C
        const val PLAYST_CHAPTER_INDEX = 0x0E
        const val PLAYST_FACTION = 0x0F
        const val PLAYST_TURN = 0x10
        const val GC_NEXT_ACTION = 0x29

        val CONTINUE_MENU_SETTLE = listOf(
            "gProcScr_DrawTitleSprites <- gProcScr_TitleScreen 900-919",
            "gProcScr_GameControl <- tree 3 900-960",
            "gProcScr_TitleScreen <- gProcScr_GameControl 900-919",
            "ProcScr_FadeCore <- gProcScr_TitleScreen 903-918",
            "ProcScr_SaveMenu <- gProcScr_GameControl 921-960",
            "sMusicProc2Script <- tree 3 921-960",
            "ProcScr_ApProc <- tree 3 937-960",
            "ProcScr_savedraw <- ProcScr_SaveMenu 937-960",
            "gProcScr_SaveDrawCursor <- ProcScr_savedraw 937-960",
            "ProcScr_BmFadeIN <- tree 4 944-960",
        )

        val CONTINUE_SLOT_SELECT = listOf(
            "ProcScr_ApProc <- tree 3 1001-1100",
            "ProcScr_SaveMenu <- gProcScr_GameControl 1001-1100",
            "ProcScr_savedraw <- ProcScr_SaveMenu 1001-1100",
            "gProcScr_GameControl <- tree 3 1001-1100",
            "gProcScr_SaveDrawCursor <- ProcScr_savedraw 1001-1100",
            "gProcScr_HelpPromptSpr <- ProcScr_SaveMenu 1049-1100",
            "ProcScr_HelpBoxIntro <- tree 3 1094-1100",
            "gProcScr_HelpBox <- tree 3 1094-1100",
        )

        val CONTINUE_CONFIRM_BOX = listOf(
            "ProcScr_ApProc <- tree 3 1101-1175",
            "ProcScr_HelpBoxIntro <- tree 3 1101-1103",
            "ProcScr_SaveMenu <- gProcScr_GameControl 1101-1213",
            "ProcScr_savedraw <- ProcScr_SaveMenu 1101-1213",
            "gProcScr_GameControl <- tree 3 1101-1213",
            "gProcScr_HelpBox <- tree 3 1101-1183",
            "gProcScr_HelpPromptSpr <- ProcScr_SaveMenu 1101-1213",
            "gProcScr_SaveDrawCursor <- ProcScr_savedraw 1101-1213",
            "gProcScr_HelpBoxTextScroll <- tree 3 1104-1128",
            "sMusicProc2Script <- tree 3 1176-1195",
            "ProcScr_BmFadeOUT <- tree 4 1177-1211",
        )

        val CONTINUE_LOAD = listOf(
            "ProcScr_SaveMenu <- gProcScr_GameControl 1214-1215",
            "gProcScr_GameControl <- tree 3 1214-1230",
            "gProcScr_HelpPromptSpr <- ProcScr_SaveMenu 1214-1215",
            "gProc_BMapMain <- tree 2 1223-1230",
            "gProc_MapTask <- tree 4 1223-1230",
            "sProc_BMVSync <- tree 0 1223-1230",
            "gProcScr_ChapterIntro <- gProc_BMapMain 1225-1230",
            "ProcScr_ChapterIntro_Bg1And3Scroll <- gProcScr_ChapterIntro 1228-1230",
        )

        val CONTINUE_EVENT_START = listOf(
            "ProcScr_ChapterIntro_KeyListen <- gProcScr_ChapterIntro 1826-1829",
            "gProcScr_ChapterIntro <- gProc_BMapMain 1826-1829",
            "gProcScr_GameControl <- tree 3 1826-1840",
            "gProc_BMapMain <- tree 2 1826-1840",
            "gProc_MapTask <- tree 4 1826-1840",
            "sProc_BMVSync <- tree 0 1826-1840",
            "ProcScr_StdEventEngine <- tree 3 1831-1840",
            "ProcScr_Mu <- tree 5 1832-1840",
            "ProcScr_Mu <- tree 5 1832-1840",
            "ProcScr_MuCtrl <- tree 5 1832-1840",
            "ProcScr_MuCtrl <- tree 5 1832-1840",
            "ProcScr_Mu <- tree 5 1833-1840",
            "ProcScr_Mu <- tree 5 1833-1840",
            "ProcScr_MuCtrl <- tree 5 1833-1840",
            "ProcScr_MuCtrl <- tree 5 1833-1840",
            "ProcScr_MuStepSe <- tree 3 1834-1835",
        )

        val RESUME_SLOT_SELECT = listOf(
            "ProcScr_ApProc <- tree 3 1002-1062",
            "ProcScr_SaveMenu <- gProcScr_GameControl 1002-1062",
            "ProcScr_savedraw <- ProcScr_SaveMenu 1002-1062",
            "gProcScr_GameControl <- tree 3 1002-1062",
            "gProcScr_SaveDrawCursor <- ProcScr_savedraw 1002-1062",
            "gProcScr_HelpPromptSpr <- ProcScr_SaveMenu 1017-1062",
        )

        val RESUME_FADE_OUT_LOAD = listOf(
            "ProcScr_ApProc <- tree 3 1063-1098",
            "ProcScr_SaveMenu <- gProcScr_GameControl 1063-1102",
            "ProcScr_savedraw <- ProcScr_SaveMenu 1063-1098",
            "gProcScr_GameControl <- tree 3 1063-1110",
            "gProcScr_HelpPromptSpr <- ProcScr_SaveMenu 1063-1102",
            "gProcScr_SaveDrawCursor <- ProcScr_savedraw 1063-1098",
            "sMusicProc2Script <- tree 3 1063-1080",
            "ProcScr_BmFadeOUT <- tree 4 1064-1096",
            "gProc_BMapMain <- tree 2 1109-1110",
            "gProc_MapTask <- tree 4 1109-1110",
            "sProc_BMVSync <- tree 0 1109-1110",
        )

        val RESUME_PLAYER_PHASE = listOf(
            "ProcScr_FadeFromBlack <- tree 3 1111-1126",
            "gProcScr_GameControl <- tree 3 1111-1135",
            "gProc_BMapMain <- tree 2 1111-1135",
            "gProc_MapTask <- tree 4 1111-1135",
            "sProc_BMVSync <- tree 0 1111-1135",
            "gProcScr_PlayerPhase <- gProc_BMapMain 1129-1135",
            "ProcScr_GreenTextColor <- gProcScr_GoalDisplay 1132-1135",
            "gProcScr_GoalDisplay <- tree 3 1132-1135",
            "gProcScr_TerrainDisplay <- tree 3 1132-1135",
            "gProcScr_UnitDisplay_MinimugBox <- tree 3 1132-1135",
        )

        val ITEMS_CURSOR_WALK = listOf(
            "gProcScr_DrawTitleSprites <- gProcScr_TitleScreen 900-919",
            "gProcScr_GameControl <- tree 3 900-1320",
            "gProcScr_TitleScreen <- gProcScr_GameControl 900-919",
            "ProcScr_FadeCore <- gProcScr_TitleScreen 903-918",
            "ProcScr_SaveMenu <- gProcScr_GameControl 921-1320",
            "sMusicProc2Script <- tree 3 921-970",
            "ProcScr_ApProc <- tree 3 933-1320",
            "ProcScr_savedraw <- ProcScr_SaveMenu 933-1320",
            "gProcScr_SaveDrawCursor <- ProcScr_savedraw 933-1320",
            "ProcScr_BmFadeIN <- tree 4 938-955",
        )
    }
}
