package gbafe.audio

/**
 * One m4a sequencer track (`struct MusicPlayerTrack`). Fields hold the C values as Ints: `u8`
 * fields 0..255, `s8` fields -128..127. Only the state ported so far is modelled.
 */
public class M4aTrack {
    /** `flags`: the `FLG_*` bits ([FLG_VOLSET], [FLG_PITSET], [FLG_START], [FLG_EXIST], ...). */
    public var flags: Int = 0

    /** `wait` (u8): ticks until the next command. */
    public var wait: Int = 0

    /** `patternLevel` (u8): `PATT` call depth (0..3). */
    public var patternLevel: Int = 0

    /** `repN` (u8): `REPT` loop counter. */
    public var repN: Int = 0

    /** `gateTime` (u8): gate time of the last note. */
    public var gateTime: Int = 0

    /** `key` (u8): last note key. */
    public var key: Int = 0

    /** `velocity` (u8): last note velocity. */
    public var velocity: Int = 0

    /** `runningStatus` (u8): last command byte, repeated by bytes < 0x80. */
    public var runningStatus: Int = 0

    /** `mod` (u8): `MOD` depth. */
    public var mod: Int = 0

    /** `lfoSpeed` (u8): `LFOS`. */
    public var lfoSpeed: Int = 0

    /** `lfoSpeedC` (u8): LFO phase. */
    public var lfoSpeedC: Int = 0

    /** `lfoDelay` (u8): `LFODL`. */
    public var lfoDelay: Int = 0

    /** `lfoDelayC` (u8): LFO delay countdown. */
    public var lfoDelayC: Int = 0

    /** `priority` (u8): `PRIO`. */
    public var priority: Int = 0

    /** `cmdPtr`: GBA address of the next command byte. */
    public var cmdPtr: Int = 0

    /** `patternStack`: return addresses of `PATT` calls. */
    public val patternStack: IntArray = IntArray(3)

    /** `tone` (struct ToneData, 12 bytes) as its three little-endian words. */
    public val tone: IntArray = IntArray(3)

    /** `keyM` (u8): key offset in semitones applied to playing notes. */
    public var keyM: Int = 0

    /** `pitM` (u8): fine pitch in 1/256 semitone. */
    public var pitM: Int = 0

    /** `keyShift` (s8): `KEYSH` transpose. */
    public var keyShift: Int = 0

    /** `keyShiftX` (s8): external transpose (pitch control). */
    public var keyShiftX: Int = 0

    /** `tune` (s8): `TUNE`. */
    public var tune: Int = 0

    /** `pitX` (u8): external fine pitch. */
    public var pitX: Int = 0

    /** `bend` (s8): `BEND`. */
    public var bend: Int = 0

    /** `bendRange` (u8): `BENDR`. */
    public var bendRange: Int = 0

    /** `volMR` (u8): right volume after pan. */
    public var volMR: Int = 0

    /** `volML` (u8): left volume after pan. */
    public var volML: Int = 0

    /** `vol` (u8): `VOL`. */
    public var vol: Int = 0

    /** `volX` (u8): external volume (fades, volume control). */
    public var volX: Int = 0

    /** `pan` (s8): `PAN`. */
    public var pan: Int = 0

    /** `panX` (s8): external pan. */
    public var panX: Int = 0

    /** `modM` (s8): current LFO modulation value. */
    public var modM: Int = 0

    /** `modT` (u8): modulation target (0 pitch, 1 volume, 2 pan). */
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

    /** `MPT_FLG_*` values. */
    public companion object {
        /** Volume needs recomputing. */
        public const val FLG_VOLSET: Int = 0x01

        /** Volume changed (both volume bits). */
        public const val FLG_VOLCHG: Int = 0x03

        /** Pitch needs recomputing. */
        public const val FLG_PITSET: Int = 0x04

        /** Pitch changed (both pitch bits). */
        public const val FLG_PITCHG: Int = 0x0C

        /** Track (re)starts. */
        public const val FLG_START: Int = 0x40

        /** Track is in use. */
        public const val FLG_EXIST: Int = 0x80
    }
}
