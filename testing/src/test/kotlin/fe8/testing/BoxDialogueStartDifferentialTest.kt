package fe8.testing

import fe8.game.RomView
import fe8.game.text.Fe8Text
import fe8.game.ui.BoxDialogue
import fe8.game.ui.BoxDialogueState
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import gbafe.text.TextSystem
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `PC-11b2a` against `ram_trace_newgame_easy`: the run of `gProcScr_BoxDialogue` from `f18230` to
 * `f18240` (script position per frame, `sub_808A848` calling `InitBoxDialogue` and
 * `DrawBoxDialogueText`, `sub_808A87C` waiting, with the `r18240` EWRAM dump for
 * `gBoxDialogueConf` and the palette bank 0x15); and the first frame of `gProcScr_BoxDialogue`
 * and `ProcScr_TalkBoxIdle`, started by `sub_808AA04`; the proc fields (`x`, `y`, `msg`,
 * `unk_38`, `pad_idx`) and the config reset are compared with the capture. The tutorial box is
 * started at (0, 0) (both coordinates 0xFF in the event), so `GetBoxDialogueSize` is not called
 * there; it is checked on control-code-only strings against the C control flow.
 */
class BoxDialogueStartDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    private fun newBox(): Pair<ProcEngine, BoxDialogue> {
        val romFile = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
            "baserom.gba",
        )
        assumeTrue(romFile.isFile, "ROM not present")
        val romBytes = RomView.ofRom(romFile.readBytes()).wholeRomBytes()
        val engine = ProcEngine()
        val textSystem = TextSystem(romBytes, TextVram(), Fe8Text.CONFIG)
        val messages = MsgStrings(
            romBytes,
            MsgHuffman(
                romBytes,
                0x0815A72C - 0x08000000,
                word(romBytes, 0x0815D488 - 0x08000000) and 0x01FFFFFF,
            ),
            0x0815D48C - 0x08000000,
        )
        return engine to BoxDialogue(engine, textSystem, messages)
    }

    @Test
    @VerifiesPort("sub_808AA04", "SetDialogueBoxConfig", "GetDialogueBoxConfig")
    fun `start matches the box proc row at f18230`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val dump = ProcArrayDump.ofWatch(ref.run(RUN).watch("procs"), STEP, names)
        val row = dump.find("gProcScr_BoxDialogue")!!
        assumeTrue(dump.find("ProcScr_TalkBoxIdle") != null, "idle proc row")
        val (engine, box) = newBox()
        box.setDialogueBoxConfig(0x1234)
        assertEquals(0x1234, box.getDialogueBoxConfig())
        val proc = box.startBoxDialogue(row.u32(0x2C), row.u32(0x30), row.u16(0x34))
        assertEquals(0, box.getDialogueBoxConfig())
        val s = proc.state as BoxDialogueState
        assertEquals(row.u32(0x2C), s.x, "x")
        assertEquals(row.u32(0x30), s.y, "y")
        assertEquals(row.u16(0x34), s.msg, "msg")
        assertEquals(row.u8(0x38), s.unk38, "unk_38")
        assertEquals(row.u8(0x40), s.padIdx, "pad_idx")
        assertEquals(row.u32(0x14), 3, "parent root of the capture")
        assertEquals(1, engine.count(box.talkBoxIdleScript))
        box.startBoxDialogue(1, 2, 3)
        assertEquals(1, engine.count(box.script), "the old box ended")
    }

    @Test
    @VerifiesPort("sub_808A848", "sub_808A87C", "InitBoxDialogue", "gProcScr_BoxDialogue")
    fun `box procs run f18230 to f18240 like the capture`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val run = ref.run(RUN)
        assumeTrue(run.dumps.containsKey("r$AFTER"), "no r$AFTER dump")
        val watch = run.watch("procs")
        val ewram = run.region("r$AFTER", "ewram")
        val (engine, box) = newBox()
        val rom = romBytes()
        val first = ProcArrayDump.ofWatch(watch, STEP, names).find("gProcScr_BoxDialogue")!!
        val palette = ByteArray(0x20)
        var paletteBank = -1
        val drawn = ArrayList<List<Int>>()
        box.hooks.applyPalette = { src, bank ->
            System.arraycopy(rom, src - 0x08000000, palette, 0, 0x20)
            paletteBank = bank
        }
        box.hooks.drawBoxDialogueText = { x, y, msg -> drawn += listOf(x, y, msg) }
        val proc = box.startBoxDialogue(first.u32(0x2C), first.u32(0x30), first.u16(0x34))
        // The event sets the config bits after starting the box: the capture value.
        box.setDialogueBoxConfig(u16(ewram, CONF + 0x42))
        for (step in STEP..AFTER) {
            val row = ProcArrayDump.ofWatch(watch, step, names).find("gProcScr_BoxDialogue")!!
            assertEquals(row.scrCur, proc.scrCur, "script position at the start of f$step")
            engine.run(engine.root(3))
        }
        assertEquals(listOf(listOf(first.u32(0x2C), first.u32(0x30), first.u16(0x34))), drawn)
        assertEquals(u32(ewram, CONF), box.font.vramDest, "font vram")
        assertEquals(u32(ewram, CONF + 0x08), box.font.drawGlyph, "font draw_glyph")
        assertEquals(u32(ewram, CONF + 0x0C), box.font.getDrawDest, "font get_draw_dest")
        assertEquals(u16(ewram, CONF + 0x10), box.font.tileref, "font tileref")
        assertEquals(u16(ewram, CONF + 0x12), box.font.chrCounter, "font chr_counter")
        assertEquals(u16(ewram, CONF + 0x14), box.font.palid, "font palid")
        // Text x and colour are rewritten by the drawing (PC-11b2c) and are not compared.
        for (i in 0 until 5) {
            val o = CONF + 0x18 + 8 * i
            val t = box.texts[i]
            assertEquals(u16(ewram, o), t.chrPosition, "text $i chr_position")
            assertEquals(ewram[o + 4].toInt() and 0xFF, t.tileWidth, "text $i tile_width")
            assertEquals(ewram[o + 5].toInt() and 0xFF, if (t.dbEnabled) 1 else 0, "text $i db")
        }
        assertEquals(u16(ewram, CONF + 0x40), box.unk40, "unk_40")
        assertEquals(0x15, paletteBank, "palette bank")
        val base = PALETTE + 0x15 * 0x20
        for (i in 0 until 0x20) assertEquals(ewram[base + i], palette[i], "palette byte $i")
    }

    private fun romBytes(): ByteArray = RomView.ofRom(
        File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
            "baserom.gba",
        ).readBytes(),
    ).wholeRomBytes()

    private fun u16(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)

    @Test
    fun `size of control code strings`() {
        val (_, box) = newBox()
        // [NL] adds a line, [NL2] resets the height, [A] widens by 8, [X] ends.
        assertEquals(0 to 16, box.getBoxDialogueSize(byteArrayOf(0), 0))
        assertEquals(0 to 32, box.getBoxDialogueSize(byteArrayOf(1, 0), 0))
        assertEquals(8 to 16, box.getBoxDialogueSize(byteArrayOf(3, 0), 0))
        assertEquals(0 to 32, box.getBoxDialogueSize(byteArrayOf(1, 2, 1, 0), 0))
        assertEquals(0 to 16, box.getBoxDialogueSize(byteArrayOf(0x80.toByte(), 9, 4, 0x14), 0))
    }

    private fun word(b: ByteArray, o: Int): Int =
        (0..3).fold(0) { acc, i -> acc or ((b[o + i].toInt() and 0xFF) shl (8 * i)) }

    private companion object {
        const val RUN = "ram_trace_newgame_easy"
        const val STEP = 18230
        const val AFTER = 18240

        /** `gBoxDialogueConf` and `gPaletteBuffer` as EWRAM offsets. */
        const val CONF = 0x0203E7E8 - 0x02000000
        const val PALETTE = 0x020228A8 - 0x02000000
    }
}
