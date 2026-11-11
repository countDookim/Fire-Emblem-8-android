package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BattleMapNodes
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.PlaySt
import fe8.game.bm.ResetCursorPosition
import fe8.game.bm.UnitTable
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The transcribed [CamMove.script] (`ProcScr_CamMove`, 0x0859A548, size 0x28) and
 * [ResetCursorPosition.script] (`gProcScr_ResetCursorPosition`, 0x0859AE00, size 0x18) have the
 * same commands as the ROM: the same count, and the same opcode (u16 at +0 of each 8-byte
 * `ProcCmd`) and label / goto / sleep / mark immediate (u16 at +2) at every index.
 */
class CamMoveScriptRomTest {
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    fun `ProcScr_CamMove matches the ROM command for command`() {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val (engine, bm) = ProcEngine() to BmSt()
        check(romFile.readBytes(), 0x0859A548, 0x28, CamMove(engine, bm).script)
    }

    @Test
    fun `gProcScr_ResetCursorPosition matches the ROM command for command`() {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val bytes = romFile.readBytes()
        val engine = ProcEngine()
        val bm = BmSt()
        val reset = ResetCursorPosition(
            engine, bm, PlaySt(), UnitTable(RomView.ofRom(bytes)), CamMove(engine, bm), noNodes,
        )
        check(bytes, 0x0859AE00, 0x18, reset.script)
    }

    private fun check(rom: ByteArray, address: Int, size: Int, script: ProcScript) {
        fun u16(off: Int) = (rom[off].toInt() and 0xFF) or ((rom[off + 1].toInt() and 0xFF) shl 8)
        val base = address - 0x08000000
        val count = size / 8
        assertEquals(count, script.cmds.size, "command count")
        for (i in 0 until count) {
            val opcode = u16(base + i * 8)
            assertEquals(opcode, script.cmds[i].opcode, "opcode at $i")
            if (opcode == 0x0B || opcode == 0x0C || opcode == 0x0E || opcode == 0x0F) {
                assertEquals(u16(base + i * 8 + 2), script.cmds[i].imm, "immediate at $i")
            }
        }
    }

    private val noNodes = object : BattleMapNodes {
        override fun chapterId(node: Int) = error("unused")
        override fun isCleared(node: Int) = error("unused")
        override fun encounters(node: Int) = error("unused")
        override fun placementFlag(node: Int) = error("unused")
    }
}
