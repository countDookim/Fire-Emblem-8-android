package fe8.testing

import fe8.game.RomView
import fe8.game.text.Fe8Text
import fe8.game.ui.ItemIcon
import fe8.game.ui.Popup
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import gba.testkit.RamSymbols
import gbafe.render.BgHardware
import gbafe.text.Font
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

    @Test
    @VerifiesPort("PopupProc_GfxDraw")
    fun `gfxDraw reproduces the popup frame text icon and child fields`() {
        assumeTrue(ref.available, "reference capture unavailable")
        val romFile = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
            "baserom.gba",
        )
        assumeTrue(romFile.isFile, "ROM not present")
        val run = ref.run("ram_trace_item_popup")
        val ewram = run.region("f18630", "ewram")
        val vram = run.region("f18630", "vram")
        val iwram = run.region("f18630", "iwram")
        val dump = ProcArrayDump.ofEwram(ewram)
        val original = dump.find(Popup.PROC_SCRIPT)!!
        val popupItem = (iwram[GPOPUPITEM].toInt() and 0xFF) or
            ((iwram[GPOPUPITEM + 1].toInt() and 0xFF) shl 8)
        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)
        val root = u32(romBytes, 0x0815D488 - ROM_BASE) - ROM_BASE
        val messages = MsgStrings(
            romBytes, MsgHuffman(romBytes, 0x0815A72C - ROM_BASE, root), 0x0815D48C - ROM_BASE,
        )
        val host = VramHost()
        val text = TextSystem(romBytes, host, Fe8Text.CONFIG)
        text.initTextFont(null, 0x06002000, 0x100, 0)
        val hardware = BgHardware()
        val mine = Popup.PopupProc()
        Popup.setupSimple(mine, GOT_ITEM, 0x60, 0)
        Popup.init(mine)
        val child = Popup.gfxDraw(mine, rom, messages, text, hardware, popupItem, 0, 0) { _, _ -> }
        assertTrue(child != null, "no icon child")

        assertEquals(original.u16(0x46), mine.xGfxSize, "xGfxSize")
        assertEquals(original.u8(0x37), mine.xTileReal, "xTileReal")
        assertEquals(original.u8(0x38), mine.yTileReal, "yTileReal")
        assertEquals(original.u8(0x39), mine.xTileSize, "xTileSize")
        assertEquals(original.u8(0x3A), mine.yTileSize, "yTileSize")
        assertEquals(original.u8(0x44), mine.iconX, "iconX")
        assertEquals(original.u16(0x3E), mine.iconId, "iconId")
        val orgChild = dump.find(Popup.UPDATE_ICON_PROC_SCRIPT)!!
        assertEquals(orgChild.u32(0x2C), child!![0], "unk_2C")
        assertEquals(orgChild.u32(0x30), child[1], "unk_30")
        assertEquals(orgChild.u16(0x4A), child[2], "unk_4A")

        val bg0 = hardware.mapBuffer(0)
        val bg1 = hardware.mapBuffer(1)
        for (row in mine.yTileReal until mine.yTileReal + 4) {
            for (col in mine.xTileReal until mine.xTileReal + mine.xTileSize) {
                val idx = row * 32 + col
                assertEquals(
                    ewramU16(ewram, BG0_TM + idx * 2), bg0[idx].toInt() and 0xFFFF,
                    "BG0 ($col, $row)",
                )
                assertEquals(
                    ewramU16(ewram, BG1_TM + idx * 2), bg1[idx].toInt() and 0xFFFF,
                    "BG1 ($col, $row)",
                )
            }
        }
        val glyphBytes = (mine.xTileSize - 2) * 2 * 0x20
        assertEquals(
            vram.copyOfRange(0x2000, 0x2000 + glyphBytes).toList(),
            host.vram.copyOfRange(0x2000, 0x2000 + glyphBytes).toList(),
            "glyph tiles",
        )
        val target = ItemIcon.OBJ_VRAM0 + mine.iconObjTileId * 0x20
        for (off in intArrayOf(0, 0x400)) {
            assertEquals(
                vram.copyOfRange(target + off, target + off + 0x40).toList(),
                hardware.vram.copyOfRange(target + off, target + off + 0x40).toList(),
                "icon tiles +$off",
            )
        }
    }

    @Test
    @VerifiesPort("PopupProc_GfxClear")
    fun `gfxClear zeroes the popup rectangle like the end callback`() {
        assumeTrue(ref.available, "reference capture unavailable")
        val run = ref.run("ram_trace_item_popup")
        val steps = (18570..18750).map { it to dump(run, it) }
        val last = steps.indexOfLast {
            ProcArrayDump.ofEwram(it.second).find(Popup.PROC_SCRIPT) != null
        }
        assertTrue(last in 0 until steps.size - 1, "no before/after pair")
        val before = steps[last].second
        val after = steps[last + 1].second
        val original = ProcArrayDump.ofEwram(before).find(Popup.PROC_SCRIPT)!!
        val mine = Popup.PopupProc()
        mine.xTileReal = original.u8(0x37)
        mine.yTileReal = original.u8(0x38)
        mine.xTileSize = original.u8(0x39)
        mine.yTileSize = original.u8(0x3A)
        val bg0 = ShortArray(0x400) { ewramU16(before, BG0_TM + it * 2).toShort() }
        val bg1 = ShortArray(0x400) { ewramU16(before, BG1_TM + it * 2).toShort() }
        assertTrue(bg0.any { it.toInt() != 0 }, "BG0 already empty before the clear")
        Popup.gfxClear(mine, bg0, bg1)
        for (idx in 0 until 0x400) {
            assertEquals(ewramU16(after, BG0_TM + idx * 2), bg0[idx].toInt() and 0xFFFF, "BG0 $idx")
            assertEquals(ewramU16(after, BG1_TM + idx * 2), bg1[idx].toInt() and 0xFFFF, "BG1 $idx")
        }
    }

    @Test
    @VerifiesPort("PopupProc_PrepareGfx", "ResetIconGraphics")
    fun `prepareGfx reproduces the font frame graphics blend and icon tables`() {
        assumeTrue(ref.available, "reference capture unavailable")
        val romFile = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
            "baserom.gba",
        )
        assumeTrue(romFile.isFile, "ROM not present")
        val sym = RamSymbols.fromEnvironment()
        val run = ref.run("ram_trace_item_popup")
        // f18598: the popup proc is at script index 3 (PrepareGfx is the next call); f18599: it
        // has run PrepareGfx and MaybeSetVolume and yielded (index 6).
        val beforeIw = region(run, 18598, "iwram")
        val afterEw = dump(run, 18599)
        val afterIw = region(run, 18599, "iwram")
        // VRAM is dumped from f18630 on; GfxDraw leaves the frame tiles at 0.. untouched.
        val afterVram = run.region("f18630", "vram")
        val original = ProcArrayDump.ofEwram(afterEw).find(Popup.PROC_SCRIPT)!!
        assertEquals(6, original.scrCur)
        val popupItem = (beforeIw[GPOPUPITEM].toInt() and 0xFF) or
            ((beforeIw[GPOPUPITEM + 1].toInt() and 0xFF) shl 8)
        val romBytes = romFile.readBytes()
        val root = u32(romBytes, 0x0815D488 - ROM_BASE) - ROM_BASE
        val messages = MsgStrings(
            romBytes, MsgHuffman(romBytes, 0x0815A72C - ROM_BASE, root), 0x0815D48C - ROM_BASE,
        )
        val text = TextSystem(romBytes, EnglishHost(), Fe8Text.CONFIG)
        text.initTextFont(null, 0x06001000, 0x80, 0) // ResetText, the font before PrepareGfx
        val hardware = BgHardware()
        hardware.lcd.readFrom(beforeIw, LCD_BUFFER)
        // The trace has no stale window bits or icon entries before PrepareGfx, so seed some:
        // the original's result must still be the traced, cleared state.
        hardware.lcd.dispcnt = hardware.lcd.dispcnt or 0xE000
        val drawn = ByteArray(ItemIcon.MAX_ICON_COUNT * 2) { 0x5A }
        val gfxIds = ByteArray(ItemIcon.MAX_ICON_GFX_COUNT) { 0x5A }
        val mine = Popup.PopupProc()
        Popup.setupSimple(mine, GOT_ITEM, 0x60, 0)
        Popup.init(mine)
        Popup.prepareGfx(
            mine, RomView.ofRom(romBytes), messages, text, hardware, drawn, gfxIds, popupItem, 0, 0,
        ) { _, _ -> }

        val lcd = gbafe.render.LcdControl().apply { readFrom(afterIw, LCD_BUFFER) }
        assertEquals(lcd.dispcnt, hardware.lcd.dispcnt, "dispcnt")
        assertEquals(lcd.bldcnt, hardware.lcd.bldcnt, "bldcnt")
        assertEquals(lcd.blendCoeffA, hardware.lcd.blendCoeffA, "blendCoeffA")
        assertEquals(lcd.blendCoeffB, hardware.lcd.blendCoeffB, "blendCoeffB")
        assertEquals(lcd.blendY, hardware.lcd.blendY, "blendY")
        assertEquals(
            afterEw.copyOfRange(ICON_TABLE, ICON_TABLE + drawn.size).toList(), drawn.toList(),
            "DrawnIconLookupTable",
        )
        assertEquals(
            afterEw.copyOfRange(ICON_GFX_TABLE, ICON_GFX_TABLE + gfxIds.size).toList(),
            gfxIds.toList(), "IconGFXIDLookupTable",
        )
        assertEquals(original.u16(0x46), mine.xGfxSize, "xGfxSize")
        val font = ByteArray(Font.SIZE)
        text.defaultFont.serialize(font)
        val fontAt = sym["gDefaultFont"].address - GbaMem.EWRAM
        assertEquals(afterEw.copyOfRange(fontAt, fontAt + Font.SIZE).toList(), font.toList(), "font")
        // The decompressed frame image is the non-zero prefix of the (initially empty) VRAM.
        val frameBytes = (hardware.vram.indexOfLast { it.toInt() != 0 } + 0x20) and 0x1F.inv()
        assertTrue(frameBytes > 0x100, "frame image not loaded")
        assertEquals(
            afterVram.copyOfRange(0, frameBytes).toList(),
            hardware.vram.copyOfRange(0, frameBytes).toList(), "frame tiles",
        )
        val pal = sym["gPaletteBuffer"].address - GbaMem.EWRAM + 32
        for (i in 0 until 16) {
            assertEquals(
                ewramU16(afterEw, pal + i * 2), hardware.palette[16 + i].toInt() and 0xFFFF,
                "frame palette $i",
            )
        }
    }

    @Test
    @VerifiesPort("PopupProc_MaybeSetVolume", "PopupProc_MaybeResetVolume")
    fun `volume callbacks start the traced BGM volume change children`() {
        assumeTrue(ref.available, "reference capture unavailable")
        val run = ref.run("ram_trace_item_popup")
        // sMusicProc2Script (0x08587970): vc_init_volume +0x64, vc_end_volume +0x66,
        // vc_time_end +0x6A; the parent (+0x14) is the popup proc (Proc_StartBlocking).
        fun child(step: Int): Pair<ProcArrayDump.Proc, ProcArrayDump.Proc>? {
            val d = ProcArrayDump.ofEwram(dump(run, step))
            val c = d.find(0x08587970) ?: return null
            return d.find(Popup.PROC_SCRIPT)!! to c
        }
        val set = child(18599)!!
        val reset = (18600..18750).firstNotNullOf { s ->
            child(s)?.takeIf { it.second.u16(0x64) == 0x80 }
        }
        for ((popup, vc, call) in listOf(
            Triple(set.first, set.second, Popup::maybeSetVolume),
            Triple(reset.first, reset.second, Popup::maybeResetVolume),
        )) {
            assertEquals(popup.address, vc.u32(0x14), "child parent")
            val mine = Popup.PopupProc()
            mine.soundId = popup.u16(0x48)
            val calls = ArrayList<List<Int>>()
            call(mine) { v, e, t -> calls.add(listOf(v, e, t)) }
            assertEquals(listOf(listOf(vc.u16(0x64), vc.u16(0x66), vc.u16(0x6A))), calls)
        }
    }

    private fun ewramU16(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun region(run: ReferenceData.Run, step: Int, name: String): ByteArray =
        try {
            run.region("r$step", name)
        } catch (e: java.io.IOException) {
            run.region("f$step", name)
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
        const val LCD_BUFFER = 0x3080

        /** `DrawnIconLookupTable` (0x02026A90) and `IconGFXIDLookupTable` in EWRAM. */
        const val ICON_TABLE = 0x26A90
        const val ICON_GFX_TABLE = 0x26C50
        const val BG0_TM = 0x22CA8
        const val BG1_TM = 0x234A8
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

    /** English host with a plain VRAM array; palette changes are ignored. */
    private class VramHost : TextHost {
        val vram = ByteArray(0x18000)

        override fun language() = 1

        override fun applyTextPalette(talk: Boolean, palid: Int) = Unit

        override fun readVram32(addr: Int): Int {
            var v = 0
            for (k in 0..3) v = v or ((vram[addr - 0x06000000 + k].toInt() and 0xFF) shl (8 * k))
            return v
        }

        override fun writeVram32(addr: Int, value: Int) {
            for (k in 0..3) vram[addr - 0x06000000 + k] = (value shr (8 * k)).toByte()
        }

        override fun clearVram(dest: Int, byteCount: Int) {
            vram.fill(0, dest - 0x06000000, dest - 0x06000000 + byteCount)
        }
    }
}
