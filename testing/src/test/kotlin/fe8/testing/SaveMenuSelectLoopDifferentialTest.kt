package fe8.testing

import fe8.game.RomView
import fe8.game.savemenu.PostSaveMenu
import fe8.game.savemenu.SaveMenuSelectLoop
import fe8.game.savemenu.SaveMenuState
import fe8.game.text.Fe8Text
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.label
import gbafe.render.BgHardware
import gbafe.text.Font
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import gbafe.text.Text
import gbafe.text.TextHost
import gbafe.text.TextSystem
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential test for `PC-4e1` against `ram_trace_prologue_end` (`PC-cap1`): the two `A`
 * presses with `MAIN_MENU_OPTION_INVALID` in `SaveMenu_SaveSlotSelectLoop`. The first (f9599)
 * opens the confirm sub-box (`cursor_config` 1, BG1 row 17 and the sub-box text tiles); the
 * second (f9799) runs `WriteGameSave(sus_slot)` (slot 0), jumps to `PL_SAVEMENU_SLOT_SELECTED`
 * and clears the sub-box. The SRAM written by `WriteGameSave` itself is covered by
 * `ReadGameSaveReferenceTest` and the full-menu integration (`PC-4`).
 */
class SaveMenuSelectLoopDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private class Harness(val run: ReferenceData.Run, romBytes: ByteArray) {
        val rom = RomView.ofRom(romBytes)
        val engine = ProcEngine()
        val hardware = BgHardware()
        val host = object : TextHost {
            override fun language(): Int = 1

            override fun applyTextPalette(talk: Boolean, palid: Int) {
                val palAddr = PAL_TEXT + if (talk) 0x20 else 0
                for (i in 0 until 16) {
                    hardware.palette[palid * 16 + i] = rom.u16(palAddr + 2 * i).toShort()
                }
                hardware.palette[palid * 16] = 0
                hardware.enablePaletteSync()
            }

            override fun readVram32(addr: Int): Int {
                val o = addr - VRAM_BASE
                var v = 0
                for (k in 0..3) v = v or ((hardware.vram[o + k].toInt() and 0xFF) shl (8 * k))
                return v
            }

            override fun writeVram32(addr: Int, value: Int) {
                val o = addr - VRAM_BASE
                for (k in 0..3) hardware.vram[o + k] = (value shr (8 * k)).toByte()
            }

            override fun clearVram(dest: Int, byteCount: Int) {
                hardware.vram.fill(0, dest - VRAM_BASE, dest - VRAM_BASE + byteCount)
            }
        }
        val text = TextSystem(romBytes, host, Fe8Text.CONFIG)
        val messages = MsgStrings(
            romBytes,
            MsgHuffman(
                romBytes,
                0x0815A72C - 0x08000000,
                u32(romBytes, 0x0815D488 - 0x08000000) and 0x01FFFFFF,
            ),
            0x0815D48C - 0x08000000,
        )
        val font = Font()
        val subText = Text()
        var keys = 0
        val sounds = ArrayList<Int>()
        val written = ArrayList<Int>()
        var helpBoxClosed = 0
        val loop = SaveMenuSelectLoop(
            engine, hardware, text, messages, font, subText,
            bitfileToIndex = { bits -> (0 until 8).firstOrNull { (bits shr it) and 1 != 0 } ?: -1 },
        ).also {
            it.hooks.newKeys = { keys }
            it.hooks.playSoundEffect = { id -> sounds += id }
            it.hooks.writeGameSave = { slot -> written += slot }
            it.hooks.closeHelpBox = { helpBoxClosed++ }
        }

        fun dump(step: Int): ProcArrayDump.Proc =
            ProcArrayDump.ofWatch(run.watch("procs"), step, ProcArrayDump.romNames())
                .find("gProcScr_SaveMenuPostChapter")!!

        /** A proc carrying the `SaveMenuProc` fields of capture step [step]. */
        fun procAt(step: Int): Proc {
            val parent = engine.start(ProcScript("parent", listOf(block())), 3)
            val proc = engine.start(
                ProcScript(
                    "child",
                    listOf(block(), label(5), block(), label(6), block(), label(17), block()),
                    newState = { SaveMenuState() },
                ),
                parent,
            )!!
            val sm = dump(step)
            val s = proc.state as SaveMenuState
            s.susSlot = sm.u8(SM_SUS_SLOT)
            s.cursorSlot = sm.u8(SM_CURSOR_SLOT)
            s.cursorConfig = sm.u8(SM_CURSOR_CONFIG)
            s.hbEn = sm.u8(SM_HB_EN) != 0
            s.susSlotCur = sm.u8(SM_SUS_SLOT_CUR)
            s.ctrlTimer = sm.u8(SM_CTRL_TIMER)
            s.mainSelBitfile = sm.u16(SM_MAIN_SEL_BITFILE)
            s.scrollCnt = 7
            return proc
        }

