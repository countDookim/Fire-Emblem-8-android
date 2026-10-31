package gbafe.audio

import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * m4a (MusicPlayer2000) pitch math shared by every GBA FE game.
 *
 * The library's `gScaleTable` (key -> octave shift and semitone) and `gFreqTable` (2^31 * 2^(n/12))
 * are generated from their formulas; tests check both against the ROM.
 */
public object M4aPitch {
    /** `gScaleTable`: for key k, `(14 - k / 12) shl 4 or k % 12` (181 entries: key 180 is read). */
    private val scaleTable: IntArray = IntArray(SCALE_SIZE) { ((14 - it / 12) shl 4) or (it % 12) }

    /** `gFreqTable`: unsigned 32-bit 2^31 * 2^(n/12), n = 0..11, stored as Int bits. */
    private val freqTable: IntArray = IntArray(12) { 2.0.pow(31 + it / 12.0).roundToLong().toInt() }

    /**
     * Sample step of a DirectSound channel playing a wave whose `WaveData.freq` is [waveFreq] at
     * MIDI [key] (0..255) with fine pitch [fineAdjust] (0..255, 1/256 semitone). Returns the
     * unsigned 32-bit result as Int bits (`SoundChannel.freq`).
     *
     * Ported from src/m4a.c: MidiKeyToFreq
     */
    public fun midiKeyToFreq(waveFreq: Int, key: Int, fineAdjust: Int): Int {
        var k = key and 0xFF
        var fine = (fineAdjust and 0xFF).toLong() shl 24
        if (k > MAX_KEY) {
            k = MAX_KEY
            fine = 0xFFL shl 24
        }
        val v1 = scaled(k)
        val v2 = scaled(k + 1)
        val step = (v1 + umulHigh((v2 - v1) and MASK, fine)) and MASK
        return umulHigh(waveFreq.toLong() and MASK, step).toInt()
    }

    /**
     * Frequency register value of CGB channel [chanNum] (1..3 tone/wave: `NRx3/NRx4` period + 2048;
     * 4 noise: `NR43` polynomial) at MIDI [key] (0..255) with fine pitch [fineAdjust] (0..255).
     *
     * Ported from src/m4a.c: MidiKeyToCgbFreq
     */
    public fun midiKeyToCgbFreq(chanNum: Int, key: Int, fineAdjust: Int): Int {
        var k = key and 0xFF
        if ((chanNum and 0xFF) == 4) {
            k = if (k <= 20) 0 else minOf(k - 21, 59)
            return noiseTableEntry(k)
        }
        var fine = fineAdjust and 0xFF
        if (k <= 35) {
            fine = 0
            k = 0
        } else {
            k -= 36
            if (k > 130) {
                k = 130
                fine = 255
            }
        }
        val v1 = cgbScaled(k)
        val v2 = cgbScaled(k + 1)
        return v1 + ((fine * (v2 - v1)) shr 8) + 2048
    }

    /** Entry [index] (0..131) of `gCgbScaleTable`: `(k / 12) shl 4 or k % 12`. */
    public fun cgbScaleTableEntry(index: Int): Int = ((index / 12) shl 4) or (index % 12)

    /** Entry [note] (0..11) of `gCgbFreqTable` (signed). */
    public fun cgbFreqTableEntry(note: Int): Int = CGB_FREQ[note]

    /** Entry [index] (0..59) of `gNoiseTable` (`NR43`: shift 13..0 x ratio 7..4, then 3..0). */
    public fun noiseTableEntry(index: Int): Int =
        if (index < 56) ((13 - index / 4) shl 4) or (7 - index % 4) else 59 - index

    private fun cgbScaled(key: Int): Int {
        val s = cgbScaleTableEntry(key)
        return CGB_FREQ[s and 0xF] shr (s shr 4)
    }

    /** Entry [index] (0..180) of `gScaleTable`. */
    public fun scaleTableEntry(index: Int): Int = scaleTable[index]

    /** Entry [note] (0..11) of `gFreqTable`, as unsigned 32-bit Int bits. */
    public fun freqTableEntry(note: Int): Int = freqTable[note]

    private fun scaled(key: Int): Long {
        val s = scaleTable[key]
        return (freqTable[s and 0xF].toLong() and MASK) ushr (s shr 4)
    }

    /** `umul3232H32`: high 32 bits of the unsigned 64-bit product of two 32-bit values. */
    private fun umulHigh(a: Long, b: Long): Long {
        // a, b < 2^32: split b to keep the product within 64 unsigned bits.
        val lo = a * (b and 0xFFFF)
        val hi = a * (b ushr 16)
        return ((hi + (lo ushr 16)) ushr 16) and MASK
    }

    /** `gCgbFreqTable` (m4a library constants; checked against the ROM). */
    private val CGB_FREQ =
        intArrayOf(
            -2004, -1891, -1785, -1685, -1591, -1501, -1417, -1337, -1262, -1192, -1125, -1062,
        )

    private const val MAX_KEY = 178
    private const val SCALE_SIZE = 181
    private const val MASK = 0xFFFFFFFFL
}
