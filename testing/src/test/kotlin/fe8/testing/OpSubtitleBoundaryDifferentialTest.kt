package fe8.testing

import fe8.game.RomView
import fe8.game.opanim.OpSubtitleSlides
import fe8.game.opanim.OpSubtitleScenes
import fe8.game.opanim.OpSubtitleSetup
import gba.testkit.ReferenceData
import gbafe.render.BgHardware
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Original scene-setup callback phases and completed proc updates across the flare boundary. */
class OpSubtitleBoundaryDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val assets =
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
    private val romFile = File(assets, "baserom.gba")

    @Test
    fun `scene setup spans video frames and matches captured callback return`() {
        assumeTrue(ref.available && romFile.isFile, "reference capture or ROM unavailable")
        val run = ref.run("ram_trace_subtitle_boundary")
        val calls = File(ref.root, "ram_trace_subtitle_boundary/calls.txt").readLines().map {
            it.split(" ")
        }
        assertEquals(listOf("call_setup_1_entry", "call_setup_1_exit"), calls.map { it[0] })
        val entryStep = calls[0][1].toInt()
        val exitStep = calls[1][1].toInt()
        assertEquals(2355, entryStep)
        assertEquals(2357, exitStep)
        assertEquals("080C50A0", calls[0][2])
        assertEquals(calls[0][6], calls[1][2], "callback returns to its captured caller")
        val trace = run.watch("procs")
        val clock = run.watch("clock")
        val procAddress = calls[0][3].toLong(16).toInt()
        val entry = run.region("call_setup_1_entry", "ewram")
        val exit = run.region("call_setup_1_exit", "ewram")
        assertEquals(procAddress, ProcArrayDump.ofEwram(entry).find(SCRIPT)!!.address)
        assertEquals(procAddress, ProcArrayDump.ofEwram(exit).find(SCRIPT)!!.address)
        val entryCursor = ProcArrayDump.ofEwram(entry).find(SCRIPT)!!.scrCur
        for (step in entryStep until exitStep) {
            assertEquals(entryCursor, ProcArrayDump.ofWatch(trace, step).find(SCRIPT)!!.scrCur)
        }
        assertEquals(
            clock.u32(entryStep, 0) + (exitStep - entryStep), clock.u32(exitStep, 0),
            "the video clock advances while the same callback runs",
        )

        val hardware = BgHardware()
        for (i in hardware.palette.indices) {
            hardware.palette[i] = u16(entry, 0x228A8 + 2 * i).toShort()
        }
        for (bank in 0..1) {
            for (i in 0 until 1024) {
                hardware.tilemaps[bank][i] = u16(entry, 0x22CA8 + bank * 0x800 + 2 * i).toShort()
            }
        }
        val originalEntry = ProcArrayDump.ofEwram(entry).find(SCRIPT)!!
        val originalExit = ProcArrayDump.ofEwram(exit).find(SCRIPT)!!
        val state = OpSubtitleSlides.State(
            originalEntry.u8(0x29).toByte().toInt(), originalEntry.u16(0x2C),
            originalEntry.u16(0x2A),
        )
        val rom = RomView.ofRom(romFile.readBytes())
        OpSubtitleScenes(
            hardware, rom, OpSubtitleSetup(rom, hardware), OpSubtitleSlides(rom, hardware),
        ).clearForFlare(state)
        assertEquals(originalExit.u16(0x2A), state.holdTimer, "hold at callback return")
        assertEquals(originalExit.u16(0x2C), state.transitionTimer, "timer at callback return")
        for (bank in 0..1) {
            val expected = ShortArray(1024) {
                u16(exit, 0x22CA8 + bank * 0x800 + 2 * it).toShort()
            }
            assertArrayEquals(expected, hardware.tilemaps[bank], "BG$bank at callback return")
        }
        for (i in 0xE0 until 0x100) {
            assertEquals(
                u16(exit, 0x228A8 + 2 * i), hardware.palette[i].toInt() and 0xFFFF,
                "palette $i at callback return",
            )
        }
    }

    @Test
    fun `boundary capture shares prologue input and sound RAM around known divergence`() {
        assumeTrue(ref.available, "reference capture unavailable")
        val original = ref.run("ram_trace_prologue")
        val boundary = ref.run("ram_trace_subtitle_boundary")
        assertArrayEquals(original.inputs.copyOf(boundary.inputs.size), boundary.inputs)
        for (step in listOf(960, 990, 1230, 2340, 2370)) {
            val dump = "r%05d".format(step)
            for (region in listOf("ewram", "iwram")) {
                assertArrayEquals(
                    original.region(dump, region), boundary.region(dump, region), "$dump $region",
                )
            }
        }
    }

    private companion object {
        const val SCRIPT = 0x08AA21BC

        fun u16(bytes: ByteArray, at: Int): Int =
            (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
    }
}
