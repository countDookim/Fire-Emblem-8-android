package fe8.testing

import fe8.game.RomView
import fe8.game.bm.GameOverScreen
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware
import gbafe.render.ColorFade
import java.io.File
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * Differential test of `GameOverScreenHBlank` (`PC-8h`) against
 * `ram_trace_prologue_gameover_hblank` (`PC-cap2b`, 912
 * `call_GameOverScreenHBlank_*_{entry,exit}.io.bin` pairs across frames `6130..6133`, covering
 * all 228 scanlines `VCOUNT` `0..227` four times).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
class GameOverScreenHBlankDifferentialTest {
    private var cachedCalls: List<CallPair>? = null
    private var cachedRom: ByteArray? = null

    @AfterAll
    fun release() {
        cachedCalls = null
        cachedRom = null
    }

    private fun loadCalls(): List<CallPair> {
        cachedCalls?.let { return it }
        val ref = ReferenceData.fromEnvironment()
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val runDir = File(ref.root, RUN)
        val callsFile = File(runDir, "calls.txt")
        assumeTrue(callsFile.isFile, "$RUN calls.txt missing")
        val lines = callsFile.readLines().filter { it.isNotBlank() }.map { it.trim().split(" ") }
        assertEquals(EXPECTED_CALLS * 2, lines.size, "entry/exit line count in calls.txt")
        val run = ref.run(RUN)
        val out = ArrayList<CallPair>(EXPECTED_CALLS)
        for (i in 0 until EXPECTED_CALLS) {
            val entry = lines[2 * i]
            val exit = lines[2 * i + 1]
            val index = i + 1
            val expectedEntryName = "call_GameOverScreenHBlank_${index}_entry"
            val expectedExitName = "call_GameOverScreenHBlank_${index}_exit"
            assertEquals(expectedEntryName, entry[0], "call $index entry name")
            assertEquals(expectedExitName, exit[0], "call $index exit name")
            assertEquals(entry[1], exit[1], "call $index step")
            assertEquals(ENTRY_PC, entry[2], "call $index entry PC")
            assertEquals(entry[6], exit[2], "call $index exit PC matches return address")
            val entryIo = run.region(entry[0], "io")
            val exitIo = run.region(exit[0], "io")
            assertEquals(IO_SIZE, entryIo.size, "$expectedEntryName io.bin size")
            assertEquals(IO_SIZE, exitIo.size, "$expectedExitName io.bin size")
            out += CallPair(index, entry[1].toInt(), entryIo, exitIo)
        }
        cachedCalls = out
        return out
    }

    private fun loadRom(): RomView {
        cachedRom?.let { return RomView.ofRom(it) }
        val assets = System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
        val romFile = File(assets, "baserom.gba")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val bytes = romFile.readBytes()
        cachedRom = bytes
        return RomView.ofRom(bytes)
    }

    private fun assertMatchesExit(label: String, call: CallPair, io: ByteArray) {
        val vcount = u16(call.entryIo, REG_VCOUNT)
        val at = "$label f${call.step} VCOUNT=$vcount"
        assertEquals(
            call.exitIo[REG_BLDCA].toInt() and 0xFF,
            io[REG_BLDCA].toInt() and 0xFF,
            "$at BLDCA (io[0x52])",
        )
        assertEquals(
            call.exitIo[REG_BLDCB].toInt() and 0xFF,
            io[REG_BLDCB].toInt() and 0xFF,
            "$at BLDCB (io[0x53])",
        )
        // DISPSTAT (0x04..0x05) and VCOUNT (0x06..0x07) are hardware status registers that
        // advance when HBlank finishes before the exit breakpoint fires.
        val entryDispstat = u16(call.entryIo, REG_DISPSTAT)
        val exitDispstat = u16(call.exitIo, REG_DISPSTAT)
        assertEquals(0, (entryDispstat xor exitDispstat) and 0x0002.inv(), "$at DISPSTAT bits")
        val exitVcount = u16(call.exitIo, REG_VCOUNT)
        assertTrue(exitVcount == vcount || exitVcount == vcount + 1, "$at exit VCOUNT=$exitVcount")

        val expected = call.exitIo.copyOf()
        for (offset in REG_DISPSTAT until REG_VCOUNT + 2) {
            expected[offset] = io[offset]
        }
        assertArrayEquals(expected, io, "$at full 0x60 IO")
    }

    @Test
    @VerifiesPort("GameOverScreenHBlank")
    fun `gameOverScreenHBlank matches PC-cap2b BLDALPHA and full IO on all 912 calls`() {
        val calls = loadCalls()
        val seenVcounts = HashSet<Int>(TOTAL_SCANLINES)
        for (call in calls) {
            val vcount = u16(call.entryIo, REG_VCOUNT)
            seenVcounts += vcount
            val io = call.entryIo.copyOf()
            GameOverScreen.gameOverScreenHBlank(vcount, io)
            assertMatchesExit("call#${call.index}", call, io)
        }
        assertEquals((0 until TOTAL_SCANLINES).toSet(), seenVcounts, "all VCOUNT 0..227 covered")
    }

    @Test
    @VerifiesPort("GameOverScreenHBlank")
    fun `init installs hblankHandler whose active callback matches PC-cap2b on all 912 calls`() {
        val calls = loadCalls()
        val rom = loadRom()
        val hardware = BgHardware()
        hardware.setupBackgrounds(null)
        val screen = GameOverScreen(ProcEngine(), hardware, ColorFade(hardware.palette), rom)
        screen.init(GameOverScreen.State())

        assertNotNull(hardware.hblank.primary, "init installs primary HBlank handler")
        assertNotNull(hardware.hblank.active, "init activates HBlank handler")
        val active = hardware.hblank.active!!
        assertTrue(hardware.hblank.irqEnabled, "init enables HBlank IRQ")
        assertTrue(
            (hardware.lcd.dispstat and DISPSTAT_HBLANK_IRQ) != 0,
            "init sets DISPSTAT HBlank IRQ bit",
        )

        for (call in calls) {
            val vcount = u16(call.entryIo, REG_VCOUNT)
            val io = call.entryIo.copyOf()
            active.onHBlank(vcount, io)
            assertMatchesExit("active#${call.index}", call, io)
        }

        screen.end()
        assertNull(hardware.hblank.primary, "end clears primary HBlank handler")
        assertFalse(hardware.hblank.irqEnabled, "end disables HBlank IRQ")
        assertEquals(
            0,
            hardware.lcd.dispstat and DISPSTAT_HBLANK_IRQ,
            "end clears DISPSTAT HBlank IRQ bit",
        )
    }

    private class CallPair(
        val index: Int,
        val step: Int,
        val entryIo: ByteArray,
        val exitIo: ByteArray,
    )

    private companion object {
        const val RUN = "ram_trace_prologue_gameover_hblank"
        const val EXPECTED_CALLS = 912
        const val IO_SIZE = 0x60
        const val TOTAL_SCANLINES = 228
        const val ENTRY_PC = "0802127C"
        const val REG_DISPSTAT = 0x04
        const val REG_VCOUNT = 0x06
        const val REG_BLDCA = 0x52
        const val REG_BLDCB = 0x53
        const val DISPSTAT_HBLANK_IRQ = 0x10

        fun u16(bytes: ByteArray, at: Int): Int =
            (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
    }
}
