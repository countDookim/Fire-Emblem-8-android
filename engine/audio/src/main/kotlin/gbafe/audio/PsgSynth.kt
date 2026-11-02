package gbafe.audio

/**
 * Plays the PSG registers of [psg] (written by `CgbSound`): square 1/2 (duty, envelope, length),
 * wave (32 4-bit samples, volume shift) and noise (7/15-bit LFSR, envelope), panned by NR51.
 * Square 1 has the frequency sweep (NR10); NR50 master volume and the SOUNDCNT_H PSG ratio
 * scale the mix. Output is box-filtered per sample (band-limited
 * edges, no aliasing whine at the 13379 Hz mixer rate). Not a port (hardware behaviour, GBATEK). Nothing allocates per call.
 */
public class PsgSynth(
    /** Register file to play. */
    public val psg: M4aPsg,
    /** Output sample rate in Hz. */
    public val sampleRate: Int,
) {
    private val on = BooleanArray(4)
    private val phase = DoubleArray(4)
    private val volume = IntArray(4)
    private val envTimer = IntArray(4)
    private val length = IntArray(4)
    private var lfsr = 0x7FFF
    private var seqPhase = 0.0
    private var shadowFreq = 0
    private var sweepTimer = 0
    private var sweepOn = false

    /** Channel [ch] (1..4) is currently sounding. */
    public fun isOn(ch: Int): Boolean = on[ch - 1]

    /** Current envelope volume (0..15) of channel [ch] (1..4). */
    public fun volumeOf(ch: Int): Int = volume[ch - 1]

    /** Channels mixed into the output (bit 0 = square 1 .. bit 3 = noise); others still run. */
    public var channelMask: Int = 0xF

    /**
     * Mixes [frames] stereo frames into [out] (interleaved left/right, added, not cleared) at
     * [gain] per unit of PSG level (one channel at volume 15 = 15 * [gain]).
     */
    public fun render(out: FloatArray, frames: Int, gain: Float) {
        val loads = psg.takeLengthLoads()
        for (c in 0 until 4) {
            if (loads ushr c and 1 == 0) continue
            val v = psg.read(NRX1[c])
            length[c] = if (c == 2) 256 - v else 64 - (v and 0x3F)
        }
        val t = psg.takeTriggers()
        for (c in 0 until 4) if (t ushr c and 1 != 0) trigger(c)
        if (psg.read(0x04000070) and 0x80 == 0) on[2] = false // NR30 DAC off
        val nr51 = psg.read(M4aPsg.NR51)
        val nr50 = psg.read(M4aPsg.NR50)
        val ratio = RATIO[psg.read(M4aPsg.SOUNDCNT_H) and 3]
        val gainL = gain * ratio * (((nr50 ushr 4) and 7) + 1) / 8f
        val gainR = gain * ratio * ((nr50 and 7) + 1) / 8f
        for (i in 0 until frames) {
            seqPhase += SEQUENCER_HZ / sampleRate
            while (seqPhase >= 1.0) {
                seqPhase -= 1.0
                sequencerStep()
            }
            var l = 0f
            var r = 0f
            for (c in 0 until 4) {
                if (!on[c]) continue
                val v = sample(c)
                if (channelMask ushr c and 1 == 0) continue
                if (nr51 ushr (4 + c) and 1 != 0) l += v
                if (nr51 ushr c and 1 != 0) r += v
            }
            out[2 * i] += l * gainL
            out[2 * i + 1] += r * gainR
        }
    }

    private var seqStep = 0

    private fun sequencerStep() {
        seqStep = (seqStep + 1) and 7
        if (seqStep == 2 || seqStep == 6) sweepStep()
        if (seqStep and 1 == 0) {
            for (c in 0 until 4) {
                if (!on[c] || length[c] <= 0 || n4(c) and 0x40 == 0) continue
                if (--length[c] == 0) on[c] = false
            }
        }
        if (seqStep == 7) {
            for (c in ENVELOPE_CHANNELS) {
                val nrx2 = psg.read(NRX2[c])
                val period = nrx2 and 7
                if (!on[c] || period == 0) continue
                if (--envTimer[c] > 0) continue
                envTimer[c] = period
                if (nrx2 and 8 != 0) {
                    if (volume[c] < 15) volume[c]++
                } else if (volume[c] > 0) {
                    volume[c]--
                }
            }
        }
    }

    private fun n4(c: Int): Int = psg.read(NRX4[c])

    private fun freqReg(c: Int): Int = psg.read(NRX3[c]) or ((n4(c) and 7) shl 8)

    private fun trigger(c: Int) {
        on[c] = true
        phase[c] = 0.0
        if (c == 2) {
            on[c] = psg.read(0x04000070) and 0x80 != 0
            if (length[c] == 0) length[c] = 256
            return
        }
        val nrx2 = psg.read(NRX2[c])
        volume[c] = nrx2 ushr 4
        envTimer[c] = nrx2 and 7
        if (length[c] == 0) length[c] = 64
        if (nrx2 and 0xF8 == 0) on[c] = false // DAC off
        if (c == 3) lfsr = 0x7FFF
        if (c == 0) {
            val nr10 = psg.read(NR10)
            shadowFreq = freqReg(0)
            sweepTimer = ((nr10 ushr 4) and 7).let { if (it == 0) 8 else it }
            sweepOn = nr10 and 0x77 != 0
            if (nr10 and 7 != 0 && sweepTarget(nr10) > 2047) on[0] = false
        }
    }

    private fun sweepTarget(nr10: Int): Int {
        val d = shadowFreq ushr (nr10 and 7)
        return if (nr10 and 8 != 0) shadowFreq - d else shadowFreq + d
    }

    /** 128 Hz sweep clock of square 1: new frequency written back to NR13/NR14. */
    private fun sweepStep() {
        if (!on[0] || !sweepOn || --sweepTimer > 0) return
        val nr10 = psg.read(NR10)
        val period = (nr10 ushr 4) and 7
        sweepTimer = if (period == 0) 8 else period
        if (period == 0) return
        val f = sweepTarget(nr10)
        if (f > 2047) {
            on[0] = false
            return
        }
        if (nr10 and 7 == 0) return
        shadowFreq = f
        psg.regs[NRX3[0] - M4aPsg.BASE] = f and 0xFF
        val i = NRX4[0] - M4aPsg.BASE
        psg.regs[i] = (psg.regs[i] and 0xF8) or (f ushr 8)
        if (sweepTarget(nr10) > 2047) on[0] = false
    }

    /**
     * Channel [c]'s mean level over the next output sample (box filter): edges between samples
     * give fractional levels instead of aliasing at the low mixer rate.
     */
    private fun sample(c: Int): Float {
        when (c) {
            0, 1 -> {
                val dp = 131072.0 / (2048 - freqReg(c)) / sampleRate
                val duty = DUTY[(psg.read(NRX1[c]) ushr 6) and 3]
                val a = phase[c]
                val b = a + dp
                phase[c] = b - b.toInt()
                val vol = volume[c].toFloat()
                if (b < 1.0 && (b <= duty || a >= duty)) return if (a < duty) vol else -vol
                val high = highTime(b, duty) - highTime(a, duty)
                return vol * (2.0 * high / dp - 1.0).toFloat()
            }
            2 -> {
                val shift = WAVE_SHIFT[(psg.read(0x04000073) ushr 5) and 3]
                if (shift < 0) {
                    phase[c] = 0.0
                    return 0f
                }
                val dx = 65536.0 * 32 / (2048 - freqReg(c)) / sampleRate
                val a = phase[c] * 32
                val b = a + dx
                val bw = b % 32
                phase[c] = bw / 32
                if (a.toInt() == b.toInt()) return waveLevel(a.toInt(), shift)
                val total = waveArea(32, shift)
                val area = (b - bw) / 32 * total + waveArea(bw, shift) - waveArea(a, shift)
                return (area / dx).toFloat()
            }
            else -> {
                val nr43 = psg.read(0x0400007C)
                val r = (nr43 and 7).let { if (it == 0) 0.5 else it.toDouble() }
                val dp = 524288.0 / r / (1 shl ((nr43 ushr 4) + 1)) / sampleRate
                val vol = volume[c].toFloat()
                var level = if (lfsr and 1 == 0) vol else -vol
                var t = phase[c]
                var end = t + dp
                if (end < 1.0) {
                    phase[c] = end
                    return level
                }
                var acc = 0.0
                while (end >= 1.0) {
                    acc += (1.0 - t) * level
                    val bit = (lfsr xor (lfsr ushr 1)) and 1
                    lfsr = (lfsr ushr 1) or (bit shl 14)
                    if (nr43 and 8 != 0) lfsr = (lfsr and 0x40.inv()) or (bit shl 6)
                    level = if (lfsr and 1 == 0) vol else -vol
                    t = 0.0
                    end -= 1.0
                }
                acc += (end - t) * level
                phase[c] = end
                return (acc / dp).toFloat()
            }
        }
    }

    /** Wave sample [pos] (0..31) as a centred level. */
    private fun waveLevel(pos: Int, shift: Int): Float {
        val b = psg.read(M4aPsg.WAVE_RAM + pos / 2)
        val s = if (pos and 1 == 0) b ushr 4 else b and 0x0F
        return ((s shr shift) * 2 - (15 shr shift)) / 2f
    }

    /** Integral of the wave level from sample position 0 to [x] (0..32). */
    private fun waveArea(x: Double, shift: Int): Double {
        val n = x.toInt()
        var sum = 0.0
        for (i in 0 until n) sum += waveLevel(i, shift)
        if (n < 32) sum += (x - n) * waveLevel(n, shift)
        return sum
    }

    private fun waveArea(n: Int, shift: Int): Double = waveArea(n.toDouble(), shift)

    private companion object {
        /** Time spent high by a square of [duty] from phase 0 to [x] (in periods). */
        fun highTime(x: Double, duty: Double): Double {
            val n = x.toInt()
            return n * duty + minOf(x - n, duty)
        }


        const val SEQUENCER_HZ = 512.0
        const val NR10 = 0x04000060
        val RATIO = floatArrayOf(0.25f, 0.5f, 1f, 0f)
        val ENVELOPE_CHANNELS = intArrayOf(0, 1, 3)
        val DUTY = doubleArrayOf(0.125, 0.25, 0.5, 0.75)
        val WAVE_SHIFT = intArrayOf(-1, 0, 1, 2)
        val NRX1 = intArrayOf(0x04000062, 0x04000068, 0x04000072, 0x04000078)
        val NRX2 = intArrayOf(0x04000063, 0x04000069, 0x04000073, 0x04000079)
        val NRX3 = intArrayOf(0x04000064, 0x0400006C, 0x04000074, 0x0400007C)
        val NRX4 = intArrayOf(0x04000065, 0x0400006D, 0x04000075, 0x0400007D)
    }
}
