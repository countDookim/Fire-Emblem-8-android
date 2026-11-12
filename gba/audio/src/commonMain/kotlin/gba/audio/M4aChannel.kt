package gba.audio

/**
 * One m4a voice: a DirectSound channel (`struct SoundChannel`, [M4aSoundInfo.chans]) or a CGB
 * channel (`struct CgbChannel`, [M4aSoundInfo.cgbChans]). The two structs share offsets 0..0x14
 * and differ from 0x0A (CGB `eg`/`ec`) and from 0x18 on, so this one class holds the fields of
 * both; each doc says which struct a field belongs to. Fields hold the C values as Ints. Only
 * the state ported so far is modelled.
 */
public class M4aChannel(
    /** GBA address of this channel struct (identifies it in RAM traces). */
    public val address: Int = 0,
) {
    /**
     * `status` (`sf` in `CgbChannel`, u8). The decomp uses literals (m4a_1.s, `CgbSound`):
     * 0x80 = note just started (the mixer or `CgbSound` initialises it, then sets 3), 0x40 =
     * released (key off), 0x10 = DirectSound wave loops, 0x04 = echo phase (after the release),
     * 0x03 = envelope phase (3 attack, 2 decay, 1 sustain; a CGB release clears them). All of
     * `status & 0xC7` clear = the channel is free.
     */
    public var status: Int = 0

    /**
     * `type` (`ty` in `CgbChannel`, u8): the `ToneData.type` of the voice. Bits 0..2
     * (`TONEDATA_TYPE_CGB` 0x07) = CGB channel 1..4, 0 = DirectSound; bit 3 (`TONEDATA_TYPE_FIX`
     * 0x08) = fixed sample rate for DirectSound (the mixer steps one sample per output sample);
     * `CgbSound` uses it on CGB channels 1..3 for a frequency adjustment (purpose not stated in
     * the decomp).
     */
    public var type: Int = 0

    /** `rightVolume` (u8): note volume of the right output before the envelope (0..255). */
    public var rightVolume: Int = 0

    /** `leftVolume` (u8): note volume of the left output before the envelope (0..255). */
    public var leftVolume: Int = 0

    /**
     * `attack`, `decay`, `sustain`, `release` (`at`, `de`, `su`, `re` in `CgbChannel`; 4 x u8,
     * offsets 4..7) as one little-endian word: attack in bits 0..7, release in bits 24..31.
     */
    public var adsr: Int = 0

    /** `ky` (u8): played key (rhythm voices: the drum's key). */
    public var key: Int = 0

    /** `ev` (u8): current envelope level (DirectSound 0..255, CGB 0..15). */
    public var envelopeVolume: Int = 0

    /**
     * Offset 0x0A (u8). DirectSound `er`: [rightVolume] scaled by the envelope and the master
     * volume (what the mixer multiplies samples with). CGB `eg`: envelope goal level (0..15)
     * set by `CgbModVol`.
     */
    public var envelopeRight: Int = 0

    /**
     * Offset 0x0B (u8). DirectSound `el`: [leftVolume] scaled by the envelope and the master
     * volume. CGB `ec`: envelope step counter (frames until the next envelope step).
     */
    public var envelopeLeft: Int = 0

    /** `ct` (offset 0x18): samples left until the end of the wave or loop (DirectSound). */
    public var count: Int = 0

    /**
     * `fw` (offset 0x1C): fractional position between the current and next sample, in
     * units of 1/2^23 (the mixer adds `divFreq * freq` and the carries from bit 23 advance the
     * sample position) (DirectSound).
     */
    public var fw: Int = 0

    /**
     * `cp` (offset 0x28): GBA address in the wave data where the mixer is (DirectSound). CGB
     * channel 3: the wave RAM pattern last loaded, so an unchanged [wav] is not reloaded.
     */
    public var currentPointer: Int = 0

    /**
     * `echoVolume` (offset 0x0C, u8; `iev` in m4a_1.s): envelope level the release decays to
     * before the echo phase (0 = the note just ends).
     */
    public var echoVolume: Int = 0

    /** `echoLength` (offset 0x0D, u8; `iel` in m4a_1.s): frames of the echo phase. */
    public var echoLength: Int = 0

    /** `gt` (offset 0x10, u8): ticks until the release (0 = held until `EOT`/`FINE`). */
    public var gateTime: Int = 0

    /**
     * `mk` (offset 0x11, u8): the track's key when the note was played (`EOT` finds the note by
     * it); [key] can differ for rhythm voices.
     */
    public var midiKey: Int = 0

    /** `ve` (offset 0x12, u8): note velocity. */
    public var velocity: Int = 0

    /**
     * `pr` (offset 0x13, u8): note priority (player priority + track priority, at most 255); a
     * new note takes over the channel with the lowest one.
     */
    public var priority: Int = 0

    /**
     * `rp` (offset 0x14, s8): pan of a rhythm (drum) voice, `(pan - 0xC0) * 2` from its
     * `ToneData.pan_sweep`; 0 for other voices.
     */
    public var rhythmPan: Int = 0

    /** CGB `sg` (offset 0x19, u8): sustain level, `(eg * sustain + 15) >> 4`. */
    public var sustainGoal: Int = 0

    /**
     * CGB `n4` (offset 0x1A, u8): the high bits written to `NRx4` (bit 7 trigger, bit 6 length
     * enable) with the frequency's top bits added in.
     */
    public var n4: Int = 0

    /** CGB `pan` (offset 0x1B, u8): this channel's bits of `NR51` (left/right enable). */
    public var pan: Int = 0

    /**
     * CGB `mo` (offset 0x1D, u8): register updates `CgbSound` still has to write (bit 0 volume
     * and pan, bit 1 frequency).
     */
    public var modify: Int = 0

    /**
     * CGB `panMask` (offset 0x1C, u8): this channel's bits of `NR51` (0x11, 0x22, 0x44, 0x88 for
     * channels 1..4).
     */
    public var panMask: Int = 0

    /** CGB `le` (offset 0x1E, u8): sound length from `ToneData.length` (`NRx1` length bits). */
    public var length: Int = 0

    /**
     * CGB `sw` (offset 0x1F, u8): `NR10` value (channel 1 sweep) from `ToneData.pan_sweep`;
     * 8 = no sweep.
     */
    public var sweep: Int = 0

    /**
     * `freq` (offset 0x20; `fr` in `CgbChannel`): DirectSound sample step per output sample
     * (`MidiKeyToFreq`), or the CGB frequency register value (`MidiKeyToCgbFreq`).
     */
    public var frequency: Int = 0

    /**
     * `wav` (`wp` in `CgbChannel`): GBA address of the `WaveData` (DirectSound). CGB: channels
     * 1/2 the duty (0..3, shifted into `NRx1`), channel 3 the address of the 16-byte wave
     * pattern, channel 4 the noise width bit (shifted into `NR43`).
     */
    public var wav: Int = 0

    /** `track` (offset 0x2C): the [M4aTrack] that plays this note, or null when unused. */
    public var track: M4aTrack? = null

    /** `pp` (offset 0x30): the previous (newer) channel in the track's chain, or null. */
    public var prev: M4aChannel? = null

    /** `np` (offset 0x34): the next (older) channel in the track's chain, or null. */
    public var next: M4aChannel? = null

    /** True if the channel is in use: `status & 0xC7` is not 0 (see [status]). */
    public val isActive: Boolean get() = status and 0xC7 != 0

    /**
     * Sets [rightVolume]/[leftVolume] from [velocity], [rhythmPan] and the [track]'s
     * `volMR`/`volML`: `(128 + rp) * ve * volMR >> 14` right, `(127 - rp) * ve * volML >> 14`
     * left, each capped at 255.
     *
     * Ported from src/m4a_1.s: ChnVolSetAsm
     */
    public fun volSet(track: M4aTrack) {
        rightVolume = minOf(255, ((128 + rhythmPan) * velocity * track.volMR) shr 14)
        leftVolume = minOf(255, ((127 - rhythmPan) * velocity * track.volML) shr 14)
    }

    /**
     * Unlinks this channel from its track's chain (fixing the neighbours' [prev]/[next] or the
     * track's head `chan`) and clears [track]; [prev]/[next] of this channel stay as they were.
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
