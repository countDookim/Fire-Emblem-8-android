package fe8.testing

import fe8.game.RomView
import fe8.game.mapanim.MapLevelUpLabelColor
import gba.core.VerifiesPort
import gba.extract.Symbols
import gba.testkit.GbaMem
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.render.BgHardware
import java.io.File
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Original Prologue level-up color child, f6520..6728; other palette writers are excluded. */
class MapLevelUpLabelColorDifferentialTest {
    @Test
    fun `repeating callback does not allocate`() {
        val romFile = File(System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")
        assumeTrue(romFile.isFile, "ROM missing")
        val meter = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue(meter != null && meter.isThreadAllocatedMemorySupported, "meter unavailable")
        meter!!.isThreadAllocatedMemoryEnabled = true
        val controller = MapLevelUpLabelColor(BgHardware(), RomView.ofRom(romFile.readBytes()))
        val state = MapLevelUpLabelColor.State().apply { pal = 3 }
        repeat(100_000) { controller.loop(state) }
        val thread = Thread.currentThread().id
        val before = meter.getThreadAllocatedBytes(thread)
        repeat(100_000) { controller.loop(state) }
        val after = meter.getThreadAllocatedBytes(thread)
        assertEquals(0L, after - before, "repeating palette loop allocations")
    }

    @Test
    @VerifiesPort("ManimLevelUpLabelColor_Init", "ManimLevelUpLabelColor_Loop")
    fun `color proc and palette follow the original every step`() {
        val ref = ReferenceData.fromEnvironment()
        val romFile = File(System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")
        val nm = ProcArrayDump.nmFile()
        assumeTrue(ref.available && romFile.isFile, "capture or ROM missing")
        val symbols = Symbols(requireNotNull(nm) { "symbol table missing" }.readLines())
        assertEquals(0x089AC5CC, symbols["Pal_ManimLevelUpStatGainCycling"].address)
        val run = ref.run("ram_trace_prologue_levelup")
        assertTrue(run.hasWatch("procs"), "proc watch missing")
        val trace = run.watch("procs")
        val names = ProcArrayDump.romNames()
        val paletteAddress = RamSymbols.fromEnvironment()["gPaletteBuffer"].address
        fun palette(step: Int): ShortArray {
            val name = "%s%05d".format(if (step % 10 == 0) "f" else "r", step)
            val ew = run.region(name, "ewram")
            return ShortArray(512) { GbaMem.u16(ew, GbaMem.EWRAM, paletteAddress + it * 2).toShort() }
        }
        val hardware = BgHardware()
        val controller = MapLevelUpLabelColor(hardware, RomView.ofRom(romFile.readBytes()))
        val engine = ProcEngine()
        val first = requireNotNull(
            ProcArrayDump.ofWatch(trace, 6520, names).find("ProcScr_ManimLevelUpLabelColor"),
        )
        // include/mapanim.h: clock +0x54 and pal +0x64. Recover the slot's stale clock;
        // if it was zero, use a controlled nonzero value to exercise initialization.
        val stale = GbaMem.u32(trace.step(6519), 0, first.slot * ProcArrayDump.PROC_SIZE + 0x54)
        val script = ProcScript(controller.script.name, controller.script.cmds) {
            MapLevelUpLabelColor.State().apply { clock = if (stale == 0) 42 else stale }
        }
        val proc = engine.start(script, 3)
        val state = proc.state as MapLevelUpLabelColor.State
        state.pal = first.u16(0x64)
        var wraps = 0
        for (step in 6520..6728) {
            // Initial bank contents are established by another proc during f6520.
            val before = palette(if (step == 6520) step else step - 1)
            before.copyInto(hardware.palette)
            hardware.flushBackgrounds()
            engine.run(proc)
            val original = requireNotNull(
                ProcArrayDump.ofWatch(trace, step, names).find("ProcScr_ManimLevelUpLabelColor"),
            )
            assertEquals(original.u32(0x54), state.clock, "clock f$step")
            assertEquals(original.u16(0x64), state.pal, "pal f$step")
            val actual = palette(step)
            if (step % 10 == 0 && step > 6520) {
                val pal = run.region("f%05d".format(step), "pal")
                for (i in (313..319) + (329..335)) {
                    assertEquals(
                        GbaMem.u16(pal, 0x05000000, 0x05000000 + 2 * i).toShort(),
                        hardware.paletteRam[i], "palette RAM[$i] f$step",
                    )
                }
            }
            for (i in hardware.palette.indices) {
                val inCycle = i in 313..319 || i in 329..335
                if (inCycle) assertEquals(actual[i], hardware.palette[i], "palette[$i] f$step")
                else assertEquals(before[i], hardware.palette[i], "untouched[$i] f$step")
            }
            // Sync flag follows CopyToPaletteBuffer; the capture does not record the flag.
            if (state.clock % 4 == 0) {
                assertTrue(hardware.modifiedPalette, "sync f$step")
                if ((state.clock shr 2) and 15 == 0) wraps++
            } else {
                assertFalse(hardware.modifiedPalette, "sync f$step")
                assertArrayEquals(before, hardware.palette, "nonupdate f$step")
            }
        }
        assertTrue(wraps > 0)
    }
}