        fun seed(frame: String) {
            val ew = run.region(frame, "ewram")
            val tm = hardware.mapBuffer(1)
            for (i in tm.indices) tm[i] = u16(ew, BG1_TILEMAP - EWRAM + i * 2).toShort()
            text.resetText()
            text.initTextFont(font, VRAM_BASE + 0x80 * 0x20, 0x80, 4)
            text.initText(subText, 10)
            run.region(frame, "vram").copyInto(hardware.vram)
        }

        fun cursorConfig(step: Int): Int = dump(step).u8(SM_CURSOR_CONFIG)
    }

    private fun harness(): Harness {
        assumeTrue(ref.available && romFile.isFile, "mGBA reference data / ROM not present")
        assumeTrue(ProcArrayDump.romNames().isNotEmpty(), "decomp nm file not present")
        val run = ref.run(RUN)
        assumeTrue(run.hasWatch("procs"), "$RUN lacks procs watch")
        return Harness(run, romFile.readBytes())
    }

    private fun compareSubBox(h: Harness, frame: String, what: String) {
        val ew = h.run.region(frame, "ewram")
        val tm = h.hardware.mapBuffer(1)
        for (row in 17..18) {
            for (col in 7 until 7 + 12) {
                val i = row * 32 + col
                assertEquals(u16(ew, BG1_TILEMAP - EWRAM + i * 2), tm[i].toInt() and 0xFFFF, "$what BG1 [$row][$col]")
            }
        }
        assertArrayEquals(
            h.run.region(frame, "vram").copyOfRange(0x1000, 0x1280),
            h.hardware.vram.copyOfRange(0x1000, 0x1280),
            "$what sub-box text VRAM",
        )
    }

    @Test
    @VerifiesPort("SaveMenuDrawSubSelBoxExt")
    fun `A press at f9599 opens the confirm sub-box like PC-cap1 f9600`() {
        val h = harness()
        val open = (9590..9610).first { h.cursorConfig(it) == 1 }
        h.seed("f09494")
        val proc = h.procAt(open - 1)
        h.keys = A
        h.loop.saveMenuSaveSlotSelectLoop(proc)

        val after = h.dump(open)
        val s = proc.state as SaveMenuState
        assertEquals(after.u8(SM_CURSOR_CONFIG), s.cursorConfig)
        assertEquals(after.u8(SM_SCROLL_CNT), s.scrollCnt)
        assertEquals(after.u8(SM_JUMP_LABEL), s.jumpLabel)
        assertEquals(after.u8(SM_HB_EN) != 0, s.hbEn)
        assertEquals(listOf(0x6A), h.sounds)
        // Close branch only without a suspend save (`sus_slot_cur == 0xFF`).
        assertEquals(if (s.susSlotCur == 0xFF) 1 else 0, h.helpBoxClosed)
        assertEquals(after.u8(SM_SUS_SLOT_CUR), s.susSlotCur)
        assertTrue(h.hardware.modifiedBgs and 2 != 0, "BG1 sync requested")
        compareSubBox(h, "f09600", "open")
    }

    @Test
    @VerifiesPort("SaveMenuDrawSubSelBoxExt")
    fun `second A press at f9799 writes slot 0 and clears the sub-box like PC-cap1 f9802`() {
        val h = harness()
        h.seed("f09799")
        val proc = h.procAt(9798)
        assertEquals(1, (proc.state as SaveMenuState).cursorConfig)
        h.keys = A
        h.loop.saveMenuSaveSlotSelectLoop(proc)

        val s = proc.state as SaveMenuState
        assertEquals(listOf(0), h.written)
        assertEquals(listOf(0x60), h.sounds)
        assertEquals(0, s.cursorConfig)
        assertEquals(PostSaveMenu.MAIN_MENU_OPTION_INVALID, s.mainSelBitfile)
        assertEquals(h.cursorConfig(9802), s.cursorConfig)
        compareSubBox(h, "f09802", "clear")
    }

    private companion object {
        const val RUN = "ram_trace_prologue_end"
        const val A = 1
        const val EWRAM = 0x02000000
        const val VRAM_BASE = 0x06000000
        const val PAL_TEXT = 0x0859EF00
        const val BG1_TILEMAP = 0x020234A8

        const val SM_SCROLL_CNT = 0x29
        const val SM_SUS_SLOT = 0x2C
        const val SM_CURSOR_SLOT = 0x2D
        const val SM_JUMP_LABEL = 0x2E
        const val SM_CURSOR_CONFIG = 0x36
        const val SM_HB_EN = 0x3E
        const val SM_SUS_SLOT_CUR = 0x3F
        const val SM_CTRL_TIMER = 0x40
        const val SM_MAIN_SEL_BITFILE = 0x42

        fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF
        fun u16(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8)
        fun u32(b: ByteArray, o: Int): Int = u16(b, o) or (u16(b, o + 2) shl 16)
    }
}
