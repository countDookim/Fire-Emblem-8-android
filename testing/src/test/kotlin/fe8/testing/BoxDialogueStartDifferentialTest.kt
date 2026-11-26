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
 * `PC-11b2a` against `ram_trace_newgame_easy` `f18230`: the first frame of `gProcScr_BoxDialogue`
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
        assertEquals(row.u8(0x3C), s.padIdx, "pad_idx")
        assertEquals(row.u32(0x14), 3, "parent root of the capture")
        assertEquals(1, engine.count(box.talkBoxIdleScript))
        box.startBoxDialogue(1, 2, 3)
        assertEquals(1, engine.count(box.script), "the old box ended")
    }

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
    }
}
