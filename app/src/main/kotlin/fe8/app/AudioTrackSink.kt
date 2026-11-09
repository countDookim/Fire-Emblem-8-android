/**
 * Low-latency Android audio playback sink and background mixer thread.
 *
 * Implements [gba.audio.AudioSink] via Android's [AudioTrack] API in streaming mode,
 * delivering 16-bit stereo PCM audio with minimal buffering latency to keep sound effects
 * and music closely synchronized with the game loop.
 */

package fe8.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import gba.audio.AudioSink
import gba.audio.AudioSource

/**
 * Low-latency [AudioSink] (M3-12): a streaming 16-bit stereo [AudioTrack] with
 * `PERFORMANCE_MODE_LOW_LATENCY` and the smallest buffer the device allows (twice its minimum).
 *
 * [write] blocks until the samples are queued in the native HAL ring buffer, which naturally
 * paces the mixer thread to real-time audio consumption.
 *
 * @property sampleRate Playback sample rate in Hz (defaults to [DEFAULT_RATE] = 48,000 Hz).
 */
internal class AudioTrackSink(override val sampleRate: Int = DEFAULT_RATE) : AudioSink {
    /** Frames per [write] chunk: the device minimum buffer split in halves (at least [MIN_CHUNK_FRAMES] frames). */
    val chunkFrames: Int

    private val track: AudioTrack

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
     * Writes [frames] stereo sample frames from [buffer] into the audio track.
     * Blocks until the samples are enqueued in the native audio buffer.
     *
     * @param buffer Interleaved 16-bit stereo PCM samples (L, R, L, R...).
     * @param frames Number of stereo frames to write (length in shorts is `frames * CHANNELS_STEREO`).
     */
    override fun write(buffer: ShortArray, frames: Int) {
        track.write(buffer, 0, frames * CHANNELS_STEREO, AudioTrack.WRITE_BLOCKING)
    }

    /** Starts audio track playback. */
    fun play() = track.play()

    /** Stops playback and flushes any queued pending audio samples. */
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

        /** Buffer size multiplier used for double-buffering. */
        private const val AUDIO_BUFFER_MULTIPLIER: Int = 2

        /** GBA m4a output is resampled to this rate (common native rate: no system resampling). */
        const val DEFAULT_RATE: Int = 48_000
    }
}

/**
 * Dedicated mixer thread: pulls samples from [source] in [AudioTrackSink.chunkFrames] chunks
 * into a single pre-allocated buffer and writes them to [sink] at `THREAD_PRIORITY_URGENT_AUDIO`.
 * Runs until [stop] is called; performs no per-buffer memory allocations during mixing.
 *
 * @property source Audio provider rendering mixed PCM audio frames.
 * @property sink Target audio track playing samples to device hardware.
 */
internal class AudioThread(private val source: AudioSource, private val sink: AudioTrackSink) {
    @Volatile private var running = false
    private var thread: Thread? = null

    /** Starts the audio mixing thread (no-op if already running). */
    fun start() {
        if (running) return
        running = true
        sink.play()
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = ShortArray(sink.chunkFrames * 2)
            while (running) {
                source.render(buffer, sink.chunkFrames)
                sink.write(buffer, sink.chunkFrames)
            }
        }, "fe8-audio").also { it.start() }
    }

    /**
     * Stops the audio mixing thread and waits up to [THREAD_JOIN_TIMEOUT_MS] for completion.
     * The sink is stopped and flushed once the thread terminates.
     */
    fun stop() {
        if (!running) return
        running = false
        thread?.join(THREAD_JOIN_TIMEOUT_MS) // a playing track drains the current chunk, so this returns promptly
        thread = null
        sink.stop()
    }

    private companion object {
        /** Maximum time in milliseconds to wait for the mixer thread to join on shutdown. */
        private const val THREAD_JOIN_TIMEOUT_MS: Long = 500L
    }
}
