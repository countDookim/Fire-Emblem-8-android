package fe8.testing

import fe8.game.text.Fe8Text
import gba.core.VerifiesPort
import gbafe.text.Font
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import gbafe.text.Text
import gbafe.text.TextHost
import gbafe.text.TextSystem
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential test of [TextSystem] (fontgrp.c) against `ram_trace_prologue_talk`.
 *
 * Fonts: between frames 9850 and 9870 `InitTalk` runs `InitTextFont(&sTalkFont, ...)`,
 * `SetInitTalkTextFont` and two `InitText` calls. The structs are seeded with sentinels and the
 * result is compared with `sTalkFont` / `sTalkText` (IWRAM 0x030000F0 / 0x030000D0) and
 * `gActiveFont` at frame 9870; `gDefaultFont` (EWRAM) at frame 9850 is the product of
 * `InitTextFont(NULL, ...)`.
 *
 * Widths: the traced messages come from [MsgStrings]; the final pen position `x` of
 * `sTalkText[i]` (just before it drops to 0 for the next line) is the width of a line.
 *
 * Message bytes are never printed or stored.
 */
class TextSystemRomTest {
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )
    private val romFile = File(assets, "baserom.gba")
    private val runDir = File(assets, "reference/out/ram_trace_prologue_talk")

    @Test
    @VerifiesPort("InitTextFont", "SetTextFont", "SetTextFontGlyphs")
    fun `initTextFont reproduces gDefaultFont and sTalkFont and activates the font`() {
        val (_, ts, host) = setup()
        val before = frame("f09850")
        val after = frame("f09870")
        // gDefaultFont: InitTextFont(NULL, VRAM + 0x1000, 0x80, 0) from ResetText.
        sentinel(ts.defaultFont)
        ts.initTextFont(null, 0x06001000, 0x80, 0)
        assertEquals(
            slice(before.ew, DEFAULT_FONT, Font.SIZE).toList(), ser(ts.defaultFont).toList(),
        )
        assertSame(ts.defaultFont, ts.activeFont)
        assertEquals(Fe8Text.G_DEFAULT_FONT, u32(before.ew, ACTIVE_FONT))
        // sTalkFont: InitTextFont(&sTalkFont, VRAM + 0x1000, 0x80, 2), then the talk glyphs.
        val talk = Font()
        sentinel(talk)
        ts.initTextFont(talk, 0x06001000, 0x80, 2)
        assertSame(talk, ts.activeFont)
        ts.initTalkTextFont()
        // InitTalk then gives two 30-tile lines (the chr_counter field).
        val line = Text()
        ts.initText(line, 30)
        ts.initText(line, 30)
        assertEquals(slice(after.iw, TALK_FONT, Font.SIZE).toList(), ser(talk).toList())
        assertEquals(TALK_FONT_ADDR, u32(after.ew, ACTIVE_FONT))
        // SetTextFontGlyphs flips the table back and forth on the active font.
        ts.setTextFontGlyphs(TextSystem.GLYPHS_SYSTEM)
        assertEquals(u32(before.ew, DEFAULT_FONT + 4), talk.glyphs)
        ts.setTextFontGlyphs(TextSystem.GLYPHS_TALK)
        assertEquals(u32(after.iw, TALK_FONT + 4), talk.glyphs)
        ts.setTextFont(null)
        assertSame(ts.defaultFont, ts.activeFont)
        ts.setTextFont(talk)
        assertSame(talk, ts.activeFont)
        assertEquals(listOf(false to 0, false to 2, true to 2), host.palettes)
    }

    @Test
    @VerifiesPort("InitTalkTextFont")
    fun `initTalkTextFont writes only the draw routine and glyphs of the active font`() {
        val (_, ts, _) = setup()
        val after = frame("f09870")
        val talk = Font()
        sentinel(talk)
        ts.setTextFont(talk)
        ts.initTalkTextFont()
        val got = ser(talk)
        val want = slice(after.iw, TALK_FONT, Font.SIZE)
        assertEquals(want.copyOfRange(4, 12).toList(), got.copyOfRange(4, 12).toList())
        // The other fields keep their sentinel value.
        val seed = ser(Font().also { sentinel(it) })
        assertEquals(seed.copyOfRange(0, 4).toList(), got.copyOfRange(0, 4).toList())
        assertEquals(seed.copyOfRange(12, 24).toList(), got.copyOfRange(12, 24).toList())
    }

    @Test
    @VerifiesPort("InitText", "ClearText")
    fun `initText reproduces the sTalkText structs and clears their VRAM strips`() {
        val (_, ts, host) = setup()
        val after = frame("f09870")
        val talk = Font()
        ts.initTextFont(talk, 0x06001000, 0x80, 2)
        ts.initTalkTextFont()
        val texts = Array(2) { Text() }
        for (t in texts) {
            t.chrPosition = 0x5555
            t.x = 0x66
            t.colorId = 0x77
            t.tileWidth = 0x44
            t.dbEnabled = true
            t.dbId = 9
            t.isPrinting = true
            ts.initText(t, 30)
            t.colorId = 1 // Text_SetColor(sTalkText + i, 1) in InitTalk
        }
        for (i in 0..1) {
            val b = ByteArray(Text.SIZE)
            texts[i].serialize(b)
            assertEquals(
                slice(after.iw, TALK_TEXT + i * Text.SIZE, Text.SIZE).toList(), b.toList(),
            )
        }
        assertEquals(u32(after.iw, TALK_FONT + 0x10) shr 16, talk.chrCounter)
        // ClearText zeroed the two 30-tile strips, one after the other; VRAM agrees.
        assertEquals(listOf(0x06001000 to 1920, 0x06001000 + 1920 to 1920), host.cleared)
        for ((dest, n) in host.cleared) {
            val o = dest - 0x06000000
            assertTrue(after.vram.copyOfRange(o, o + n).all { it.toInt() == 0 }, "strip $o")
        }
    }

    @Test
    @VerifiesPort(
        "GetStringTextLen", "GetCharTextLen", "GetStringTextLenASCII", "GetCharTextLenASCII",
    )
    fun `widths of traced messages match the glyph widths and the traced pen positions`() {
        val (rom, ts, _) = setup()
        val talk = Font()
        ts.initTextFont(talk, 0x06001000, 0x80, 2)
        ts.initTalkTextFont()
        assertEquals(1, talk.lang)
        val width = IntArray(256) { glyphWidth(rom, Fe8Text.GLYPHS_TALK, it) } // -1 = NULL glyph
        val peaks = penPeaks()
        val ms = makeStrings(rom)
        val out = IntArray(1)
        var lines = 0
        var matched = 0
        for (id in tracedIds()) {
            val s = ms.getStringFromIndex(id)
            var start = 0
            while (true) {
                // Keep only bytes with a glyph; the talk code consumes the others itself.
                var end = start
                var n = 0
                while (s[end].toInt() != 0 && s[end].toInt() != 1) {
                    if (width[s[end].toInt() and 0xFF] >= 0) n++
                    end++
                }
                val line = ByteArray(n + 1)
                var k = 0
                var expect = 0
                for (i in start until end) {
                    val w = width[s[i].toInt() and 0xFF]
                    if (w >= 0) {
                        line[k++] = s[i]
                        expect += w
                    }
                }
                val got = ts.getStringTextLen(line)
                assertEquals(expect, got, "message $id line at $start")
                var p = 0
                var sum = 0
                while (line[p].toInt() != 0) {
                    p = ts.getCharTextLen(line, p, out)
                    sum += out[0]
                }
                assertEquals(expect, sum, "message $id line at $start per char")
                lines++
                if (got in peaks) matched++
                if (s[end].toInt() == 0) break
                start = end + 1
            }
        }
        assertTrue(lines > 5 && matched >= 5, "lines=$lines matched=$matched peaks=$peaks")
        // A string stops at the newline; a NULL glyph falls back to '?'.
        assertEquals(width[0x41], ts.getStringTextLen(byteArrayOf(0x41, 1, 0x41, 0)))
        val nullChar = (0..255).first { width[it] < 0 }
        ts.getCharTextLen(byteArrayOf(nullChar.toByte(), 0), 0, out)
        assertEquals(width['?'.code], out[0])
    }

    /** Final pen positions: `sTalkText[0..1].x` right before it drops to zero. */
    private fun penPeaks(): Set<Int> {
        val peaks = HashSet<Int>()
        val prev = intArrayOf(0, 0)
        for (f in runDir.listFiles { _, n -> n.endsWith(".iwram.bin") }!!.sortedBy { it.name }) {
            val d = f.readBytes()
            for (i in 0..1) {
                val x = d[TALK_TEXT + i * Text.SIZE + 2].toInt() and 0xFF
                if (x == 0 && prev[i] > 0) peaks.add(prev[i])
                prev[i] = x
            }
        }
        return peaks
    }

    private fun tracedIds(): List<Int> {
        val ids = ArrayList<Int>()
        for (f in runDir.listFiles { _, n -> n.endsWith(".ewram.bin") }!!.sortedBy { it.name }) {
            val id = u32(f.readBytes(), 0x2B6AC)
            if (id != 0 && (ids.isEmpty() || ids.last() != id)) ids.add(id)
        }
        return ids
    }

    private fun glyphWidth(rom: ByteArray, table: Int, ch: Int): Int {
        val p = u32(rom, table - ROM_BASE + ch * 4)
        return if (p == 0) -1 else rom[p - ROM_BASE + 5].toInt() and 0xFF
    }

    private fun makeStrings(rom: ByteArray): MsgStrings {
        val root = u32(rom, 0x0815D488 - ROM_BASE) - ROM_BASE
        val huff = MsgHuffman(rom, 0x0815A72C - ROM_BASE, root)
        return MsgStrings(rom, huff, 0x0815D48C - ROM_BASE)
    }

    private fun setup(): Triple<ByteArray, TextSystem, FakeHost> {
        assumeTrue(romFile.isFile && runDir.isDirectory, "ROM or reference run not present")
        val rom = romFile.readBytes()
        val host = FakeHost()
        return Triple(rom, TextSystem(rom, host, Fe8Text.CONFIG), host)
    }

    private fun sentinel(f: Font) {
        f.vramDest = 0x55555555
        f.glyphs = 0x55555555
        f.drawGlyph = 0x55555555
        f.getDrawDest = 0x55555555
        f.tileref = 0x5555
        f.chrCounter = 0x5555
        f.palid = 0x5555
        f.lang = 0x55
    }

    private fun ser(f: Font) = ByteArray(Font.SIZE).also { f.serialize(it) }

    private fun slice(b: ByteArray, o: Int, n: Int) = b.copyOfRange(o, o + n)

    private fun u32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private class Frame(val ew: ByteArray, val iw: ByteArray, val vram: ByteArray)

    private fun frame(name: String) = Frame(
        File(runDir, "$name.ewram.bin").readBytes(),
        File(runDir, "$name.iwram.bin").readBytes(),
        File(runDir, "$name.vram.bin").readBytes(),
    )

    /** Host for the real game: English, `GetTextDrawDest` arithmetic, records VRAM clears. */
    private class FakeHost : TextHost {
        val palettes = ArrayList<Pair<Boolean, Int>>()
        val cleared = ArrayList<Pair<Int, Int>>()

        override fun language() = 1

        override fun applyTextPalette(talk: Boolean, palid: Int) {
            palettes.add(talk to palid)
        }

        override fun getDrawDest(font: Font, text: Text) =
            font.vramDest + (text.dbId * text.tileWidth + text.chrPosition) * 2 * 0x20

        override fun clearVram(dest: Int, byteCount: Int) {
            cleared.add(dest to byteCount)
        }
    }

    private companion object {
        const val ROM_BASE = 0x08000000
        const val DEFAULT_FONT = 0x28E58
        const val ACTIVE_FONT = 0x28E70
        const val TALK_FONT = 0xF0
        const val TALK_FONT_ADDR = 0x030000F0
        const val TALK_TEXT = 0xD0
    }
}
