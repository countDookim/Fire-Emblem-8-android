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

    /** `CgbOscOff(n)`: silences CGB channel n (1..4) in hardware; null = no hardware. */
    public var cgbOscOff: ((Int) -> Unit)? = null
}
