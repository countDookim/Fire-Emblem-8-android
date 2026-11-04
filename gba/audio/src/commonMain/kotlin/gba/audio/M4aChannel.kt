package gba.audio

/**
 * One m4a voice (`struct SoundChannel`; the shared head of `struct CgbChannel`). Fields hold the
 * C values as Ints. Only the state ported so far is modelled.
 */
public class M4aChannel(
    /** GBA address of this channel struct (identifies it in RAM traces). */
    public val address: Int = 0,
) {
    /** `statusFlags` (u8): 0x80 start, 0x40 releasing, 0x07 envelope phase; 0xC7 clear = free. */
    public var status: Int = 0

    /** `type` (u8): the voice's ToneData type (low 3 bits: CGB channel 1..4, 0 = DirectSound). */
    public var type: Int = 0

    /** `rightVolume` (u8). */
    public var rightVolume: Int = 0

    /** `leftVolume` (u8). */
    public var leftVolume: Int = 0

    /** `attack`..`release` (4 x u8) as one little-endian word. */
    public var adsr: Int = 0

    /** `ky` (u8): played key (rhythm voices: the drum's key). */
    public var key: Int = 0

    /** `ev` (u8): envelope level 0..255. */
    public var envelopeVolume: Int = 0

    /** `er` (u8): right output volume after the envelope. */
    public var envelopeRight: Int = 0

    /** `el` (u8): left output volume after the envelope. */
    public var envelopeLeft: Int = 0

    /** `ct`: samples left in the wave (DirectSound). */
    public var count: Int = 0

    /** `fw`: fractional sample position (DirectSound). */
    public var fw: Int = 0

    /** `cp`: GBA address of the next sample (DirectSound). */
    public var currentPointer: Int = 0

    /** `echoVolume` (u8). */
    public var echoVolume: Int = 0

    /** `echoLength` (u8). */
    public var echoLength: Int = 0

    /** `gateTime` (u8): ticks until release (0 = held). */
    public var gateTime: Int = 0

    /** `midiKey` (u8): the track key of the note. */
    public var midiKey: Int = 0

    /** `ve` (u8): note velocity. */
    public var velocity: Int = 0

    /** `priority` (u8). */
    public var priority: Int = 0

    /** `rp` (s8): rhythm pan of the note's instrument. */
    public var rhythmPan: Int = 0

    /** CGB `sg` (u8, offset 0x19): sustain goal of the envelope. */
    public var sustainGoal: Int = 0

    /** CGB `n4` (u8, offset 0x1A): length-enable bits of NRx4. */
    public var n4: Int = 0

    /** CGB `pan` (u8, offset 0x1B): this channel's `SOUNDCNT_L` pan bits. */
    public var pan: Int = 0

    /** CGB `mo` (u8, offset 0x1D): pending register updates (1 volume, 2 frequency). */
    public var modify: Int = 0

    /** CGB `panMask` (u8, offset 0x1C): the channel's bits of `SOUNDCNT_L` panning. */
    public var panMask: Int = 0

    /** CGB `length` (u8, offset 0x1E). */
    public var length: Int = 0

    /** CGB `sweep` (u8, offset 0x1F). */
    public var sweep: Int = 0

    /** `freq`: DirectSound playback rate, or the CGB frequency register value. */
    public var frequency: Int = 0

    /** `wav`: GBA address of the WaveData (CGB: wave/duty data). */
    public var wav: Int = 0

    /** `track`: owning track, or null. */
    public var track: M4aTrack? = null

    /** `prevChannelPointer`: newer channel of the same track. */
    public var prev: M4aChannel? = null

    /** `nextChannelPointer`: older channel of the same track. */
    public var next: M4aChannel? = null

    /** True if the channel plays or releases a note (`statusFlags & 0xC7`). */
    public val isActive: Boolean get() = status and 0xC7 != 0

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

    /**
     * Unlinks this channel from its track's chain and clears [track].
     *
     * Ported from src/m4a_1.s: RealClearChain
     */
    public fun clearChain() {
        val t = track ?: return
        val n = next
        val p = prev
        if (p != null) p.next = n else t.chan = n
        if (n != null) n.prev = p
        track = null
    }
}
