package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmMap
import fe8.game.bm.BmMapRender
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.ChapterData
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.PlayerPhase
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitTable
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.render.BgHardware
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The transcribed [PlayerPhase.script] (`gProcScr_PlayerPhase`, 0x0859AAD8, size 0x210) and
 * [PlayerPhase.selectEventsScript] (`gProcScr_0859ACE8`, 0x0859ACE8, size 0x20) have the same
 * commands as the ROM: the same count, opcode (u16 at +0 of each 8-byte `ProcCmd`) and
 * label / goto / sleep / mark immediate (u16 at +2) at every index.
 */
class PlayerPhaseScriptRomTest {
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    fun `gProcScr_PlayerPhase matches the ROM command for command`() {
        compare(0x0859AAD8, 0x210) { it.script }
    }

    @Test
    fun `gProcScr_0859ACE8 matches the ROM command for command`() {
        compare(0x0859ACE8, 0x20) { it.selectEventsScript }
    }

    private fun compare(address: Int, size: Int, script: (PlayerPhase) -> ProcScript) {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        val view = RomView.ofRom(rom)
        val engine = ProcEngine()
        val bm = BmSt()
        val playSt = PlaySt()
        val map = BmMap(view, ChapterData(view), bm)
        val units = UnitTable(view)
        val phase = PlayerPhase(
            engine, bm, playSt, map, units, CamMove(engine, bm),
            EntityBmMaps(map, units, playSt), BmMapRender(bm, map, BgHardware()),
            KeyStatus(), { false },
        )
        val cmds = script(phase).cmds
        fun u16(off: Int) = (rom[off].toInt() and 0xFF) or ((rom[off + 1].toInt() and 0xFF) shl 8)
        val base = address - 0x08000000
        val count = size / 8
        assertEquals(count, cmds.size, "command count")
        for (i in 0 until count) {
            val opcode = u16(base + i * 8)
            assertEquals(opcode, cmds[i].opcode, "opcode at $i")
            if (opcode == 0x0B || opcode == 0x0C || opcode == 0x0E || opcode == 0x0F) {
                assertEquals(u16(base + i * 8 + 2), cmds[i].imm, "immediate at $i")
            }
        }
    }
}
