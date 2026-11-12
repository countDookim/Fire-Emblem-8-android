package gba.audio

/**
 * One m4a sequencer track (`struct MusicPlayerTrack`). Fields hold the C values as Ints: `u8`
 * fields 0..255, `s8` fields -128..127. Only the state ported so far is modelled.
 */
public class M4aTrack {
    /** `flags` (u8): the `MPT_FLG_*` bits ([FLG_VOLSET], [FLG_PITSET], [FLG_START], ...). */
    public var flags: Int = 0

    /** `wait` (u8): ticks until the next command. */
    public var wait: Int = 0

    /** `patternLevel` (u8): `PATT` call depth (0..3). */
    public var patternLevel: Int = 0

    /** `repN` (u8): `REPT` loop counter. */
    public var repN: Int = 0

    /**
     * `gateTime` (u8): length in ticks of the last note, from its note command's clock value
     * plus the optional extra byte; copied to the note's channel (`gt`).
     */
    public var gateTime: Int = 0

    /** `key` (u8): last note key. */
    public var key: Int = 0

    /** `velocity` (u8): last note velocity. */
    public var velocity: Int = 0

    /**
     * `runningStatus` (u8): the last command byte of 0xBD and above, reused when the next byte
     * is below 0x80 (a command's argument bytes without the command).
     */
    public var runningStatus: Int = 0

    /** `mod` (u8): `MOD` LFO modulation depth (0 = no modulation). */
    public var mod: Int = 0

    /** `lfoSpeed` (u8): `LFOS` LFO speed (22 after a track start; 0 = no modulation). */
    public var lfoSpeed: Int = 0

    /** `lfoSpeedC` (u8): LFO phase counter, advanced by [lfoSpeed] per tick. */
    public var lfoSpeedC: Int = 0

    /** `lfoDelay` (u8): `LFODL` ticks to wait after a note starts before the LFO acts. */
    public var lfoDelay: Int = 0

    /** `lfoDelayC` (u8): LFO delay countdown. */
    public var lfoDelayC: Int = 0

    /** `priority` (u8): `PRIO`; added to the player's priority for each note started. */
    public var priority: Int = 0

    /** `cmdPtr`: GBA address of the next command byte. */
    public var cmdPtr: Int = 0

    /** `patternStack`: return addresses of `PATT` calls. */
    public val patternStack: IntArray = IntArray(3)

    /** GBA address of this track struct (`ply_note` breaks priority ties by it). */
    public var address: Int = 0

    /** `echoVolume` (u8): `XIECV` echo volume given to new notes. */
    public var echoVolume: Int = 0

    /** `echoLength` (u8): `XIECL` echo length given to new notes. */
    public var echoLength: Int = 0

    /** `chan`: head of this track's channel chain (newest note), or null. */
    public var chan: M4aChannel? = null

    /** `tone` (struct ToneData, 12 bytes) as its three little-endian words. */
    public val tone: IntArray = IntArray(3)

    /**
     * `keyM` (u8, signed in use): semitone offset added to the notes' keys, from [keyShift],
     * [keyShiftX], [tune] and [bend] (whole semitones of `TrkVolPitSet`'s sum).
     */
    public var keyM: Int = 0

    /** `pitM` (u8): fine pitch in 1/256 semitone (the fraction of `TrkVolPitSet`'s sum). */
    public var pitM: Int = 0

    /** `keyShift` (s8): `KEYSH` transpose. */
    public var keyShift: Int = 0

    /** `keyShiftX` (s8): external transpose, set by `m4aMPlayPitchControl` (high byte). */
    public var keyShiftX: Int = 0

    /** `tune` (s8): `TUNE`. */
    public var tune: Int = 0

    /** `pitX` (u8): external fine pitch, set by `m4aMPlayPitchControl` (low byte). */
    public var pitX: Int = 0

    /** `bend` (s8): `BEND` pitch bend (argument - `C_V` 0x40), scaled by [bendRange]. */
    public var bend: Int = 0

    /** `bendRange` (u8): `BENDR` pitch bend range (2 after a track start). */
    public var bendRange: Int = 0

    /** `volMR` (u8): track volume of the right output after pan (set by [volPitSet]). */
    public var volMR: Int = 0

    /** `volML` (u8): track volume of the left output after pan (set by [volPitSet]). */
    public var volML: Int = 0

    /** `vol` (u8): `VOL` track volume. */
    public var vol: Int = 0

    /**
     * `volX` (u8): external volume factor (64 after a track start), set by fades and
     * `m4aMPlayVolumeControl`.
     */
    public var volX: Int = 0

    /** `pan` (s8): `PAN` (argument - `C_V` 0x40, so 0 = centre). */
    public var pan: Int = 0

    /** `panX` (s8): external pan, set by `m4aMPlayPanpotControl`. */
    public var panX: Int = 0

    /** `modM` (s8): current LFO modulation value. */
    public var modM: Int = 0

    /** `modT` (u8): `MODT` modulation target (0 pitch, 1 volume, 2 pan). */
    public var modT: Int = 0

    /**
     * Recomputes [volMR]/[volML] (if [FLG_VOLSET]) and [keyM]/[pitM] (if [FLG_PITSET]), then clears
     * those two flags (the `*CHG` bits stay for the caller).
     *
     * Ported from src/m4a.c: TrkVolPitSet
     */
    public fun volPitSet() {
        if (flags and FLG_VOLSET != 0) {
            var x = (vol * volX) ushr 5
            if (modT == 1) x = (x * (modM + 128)) ushr 7
            var y = 2 * pan + panX
            if (modT == 2) y += modM
            y = y.coerceIn(-128, 127)
            volMR = (((y + 128) * x) ushr 8) and 0xFF
            volML = (((127 - y) * x) ushr 8) and 0xFF
        }
        if (flags and FLG_PITSET != 0) {
            var x = (tune + bend * bendRange) * 4 + (keyShift shl 8) + (keyShiftX shl 8) + pitX
            if (modT == 0) x += 16 * modM
            keyM = (x shr 8) and 0xFF
            pitM = x and 0xFF
        }
        flags = flags and (FLG_PITSET or FLG_VOLSET).inv()
    }

    /** `MPT_FLG_*` values (`flags`). */
    public companion object {
        /** `MPT_FLG_VOLSET`: track volume/pan changed; [volPitSet] recomputes `volMR`/`volML`. */
        public const val FLG_VOLSET: Int = 0x01

        /**
         * `MPT_FLG_VOLCHG`: [FLG_VOLSET] plus bit 1, which stays until the track's playing
         * channels have taken the new volume.
         */
        public const val FLG_VOLCHG: Int = 0x03

        /** `MPT_FLG_PITSET`: pitch inputs changed; [volPitSet] recomputes `keyM`/`pitM`. */
        public const val FLG_PITSET: Int = 0x04

        /**
         * `MPT_FLG_PITCHG`: [FLG_PITSET] plus bit 3, which stays until the track's playing
         * channels have taken the new pitch.
         */
        public const val FLG_PITCHG: Int = 0x0C

        /**
         * `MPT_FLG_START`: the track was just started (`MPlayStart`); `MPlayMain` first resets
         * it to its defaults (`Clear64byte`, bend range 2, volX 64, LFO speed 22, tone type 1).
         */
        public const val FLG_START: Int = 0x40

        /** `MPT_FLG_EXIST`: the track is in use (it has a song part to play). */
        public const val FLG_EXIST: Int = 0x80
    }
}
