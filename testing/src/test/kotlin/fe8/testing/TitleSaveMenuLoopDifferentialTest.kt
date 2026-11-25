package fe8.testing

import fe8.game.bm.PlaySt
import fe8.game.flags.Fe8EventFlags
import fe8.game.save.BmSaveLib
import fe8.game.save.NewGameRam
import fe8.game.savemenu.SaveMenuMainLoop
import fe8.game.savemenu.SaveMenuNewGame
import fe8.game.savemenu.SaveMenuState
import gba.core.VerifiesPort
import gba.core.sram.CartSram
import gba.testkit.ReferenceData
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.render.ArchivedPaletteFade
import gbafe.render.BgHardware
import gbafe.render.PaletteFade
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * `PC-11c` against the title runs and `ram_trace_newgame`: `SameMenu_CtrlLoop` (UP / DOWN along
 * the six-item list of `ram_trace_title_menu_items`, A on Resume in `ram_trace_title_resume` f1002,
 * A on Restart in `ram_trace_title_continue` f1033), `SaveMenuScrollSlot` (14 frames after each A,
 * breaking into `PL_SAVE_SLOT_SEL`), `SaveMenuSlotSelDrawSprite` (prompt sprite proc at
 * f1017 / f1049, skipped for New Game at f1116) and `SaveMenuModifySaveSlot` (slot 0 from the
 * fixture SRAM).
 */
class TitleSaveMenuLoopDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    private class Rig(val run: ReferenceData.Run, sramStep: String = "s01000") {
        val engine = ProcEngine()
        val keys = KeyStatus()
        val lib = BmSaveLib(
            CartSram().also { if (run.hasSram) it.copyIn(run.sram(sramStep)) },
        ).also { it.sramInit() }
        private val hardware = BgHardware()
        private val fade = ArchivedPaletteFade(PaletteFade(hardware, engine), hardware, engine)
        private val bridge = SaveMenuNewGame(
            engine, hardware, lib,
            NewGameRam(PlaySt(), Fe8EventFlags.create(), ByteArray(200)), fade,
        )
        val loop = SaveMenuMainLoop(engine, keys, lib)
        val proc: Proc = engine.start(bridge.script, 3)
        val state = proc.state as SaveMenuState
        val sprites = ArrayList<List<Int>>()

        init {
            bridge.hooks.funcs["SameMenu_CtrlLoop"] = ProcFunc { loop.sameMenuCtrlLoop(it) }
            bridge.hooks.funcs["SaveMenuScrollSlot"] = ProcFunc { loop.saveMenuScrollSlot(it) }
            bridge.hooks.funcs["SaveMenuSlotSelDrawSprite"] =
                ProcFunc { loop.saveMenuSlotSelDrawSprite(it) }
            loop.hooks.startHelpPromptSprite = { x, y, z, _ -> sprites += listOf(x, y, z) }
            engine.goto(proc, PL_MAIN_LOOP)
            engine.run(proc)
        }

        fun cap(step: Int) = ProcArrayDump.ofEwram(
            run.region("r%05d".format(step), "ewram"), ProcArrayDump.romNames(),
        ).find("ProcScr_SaveMenu")!!

        fun seed(step: Int) {
            val c = cap(step)
            state.mainOptions = c.u8(MAIN_OPTIONS)
            state.unk31 = c.u8(MENU_COUNT)
            state.mainSelect = c.u8(MAIN_SELECT)
            state.susSlotCur = c.u8(SUS_SLOT_CUR)
            state.mainSelBitfile = c.u16(MAIN_SEL_BITFILE)
            state.susSlot = 9 // so the write by the loop is visible
        }

        fun step(mask: Int) {
            keys.repeatedKeys = mask
            keys.newKeys = mask
            engine.run(proc)
            keys.repeatedKeys = 0
            keys.newKeys = 0
        }

