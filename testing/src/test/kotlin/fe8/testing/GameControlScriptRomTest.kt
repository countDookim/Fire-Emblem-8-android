package fe8.testing

import fe8.game.GameControl
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M5-33: the transcribed [GameControl.script] has the same commands as `gProcScr_GameControl` in
 * the ROM (nm: 0x085916D4, size 0x3D0): the same count, and the same opcode (u16 at +0 of each
 * 8-byte `ProcCmd`) and label / goto / sleep / mark immediate (u16 at +2) at every index.
 */
class GameControlScriptRomTest {
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun ours(): ProcScript {
        val stand = ProcScript("stand-in", emptyList())
        return GameControl(ProcEngine(), KeyStatus(), { stand }, { stand }).script
    }

    @Test
    fun `script matches the ROM command for command`() {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        fun u16(off: Int) = (rom[off].toInt() and 0xFF) or ((rom[off + 1].toInt() and 0xFF) shl 8)
        val base = 0x085916D4 - 0x08000000
        val count = 0x3D0 / 8
        val cmds = ours().cmds
        assertEquals(122, count)
        assertEquals(count, cmds.size, "command count")
        for (i in 0 until count) {
            val opcode = u16(base + i * 8)
            assertEquals(opcode, cmds[i].opcode, "opcode at $i")
            // The immediate is the label id (0x0B, 0x0C), sleep time (0x0E) or mark (0x0F).
            if (opcode == 0x0B || opcode == 0x0C || opcode == 0x0E || opcode == 0x0F) {
                assertEquals(u16(base + i * 8 + 2), cmds[i].imm, "immediate at $i")
            }
        }
    }
}
