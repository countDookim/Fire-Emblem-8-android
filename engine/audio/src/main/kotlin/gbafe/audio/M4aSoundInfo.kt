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
    /** `maxChans` (u8): DirectSound channels in use. */
    public var maxChans: Int = chans.size

    /** `reverb` (u8): 0 = off. */
    public var reverb: Int = 0

    /** `pcmDmaCounter` (u8): VBlanks left in the current PCM DMA period. */
    public var pcmDmaCounter: Int = 0

    /** `pcmDmaPeriod` (u8): VBlanks per PCM buffer. */
    public var pcmDmaPeriod: Int = 0

    /** `pcmSamplesPerVBlank`. */
    public var pcmSamplesPerVBlank: Int = 0

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
    }
}
