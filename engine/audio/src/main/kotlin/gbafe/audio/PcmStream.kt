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

    /** Queues this frame's samples of [info] (call right after [M4aMixer.frame]). */
    public fun push(info: M4aSoundInfo) {
        val n = M4aSoundInfo.PCM_DMA_BUF_SIZE
        val r7 = info.pcmDmaCounter - 1
        val at = if (r7 == 0) 0 else (info.pcmDmaPeriod - r7) * info.pcmSamplesPerVBlank
        var w = written
        for (i in 0 until info.pcmSamplesPerVBlank) {
            if (w - read > mask) break // full: drop the rest of this frame
            val o = 2 * (w and mask.toLong()).toInt()
            ring[o] = (info.pcmBuffer[at + i + n].toInt() shl 8).toShort() // left
            ring[o + 1] = (info.pcmBuffer[at + i].toInt() shl 8).toShort() // right
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
}
