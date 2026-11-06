package fe8.testing

import fe8.game.bm.Mu
import fe8.game.bm.MuProc
import fe8.game.bm.MuUnit
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `StartMu` against the first MU of `ram_trace_newgame_bm`. The unit is read from the f9674 dump
 * (the f9673 slot still holds another unit: the event loads it in the same frame); StartMu on it
 * must produce the `ProcScr_Mu` proc and `sMuConfig[0]` dumped at f9674. `hidden_b` (set by event
 * code after StartMu) and the sprite palette (`GetUnitSpritePalette`, another function; taken from
 * the dump) are not compared. The `MuCtrl` proc belongs to muctrl.c, not to StartMu.
 */
class MuStartDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    @VerifiesPort("StartMu", "StartMuInternal")
    fun `StartMu from the unit as dumped at f9674 matches the f9674 MU proc and config`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run("ram_trace_newgame_bm")
        val after = run.region("r09674", "ewram")
        val iw = run.region("r09674", "iwram")
        val dumped = checkNotNull(ProcArrayDump.ofEwram(after).find(PROC_SCR_MU)) {
            "no ProcScr_Mu at f9674"
        }
        val unitAddr = dumped.u32(0x2C)
        fun b(o: Int) = after[unitAddr - GbaMem.EWRAM + o].toInt() and 0xFF
        val classPtr = GbaMem.u32(after, GbaMem.EWRAM, unitAddr + 4)
        fun ib(o: Int) = iw[CFG - GbaMem.IWRAM + o].toInt() and 0xFF
        val unit = MuUnit(
            address = unitAddr,
            xPos = b(0x10).toByte().toInt(),
            yPos = b(0x11).toByte().toInt(),
            classId = (classPtr - GCLASS_DATA) / CLASS_SIZE + 1,
            state = GbaMem.u32(after, GbaMem.EWRAM, unitAddr + 0x0C),
            spritePalette = ib(1),
        )
        val mu = Mu(ProcEngine())
        val proc = checkNotNull(mu.startMu(unit))
        val s = proc.state as MuProc
        assertEquals(dumped.u8(0x26), proc.mark, "mark")
        assertEquals(unitAddr, s.unitAddress, "unit")
        assertEquals(dumped.u8(0x3C), s.slot, "slot")
        assertEquals(dumped.u8(0x3E), if (s.camB) 1 else 0, "cam_b")
        assertEquals(dumped.u8(0x3F), s.state, "state")
        assertEquals(dumped.u8(0x41), s.jid, "jid")
        assertEquals(dumped.u8(0x42), s.facing, "facing")
        assertEquals(dumped.u8(0x43), s.stepSoundClock, "step_sound_clock")
        assertEquals(dumped.u8(0x44), if (s.fastWalkB) 1 else 0, "fast_walk_b")
        assertEquals(dumped.u16(0x46), s.layer, "layer")
        assertEquals(dumped.u16(0x48), s.moveClockQ4, "move_clock_q4")
        assertEquals(dumped.u16(0x4A), s.moveConfig, "moveConfig")
        assertEquals(dumped.u16(0x4C), s.xQ4 and 0xFFFF, "x_q4")
        assertEquals(dumped.u16(0x4E), s.yQ4 and 0xFFFF, "y_q4")
        assertEquals(dumped.u16(0x50), s.xOffsetQ4 and 0xFFFF, "x_offset")
        assertEquals(dumped.u16(0x52), s.yOffsetQ4 and 0xFFFF, "y_offset")
        assertEquals(dumped.u32(0x38), s.gfxVram, "pGfxVRAM")
        val c = mu.configs[0]
        assertEquals(ib(0), c.slot, "config.slot")
        assertEquals(ib(1), c.pal, "config.pal")
        assertEquals(ib(2) or (ib(3) shl 8), c.chr, "config.chr")
        assertEquals(ib(4), c.pc, "config.pc")
        assertEquals(true, mu.muExists())
    }

    private companion object {
        const val PROC_SCR_MU = 0x089A2C48
        const val GCLASS_DATA = 0x08807164
        const val CLASS_SIZE = 0x54
        const val CFG = 0x03001900
    }
}
