package fe8.testing

import fe8.game.RomView
import fe8.game.text.Fe8Text
import fe8.game.ui.Popup
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import gbafe.text.TextHost
import gbafe.text.TextSystem
import java.io.File
import org.junit.jupiter.api.Test

/** Popup proc state compared with the item popup capture (NewPopup_Simple(PopupScr_GotItem)). */
class PopupProcDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    @VerifiesPort("PopupProc_Init")
    fun `init fields match the popup proc before its graphics are drawn`() {
        assumeTrue(ref.available, "reference capture unavailable")
        val run = ref.run("ram_trace_item_popup")
        // Script index 3 is the first command after PopupProc_Init and the 10 frame sleep; the
        // icon id is still the Init value there. NewPopup_Simple(PopupScr_GotItem, 0x60, 0).
        val original = ProcArrayDump.ofEwram(dump(run, 18598)).find(Popup.PROC_SCRIPT)!!
        assertEquals(3, original.scrCur)
        val mine = Popup.PopupProc()
        Popup.setupSimple(mine, GOT_ITEM, 0x60, 0)
        Popup.init(mine)
        assertEquals(original.u32(0x2C), mine.pDefinition, "pDefinition")
        assertEquals(original.u32(0x30), mine.clock, "clock")
        assertEquals(original.u8(0x34), mine.xTileParam and 0xFF, "xTileParam")
        assertEquals(original.u8(0x35), mine.yTileParam and 0xFF, "yTileParam")
        assertEquals(original.u8(0x36), mine.winStyle, "winStyle")
        assertEquals(original.u8(0x3B), mine.textColorId, "textColorId")
        assertEquals(original.u16(0x3E), mine.iconId, "iconId")
        assertEquals(original.u16(0x40), mine.iconObjTileId, "iconObjTileId")
        assertEquals(original.u8(0x42), mine.iconPalId, "iconPalId")
        assertEquals(original.u8(0x44), mine.iconX, "iconX")
        assertEquals(original.u16(0x48), mine.soundId, "soundId")
    }

    @Test
    @VerifiesPort("PopupProc_WaitForPress")
    fun `positive clock counts down and breaks like the original`() {
        assumeTrue(ref.available, "reference capture unavailable")
        val run = ref.run("ram_trace_item_popup")
        // Only the positive-clock path is observable (the capture has no key wait). Align on
        // the first snapshot inside the wait loop (script index 9): its clock seeds the Kotlin
        // proc, then every later snapshot is one frame, i.e. one callback.
        val steps = (18570..18750).map { it to ProcArrayDump.ofEwram(dump(run, it)) }
            .mapNotNull { (step, dump) -> dump.find(Popup.PROC_SCRIPT)?.let { step to it } }
        val waitStart = steps.indexOfFirst { it.second.scrCur == 9 }
        assertTrue(waitStart >= 0)
        val mine = Popup.PopupProc()
        mine.clock = steps[waitStart].second.u32(0x30)
        var breakStep = -1
        var firstZero = -1
        for ((step, original) in steps.drop(waitStart + 1)) {
            if (mine.broken) {
                firstZero = if (original.scrCur != 9) step else -1
                break
            }
            Popup.waitForPress(mine) { 0 }
            if (mine.broken) breakStep = step
            if (original.scrCur == 9) {
                assertEquals(original.u32(0x30), mine.clock, "step $step clock")
            }
        }
        assertTrue(breakStep > 0, "never broke")
        assertEquals(0, mine.clock)
        // The original leaves the wait loop on the frame its callback breaks (index 9 -> 11
        // once the next frame runs the script), so it must have left by the step after.
        assertTrue(firstZero == breakStep + 1, "break $breakStep, left at $firstZero")
    }

    @Test
    @VerifiesPort("ParsePopupInstAndGetLen", "GetItemNameWithArticle", "GetItemIconId", "GetItemName")
    fun `parsePopupInstAndGetLen reproduces the traced width icon and sound`() {
        assumeTrue(ref.available, "reference capture unavailable")
        val romFile = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
            "baserom.gba",
        )
        assumeTrue(romFile.isFile, "ROM not present")
        val run = ref.run("ram_trace_item_popup")
        // The first snapshot after PopupProc_PrepareGfx ran (script index past 3).
        var original: ProcArrayDump.Proc? = null
        var step = 0
        for (s in 18598..18750) {
            val p = ProcArrayDump.ofEwram(dump(run, s)).find(Popup.PROC_SCRIPT)
            if (p != null && p.scrCur > 3 && p.u16(0x46) != 0) {
                original = p
                step = s
                break
            }
        }
        assertTrue(original != null, "no snapshot after PrepareGfx")
        val iwram = try {
            run.region("r$step", "iwram")
        } catch (e: java.io.IOException) {
            run.region("f$step", "iwram")
        }
        val popupItem = (iwram[GPOPUPITEM].toInt() and 0xFF) or
            ((iwram[GPOPUPITEM + 1].toInt() and 0xFF) shl 8)
        val romBytes = romFile.readBytes()
        val root = u32(romBytes, 0x0815D488 - ROM_BASE) - ROM_BASE
        val messages = MsgStrings(
            romBytes, MsgHuffman(romBytes, 0x0815A72C - ROM_BASE, root), 0x0815D48C - ROM_BASE,
        )
        val text = TextSystem(romBytes, EnglishHost(), Fe8Text.CONFIG)
        text.initTextFont(null, 0x06001000, 0x100, 0)
        val mine = Popup.PopupProc()
        Popup.setupSimple(mine, GOT_ITEM, 0x60, 0)
        Popup.init(mine)
        val palettes = ArrayList<Pair<Int, Int>>()
        mine.xGfxSize = Popup.parsePopupInstAndGetLen(
            mine, RomView.ofRom(romBytes), messages, text, popupItem, 0, 0,
        ) { slot, pal -> palettes.add(slot to pal) }
        assertEquals(original!!.u16(0x46), mine.xGfxSize, "xGfxSize")
        assertEquals(original.u8(0x44), mine.iconX, "iconX")
        assertEquals(original.u16(0x3E), mine.iconId, "iconId")
        assertEquals(original.u16(0x48), mine.soundId, "soundId")
        assertEquals(listOf(0 to original.u8(0x42)), palettes)
    }

    @Test
    // Partial: PopupProc_GfxDraw stays untagged until its frame/text/icon drawing is ported.
    fun `gfxDrawLayout reproduces the traced frame layout and icon child`() {
        assumeTrue(ref.available, "reference capture unavailable")
        val romFile = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
            "baserom.gba",
        )
        assumeTrue(romFile.isFile, "ROM not present")
        val run = ref.run("ram_trace_item_popup")
        var dump: ProcArrayDump? = null
        var original: ProcArrayDump.Proc? = null
        var step = 0
        for (s in 18598..18750) {
            val d = ProcArrayDump.ofEwram(dump(run, s))
            val p = d.find(Popup.PROC_SCRIPT)
            // Script index 9 is the wait loop, i.e. PopupProc_GfxDraw has run; earlier the
            // layout fields still hold stale bytes.
            if (p != null && p.scrCur >= 9 && p.u8(0x39) != 0) {
                dump = d
                original = p
                step = s
                break
            }
        }
        assertTrue(original != null, "no snapshot after GfxDraw")
        val iwram = try {
            run.region("r$step", "iwram")
        } catch (e: java.io.IOException) {
            run.region("f$step", "iwram")
        }
        val popupItem = (iwram[GPOPUPITEM].toInt() and 0xFF) or
            ((iwram[GPOPUPITEM + 1].toInt() and 0xFF) shl 8)
        val romBytes = romFile.readBytes()
        val root = u32(romBytes, 0x0815D488 - ROM_BASE) - ROM_BASE
        val messages = MsgStrings(
            romBytes, MsgHuffman(romBytes, 0x0815A72C - ROM_BASE, root), 0x0815D48C - ROM_BASE,
        )
        val text = TextSystem(romBytes, EnglishHost(), Fe8Text.CONFIG)
        text.initTextFont(null, 0x06001000, 0x100, 0)
        val mine = Popup.PopupProc()
        Popup.setupSimple(mine, GOT_ITEM, 0x60, 0)
        Popup.init(mine)
        val len = Popup.parsePopupInstAndGetLen(
            mine, RomView.ofRom(romBytes), messages, text, popupItem, 0, 0,
        ) { _, _ -> }
        Popup.gfxDrawLayout(mine, len)
        assertEquals(original!!.u16(0x46), mine.xGfxSize, "xGfxSize")
        assertEquals(original.u8(0x37), mine.xTileReal, "xTileReal")
        assertEquals(original.u8(0x38), mine.yTileReal, "yTileReal")
        assertEquals(original.u8(0x39), mine.xTileSize, "xTileSize")
        assertEquals(original.u8(0x3A), mine.yTileSize, "yTileSize")
        assertEquals(original.u8(0x44), mine.iconX, "iconX")
        val child = dump!!.find(Popup.UPDATE_ICON_PROC_SCRIPT)
        assertTrue(child != null, "no icon update child")
        val fields = Popup.iconChildFields(mine)
        assertEquals(child!!.u32(0x2C), fields[0], "unk_2C")
        assertEquals(child.u32(0x30), fields[1], "unk_30")
        assertEquals(child.u16(0x4A), fields[2], "unk_4A")
    }

    private fun dump(run: ReferenceData.Run, step: Int): ByteArray =
        try {
            run.region("r$step", "ewram")
        } catch (e: java.io.IOException) {
            run.region("f$step", "ewram")
        }

    private companion object {
        const val GOT_ITEM = 0x08592230
        const val ROM_BASE = 0x08000000

        /** `gPopupItem` (0x030005F4) in the IWRAM dump. */
        const val GPOPUPITEM = 0x5F4
    }

    private fun u32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    /** English host; only the language matters for string widths. */
    private class EnglishHost : TextHost {
        override fun language() = 1

        override fun applyTextPalette(talk: Boolean, palid: Int) = Unit

        override fun readVram32(addr: Int) = 0

        override fun writeVram32(addr: Int, value: Int) = Unit

        override fun clearVram(dest: Int, byteCount: Int) = Unit
    }
}
