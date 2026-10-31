package fe8.testing

import gbafe.audio.M4aPitch
import gbafe.core.VerifiesPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * m4a pitch math against the ROM and mGBA: the generated tables equal `gScaleTable` / `gFreqTable`,
 * and every active DirectSound channel in every reference RAM dump holds
 * `SoundChannel.freq == MidiKeyToFreq(wav, max(0, ky + track.keyM), track.pitM)` (as `MPlayMain`
 * and `ply_note` store it).
 */
class M4aPitchTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile =
        File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")

    @Test
    fun `generated scale and frequency tables equal the ROM`() {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        for (k in 0 until 180) {
            assertEquals(rom[SCALE_TABLE + k].toInt() and 0xFF, M4aPitch.scaleTableEntry(k), "key $k")
        }
        for (n in 0 until 12) assertEquals(u32(rom, FREQ_TABLE + 4 * n), M4aPitch.freqTableEntry(n), "n $n")
    }

    @Test
    @VerifiesPort("MidiKeyToFreq")
    fun `DirectSound channel frequencies match every reference RAM dump`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        val keys = HashSet<Int>()
        var checked = 0
        val dumps = ref.root.listFiles().orEmpty().filter { it.isDirectory }
            .flatMap { d -> d.listFiles().orEmpty().filter { it.name.endsWith(".iwram.bin") } }
        for (f in dumps) {
            val iw = f.readBytes()
            val ew = File(f.path.replace(".iwram.bin", ".ewram.bin")).readBytes()
            fun mem(a: Int): Pair<ByteArray, Int> = when (a ushr 24) {
                3 -> iw to a - IWRAM
                2 -> ew to a - EWRAM
                else -> rom to a - ROM
            }
            for (c in 0 until 12) {
                val o = SOUND_INFO + 0x50 + c * 0x40
                if (iw[o].toInt() == 0 || iw[o + 1].toInt() and 7 != 0) continue // off, or a CGB channel
                val track = u32(iw, o + 0x2C)
                if (track == 0) continue
                val (tb, to) = mem(track)
                val (wb, wo) = mem(u32(iw, o + 0x24))
                val key = maxOf(0, (iw[o + 8].toInt() and 0xFF) + tb[to + 8])
                val got = M4aPitch.midiKeyToFreq(u32(wb, wo + 4), key, tb[to + 9].toInt() and 0xFF)
                assertEquals(u32(iw, o + 0x20), got, "${f.path} channel $c")
                keys += key
                checked++
            }
        }
        assertTrue(checked > 1000 && keys.size > 20, "checked $checked channels, ${keys.size} keys")
    }

    @Test
    fun `keys above 178 clamp with full fine adjust`() {
        // Golden: key 178 / fine 255 is the cap for every larger key, whatever the fine adjust.
        val w = 0x3C000000
        assertEquals(M4aPitch.midiKeyToFreq(w, 178, 255), M4aPitch.midiKeyToFreq(w, 255, 0))
    }

    private fun u32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private companion object {
        const val ROM = 0x08000000
        const val IWRAM = 0x03000000
        const val EWRAM = 0x02000000
        const val SCALE_TABLE = 0x08207220 - ROM // gScaleTable
        const val FREQ_TABLE = 0x082072D4 - ROM // gFreqTable
        const val SOUND_INFO = 0x03005410 - IWRAM // gSoundInfo
    }
}
