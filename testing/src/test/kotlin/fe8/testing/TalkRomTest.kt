package fe8.testing

import fe8.game.scene.Talk
import fe8.game.scene.TalkHost
import fe8.game.scene.TalkState
import fe8.game.text.Fe8Text
import gba.core.VerifiesPort
import gba.data.Lz77
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import gbafe.text.Text
import gbafe.text.TextHost
import gbafe.text.TextSystem
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential test of the talk set-up half of scene.c ([Talk], [TalkState]) against
 * `ram_trace_prologue_talk` (per-frame IWRAM/EWRAM, full dumps every 10 frames).
 *
 * `sTalkStateCore` is IWRAM 0x03000048, `sTalkText` 0x030000D0, `sTalkFont` 0x030000F0.
 *
 * Seeding: every test starts from sentinel values (0xA5 bytes) for what the called function does
 * not read or write, and checks that they survive. Inputs that the call reads come from the
 * traced frame; outputs are compared with the traced frame after the call.
 *
 * Message bytes and VRAM contents are never printed or stored.
 */
class TalkRomTest {
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )
    private val romFile = File(assets, "baserom.gba")
    private val runDir = File(assets, "reference/out/ram_trace_prologue_talk")

    @Test
    @VerifiesPort("InitTalk", "SetInitTalkTextFont", "ClearTalkFaceRefs")
    fun `initTalk reproduces sTalkFont, sTalkText, lines, face refs and the bubble gfx`() {
        val rom = needRom()
        val h = Host(rom, runDir)
        val ts = TextSystem(rom, h, Fe8Text.CONFIG)
        val talk = Talk(ts, ProcEngine(), h)
        sentinelise(talk)
        h.vram.fill(SENT)
        h.io = File(runDir, "f09870.io.bin").readBytes()

        talk.initTalk(0x80, 2, true)

        // InitTalk runs in the frame the talk proc appears (f9865), together with StartTalkExt.
        val iw = iwram(9865)
        val got = ByteArray(TalkState.SIZE)
        talk.state.encode(got)
        val want = iw.copyOfRange(STATE, STATE + TalkState.SIZE)
        assertEquals(want[LINES], got[LINES], "lines")
        for (i in 0x18 until 0x38) assertEquals(want[i], got[i], "faces byte $i")
        // Nothing else of the state is InitTalk's (unk38 is a callback, encoded separately).
        for (i in 0 until TalkState.SIZE) {
            if (i == LINES || i in 0x18 until 0x38 || i in 0x38 until 0x3C) continue
            assertEquals(SENT, got[i], "state byte $i untouched")
        }
        val font = ByteArray(0x18)
        talk.font.serialize(font)
        assertArrayEquals(iw.copyOfRange(FONT, FONT + 0x18), font, "sTalkFont")
        val t = ByteArray(Text.SIZE)
        for (i in 0..1) {
            talk.texts[i].serialize(t)
            assertArrayEquals(iw.copyOfRange(TEXTS + 8 * i, TEXTS + 8 * i + 8), t, "text $i")
        }
        // The third text is not initialised for lines = 2.
        talk.texts[2].serialize(t)
        assertEquals(0x77, t[3].toInt(), "text 2 untouched")

        // Gfx of the frame after: strips cleared, bubble tiles at BG1 char base + 0x200.
        val vram = File(runDir, "f09870.vram.bin").readBytes()
        val strips = 2 * 30 * 2 * 0x20
        assertArrayEquals(
            vram.copyOfRange(0x1000, 0x1000 + strips), h.vram.copyOfRange(0x1000, 0x1000 + strips),
            "talk strips",
        )
        val n = Lz77.headerSize(rom, Talk.IMG_TALK_BUBBLE - ROM_BASE)
        assertArrayEquals(
            vram.copyOfRange(0x200, 0x200 + n), h.vram.copyOfRange(0x200, 0x200 + n),
            "bubble tiles",
        )
        val pal = File(runDir, "f09870.pal.bin").readBytes()
        assertArrayEquals(pal.copyOfRange(96, 128), h.pal.copyOfRange(96, 128), "palette 3")
    }

    @Test
    @VerifiesPort("Talk_OnInit")
    fun `talkOnInit homes the backgrounds and starts the skip listener once`() {
        val rom = needRom()
        val h = Host(rom, runDir)
        val talk = Talk(TextSystem(rom, h, Fe8Text.CONFIG), ProcEngine(), h)
        sentinelise(talk)
        talk.state.config = 0
        talk.talkOnInit()

        // The listener proc starts exactly once in the whole run, at f9867.
        val skip = 0x08591340
        var starts = 0
        var first = -1
        for (f in 9851..10574) {
            val now = hasProc(f, skip)
            if (now && !hasProc(f - 1, skip)) {
                starts++
                if (first < 0) first = f
            }
        }
        assertEquals(1, starts)
        assertEquals(9867, first)
        assertEquals(1, h.skipStarts)
        // BG0/BG1 offsets are 0 after it (BG0HOFS..BG1VOFS at IO 0x10..0x17).
        val io = File(runDir, "f09870.io.bin").readBytes()
        for (i in 0x10 until 0x18) assertEquals(0, io[i].toInt())
        assertEquals(listOf("obj", "bg0:0,0", "bg1:0,0", "skip"), h.calls)
    }

    @Test
    @VerifiesPort("TalkPrepNextChar", "TalkHasCorrectBubble")
    fun `talkPrepNextChar opens the bubble, starts the shift or puts the line as traced`() {
        val rom = needRom()
        val open = 0x08591540
        val shift = 0x085914C8
        var opens = 0
        var shifts = 0
        var puts = 0
        for (f in 9851..10574) {
            val iw = iwram(f)
            val prev = iwram(f - 1)
            val openStart = hasProc(f, open) && !hasProc(f - 1, open)
            val shiftStart = hasProc(f, shift) && !hasProc(f - 1, shift)
            val putStart =
                prev[STATE + PUT_LINES].toInt() == 0 && iw[STATE + PUT_LINES].toInt() == 1
            if (!openStart && !shiftStart && !putStart) continue

            val h = Host(rom, runDir)
            val engine = ProcEngine()
            val proc = newProc(engine)
            val talk = Talk(TextSystem(rom, h, Fe8Text.CONFIG), engine, h)
            talk.initTalk(0x80, 2, false)
            // Input state: the traced frame, except that the speaker (set by StartTalkOpen) and the
            // widths come from the frame before, and the outputs get sentinels.
            val seed = iw.copyOfRange(STATE, STATE + TalkState.SIZE)
            for (i in intArrayOf(SPEAKER, SPEAKING_WIDTH, ACTIVE_WIDTH)) seed[i] = prev[STATE + i]
            seed[PUT_LINES] = if (openStart || shiftStart) 0x5A else 0 // the put branch reads it
            seed[INSTANT] = 0x5B
            for (i in intArrayOf(0x08, 0x13, 0x14, 0x17, 0x82, 0x83)) seed[i] = SENT
            for (i in 0x18 until 0x38) seed[i] = SENT
            for (i in 0x3C until 0x80) seed[i] = SENT
            talk.state.decode(seed)
            h.lenReturn = 0

            val ret = talk.talkPrepNextChar(proc)
            val after = ByteArray(TalkState.SIZE)
            talk.state.encode(after)
            val label = "f$f"
            when {
                openStart -> {
                    opens++
                    assertTrue(ret, label)
                    assertEquals(
                        listOf(
                            "len:${talk.state.str}:false", "clear",
                            "open:${seed[ACTIVE].toInt() and 0xFF}",
                            "layer:${seed[ACTIVE].toInt() and 0xFF}:0",
                        ),
                        h.calls, label,
                    )
                    assertEquals(0x5A, after[PUT_LINES].toInt(), "$label putLines untouched")
                    assertEquals(0x5B, after[INSTANT].toInt(), "$label instantScroll untouched")
                }
                shiftStart -> {
                    shifts++
                    assertTrue(ret, label)
                    assertEquals(listOf("shift"), h.calls, label)
                    assertEquals(iw[STATE + INSTANT], after[INSTANT], "$label instantScroll")
                }
                else -> {
                    puts++
                    assertFalse(ret, label)
                    assertEquals(iw[STATE + PUT_LINES], after[PUT_LINES], "$label putLines")
                    assertEquals(0x5B, after[INSTANT].toInt(), "$label instantScroll untouched")
                    val face = seed[ACTIVE].toInt() and 0xFF
                    val mouth = if (seed[MOUTH].toInt() != 0) listOf("mouth:$face") else listOf()
                    assertEquals(listOf("sync:1") + mouth, h.calls, label)
                    comparePutTilemap(h, talk.state, f)
                }
            }
            // Everything but the three outputs is unchanged.
            for (i in 0 until TalkState.SIZE) {
                if (i == PUT_LINES || i == INSTANT || i == ACTIVE_WIDTH) continue
                if (i in 0x38 until 0x3C) continue
                assertEquals(seed[i], after[i], "$label state byte $i")
            }
        }
        assertTrue(opens >= 5, "opens=$opens")
        assertTrue(shifts >= 4, "shifts=$shifts")
        assertTrue(puts >= 5, "puts=$puts")
    }

    /**
     * What explains the first-line replay miss: the message starts with control codes (face open
     * and load, eye codes) that `TalkInterpret` consumes before the first glyph is drawn; the
     * traced string cursor at f9930 already sits after them. Replaying from the cursor through
     * `TalkPrepNextChar` + `Text_DrawCharacter` reproduces the traced line, while replaying from
     * byte 0 draws the control bytes as glyphs (missing glyph falls back to `?`) and does not.
     */
    // Not @VerifiesPort: drives the not yet ported interpreter loop from the traced cursor.
    @Test
    fun `replaying the first line from the traced string cursor reproduces it`() {
        val rom = needRom()
        val ew = ewram(9930)
        val id = u32(ew, 0x2B6AC)
        val root = u32(rom, 0x0815D488 - ROM_BASE) - ROM_BASE
        val huff = MsgHuffman(rom, 0x0815A72C - ROM_BASE, root)
        val ms = MsgStrings(rom, huff, 0x0815D48C - ROM_BASE)
        val buf = ms.getStringFromIndex(id)
        val end = buf.indexOf(0) + 1
        assertArrayEquals(ew.copyOfRange(BUF, BUF + end), buf.copyOf(end), "message bytes")

        val iw = iwram(9930)
        val cursor = u32(iw, STATE) - (0x02000000 + BUF)
        assertTrue(cursor > 0, "interpreter has consumed a control prefix")
        for (i in 0 until cursor) assertTrue((buf[i].toInt() and 0xFF) < 0x20 || i > 0, "prefix")
        assertTrue((buf[0].toInt() and 0xFF) < 0x20, "message starts with a control code")

        val want = File(runDir, "f09940.vram.bin").readBytes()
        val strip = 30 * 2 * 0x20
        val wantX = iwram(9940)[TEXTS + 2].toInt() and 0xFF

        // From the cursor.
        var h = Host(rom, runDir)
        var engine = ProcEngine()
        var proc = newProc(engine)
        var talk = Talk(TextSystem(rom, h, Fe8Text.CONFIG), engine, h)
        talk.initTalk(0x80, 2, false)
        talk.state.decode(iw, STATE)
        var off = cursor
        var drawn = 0
        while ((buf[off].toInt() and 0xFF) >= 0x20) {
            assertFalse(talk.talkPrepNextChar(proc))
            off = drawWith(talk, h, rom, buf, off)
            drawn++
        }
        assertTrue(drawn > 10)
        assertEquals(wantX, talk.texts[0].x, "pen")
        assertArrayEquals(
            want.copyOfRange(0x1000, 0x1000 + strip), h.vram.copyOfRange(0x1000, 0x1000 + strip),
        )

        // From byte 0 (control codes drawn as glyphs).
        h = Host(rom, runDir)
        engine = ProcEngine()
        proc = newProc(engine)
        talk = Talk(TextSystem(rom, h, Fe8Text.CONFIG), engine, h)
        talk.initTalk(0x80, 2, false)
        val raw = runCatching {
            var o = 0
            while ((buf[o].toInt() and 0xFF) != 0 && talk.texts[0].x < wantX) {
                o = drawWith(talk, h, rom, buf, o)
            }
            talk.texts[0].x
        }
        val same = raw.isSuccess && raw.getOrThrow() == wantX &&
            h.vram.copyOfRange(0x1000, 0x1000 + strip)
                .contentEquals(want.copyOfRange(0x1000, 0x1000 + strip))
        assertFalse(same, "replay from byte 0 must differ")
    }

    private fun newProc(engine: ProcEngine): Proc =
        engine.start(ProcScript("t", listOf(ProcScripts.yield(), ProcScripts.end())), 3)

    private fun drawWith(talk: Talk, h: Host, rom: ByteArray, buf: ByteArray, off: Int): Int {
        val ts = h.textSystemOf(talk)
        return ts.drawCharacter(talk.texts[talk.state.lineActive], buf, off)
    }

    private fun comparePutTilemap(h: Host, s: TalkState, f: Int) {
        val ew = ewram(f)
        val tm = h.tm
        val written = HashSet<Int>()
        val base = 0x20 * (s.yText + s.lineActive * 2) + s.xText
        for (row in intArrayOf(0, 0x20)) {
            for (k in 0 until 30) {
                val idx = base + row + k
                written.add(idx)
                val w = BG0_TM + idx * 2
                assertEquals(
                    (ew[w].toInt() and 0xFF) or ((ew[w + 1].toInt() and 0xFF) shl 8),
                    tm[idx].toInt() and 0xFFFF,
                    "f$f tilemap cell $idx",
                )
            }
        }
        for (i in tm.indices) if (i !in written) assertEquals(0x5555, tm[i].toInt() and 0xFFFF)
    }

    private fun sentinelise(talk: Talk) {
        val all = ByteArray(TalkState.SIZE) { SENT }
        talk.state.decode(all)
        talk.font.vramDest = 0x5A5A5A5A
        talk.font.glyphs = 0x5A5A5A5A
        talk.font.drawGlyph = 0x5A5A5A5A
        talk.font.getDrawDest = 0x5A5A5A5A
        talk.font.tileref = 0x5A5A
        talk.font.chrCounter = 0x5A5A
        talk.font.palid = 0x5A
        talk.font.lang = 0x5A
        for (t in talk.texts) {
            t.chrPosition = 0x7777
            t.x = 0x77
            t.colorId = 0x77
            t.tileWidth = 0x77
        }
    }

    private fun needRom(): ByteArray {
        assumeTrue(romFile.isFile && runDir.isDirectory, "ROM or reference run not present")
        return romFile.readBytes()
    }

    private fun dump(frame: Int, kind: String): ByteArray {
        val r = File(runDir, "r%05d.$kind.bin".format(frame))
        return (if (r.isFile) r else File(runDir, "f%05d.$kind.bin".format(frame))).readBytes()
    }

    private fun iwram(f: Int) = dump(f, "iwram")

    private fun ewram(f: Int) = dump(f, "ewram")

    private fun hasProc(f: Int, script: Int) = ProcArrayDump.ofEwram(ewram(f)).find(script) != null

    private fun u32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    /** Host recording the calls; VRAM, palette and tilemap are plain arrays (sentinels). */
    private class Host(val rom: ByteArray, val runDir: File) : TextHost, TalkHost {
        val vram = ByteArray(0x18000)
        val pal = ByteArray(0x400) { SENT }
        val tm = ShortArray(0x400) { 0x5555 }
        val calls = ArrayList<String>()
        var skipStarts = 0
        var lenReturn = 0
        var io = ByteArray(0x400)
        private var system: TextSystem? = null

        fun textSystemOf(talk: Talk): TextSystem {
            // The talk's own TextSystem is private; a second one over the same host draws with the
            // active font, which is the talk font after InitTalk.
            val ts = system ?: TextSystem(rom, this, Fe8Text.CONFIG).also { system = it }
            ts.setTextFont(talk.font)
            return ts
        }

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

        override fun backgroundTileDataOffset(bg: Int): Int {
            val cnt = (io[8 + 2 * bg].toInt() and 0xFF) or ((io[9 + 2 * bg].toInt() and 0xFF) shl 8)
            return ((cnt shr 2) and 3) * 0x4000
        }

        override fun decompressToVram(src: Int, dest: Int) {
            val d = Lz77.decode(rom, src - ROM_BASE).data
            d.copyInto(vram, dest - VRAM_BASE)
        }

        override fun applyPalette(src: Int, palId: Int) {
            rom.copyInto(pal, palId * 32, src - ROM_BASE, src - ROM_BASE + 32)
        }

        override fun loadObjUiGfx() {
            calls.add("obj")
        }

        override fun setBgPosition(bg: Int, x: Int, y: Int) {
            calls.add("bg$bg:$x,$y")
        }

        override fun startTalkSkipListener() {
            skipStarts++
            calls.add("skip")
        }

        override fun bg0Tilemap() = tm

        override fun getStrTalkLen(str: Int, isBubbleOpen: Boolean): Int {
            calls.add("len:$str:$isBubbleOpen")
            return lenReturn
        }

        override fun clearTalkBubble() {
            calls.add("clear")
        }

        override fun startTalkOpen(faceSlot: Int, parent: Proc) {
            calls.add("open:$faceSlot")
        }

        override fun setTalkFaceLayer(faceSlot: Int, flag: Int) {
            calls.add("layer:$faceSlot:$flag")
        }

        override fun startTalkShiftClear(parent: Proc) {
            calls.add("shift")
        }

        override fun talkBgSync(bg: Int) {
            calls.add("sync:$bg")
        }

        override fun setTalkFaceMouthMove(faceSlot: Int) {
            calls.add("mouth:$faceSlot")
        }
    }

    private companion object {
        const val SENT: Byte = 0xA5.toByte()
        const val ROM_BASE = 0x08000000
        const val VRAM_BASE = 0x06000000
        const val STATE = 0x48
        const val TEXTS = 0xD0
        const val FONT = 0xF0
        const val LINES = 0x0A
        const val ACTIVE_WIDTH = 0x0E
        const val SPEAKER = 0x0F
        const val SPEAKING_WIDTH = 0x10
        const val ACTIVE = 0x11
        const val INSTANT = 0x12
        const val MOUTH = 0x16
        const val PUT_LINES = 0x15
        const val BUF = 0x2A6AC
        const val BG0_TM = 0x22CA8
    }
}
