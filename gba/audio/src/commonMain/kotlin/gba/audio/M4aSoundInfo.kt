package gba.audio

/**
 * The sound state shared by all music players (`struct SoundInfo`, `gSoundInfo`): the
 * DirectSound [chans] (of which [maxChans] are used), the four CGB channels [cgbChans], the
 * mixer settings (reverb, master volume, sample rate) and the PCM mixer buffer [pcmBuffer].
 * Only the state ported so far is modelled.
 */
public class M4aSoundInfo(
    /** `chans`: DirectSound channels (at least [maxChans]). */
    public val chans: Array<M4aChannel>,
    /** `cgbChans`: CGB channels 1..4, or null. */
    public val cgbChans: Array<M4aChannel>?,
) {
    /**
     * `ident`: the lock word. `ID_NUMBER` when idle; `ID_NUMBER + 1` while `SoundMain` or an
     * `m4a*` call is changing the state (all of them return at once on any other value); 10
     * more after `m4aSoundVSyncOff`; 0 during `SoundInit`.
     */
    public var ident: Int = M4aPlayer.ID_NUMBER

    /**
     * `intp`: the player most recently opened by [open], the head of the chain `SoundMain` runs
     * (`func` = `MPlayMain` exactly when this is non-null).
     */
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

    /**
     * `freq` (u8): the sample rate setting, the `SOUND_MODE_FREQ_*` index 1..12 (bits 16..19 of
     * a sound mode; 5734 Hz .. 42048 Hz).
     */
    public var freq: Int = 0

    /**
     * `SampleFreqSet`: sets [freq], [pcmSamplesPerVBlank] (from `gPcmSamplesPerVBlankTable`),
     * [pcmDmaPeriod], [pcmFreq] and [divFreq] from a `SOUND_MODE_FREQ_*` value ([mode] bits
     * 16..19, 1..12). The timer 0 setup,
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

    /**
     * `SoundInit`: clears the modelled `SoundInfo` state (no players, no live channels, silent
     * [pcmBuffer], no reverb), then 8 channels, master volume 15 and the 13379 Hz rate
     * ([sampleFreqSet]), and unlocks ([ident] = `ID_NUMBER`). DMA / sound registers, the
     * function pointers and `MPlayJumpTableCopy` are hardware or fixed in the port.
     *
     * Ported from src/m4a.c: SoundInit
     */
    public fun soundInit() {
        reverb = 0
        intp = null
        // MAX_DIRECTSOUND_CHANNELS = 12 slots
        for (i in 0 until minOf(12, chans.size)) chans[i].status = 0
        pcmBuffer.fill(0)
        maxChans = 8
        masterVolume = 15
        sampleFreqSet(FREQ_13379)
        pcmDmaCounter = 0 // m4aSoundVSyncOn
        ident = M4aPlayer.ID_NUMBER
    }

    /**
     * `m4aSoundMode`: applies each non-zero field of [mode]: reverb (`SOUND_MODE_REVERB_SET`
     * 0x80 and `SOUND_MODE_REVERB_VAL` 0x7F: the value is stored), max channels (bits 8..11;
     * frees all 12 `MAX_DIRECTSOUND_CHANNELS`), master volume (bits 12..15) and sample rate
     * (bits 16..19: `m4aSoundVSyncOff` clears [pcmBuffer], then [sampleFreqSet] and
     * `m4aSoundVSyncOn` reset [pcmDmaCounter]). The DA bit depth (`SOUNDBIAS`) is hardware.
     * Ignored unless [ident] is `ID_NUMBER`.
     *
     * Ported from src/m4a.c: m4aSoundMode
     */
    public fun soundMode(mode: Int) {
        if (ident != M4aPlayer.ID_NUMBER) return
        if (mode and 0xFF != 0) reverb = mode and 0x7F
        if (mode and 0xF00 != 0) {
            maxChans = (mode and 0xF00) shr 8
            // MAX_DIRECTSOUND_CHANNELS = 12 slots
            for (i in 0 until minOf(12, chans.size)) chans[i].status = 0
        }
        if (mode and 0xF000 != 0) masterVolume = (mode and 0xF000) shr 12
        if (mode and 0xF0000 != 0) {
            pcmBuffer.fill(0) // m4aSoundVSyncOff
            sampleFreqSet(mode and 0xF0000)
            pcmDmaCounter = 0 // m4aSoundVSyncOn
        }
    }

    /**
     * `maxChans` (u8): how many of the DirectSound [chans] the mixer runs and notes may take
     * (8 after `SoundInit`; `m4aSoundMode` bits 8..11).
     */
    public var maxChans: Int = chans.size

    /** `reverb` (u8): mixer reverb strength 0..127 (`SOUND_MODE_REVERB_VAL`); 0 = off. */
    public var reverb: Int = 0

    /** `pcmDmaCounter` (u8): VBlanks left in the current PCM DMA period. */
    public var pcmDmaCounter: Int = 0

    /** `pcmDmaPeriod` (u8): VBlanks per PCM DMA, `PCM_DMA_BUF_SIZE / pcmSamplesPerVBlank`. */
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

    /**
     * `pcmSamplesPerVBlank`: samples the mixer produces per VBlank at the current rate
     * (`gPcmSamplesPerVBlankTable`, set by [sampleFreqSet]).
     */
    public var pcmSamplesPerVBlank: Int = 0

    /** `pcmFreq`: mixer output rate in Hz. */
    public var pcmFreq: Int = 0

    /**
     * `divFreq`: `(2^24 / pcmFreq + 1) >> 1`; the mixer multiplies it with a channel's `freq` to
     * get the sample position step per output sample.
     */
    public var divFreq: Int = 0

    /**
     * `pcmBuffer` (2 * `PCM_DMA_BUF_SIZE` bytes): signed 8-bit mixer output, several VBlanks
     * of samples per half. The first half (0..0x62F) is the right channel (DMA1, FIFO A), the
     * second (0x630..) the left (DMA2, FIFO B), as `SoundInit` sets up `SOUNDCNT_H`.
     */
    public val pcmBuffer: ByteArray = ByteArray(2 * PCM_DMA_BUF_SIZE)

    /**
     * `c15` (u8, offset 0x0A): `CgbSound`'s frame counter, counting 14 down to 0 once per
     * frame; in the frame it is 0, the CGB envelope counter takes one extra step. (The decomp
     * does not say why; hardware envelopes run at 64 Hz, the frame rate is 59.7275 Hz.)
     */
    public var c15: Int = 0

    /**
     * `masterVolume` (u8, 0..15): DirectSound mixer volume, scaled `(masterVolume + 1) / 16`
     * (15 after `SoundInit`; `m4aSoundMode` bits 12..15).
     */
    public var masterVolume: Int = 15

    /**
     * `CgbOscOff(n)` hook (`soundInfo->CgbOscOff`): silences CGB channel n (1..4) in the sound
     * hardware; null = no hardware to silence.
     */
    public var cgbOscOff: ((Int) -> Unit)? = null

    /** Constants. */
    public companion object {
        /** `PCM_DMA_BUF_SIZE`. */
        public const val PCM_DMA_BUF_SIZE: Int = 0x630

        /** `SOUND_MODE_FREQ_13379`: the rate `SoundInit` selects (and FE8U's `m4aSoundInit`). */
        public const val FREQ_13379: Int = 0x40000

        /** `gPcmSamplesPerVBlankTable` (src/m4a_tables.c). */
        private val SAMPLES_PER_VBLANK = intArrayOf(
            96, 132, 176, 224, 264, 304, 352, 448, 528, 608, 672, 704,
        )

        /** `MAX_MUSICPLAYER_TRACKS`. */
        public const val MAX_TRACKS: Int = 16
    }
}
