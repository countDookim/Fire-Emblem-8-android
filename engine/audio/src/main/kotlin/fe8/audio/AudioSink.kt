package fe8.audio

/**
 * Destination for mixed PCM. `:app` implements it with a low-latency `AudioTrack`; tests implement it
 * with an in-memory buffer so the mixer (a reimplementation of the m4a/MP2K engine, milestone 3) can be
 * verified on the JVM.
 */
public interface AudioSink {
    /** Output sample rate in Hz. */
    public val sampleRate: Int

    /** Writes [frames] interleaved stereo 16-bit frames from [buffer]. Must not allocate. */
    public fun write(buffer: ShortArray, frames: Int)
}
