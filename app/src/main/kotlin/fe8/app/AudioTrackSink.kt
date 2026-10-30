package fe8.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import gbafe.audio.AudioSink
import gbafe.audio.AudioSource

/**
 * Low-latency [AudioSink] (M3-12): a streaming 16-bit stereo `AudioTrack` with
 * `PERFORMANCE_MODE_LOW_LATENCY` and the smallest buffer the device allows (twice its minimum).
 * [write] blocks until the samples are queued, which paces the mixer thread.
 */
internal class AudioTrackSink(override val sampleRate: Int = DEFAULT_RATE) : AudioSink {
    /** Frames per [write] chunk: the device minimum buffer split in halves (at least 64 frames). */
    val chunkFrames: Int

    private val track: AudioTrack

    init {
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT)
        chunkFrames = maxOf(64, minBytes / 4 / 2)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build())
            .setBufferSizeInBytes(maxOf(minBytes, chunkFrames * 4) * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    override fun write(buffer: ShortArray, frames: Int) {
        track.write(buffer, 0, frames * 2, AudioTrack.WRITE_BLOCKING)
    }

    /** Starts playback. */
    fun play() = track.play()

    /** Stops playback and drops queued samples. */
    fun stop() {
        track.pause()
        track.flush()
    }

    /** Frees the native track; the sink is unusable afterwards. */
    fun release() = track.release()

    companion object {
        /** GBA m4a output is resampled to this rate (common native rate: no system resampling). */
        const val DEFAULT_RATE: Int = 48_000
    }
}

/**
 * Mixer thread: pulls [source] in [AudioTrackSink.chunkFrames] chunks into one pre-allocated buffer and
 * writes them to [sink] at audio priority, until [stop]. No per-buffer allocation.
 */
internal class AudioThread(private val source: AudioSource, private val sink: AudioTrackSink) {
    @Volatile private var running = false
    private var thread: Thread? = null

    /** Starts mixing (no-op if running). */
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

    /** Stops mixing and waits for the thread to end. */
    fun stop() {
        if (!running) return
        running = false
        thread?.join(500) // a playing track drains the current chunk, so this returns promptly
        thread = null
        sink.stop()
    }
}
