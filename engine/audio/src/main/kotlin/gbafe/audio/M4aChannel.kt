package gbafe.audio

/**
 * One m4a voice (`struct SoundChannel`; the shared head of `struct CgbChannel`). Fields hold the
 * C values as Ints. Only the state ported so far is modelled.
 */
public class M4aChannel {
    /** `rightVolume` (u8). */
    public var rightVolume: Int = 0

    /** `leftVolume` (u8). */
    public var leftVolume: Int = 0

    /** `ve` (u8): note velocity. */
    public var velocity: Int = 0

    /** `rp` (s8): rhythm pan of the note's instrument. */
    public var rhythmPan: Int = 0

    /**
     * Sets [rightVolume]/[leftVolume] from [velocity], [rhythmPan] and the [track]'s
     * `volMR`/`volML`, each capped at 255.
     *
     * Ported from src/m4a_1.s: ChnVolSetAsm
     */
    public fun volSet(track: M4aTrack) {
        rightVolume = minOf(255, ((128 + rhythmPan) * velocity * track.volMR) shr 14)
        leftVolume = minOf(255, ((127 - rhythmPan) * velocity * track.volML) shr 14)
    }
}
