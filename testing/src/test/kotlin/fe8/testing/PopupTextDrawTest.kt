package fe8.testing

import fe8.game.RomView
import fe8.game.text.Fe8Text
import fe8.game.ui.Popup
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import gbafe.text.Text
import gbafe.text.TextHost
import gbafe.text.TextSystem
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Popup text drawing compared with the full dump f18630 of `ram_trace_item_popup`. */
class PopupTextDrawTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    @VerifiesPort("GeneratePopupText")
    fun `generatePopupText reproduces the popup glyph tiles and BG0 text cells`() {
        assumeTrue(ref.available, "reference capture unavailable")
        val romFile = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
            "baserom.gba",
        )
        assumeTrue(romFile.isFile, "ROM not present")
        val run = ref.run("ram_trace_item_popup")
        val vram = run.region("f18630", "vram")
        val ewram = run.region("f18630", "ewram")
        val iwram = run.region("f18630", "iwram")
        val popupItem = (iwram[GPOPUPITEM].toInt() and 0xFF) or
            ((iwram[GPOPUPITEM + 1].toInt() and 0xFF) shl 8)

        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)
        val root = u32(romBytes, 0x0815D488 - ROM_BASE) - ROM_BASE
        val messages = MsgStrings(
            romBytes, MsgHuffman(romBytes, 0x0815A72C - ROM_BASE, root), 0x0815D48C - ROM_BASE,
        )
        val host = VramHost()
        val ts = TextSystem(romBytes, host, Fe8Text.CONFIG)
        // PopupProc_PrepareGfx: InitTextFont(0, BG_SCREEN_ADDR(4), 0x100, 0).
        ts.initTextFont(null, VRAM_BASE + 0x2000, 0x100, 0)

        val proc = Popup.PopupProc()
        Popup.setupSimple(proc, GOT_ITEM, 0x60, 0)
        Popup.init(proc)
        val len = Popup.parsePopupInstAndGetLen(
            proc, rom, messages, ts, popupItem, 0, 0,
        ) { _, _ -> }
        val tileLen = Popup.gfxDrawLayout(proc, len)
        val iconPos = (tileLen * 8 - proc.xGfxSize) shr 1

        val th = Text()
        ts.initText(th, tileLen)
        th.colorId = proc.textColorId
        th.x = iconPos
        Popup.generatePopupText(proc.pDefinition, th, rom, messages, ts, popupItem, 0, 0)
        val tm = ShortArray(0x400)
        ts.putText(th, tm, (proc.yTileReal + 1) * 32 + proc.xTileReal + 1)

        val bytes = tileLen * 2 * 0x20
        assertTrue(tileLen > 0)
        assertEquals(
            vram.copyOfRange(0x2000, 0x2000 + bytes).toList(),
            host.vram.copyOfRange(0x2000, 0x2000 + bytes).toList(),
            "glyph tiles",
        )
        for (row in 0..1) {
            for (i in 0 until tileLen) {
                val idx = (proc.yTileReal + 1 + row) * 32 + proc.xTileReal + 1 + i
                val w = BG0_TM + idx * 2
                assertEquals(
                    (ewram[w].toInt() and 0xFF) or ((ewram[w + 1].toInt() and 0xFF) shl 8),
                    tm[idx].toInt() and 0xFFFF,
                    "tilemap row $row col $i",
                )
            }
        }
    }

    private companion object {
        const val GOT_ITEM = 0x08592230
        const val ROM_BASE = 0x08000000
        const val VRAM_BASE = 0x06000000
        const val BG0_TM = 0x22CA8

        /** `gPopupItem` (0x030005F4) in the IWRAM dump. */
        const val GPOPUPITEM = 0x5F4

        fun u32(b: ByteArray, o: Int) =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    }

    /** English host with a plain VRAM array; palette changes are ignored. */
    private class VramHost : TextHost {
        val vram = ByteArray(0x18000)

        override fun language() = 1

        override fun applyTextPalette(talk: Boolean, palid: Int) = Unit

        override fun readVram32(addr: Int): Int {
            var v = 0
            for (k in 0..3) v = v or ((vram[addr - VRAM_BASE + k].toInt() and 0xFF) shl (8 * k))
            return v
        }

        override fun writeVram32(addr: Int, value: Int) {
            for (k in 0..3) vram[addr - VRAM_BASE + k] = (value shr (8 * k)).toByte()
        }

        override fun clearVram(dest: Int, byteCount: Int) {
            vram.fill(0, dest - VRAM_BASE, dest - VRAM_BASE + byteCount)
        }
    }
}