        fun assertScroll(first: Int, label: String) {
            for (k in 1..14) {
                step(0)
                val stepNum = first + k - 1
                val c = cap(stepNum)
                assertEquals(c.u8(SCROLL_CNT), state.scrollCnt, "$label scroll_cnt f$stepNum")
                assertEquals(c.u8(SCROLL_X), state.unk2f, "$label unk_2f f$stepNum")
                assertEquals(c.u8(JUMP_LABEL), state.jumpLabel, "$label jump_label f$stepNum")
                assertEquals(c.scrCur, proc.scrCur, "$label scrCur f$stepNum")
            }
        }
    }

    private fun rig(name: String, seedStep: Int = 1001, sramStep: String = "s01000"): Rig {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(ProcArrayDump.romNames().isNotEmpty(), "decomp nm file not present")
        val run = ref.run(name)
        assumeTrue(run.dumps.containsKey("r%05d".format(seedStep)), "$name f$seedStep not present")
        return Rig(run, sramStep)
    }

    @Test
    @Timeout(300)
    @VerifiesPort("SameMenu_CtrlLoop")
    fun `down and up presses move main_select along the six items as in the cursor walk run`() {
        val r = rig("ram_trace_title_menu_items")
        r.seed(1001)
        val presses = (0 until 5).map { 1002 + 30 * it to DPAD_DOWN } +
            (0 until 5).map { 1152 + 30 * it to DPAD_UP }
        for ((stepNum, mask) in presses) {
            r.step(mask)
            assertEquals(r.cap(stepNum).u8(MAIN_SELECT), r.state.mainSelect, "main_select f$stepNum")
            assertEquals(PL_MAIN_LOOP, r.state.jumpLabel)
            r.step(0)
            assertEquals(r.cap(stepNum + 1).u8(MAIN_SELECT), r.state.mainSelect)
        }
    }

    @Test
    @Timeout(300)
    @VerifiesPort("SameMenu_CtrlLoop", "SaveMenuScrollSlot", "SaveMenuSlotSelDrawSprite")
    fun `A on Resume stores the suspend slot and scrolls to the slot select as in the resume run`() {
        val r = rig("ram_trace_title_resume")
        r.seed(1001)
        r.step(A_BUTTON)
        assertEquals(r.cap(1002).u16(MAIN_SEL_BITFILE), r.state.mainSelBitfile)
        assertEquals(r.cap(1002).scrCur, r.proc.scrCur, "resume goto PL_SCROLL_SLOT scrCur")
        assertEquals(r.cap(1003).u8(SUS_SLOT), r.state.susSlot)
        assertEquals(0, r.state.scrollCnt)
        r.assertScroll(1003, "resume")
        // After scroll frame 14 breaks, the next tick enters PL_SAVE_SLOT_SEL and draws sprite.
        r.step(0)
        assertEquals(r.cap(1017).scrCur, r.proc.scrCur, "resume PL_SAVE_SLOT_SEL scrCur f1017")
        assertSpriteStart(r, 1016, 1017)
    }

    @Test
    @Timeout(300)
    @VerifiesPort(
        "SameMenu_CtrlLoop", "SaveMenuScrollSlot", "SaveMenuSlotSelDrawSprite",
        "SaveMenuModifySaveSlot",
    )
    fun `A on Restart picks the last used valid slot and scrolls as in the continue run`() {
        val r = rig("ram_trace_title_continue")
        r.seed(1001)
        r.step(DPAD_DOWN)
        assertEquals(r.cap(1002).u8(MAIN_SELECT), r.state.mainSelect)
        r.step(A_BUTTON)
        // Continue captures f1033 mid-frame before Proc_Goto finishes due to the SRAM read; f1034
        // holds the post-goto cursor at label(PL_SCROLL_SLOT).
        assertEquals(r.cap(1033).u16(MAIN_SEL_BITFILE), r.state.mainSelBitfile)
        assertEquals(r.cap(1034).scrCur, r.proc.scrCur, "restart goto PL_SCROLL_SLOT scrCur")
        assertEquals(r.cap(1035).u8(SUS_SLOT), r.state.susSlot)
        val slot = r.loop.saveMenuModifySaveSlot(r.lib.readLastGameSaveId(), true, 1)
        assertEquals(r.cap(1035).u8(SUS_SLOT), slot)
        r.assertScroll(1035, "restart")
        r.step(0)
        assertEquals(r.cap(1049).scrCur, r.proc.scrCur, "restart PL_SAVE_SLOT_SEL scrCur f1049")
        assertSpriteStart(r, 1048, 1049)
    }

    @Test
    @Timeout(300)
    @VerifiesPort("SaveMenuSlotSelDrawSprite")
    fun `New Game option skips starting the help prompt sprite as in ram_trace_newgame`() {
        val r = rig("ram_trace_newgame", seedStep = 1115, sramStep = "s01128")
        r.seed(1115)
        assertEquals(0x10, r.state.mainSelBitfile)
        val names = ProcArrayDump.romNames()
        val hasPrompt = ProcArrayDump.ofEwram(r.run.region("r01116", "ewram"), names)
            .find("gProcScr_HelpPromptSpr") != null
        assertEquals(false, hasPrompt)
        r.loop.saveMenuSlotSelDrawSprite(r.proc)
        assertEquals(0, r.sprites.size)
    }

    private fun assertSpriteStart(r: Rig, before: Int, after: Int) {
        val names = ProcArrayDump.romNames()
        fun has(step: Int) = ProcArrayDump.ofEwram(r.run.region("r%05d".format(step), "ewram"), names)
            .find("gProcScr_HelpPromptSpr") != null
        assertEquals(false, has(before))
        assertEquals(true, has(after))
        assertEquals(1, r.sprites.size)
    }

    private companion object {
        const val A_BUTTON = 1
        const val DPAD_UP = 1 shl 6
        const val DPAD_DOWN = 1 shl 7
        const val PL_MAIN_LOOP = 2
        const val SCROLL_CNT = 0x29
        const val MAIN_SELECT = 0x2B
        const val SUS_SLOT = 0x2C
        const val JUMP_LABEL = 0x2E
        const val SCROLL_X = 0x2F
        const val MAIN_OPTIONS = 0x30
        const val MENU_COUNT = 0x31
        const val SUS_SLOT_CUR = 0x3F
        const val MAIN_SEL_BITFILE = 0x42
    }
}
