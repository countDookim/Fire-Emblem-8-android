package fe8.testing

import gba.core.VerifiesPort
import gbafe.audio.M4aPitch
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * m4a pitch math against the ROM and mGBA: the generated tables equal `gScaleTable` / `gFreqTable`,
 * and every active DirectSound channel in every reference RAM dump holds
 * `SoundChannel.freq == MidiKeyToFreq(wav, max(0, ky + track.keyM), track.pitM)` (as `MPlayMain`
 * and `ply_note` store it); likewise `CgbChannel.fr` and `MidiKeyToCgbFreq` (the traces only hold
 * channels 1..3; the noise branch is a table lookup checked against the ROM).
 */
class M4aPitchTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    fun `generated scale and frequency tables equal the ROM`() {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        for (k in 0 until 180) {
            val v = rom[SCALE_TABLE + k].toInt() and 0xFF
            assertEquals(v, M4aPitch.scaleTableEntry(k), "key $k")
        }
        for (n in 0 until 12) {
            assertEquals(u32(rom, FREQ_TABLE + 4 * n), M4aPitch.freqTableEntry(n), "n $n")
        }
        for (k in 0 until 132) {
            val v = rom[CGB_SCALE_TABLE + k].toInt() and 0xFF
            assertEquals(v, M4aPitch.cgbScaleTableEntry(k), "cgb key $k")
        }
        for (n in 0 until 12) {
            val o = CGB_FREQ_TABLE + 2 * n
            val v = (rom[o].toInt() and 0xFF) or (rom[o + 1].toInt() shl 8)
            assertEquals(v, M4aPitch.cgbFreqTableEntry(n), "cgb n $n")
        }
        for (i in 0 until 60) {
            val v = rom[NOISE_TABLE + i].toInt() and 0xFF
            assertEquals(v, M4aPitch.noiseTableEntry(i), "noise $i")
        }
    }

    @Test
    @VerifiesPort("MidiKeyToFreq")
    fun `DirectSound channel frequencies match every reference RAM dump`() {
        assumeTrue(ref.available && romFile.isFile, MISSING)
        val rom = romFile.readBytes()
        val keys = HashSet<Int>()
        var checked = 0
        for (f in iwramDumps()) {
            val iw = f.readBytes()
            val ew = File(f.path.replace(".iwram.bin", ".ewram.bin")).readBytes()
            fun mem(a: Int): Pair<ByteArray, Int> = when (a ushr 24) {
                3 -> iw to a - IWRAM
                2 -> ew to a - EWRAM
                else -> rom to a - ROM
            }
            for (c in 0 until 12) {
                val o = SOUND_INFO + 0x50 + c * 0x40
                // Off, or a CGB channel.
                if (iw[o].toInt() == 0 || iw[o + 1].toInt() and 7 != 0) continue
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
    @VerifiesPort("MidiKeyToCgbFreq")
    fun `CGB channel frequencies match every reference RAM dump`() {
        assumeTrue(ref.available && romFile.isFile, MISSING)
        var checked = 0
        for (f in iwramDumps()) {
            val iw = f.readBytes()
            val ew = File(f.path.replace(".iwram.bin", ".ewram.bin")).readBytes()
            for (c in 0 until 4) {
                val o = CGB_CHANS + c * 0x40
                val track = u32(iw, o + 0x2C)
                if (iw[o].toInt() == 0 || track == 0) continue
                val (tb, to) = if (track ushr 24 == 3) iw to track - IWRAM else ew to track - EWRAM
                val key = maxOf(0, (iw[o + 8].toInt() and 0xFF) + tb[to + 8])
                val got = M4aPitch.midiKeyToCgbFreq(c + 1, key, tb[to + 9].toInt() and 0xFF)
                assertEquals(u32(iw, o + 0x20), got, "${f.path} CGB channel ${c + 1}")
                checked++
            }
        }
        assertTrue(checked > 100, "checked $checked CGB channels")
    }

    private fun iwramDumps(): List<File> = ref.root.listFiles().orEmpty().filter { it.isDirectory }
        .flatMap { d -> d.listFiles().orEmpty().filter { it.name.endsWith(".iwram.bin") } }

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
        const val MISSING = "ROM / mGBA reference data not present (run tools/setup.sh)"
        const val ROM = 0x08000000
        const val IWRAM = 0x03000000
        const val EWRAM = 0x02000000
        const val SCALE_TABLE = 0x08207220 - ROM // gScaleTable
        const val FREQ_TABLE = 0x082072D4 - ROM // gFreqTable
        const val CGB_SCALE_TABLE = 0x0820731C - ROM // gCgbScaleTable
        const val CGB_FREQ_TABLE = 0x082073A0 - ROM // gCgbFreqTable
        const val NOISE_TABLE = 0x082073B8 - ROM // gNoiseTable
        const val CGB_CHANS = 0x03006510 - IWRAM // gCgbChans
        const val SOUND_INFO = 0x03005410 - IWRAM // gSoundInfo
    }
}
