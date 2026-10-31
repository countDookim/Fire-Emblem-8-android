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
     * Sample step of a DirectSound channel playing a wave whose `WaveData.freq` is [waveFreq] at MIDI
     * [key] (0..255) with fine pitch [fineAdjust] (0..255, 1/256 semitone). Returns the unsigned
     * 32-bit result as Int bits (`SoundChannel.freq`).
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
        return umulHigh(waveFreq.toLong() and MASK, (v1 + umulHigh((v2 - v1) and MASK, fine)) and MASK)
            .toInt()
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

    private const val MAX_KEY = 178
    private const val SCALE_SIZE = 181
    private const val MASK = 0xFFFFFFFFL
}
