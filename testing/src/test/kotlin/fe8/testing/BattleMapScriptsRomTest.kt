package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BattleMapStart
import fe8.game.bm.BmMap
import fe8.game.bm.BmMapRender
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.ChapterMapGfx
import fe8.game.bm.PlaySt
import fe8.game.flags.Fe8EventFlags
import gbafe.core.rng.RngService
import gbafe.core.time.GameClock
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.render.BgHardware
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M5-43: the proc scripts of [BattleMapStart] have the same commands as the ROM's: `gProc_MapTask`
 * (0x0859D908, size 0x50) and `sProc_BMVSync` (0x0859D8B8, size 0x50), command by command (count,
 * opcode, and the label / goto / sleep / mark immediates). The BMapMain placeholder is not
 * checked (its real script is M5-44).
 */
class BattleMapScriptsRomTest {
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun start(rom: ByteArray): BattleMapStart {
        val view = RomView.ofRom(rom)
        val playSt = PlaySt()
        val bmSt = BmSt()
        val hw = BgHardware()
        val chapters = ChapterData(view)
        val map = BmMap(view, chapters, bmSt)
        return BattleMapStart(
            ProcEngine(), playSt, bmSt, hw, chapters, map,
            ChapterMapGfx(view, chapters, hw, playSt), BmMapRender(bmSt, map, hw),
            Fe8EventFlags.create(), RngService(), GameClock(),
        )
    }

    private fun check(rom: ByteArray, script: ProcScript, address: Int, size: Int) {
        fun u16(off: Int) = (rom[off].toInt() and 0xFF) or ((rom[off + 1].toInt() and 0xFF) shl 8)
        val base = address - 0x08000000
        assertEquals(size / 8, script.cmds.size, "${script.name} command count")
        for (i in 0 until size / 8) {
            val opcode = u16(base + i * 8)
            assertEquals(opcode, script.cmds[i].opcode, "${script.name} opcode at $i")
            if (opcode == 0x0B || opcode == 0x0C || opcode == 0x0E || opcode == 0x0F) {
                assertEquals(u16(base + i * 8 + 2), script.cmds[i].imm, "immediate at $i")
            }
        }
    }

    @Test
    fun `map task and vsync scripts match the ROM command for command`() {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        val start = start(rom)
        check(rom, start.mapTaskScript, 0x0859D908, 0x50)
        check(rom, start.vSyncScript, 0x0859D8B8, 0x50)
    }
}
