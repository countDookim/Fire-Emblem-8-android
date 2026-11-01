package gbafe.audio

/**
 * Hands the m4a mixer's per-frame PCM to an audio thread: the game thread [push]es each frame's
 * `pcmSamplesPerVBlank` stereo samples (signed 8-bit, at [inputRate] Hz); the audio thread
 * [render]s them at [outputRate] Hz with linear interpolation. Single producer, single consumer;
 * an underrun repeats the last sample. Not a port (app plumbing); allocates only in the constructor.
 */
public class PcmStream(
    /** Mixer rate in Hz (`SoundInfo.pcmFreq`). */
    public val inputRate: Int,
    /** Device rate in Hz. */
    public val outputRate: Int,
    /** Ring size in input frames (power of two). */
    capacity: Int = 8192,
) : AudioSource {
    private val mask = capacity - 1
    private val ring = ShortArray(2 * capacity)
    private val step = ((inputRate.toLong() shl 16) / outputRate).toInt()

    @Volatile private var written = 0L

    @Volatile private var read = 0L
    private var frac = 0

    init {
        require(capacity > 0 && capacity and mask == 0) { "capacity must be a power of two" }
    }

    /** Input frames queued and not yet consumed. */
    public val queued: Int get() = (written - read).toInt()

    /** PSG channels mixed into each pushed frame (rendered at `pcmFreq`); null: DirectSound only. */
    public var psg: PsgSynth? = null

    private val psgOut = FloatArray(2 * MAX_SAMPLES_PER_VBLANK)

    /** Queues this frame's samples of [info] (call right after [M4aMixer.frame]). */
    public fun push(info: M4aSoundInfo) {
        val n = M4aSoundInfo.PCM_DMA_BUF_SIZE
        val r7 = info.pcmDmaCounter - 1
        val at = if (r7 == 0) 0 else (info.pcmDmaPeriod - r7) * info.pcmSamplesPerVBlank
        val count = minOf(info.pcmSamplesPerVBlank, MAX_SAMPLES_PER_VBLANK)
        val synth = psg
        psgOut.fill(0f, 0, 2 * count)
        synth?.render(psgOut, count, PSG_GAIN)
        var w = written
        for (i in 0 until count) {
            if (w - read > mask) break // full: drop the rest of this frame
            val o = 2 * (w and mask.toLong()).toInt()
            val l = (info.pcmBuffer[at + i + n].toInt() shl 8) + psgOut[2 * i].toInt()
            val r = (info.pcmBuffer[at + i].toInt() shl 8) + psgOut[2 * i + 1].toInt()
            ring[o] = l.coerceIn(-32768, 32767).toShort()
            ring[o + 1] = r.coerceIn(-32768, 32767).toShort()
            w++
        }
        written = w
    }

    override fun render(buffer: ShortArray, frames: Int) {
        var r = read
        for (i in 0 until frames) {
            val avail = written - r
            val a = 2 * (r and mask.toLong()).toInt()
            val b = if (avail >= 2) 2 * ((r + 1) and mask.toLong()).toInt() else a
            if (avail <= 0) {
                val last = 2 * ((r - 1) and mask.toLong()).toInt()
                buffer[2 * i] = ring[last]
                buffer[2 * i + 1] = ring[last + 1]
                continue
            }
            buffer[2 * i] = lerp(ring[a], ring[b])
            buffer[2 * i + 1] = lerp(ring[a + 1], ring[b + 1])
            frac += step
            val adv = frac ushr 16
            frac = frac and 0xFFFF
            r += minOf(adv.toLong(), avail)
        }
        read = r
    }

    private fun lerp(x: Short, y: Short): Short = (x + (((y - x) * frac) shr 16)).toShort()

    private companion object {
        /** Upper bound of `pcmSamplesPerVBlank` (42048 Hz mode: 701). */
        const val MAX_SAMPLES_PER_VBLANK = 704

        /**
         * Output units per PSG level step: one channel at volume 15 is about a quarter of full-scale
         * DirectSound (NR50 7/7, SOUNDCNT_H PSG 100%). An approximation, not measured.
         */
        const val PSG_GAIN = 512f
    }
}
