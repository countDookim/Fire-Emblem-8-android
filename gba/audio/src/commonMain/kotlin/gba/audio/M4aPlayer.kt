package gba.audio

/**
 * One m4a music player (`struct MusicPlayerInfo`) and its [tracks]. Fields hold the C values as
 * Ints. Only the state ported so far is modelled.
 */
public class M4aPlayer(
    /** The player's track slots (`tracks`, `trackCount` in use). */
    public val tracks: Array<M4aTrack>,
) {
    /** `songHeader`: GBA address of the `SongHeader` of the song last started (0 = none). */
    public var songHeader: Int = 0

    /**
     * `status`: bit n set (`MUSICPLAYER_STATUS_TRACK`, bits 0..15) while track n is live;
     * [STATUS_PAUSE] when stopped or paused.
     */
    public var status: Int = 0

    /**
     * `trackCount` (u8): track slots this player was opened with (`MPlayOpen`, at most
     * `MAX_MUSICPLAYER_TRACKS` 16); a song uses at most this many of its tracks.
     */
    public var trackCount: Int = 0

    /** `priority` (u8): priority of the song playing (`SongHeader.priority`). */
    public var priority: Int = 0

    /**
     * `unk_B` (u8), set from `gMPlayTable[].unk_A`: non-zero = `MPlayStart` does not replace a
     * song of higher [priority]. Its only use in the decomp is that check; the name is kept
     * because the decomp has none.
     */
    public var unkB: Int = 0

    /** `clock`: ticks played since the song started. */
    public var clock: Int = 0

    /** `tempoD` (u16): song tempo x 2 (`TEMPO` argument * 2; 150 at a song start). */
    public var tempoD: Int = 0

    /** `tempoU` (u16): external tempo scale (0x100 = 1.0). */
    public var tempoU: Int = 0

    /** `tempoI` (u16): tempo increment per frame (`tempoD * tempoU >> 8`). */
    public var tempoI: Int = 0

    /** `tempoC` (u16): tempo accumulator; one tick per 150. */
    public var tempoC: Int = 0

    /** `fadeOI` (u16): frames per fade step (the fade speed; 0 = no fade running). */
    public var fadeOI: Int = 0

    /** `fadeOC` (u16): fade step countdown. */
    public var fadeOC: Int = 0

    /**
     * `fadeOV` (u16): fade volume 0..64 shifted left by `FADE_VOL_SHIFT` (2), with the flags
     * `FADE_IN` and `TEMPORARY_FADE` in its low bits.
     */
    public var fadeOV: Int = 0

    /** `tone`: GBA address of the song's voice group (`SongHeader.tone`). */
    public var tone: Int = 0

    /** `ident`: [ID_NUMBER] when idle; other values lock the player. */
    public var ident: Int = ID_NUMBER

    /** `intp`: the next player `SoundMain` runs (`func` = `MPlayMain` when non-null). */
    public var intp: M4aPlayer? = null

    /**
     * `Clear64byte` as `MPlayOpen` applies it: zeroes the struct's first 64 bytes, i.e. every
     * field above including [ident] (the `tracks` pointer is set again by `MPlayOpen`).
     */
    internal fun clear64() {
        songHeader = 0
        status = 0
        trackCount = 0
        priority = 0
        unkB = 0
        clock = 0
        tempoD = 0
        tempoU = 0
        tempoI = 0
        tempoC = 0
        fadeOI = 0
        fadeOC = 0
        fadeOV = 0
        tone = 0
        ident = 0
        intp = null
    }

    /** `MusicPlayerInfo` constants. */
    public companion object {
        /** `MUSICPLAYER_STATUS_PAUSE` (0x80000000): set in [status] when stopped or paused. */
        public const val STATUS_PAUSE: Int = Int.MIN_VALUE

        /** `ID_NUMBER`: ASCII "Smsh" in memory order, the idle value of the `ident` lock words. */
        public const val ID_NUMBER: Int = 0x68736D53
    }
}
