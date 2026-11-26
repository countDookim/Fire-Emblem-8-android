package fe8.testing

import fe8.game.Fe8Boot
import fe8.game.GameControl
import fe8.game.RomView
import fe8.game.savemenu.SaveMenuState
import gba.core.sram.SaveStorage
import gba.testkit.AllocationMeter
import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.proc.Proc
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * `PC-11` WIRE: [Fe8Boot] (`realTitleMenu`) runs the real title menu (`ProcScr_SaveMenu`) from
 * boot against `ram_trace_title_continue` (Restart Chapter) and `ram_trace_title_resume`
 * (Resume Chapter). The SRAM is the capture's own first SRAM dump, the keys are the capture's
 * `inputs.bin` once the menu fade-in lines up with the capture's (`SaveMenu_InitScreen` lags
 * in the capture, so the boot is [shift] frames ahead of it from there on). Compared at settled
 * frames: the `SaveMenuProc` fields, the loaded `gPlaySt`, `nextAction`, the SRAM after the
 * load and the proc that follows (chapter intro; resumed player phase).
 */
@Timeout(600)
class TitleMenuWireTest {
    private val ref = ReferenceData.fromEnvironment()
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )

    private class Session(val boot: Fe8Boot, val run: ReferenceData.Run) {
        var frame = 0
        var shift = 0
        var aligned = false
        var keys: Any = 0
        var manual: Int? = null
        val keyBoxes = HashMap<Int, Any>()
    }

    private fun available() = ref.available && File(assets, "baserom.gba").isFile &&
        ProcArrayDump.romNames().isNotEmpty()

    private fun start(name: String): Session {
        val run = ref.run(name)
        val save = run.sram("s01000")
        val storage = object : SaveStorage {
            override fun load(dest: ByteArray): Boolean {
                save.copyInto(dest)
                return true
            }

            override fun store(src: ByteArray) {}
        }
        lateinit var session: Session
        val source: () -> Any = { session.keys }
        @Suppress("UNCHECKED_CAST")
        val boot = Fe8Boot(
            RomView.ofRom(File(assets, "baserom.gba").readBytes()),
            source as () -> Int,
            RngService(),
            saveStorage = storage,
            realTitleMenu = true,
        )
        session = Session(boot, run)
        return session
    }

    /** Advances one frame, feeding the capture's input of the step the frame stands for. */
    private fun step(s: Session) {
        val cap = s.frame + 1 + s.shift
        val manual = s.manual
        val raw = manual ?: if (cap - 1 < s.run.inputs.size) s.run.inputs[cap - 1] else 0
        s.keys = s.keyBoxes.getOrPut(raw) { raw }
        s.boot.frame()
        s.frame++
        if (!s.aligned && s.boot.engine.procs.any { it.script?.name == "ProcScr_BmFadeIN" }) {
            s.aligned = true
            s.shift = FADE_IN_CAPTURE_FRAME - s.frame
        }
    }

    private fun live(s: Session) = s.boot.engine.procs.mapNotNull { it.script?.name }

    /** Runs until the capture frame [cap] (our frame `cap - shift`). */
    private fun runTo(s: Session, cap: Int) {
        var guard = 0
        while (s.frame + s.shift < cap && guard++ < 6000) step(s)
        assertEquals(cap, s.frame + s.shift, "reached capture frame")
    }

    private fun menu(s: Session): Proc? =
        s.boot.engine.procs.firstOrNull { it.script?.name == "ProcScr_SaveMenu" }

    private fun capMenu(s: Session, cap: Int, names: Map<Int, String>) =
        ProcArrayDump.ofEwram(s.run.region(dump(s, cap), "ewram"), names).find("ProcScr_SaveMenu")

    private fun dump(s: Session, cap: Int): String =
        listOf("r%05d", "f%05d").map { it.format(cap) }.first { s.run.dumps.containsKey(it) }

    private fun menuFields(p: ProcArrayDump.Proc) = listOf(
        0x2B, 0x2C, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x36, 0x3F,
    ).map { p.u8(it) } + p.u16(0x42)

    private fun menuFields(m: SaveMenuState) = listOf(
        m.mainSelect, m.susSlot, m.cursorSlot, m.jumpLabel, m.unk2f, m.mainOptions, m.unk31,
        m.cursorConfig, m.susSlotCur, m.mainSelBitfile,
    )

    private fun checkMenu(s: Session, cap: Int, names: Map<Int, String>, what: String) {
        val ours = menu(s)
        assertTrue(ours != null, "$what: menu alive at f$cap")
        val want = capMenu(s, cap, names)!!
        assertEquals(menuFields(want), menuFields(ours!!.state as SaveMenuState), "$what f$cap")
    }

    private fun playSt(s: Session): ByteArray =
        ByteArray(PLAY_ST_SIZE).also { s.boot.control.playSt.encode(it, 0) }

    private fun capPlaySt(s: Session, cap: Int): ByteArray =
        s.run.region(dump(s, cap), "ewram").copyOfRange(
            GPLAYST - GbaMem.EWRAM, GPLAYST - GbaMem.EWRAM + PLAY_ST_SIZE,
        )

    private fun sram(s: Session) =
        ByteArray(SRAM_SIZE).also { s.boot.sram.copyOut(it) }

    private fun nextAction(s: Session): Int =
        (s.boot.engine.find(s.boot.control.script)?.state as GameControl.State).nextAction

    /** Runs until `nextAction` is [action]; the capture frame equivalent, or -1. */
    private fun runToAction(s: Session, action: Int): Int {
        var guard = 0
        while (guard++ < 600) {
            step(s)
            if (nextAction(s) == action) return s.frame + s.shift
        }
        return -1
    }

    /** Zeroes the descriptor magic words (uninitialised stack in the capture, PC-11k). */
    private fun masked(b: ByteArray): ByteArray = b.copyOf().also {
        for (o in MAGIC_OFFSETS) for (i in 0 until 4) it[o + i] = 0
    }

    @Test
    fun `restart chapter loads slot 1 and starts the chapter as ram_trace_title_continue`() {
        assumeTrue(available(), "reference data / ROM / decomp nm file")
        val names = ProcArrayDump.romNames()
        val s = start("ram_trace_title_continue")
        runTo(s, 1000)
        checkMenu(s, 1000, names, "settled")
        runTo(s, 1031)
        checkMenu(s, 1031, names, "after DOWN")
        runTo(s, 1093)
        checkMenu(s, 1093, names, "slot select")
        runTo(s, 1154)
        checkMenu(s, 1154, names, "confirm box")
        // The capture's help box lags a few frames, so the load is compared by its event.
        val load = runToAction(s, 1)
        assertTrue(load in 1205..1215, "slot load at capture f1215, ours f$load")
        assertArrayEquals(capPlaySt(s, 1215), playSt(s), "gPlaySt of the slot, f1215")
        repeat(10) { step(s) }
        assertArrayEquals(
            masked(s.run.sram("s01216")), masked(sram(s)), "SRAM after ReadGameSave",
        )
        assertTrue("ProcScr_SaveMenu" !in live(s), "menu ended")
        var guard = 0
        while (guard++ < 200 && "gProcScr_ChapterIntro" !in live(s)) step(s)
        assertTrue("gProcScr_ChapterIntro" in live(s), "chapter intro starts: ${live(s)}")
        assertTrue("gProc_BMapMain" in live(s), "battle map started: ${live(s)}")
    }

    @Test
    fun `resume chapter reads the suspend save and resumes the map as ram_trace_title_resume`() {
        assumeTrue(available(), "reference data / ROM / decomp nm file")
        val names = ProcArrayDump.romNames()
        val s = start("ram_trace_title_resume")
        runTo(s, 1000)
        checkMenu(s, 1000, names, "settled")
        runTo(s, 1031)
        checkMenu(s, 1031, names, "slot select")
        runTo(s, 1062)
        checkMenu(s, 1062, names, "slot select before A")
        val load = runToAction(s, 4)
        assertTrue(load in 1095..1103, "suspend load at capture f1102, ours f$load")
        assertArrayEquals(capPlaySt(s, 1102), playSt(s), "gPlaySt of the suspend, f1102")
        repeat(10) { step(s) }
        assertArrayEquals(s.run.sram("s01102"), sram(s), "SRAM after ReadSuspendSave")
        var guard = 0
        while (guard++ < 400 && s.boot.engine.find(s.boot.playerPhaseScript) == null) step(s)
        assertTrue(s.boot.engine.find(s.boot.playerPhaseScript) != null, "player phase: ${live(s)}")
        assertEquals(1, s.boot.control.playSt.chapterIndex)
    }

    @Test
    fun `uncaptured menu items hold without throwing on ram_trace_title_menu_items SRAM`() {
        assumeTrue(available(), "reference data / ROM / decomp nm file")
        val s = start("ram_trace_title_menu_items")
        runTo(s, 1000)
        fun press(key: Int) {
            s.manual = key
            step(s)
            s.manual = 0
            repeat(PRESS_SETTLE) { step(s) }
        }

        fun label() = (menu(s)!!.state as SaveMenuState).jumpLabel
        press(DOWN)
        press(DOWN) // Copy Data
        press(A_KEY)
        repeat(SCROLL_FRAMES) { step(s) }
        assertEquals(5, label(), "Copy: slot select")
        press(A_KEY) // the Copy / Erase screens are not ported: the slot select continues
        assertTrue(menu(s) != null, "Copy: A on a slot keeps the menu")
        backToMain(s, "Copy")
        press(DOWN) // Erase Data
        press(A_KEY)
        repeat(SCROLL_FRAMES) { step(s) }
        assertEquals(5, label(), "Erase: slot select")
        backToMain(s, "Erase")
        press(DOWN) // New Game
        press(A_KEY)
        repeat(SCROLL_FRAMES) { step(s) }
        assertTrue("ProcScr_NewGameDifficultySelect" in live(s), "difficulty select: ${live(s)}")
        press(B_KEY)
        repeat(SCROLL_FRAMES) { step(s) }
        assertEquals(2, label(), "B cancels the difficulty select")
        press(DOWN) // Extras
        press(A_KEY)
        repeat(SCROLL_FRAMES) { step(s) }
        assertEquals(2, label(), "Extras returns to the main list")
        press(B_KEY) // leaves for the title
        repeat(2 * SCROLL_FRAMES) { step(s) }
        assertTrue("ProcScr_SaveMenu" !in live(s), "menu ended: ${live(s)}")
    }

    /** B (up to four presses: sub box, cursor slot, then the slide) back to the main list. */
    private fun backToMain(s: Session, what: String) {
        var presses = 0
        while (presses++ < 4 && (menu(s)!!.state as SaveMenuState).jumpLabel != 2) {
            s.manual = B_KEY
            step(s)
            s.manual = 0
            repeat(SCROLL_FRAMES) { step(s) }
        }
        val label = (menu(s)!!.state as SaveMenuState).jumpLabel
        assertEquals(2, label, "$what: B reaches the main list")
    }

    @Test
    fun `title menu allocation per frame stays bounded`() {
        assumeTrue(AllocationMeter.supported && available(), "allocation meter / reference data")
        val first = menuBytes()
        val second = menuBytes()
        val worst = (0 until first.size).maxOf { minOf(first[it], second[it]) }
        assertTrue(worst <= ALLOC_LIMIT, "up to $worst bytes in one frame")
    }

    private fun menuBytes(): LongArray {
        val s = start("ram_trace_title_continue")
        s.boot.gpuFrame
        runTo(s, MENU_FROM)
        val bytes = LongArray(MENU_WINDOW)
        for (i in 0 until MENU_WINDOW) {
            val before = AllocationMeter.current()
            step(s)
            s.boot.pack()
            bytes[i] = AllocationMeter.current() - before
        }
        return bytes
    }

    private companion object {
        const val FADE_IN_CAPTURE_FRAME = 944
        const val PLAY_ST_SIZE = 0x4C
        const val GPLAYST = 0x0202BCF0
        const val SRAM_SIZE = 0x8000
        val MAGIC_OFFSETS = intArrayOf(0x94, 0xA4)
        const val DOWN = 0x80
        const val A_KEY = 0x01
        const val B_KEY = 0x02
        const val PRESS_SETTLE = 6
        const val SCROLL_FRAMES = 30
        const val MENU_FROM = 1000
        const val MENU_WINDOW = 200
        const val ALLOC_LIMIT = 8192L
    }
}
