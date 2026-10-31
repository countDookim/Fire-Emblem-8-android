package gbafe.audio

/**
 * The channel part of `struct SoundInfo` shared by all players: [maxChans] DirectSound [chans]
 * and the four CGB channels [cgbChans] (`cgbChans`, null if no CGB voices are set up).
 */
public class M4aSoundInfo(
    /** `chans`: DirectSound channels (at least [maxChans]). */
    public val chans: Array<M4aChannel>,
    /** `cgbChans`: CGB channels 1..4, or null. */
    public val cgbChans: Array<M4aChannel>?,
) {
    /** `ident`: `ID_NUMBER` when idle, `ID_NUMBER + 1` while `SoundMain` runs; else locked. */
    public var ident: Int = M4aPlayer.ID_NUMBER

    /** `intp`: the first player `SoundMain` runs (`func` = `MPlayMain` when non-null). */
    public var intp: M4aPlayer? = null

    /** The players `SoundMain` runs, in order: [intp], then along [M4aPlayer.intp]. */
    public fun chain(): List<M4aPlayer> {
        val out = ArrayList<M4aPlayer>()
        var p = intp
        while (p != null) {
            out += p
            p = p.intp
        }
        return out
    }

    /**
     * `MPlayOpen`: clears [p], gives it [trackCount] (at most 16 and its track slots) cleared
     * tracks, pauses it and puts it at the head of the `SoundMain` chain ([intp]). Does nothing
     * if [trackCount] is 0 or this is locked ([ident] is not `ID_NUMBER`).
     *
     * Ported from src/m4a.c: MPlayOpen
     */
    public fun open(p: M4aPlayer, trackCount: Int) {
        var n = trackCount and 0xFF
        if (n == 0) return
        if (n > MAX_TRACKS) n = MAX_TRACKS
        require(n <= p.tracks.size) { "$n tracks, ${p.tracks.size} slots" }
        if (ident != M4aPlayer.ID_NUMBER) return
        p.clear64()
        p.trackCount = n
        p.status = M4aPlayer.STATUS_PAUSE
        for (i in 0 until n) p.tracks[i].flags = 0
        p.intp = intp // func != 0 exactly when intp is set
        intp = p
        p.ident = M4aPlayer.ID_NUMBER
    }

    /** `freq` (u8): the `SOUND_MODE_FREQ_*` index (1..12). */
    public var freq: Int = 0

    /**
     * `SampleFreqSet`: sets [freq], [pcmSamplesPerVBlank], [pcmDmaPeriod], [pcmFreq] and
     * [divFreq] from a `SOUND_MODE_FREQ_*` value ([mode] bits 16..19, 1..12). The timer 0 setup,
     * `m4aSoundVSyncOn` and the VCOUNT wait (hardware) are left to the port's audio output.
     *
     * Ported from src/m4a.c: SampleFreqSet
     */
    public fun sampleFreqSet(mode: Int) {
        freq = (mode and 0xF0000) shr 16
        pcmSamplesPerVBlank = SAMPLES_PER_VBLANK[freq - 1]
        pcmDmaPeriod = PCM_DMA_BUF_SIZE / pcmSamplesPerVBlank
        pcmFreq = (597275 * pcmSamplesPerVBlank + 5000) / 10000 // LCD 59.7275 Hz
        divFreq = (16777216 / pcmFreq + 1) shr 1 // CPU 16.78 MHz
    }

    /** `maxChans` (u8): DirectSound channels in use. */
    public var maxChans: Int = chans.size

    /** `reverb` (u8): 0 = off. */
    public var reverb: Int = 0

    /** `pcmDmaCounter` (u8): VBlanks left in the current PCM DMA period. */
    public var pcmDmaCounter: Int = 0

    /** `pcmDmaPeriod` (u8): VBlanks per PCM buffer. */
    public var pcmDmaPeriod: Int = 0

    /**
     * `m4aSoundVSync` (VBlank IRQ): counts [pcmDmaCounter] down; at 0 reloads it from
     * [pcmDmaPeriod] and returns true (the original restarts the sound FIFO DMAs at the buffer
     * start there; a port's audio output owns playback). Skipped (false) unless [ident] is
     * `ID_NUMBER` or `ID_NUMBER + 1`.
     *
     * Ported from src/m4a_1.s: m4aSoundVSync
     */
    public fun vSync(): Boolean {
        if ((ident - M4aPlayer.ID_NUMBER).toUInt() > 1u) return false
        pcmDmaCounter = (pcmDmaCounter - 1) and 0xFF
        if (pcmDmaCounter.toByte() > 0) return false
        pcmDmaCounter = pcmDmaPeriod
        return true
    }

    /** `pcmSamplesPerVBlank`. */
    public var pcmSamplesPerVBlank: Int = 0

    /** `pcmFreq`: mixer output rate in Hz. */
    public var pcmFreq: Int = 0

    /** `divFreq`: 2^24 / sample rate scale for `freq`. */
    public var divFreq: Int = 0

    /** `pcmBuffer`: right half (0..0x62F) and left half (0x630..), signed 8-bit samples. */
    public val pcmBuffer: ByteArray = ByteArray(2 * PCM_DMA_BUF_SIZE)

    /** `masterVolume` (u8, 0..15). */
    public var masterVolume: Int = 15

    /** `CgbOscOff(n)`: silences CGB channel n (1..4) in hardware; null = no hardware. */
    public var cgbOscOff: ((Int) -> Unit)? = null

    /** Constants. */
    public companion object {
        /** `PCM_DMA_BUF_SIZE`. */
        public const val PCM_DMA_BUF_SIZE: Int = 0x630

        /** `SOUND_MODE_FREQ_13379` (FE8's rate). */
        public const val FREQ_13379: Int = 0x40000

        /** `gPcmSamplesPerVBlankTable` (src/m4a_tables.c). */
        private val SAMPLES_PER_VBLANK = intArrayOf(
            96, 132, 176, 224, 264, 304, 352, 448, 528, 608, 672, 704,
        )

        /** `MAX_MUSICPLAYER_TRACKS`. */
        public const val MAX_TRACKS: Int = 16
    }
}
