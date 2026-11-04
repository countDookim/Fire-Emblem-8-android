package fe8.testing

import gba.audio.M4aBus
import gba.audio.M4aDriver
import gba.audio.PcmStream
import gba.testkit.ReferenceData
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** [M4aDriver] keeps a traced song playing: one full frame of samples per [M4aDriver.frame]. */
class M4aDriverTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    fun `driver mixes a traced song into the stream`() {
        val dump = File(ref.root, "ram_trace_opanim/r00600.iwram.bin")
        assumeTrue(ref.available && romFile.isFile && dump.isFile, "reference data not present")
        val rom = romFile.readBytes()
        val bus = M4aBus { x -> rom[x - 0x08000000].toInt() and 0xFF }
        val (_, info) = M4aRam.sound(dump.readBytes())
        val pcm = PcmStream(info.pcmFreq, 48000, capacity = 16384)
        val driver = M4aDriver(bus, info, info.chain(), pcm)
        assertEquals(9, driver.chain.size)
        val frames = 60
        repeat(frames) { driver.frame() }
        assertEquals(frames * info.pcmSamplesPerVBlank, pcm.queued)
        val out = ShortArray(2 * 4800)
        pcm.render(out, 4800)
        assertTrue(out.any { it.toInt() != 0 }, "silence")
    }
}
