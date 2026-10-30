package gbafe.audio

/** Producer of mixed PCM pulled by an audio thread (the m4a mixer later; [ToneSource] for tests). */
public fun interface AudioSource {
    /** Fills [frames] interleaved stereo 16-bit frames into [buffer]. Must not allocate. */
    public fun render(buffer: ShortArray, frames: Int)
}

/**
 * Square-wave test tone (M3-12 manual test): [frequency] Hz at [sampleRate], amplitude [amplitude], both
 * channels. Uses a 32-bit phase accumulator so the pitch has no drift; [render] allocates nothing.
 */
public class ToneSource(
    /** Output sample rate in Hz. */
    public val sampleRate: Int,
    /** Tone frequency in Hz. */
    public val frequency: Int = 440,
    /** Peak sample value (0..32767). */
    public val amplitude: Int = 4000,
) : AudioSource {
    private val step = ((frequency.toLong() shl 32) / sampleRate).toInt()
    private var phase = 0

    override fun render(buffer: ShortArray, frames: Int) {
        for (i in 0 until frames) {
            val v = (if (phase < 0) -amplitude else amplitude).toShort()
            buffer[2 * i] = v
            buffer[2 * i + 1] = v
            phase += step
        }
    }
}
