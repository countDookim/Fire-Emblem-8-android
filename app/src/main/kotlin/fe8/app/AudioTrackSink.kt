/**
 * How the game's sound reaches the speaker: an [AudioTrack] output ([AudioTrackSink]) and the
 * thread that keeps it fed ([AudioThread]). Started on Play, paused with the activity.
 */

package fe8.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.os.Trace
import gba.audio.AudioSink
import gba.audio.AudioSource
import gba.audio.PcmStream
import io.github.countdookim.fe8.BuildConfig

/**
 * The speaker output: a streaming 16-bit stereo [AudioTrack] in low-latency mode, with a buffer of
 * twice the device's reported minimum (not the smallest possible; see the `init` block).
 *
 * [write] is a blocking write, so the mixer thread is paced by the track draining at real-time
 * speed (Android behaviour, not checked here). The game's mixer runs at 13379 Hz
 * (`src/m4a.c:74`); `PcmStream` resamples it to [sampleRate].
 *
 * @property sampleRate Output rate in Hz, [DEFAULT_RATE] unless the caller picks another.
 */
internal class AudioTrackSink(override val sampleRate: Int = DEFAULT_RATE) : AudioSink {
    /**
     * Frames the mixer thread renders and writes per round: half the device's minimum buffer
     * (`minBytes / 4 bytes per frame / 2`), but at least [MIN_CHUNK_FRAMES].
     */
    val chunkFrames: Int

    private val track: AudioTrack

    // Track buffer = max(device minimum, one chunk) x 2. Since a chunk is half the minimum, that
    // is normally exactly twice the minimum (more only if the 64-frame floor exceeds it).
    init {
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        chunkFrames = maxOf(MIN_CHUNK_FRAMES, minBytes / BYTES_PER_FRAME / AUDIO_BUFFER_MULTIPLIER)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBytes, chunkFrames * BYTES_PER_FRAME) * AUDIO_BUFFER_MULTIPLIER)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    /**
     * Writes [frames] stereo sample frames from [buffer] into the audio track, blocking until the
     * track has taken them all (`WRITE_BLOCKING`).
     *
     * @param buffer Interleaved 16-bit stereo PCM samples (L, R, L, R...).
     * @param frames Number of stereo frames to write (length in shorts is `frames * CHANNELS_STEREO`).
     */
    override fun write(buffer: ShortArray, frames: Int) {
        track.write(buffer, 0, frames * CHANNELS_STEREO, AudioTrack.WRITE_BLOCKING)
    }

    /** Times the track ran out of data since creation (debug trace only). */
    val underrunCount: Int get() = track.underrunCount

    /** Starts (or resumes) playback; the track then drains what has been written. */
    fun play() = track.play()

    /** Pauses playback and discards queued samples, so resuming starts from fresh audio. */
    fun stop() {
        track.pause()
        track.flush()
    }

    /** Releases the native audio track resources; the sink is unusable afterwards. */
    fun release() = track.release()

    companion object {
        /** Bytes per individual 16-bit audio sample. */
        private const val BYTES_PER_SAMPLE: Int = 2

        /** Stereo channels (left and right). */
        private const val CHANNELS_STEREO: Int = 2

        /** Total bytes per stereo frame (`CHANNELS_STEREO * BYTES_PER_SAMPLE` = 4). */
        private const val BYTES_PER_FRAME: Int = CHANNELS_STEREO * BYTES_PER_SAMPLE

        /** Minimum number of frames per audio chunk. */
        private const val MIN_CHUNK_FRAMES: Int = 64

        /** Track buffer size as a multiple of the device minimum. */
        private const val AUDIO_BUFFER_MULTIPLIER: Int = 2

        /**
         * Output rate the m4a mixer's PCM is resampled to. 48 kHz is chosen as a common device
         * rate so the system need not resample again (Android behaviour, not checked here).
         */
        const val DEFAULT_RATE: Int = 48_000
    }
}

/**
 * The audio thread: repeatedly asks [source] for one chunk of sound ([AudioTrackSink.chunkFrames]
 * frames) and writes it to [sink], at `THREAD_PRIORITY_URGENT_AUDIO`, until [stop]. Reuses one
 * buffer, so the loop allocates nothing. [start] and [stop] follow the activity's resume and pause.
 *
 * NOTE: [stop] gives up waiting after [THREAD_JOIN_TIMEOUT_MS] and clears its thread handle. If
 * the thread were still inside a blocked write then, a quick [start] would run a second thread
 * beside it. Not seen in practice here; Android behaviour, not checked.
 *
 * @property source Where the sound comes from (the game's `PcmStream`).
 * @property sink The speaker output.
 */
internal class AudioThread(private val source: AudioSource, private val sink: AudioTrackSink) {
    /** True from [start] until [stop]; the loop exits when it turns false. */
    @Volatile private var running = false
    private var thread: Thread? = null

    /** Starts the speaker and the mixing thread (no-op if already running). */
    fun start() {
        if (running) return
        running = true
        sink.play()
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = ShortArray(sink.chunkFrames * 2) // 2 = stereo samples per frame
            while (running) {
                if (BuildConfig.DEBUG) Trace.beginSection("FE8 audio render")
                try {
                    source.render(buffer, sink.chunkFrames)
                } finally {
                    if (BuildConfig.DEBUG) Trace.endSection()
                }
                // Debug-only trace counters for audio glitches (setCounter needs API 29; Android
                // behaviour, not checked here).
                if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 29) {
                    val pcm = source as? PcmStream
                    if (pcm != null) {
                        Trace.setCounter("FE8 PCM queued input frames", pcm.queued.toLong())
                        Trace.setCounter("FE8 PCM output frames", pcm.outputFrames)
                        Trace.setCounter("FE8 PCM prefill silence frames", pcm.prefillSilenceFrames)
                        Trace.setCounter("FE8 PCM underrun frames", pcm.underrunFrames)
                    }
                    Trace.setCounter("FE8 AudioTrack underruns", sink.underrunCount.toLong())
                }
                if (BuildConfig.DEBUG) Trace.beginSection("FE8 audio write")
                try {
                    sink.write(buffer, sink.chunkFrames)
                } finally {
                    if (BuildConfig.DEBUG) Trace.endSection()
                }
            }
        }, "fe8-audio").also { it.start() }
    }

    /**
     * Stops the mixing thread, waiting up to [THREAD_JOIN_TIMEOUT_MS] for it, then pauses and
     * flushes the speaker (also when the wait timed out). No-op if not running.
     */
    fun stop() {
        if (!running) return
        running = false
        // Expected to return promptly: a playing track frees room for the pending blocking write.
        thread?.join(THREAD_JOIN_TIMEOUT_MS)
        thread = null
        sink.stop()
    }

    private companion object {
        /** Longest wait in milliseconds for the mixing thread to finish in [stop]. */
        private const val THREAD_JOIN_TIMEOUT_MS: Long = 500L
    }
}
