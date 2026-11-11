package fe8.testing

import fe8.game.text.Fe8Text
import gba.core.VerifiesPort
import gbafe.text.Font
import gbafe.text.Text
import gbafe.text.TextHost
import gbafe.text.TextSystem
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential test of the drawing half of [TextSystem] (fontgrp.c) against
 * `ram_trace_prologue_talk`.
 *
 * Glyphs: at every full dump the talk strip of `sTalkText[i]` (VRAM `0x06001000 + 1920 * i`) holds
 * the glyphs drawn so far and `sTalkText[i].x` is the pen. The characters are not taken from the
 * message (replaying [MsgStrings] missed every first line; the talk state is kept elsewhere): a depth-first
 * search over glyph-bearing bytes draws into a cleared strip, pruned by the dump's finished tiles,
 * until the pen reaches the traced x and the whole strip equals the dump; the found sequence is
 * then redrawn through `drawString`. This is weaker than a message replay (the sequence is chosen
 * to fit), but a wrong glyph blit, destination or colour table leaves no sequence that matches,
 * and the breaks of each tagged function fail it. All other VRAM stays at its sentinel.
 *
 * Tilemap: `PutText` is compared with `gBG0TilemapBuffer` of the same run.
 *
 * Message bytes and VRAM contents are never printed or stored.
 */
