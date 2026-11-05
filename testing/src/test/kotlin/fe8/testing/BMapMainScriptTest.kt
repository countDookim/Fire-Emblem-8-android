package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BMapMain
import fe8.game.bm.BattleMapNodes
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.ChapterData
import fe8.game.bm.PlaySt
import fe8.game.bm.ResetCursorPosition
import fe8.game.bm.UnitTable
import fe8.game.flags.Fe8EventFlags
import gbafe.proc.ProcEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M5-44: the transcribed [BMapMain.script] (`gProc_BMapMain`, 0x0859A1F0, size 0x248) has the
 * same commands as the ROM (count, opcode, label / goto / sleep / mark immediate), and
 * `SwitchPhases` moves through the factions as the C does.
 */
class BMapMainScriptTest {
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    fun `gProc_BMapMain matches the ROM command for command`() {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        val script = main(RomView.ofRom(rom), PlaySt()).script
        fun u16(off: Int) = (rom[off].toInt() and 0xFF) or ((rom[off + 1].toInt() and 0xFF) shl 8)
        val base = 0x0859A1F0 - 0x08000000
        assertEquals(0x248 / 8, script.cmds.size, "command count")
        for (i in 0 until 0x248 / 8) {
            val opcode = u16(base + i * 8)
            assertEquals(opcode, script.cmds[i].opcode, "opcode at $i")
            if (opcode == 0x0B || opcode == 0x0C || opcode == 0x0E || opcode == 0x0F) {
                assertEquals(u16(base + i * 8 + 2), script.cmds[i].imm, "immediate at $i")
            }
        }
    }

    @Test
    fun `SwitchPhases goes blue red green blue with the turn counted at green to blue`() {
        val playSt = PlaySt()
        val main = main(RomView.ofRom(ByteArray(0)), playSt)
        var supportExp = 0
        main.hooks.processTurnSupportExp = { supportExp++ }
        playSt.faction = 0x40
        playSt.chapterTurnNumber = 0
        main.switchPhases()
        assertEquals(0x00 to 1, playSt.faction to playSt.chapterTurnNumber, "green to blue")
        main.switchPhases()
        assertEquals(0x80 to 1, playSt.faction to playSt.chapterTurnNumber, "blue to red")
        main.switchPhases()
        assertEquals(0x40 to 1, playSt.faction to playSt.chapterTurnNumber, "red to green")
        assertEquals(1, supportExp, "support exp only on green to blue")
    }

    @Test
    fun `SwitchPhases stops counting turns at 999`() {
        val playSt = PlaySt()
        val main = main(RomView.ofRom(ByteArray(0)), playSt)
        playSt.faction = 0x40
        playSt.chapterTurnNumber = 999
        main.switchPhases()
        assertEquals(999, playSt.chapterTurnNumber)
        assertEquals(0, playSt.faction)
    }

    private fun main(rom: RomView, playSt: PlaySt): BMapMain {
        val engine = ProcEngine()
        val camMove = CamMove(engine, BmSt())
        val units = UnitTable(rom)
        val reset = ResetCursorPosition(engine, BmSt(), playSt, units, camMove, noNodes)
        return BMapMain(
            engine, playSt, units, Fe8EventFlags.create(), ChapterData(rom), rom, reset, camMove,
        )
    }

    private val noNodes = object : BattleMapNodes {
        override fun chapterId(node: Int) = -1
        override fun isCleared(node: Int) = false
        override fun encounters(node: Int) = 0
        override fun placementFlag(node: Int) = 0
    }
}
