package fe8.testing

import fe8.game.RomView
import fe8.game.opanim.OpSubtitleFlare
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.repeat
import gbafe.render.BgHardware
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/** The subtitle's lens-flare child compared per frame with the prologue capture. */
class OpSubtitleFlareDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun u16(mem: ByteArray, at: Int) =
        (mem[at].toInt() and 0xFF) or ((mem[at + 1].toInt() and 0xFF) shl 8)

    private fun dumpName(frame: Int) =
        (if (frame % 300 == 0) "f" else "r") + frame.toString().padStart(5, '0')

    private fun s16(mem: ByteArray, at: Int) = u16(mem, at).toShort().toInt()

    private fun s32(mem: ByteArray, at: Int) = u16(mem, at) or (u16(mem, at + 2) shl 16)

    private fun stateOf(p: ProcArrayDump.Proc) =
        OpSubtitleFlare.State(p.u16(0x4C).toShort().toInt(), p.u16(0x4E).toShort().toInt())

    private class Rig(rom: RomView, val state: OpSubtitleFlare.State) {
        val engine = ProcEngine()
        val hardware = BgHardware()
        val flare = OpSubtitleFlare(engine, hardware, rom)
        val proc = engine.start(
            ProcScript(
                "flare test", listOf(repeat(ProcFunc { flare.loop(it, state) }), block()),
            ) { state },
            3,
        )

        fun step() = engine.run(proc)
    }

    @Test
    @VerifiesPort("Subtitle_LightFlareFx_Loop", "BgAffinRotScaling", "BgAffinScaling", "BgAffinAnchoring")
    fun `loop follows original counters, affine registers and blend`() {
        assumeTrue(ref.available && romFile.isFile, "reference capture or ROM unavailable")
        val run = ref.run("ram_trace_prologue")
        val trace = run.watch("procs")
        val rom = RomView.ofRom(romFile.readBytes())
        var compared = 0
        var breaks = 0
        var dumps = 0
        for (frame in 2300..2500) {
            val before = ProcArrayDump.ofWatch(trace, frame - 1).find(SCRIPT) ?: continue
            if (before.u32(0x0C) != LOOP_CALLBACK) continue
            val after = ProcArrayDump.ofWatch(trace, frame).find(SCRIPT) ?: continue
            val afterCallback = after.u32(0x0C)
            if (afterCallback != LOOP_CALLBACK && afterCallback != 0) continue
            val rig = Rig(rom, stateOf(before))
            rig.step()
            assertEquals(after.u16(0x4C).toShort().toInt(), rig.state.frame, "frame $frame 4c")
            assertEquals(after.u16(0x4E).toShort().toInt(), rig.state.angle, "frame $frame 4e")
            val broke = afterCallback == 0
            assertEquals(broke, rig.proc.idleCb == null, "frame $frame break")
            if (frame % 30 == 0) {
                val iwram = run.region(dumpName(frame), "iwram")
                val lcd = rig.hardware.lcd
                for (k in 0..3) assertEquals(s16(iwram, LCD + 0x48 + 2 * k), lcd.affine[k], "$frame p$k")
                assertEquals(s32(iwram, LCD + 0x48 + 8), lcd.affine[4], "frame $frame dx")
                assertEquals(s32(iwram, LCD + 0x48 + 12), lcd.affine[5], "frame $frame dy")
                assertEquals(u16(iwram, LCD + 0x3C) and 0xC0, lcd.bldcnt and 0xC0, "frame $frame effect")
                assertEquals(iwram[LCD + 0x44].toInt() and 0xFF, lcd.blendCoeffA, "frame $frame eva")
                assertEquals(iwram[LCD + 0x45].toInt() and 0xFF, lcd.blendCoeffB, "frame $frame evb")
                assertEquals(iwram[LCD + 0x46].toInt() and 0xFF, lcd.blendY, "frame $frame evy")
                dumps++
            }
            compared++
            if (broke) breaks++
        }
        assertTrue(compared >= 58, "compared $compared")
        assertEquals(1, breaks)
        assertTrue(dumps >= 2, "dumps $dumps")
    }

    @Test
    @VerifiesPort("Subtitle_LightFlareFx_Init", "sub_800154C")
    fun `init draws the BG2 byte map and enables BG0 and BG2`() {
        assumeTrue(ref.available && romFile.isFile, "reference capture or ROM unavailable")
        val run = ref.run("ram_trace_prologue")
        val rom = RomView.ofRom(romFile.readBytes())
        val rig = Rig(rom, OpSubtitleFlare.State(7, 7))
        rig.hardware.tilemaps[2].fill(0)
        rig.hardware.lcd.dispcnt = 0x1F00
        rig.flare.init(rig.state)
        assertEquals(0, rig.state.frame)
        assertEquals(0, rig.state.angle)
        assertEquals(0x0500, rig.hardware.lcd.dispcnt and 0x1F00)
        assertEquals(4, rig.hardware.modifiedBgs and 4)
        val ewram = run.region("r02370", "ewram")
        for (i in 0 until 0x200) {
            assertEquals(u16(ewram, BG2_MAP + 2 * i), rig.hardware.tilemaps[2][i].toInt() and 0xFFFF, "map $i")
        }
        val iwram = run.region("r02370", "iwram")
        assertEquals(0x0500, u16(iwram, LCD) and 0x1F00 and 0x0500)
    }

    @Test
    @VerifiesPort("Subtitle_LightFlareFx_End")
    fun `end hides BG2 as in the capture after the flare`() {
        assumeTrue(ref.available && romFile.isFile, "reference capture or ROM unavailable")
        val run = ref.run("ram_trace_prologue")
        val rom = RomView.ofRom(romFile.readBytes())
        val rig = Rig(rom, OpSubtitleFlare.State())
        rig.hardware.lcd.dispcnt = 0x1F00
        rig.flare.end()
        assertEquals(0x1B00, rig.hardware.lcd.dispcnt)
        val before = u16(run.region("f02400", "iwram"), LCD)
        assertEquals(0x0400, before and 0x0400, "BG2 on during the flare")
        val after = u16(run.region("r02460", "iwram"), LCD)
        assertEquals(0, after and 0x0400, "BG2 off after the flare")
    }

    private companion object {
        const val SCRIPT = 0x08AA2154
        const val LOOP_CALLBACK = 0x080C4A89
        const val LCD = 0x3080
        const val BG2_MAP = 0x23CA8
    }
}