class TextDrawRomTest {
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )
    private val romFile = File(assets, "baserom.gba")
    private val runDir = File(assets, "reference/out/ram_trace_prologue_talk")

    @Test
    @VerifiesPort(
        "Text_DrawCharacter", "Text_DrawCharacterAscii", "DrawTextGlyph", "GetTextDrawDest",
        "Text_DrawString", "Text_DrawStringASCII",
    )
    fun `replayed talk lines reproduce the traced glyph tiles`() {
        val (rom, ts, host) = setup()
        var matched = 0
        var checked = 0
        // Search.run() is fully determined by (strip i, pen x, traced strip bytes, ROM): it refills
        // VRAM with the sentinel, builds a fresh Font and Text, and re-runs initTextFont /
        // initTalkTextFont / initText, so nothing left in ts or host by an earlier search reaches
        // it (the host language and the config are constant). Identical keys therefore repeat the
        // same outcome, and each occurrence is still counted below; only the repeat DFS is skipped.
        val outcomes = HashMap<String, Boolean>()
        for (f in dumpFrames()) {
            val iw = File(runDir, "f$f.iwram.bin").readBytes()
            val vram = File(runDir, "f$f.vram.bin").readBytes()
            for (i in 0..1) {
                val x = iw[TALK_TEXT + i * Text.SIZE + 2].toInt() and 0xFF
                if (x == 0) continue
                checked++
                val o = VRAM_TEXT + STRIP_BYTES * i
                val strip = vram.copyOfRange(o, o + STRIP_BYTES)
                val key = "$i:$x:" + String(strip, Charsets.ISO_8859_1) // exact content key
                val ok = outcomes.getOrPut(key) { Search(ts, host, rom, i, x, strip).run() }
                if (ok) matched++ else println("MISS f$f i=$i x=$x")
            }
        }
        assertEquals(checked, matched, "every traced pen position replays to the traced tiles")
        assertTrue(matched >= 40, "matched=$matched")
    }

    @Test
    @VerifiesPort("GetColorLut")
    fun `getColorLut returns the pointers of the built ROM's colour tables`() {
        val (_, ts, _) = setup()
        // Symbol addresses of gUnknown_0858829C .. gUnknown_08589A9C in the built ROM.
        val want = intArrayOf(
            0x0858829C, 0x0858849C, 0x0858869C, 0x0858889C, 0x08588A9C, 0x08588C9C, 0x08588E9C,
            0x0858909C, 0x0858929C, 0x0858949C, 0x0858969C, 0x0858989C, 0x08589A9C,
        )
        for (c in want.indices) assertEquals(want[c], ts.getColorLut(c), "color $c")
    }

    @Test
    @VerifiesPort("PutText")
    fun `putText reproduces the talk lines in gBG0TilemapBuffer`() {
        val (_, ts, _) = setup()
        val talk = Font()
        ts.initTextFont(talk, 0x06001000, 0x80, 2)
        ts.initTalkTextFont()
        val texts = Array(2) { Text() }
        for (t in texts) ts.initText(t, 30)
        val ew = File(runDir, "f10050.ewram.bin").readBytes()
        val tm = ShortArray(0x400) { 0x5555 }
        // TILEMAP_INDEX(5, 4 + 2 * line): the talk text position, see scene.c.
        val offsets = intArrayOf(4 * 32 + 5, 6 * 32 + 5)
        for (l in 0..1) ts.putText(texts[l], tm, offsets[l])
        val written = HashSet<Int>()
        for (l in 0..1) {
            for (k in 0 until 30) {
                for (row in intArrayOf(0, 0x20)) {
                    val idx = offsets[l] + row + k
                    written.add(idx)
                    val w = BG0_TM + idx * 2
                    assertEquals(
                        (ew[w].toInt() and 0xFF) or ((ew[w + 1].toInt() and 0xFF) shl 8),
                        tm[idx].toInt() and 0xFFFF,
                        "line $l col $k row $row",
                    )
                }
            }
        }
        for (i in tm.indices) if (i !in written) assertEquals(0x5555, tm[i].toInt() and 0xFFFF)
    }

    // Not @VerifiesPort: the db_id toggle is never exercised by the talk run (db_enabled = 0).
    @Test
    fun `putText toggles the double buffer id only when enabled`() {
        val (_, ts, _) = setup()
        val talk = Font()
        ts.initTextFont(talk, 0x06001000, 0x80, 2)
        val t = Text()
        t.tileWidth = 2
        val tm = ShortArray(0x80)
        ts.putText(t, tm, 0)
        assertEquals(0, t.dbId)
        t.dbEnabled = true
        ts.putText(t, tm, 0)
        assertEquals(1, t.dbId)
        ts.putText(t, tm, 0)
        assertEquals(0, t.dbId)
        t.dbId = 1
        ts.putText(t, tm, 0)
        assertEquals(talk.tileref + 2 * 2, tm[0].toInt() and 0xFFFF)
    }

    private fun dumpFrames(): List<String> =
        runDir.listFiles { _, n -> n.matches(Regex("f\\d+\\.vram\\.bin")) }!!
            .map { it.name.substringBefore('.').drop(1) }.sorted()

    private fun setup(): Triple<ByteArray, TextSystem, Host> {
        assumeTrue(romFile.isFile && runDir.isDirectory, "ROM or reference run not present")
        val rom = romFile.readBytes()
        val host = Host()
        return Triple(rom, TextSystem(rom, host, Fe8Text.CONFIG), host)
    }

    /**
     * Finds the character sequence that draws the traced strip: a depth-first search over the
     * glyph-bearing bytes, pruned by the dump (tiles left of the pen are final), then a full
     * comparison once the pen reaches the traced x. Each step goes through
     * [TextSystem.drawCharacter] on a strip that [TextSystem.initText] cleared, with the rest of
     * VRAM at a sentinel.
     */
    private class Search(
        val ts: TextSystem,
        val host: Host,
        val rom: ByteArray,
        val strip: Int,
        val x: Int,
        val want: ByteArray,
    ) {
        private val text = Text()
        private val one = ByteArray(2)
        private val path = ByteArray(64)
        private var len = 0
        private val base = VRAM_TEXT + strip * STRIP_BYTES

        fun run(): Boolean {
            host.vram.fill(0xA5.toByte())
            val talk = Font()
            ts.initTextFont(talk, 0x06001000, 0x80, 2)
            ts.initTalkTextFont()
            ts.initText(text, 30)
            if (strip == 1) ts.initText(text, 30) // chrPosition 30; strip 0 is cleared as well
            text.colorId = 1
            val ok = dfs()
            if (ok) {
                // The same characters through Text_DrawString on a freshly cleared strip.
                val firstTiles = host.vram.copyOfRange(base, base + STRIP_BYTES)
                assertTrue(firstTiles.contentEquals(want))
                host.vram.fill(0xA5.toByte())
                ts.initTextFont(talk, 0x06001000, 0x80, 2)
                ts.initTalkTextFont()
                ts.initText(text, 30)
                if (strip == 1) ts.initText(text, 30)
                text.colorId = 1
                path[len] = 0
                ts.drawString(text, path, 0)
                assertEquals(x, text.x)
                assertTrue(host.vram.copyOfRange(base, base + STRIP_BYTES).contentEquals(want))
                // Everything outside the two cleared strips is still the sentinel.
                val sentinel = 0xA5.toByte()
                assertTrue((0 until VRAM_TEXT).all { host.vram[it] == sentinel }, "below")
                assertTrue(
                    (VRAM_TEXT + 2 * STRIP_BYTES until host.vram.size)
                        .all { host.vram[it] == sentinel },
                    "above",
                )
            }
            return ok
        }

        private fun dfs(): Boolean {
            if (text.x == x) {
                return host.vram.copyOfRange(base, base + STRIP_BYTES).contentEquals(want)
            }
            val saved = host.vram.copyOfRange(base, base + STRIP_BYTES)
            val x0 = text.x
            for (ch in 0x20..0xFF) {
                val g = u32(rom, Fe8Text.GLYPHS_TALK - ROM_BASE + ch * 4)
                if (g == 0 || x0 + (rom[g - ROM_BASE + 5].toInt() and 0xFF) > x) continue
                one[0] = ch.toByte()
                ts.drawCharacter(text, one, 0)
                val final = text.x / 8 * 64
                var same = true
                for (k in 0 until final) {
                    if (host.vram[base + k] != want[k]) {
                        same = false
                        break
                    }
                }
                path[len++] = ch.toByte()
                if (same && dfs()) return true
                len--
                saved.copyInto(host.vram, base)
                text.x = x0
            }
            return false
        }
    }

    /** Host with a plain VRAM array; palette changes are ignored. */
    private class Host : TextHost {
        val vram = ByteArray(0x18000)

        override fun language() = 1

        override fun applyTextPalette(talk: Boolean, palid: Int) {}

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

    private companion object {
        fun u32(b: ByteArray, o: Int) =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

        const val ROM_BASE = 0x08000000
        const val VRAM_BASE = 0x06000000
        const val VRAM_TEXT = 0x1000
        const val STRIP_BYTES = 30 * 2 * 0x20
        const val TALK_TEXT = 0xD0
        const val BG0_TM = 0x22CA8
    }
}
