package gbafe.audio

/**
 * One m4a music player (`struct MusicPlayerInfo`) and its [tracks]. Fields hold the C values as
 * Ints. Only the state ported so far is modelled.
 */
public class M4aPlayer(
    /** The player's track slots (`tracks`, `trackCount` in use). */
    public val tracks: Array<M4aTrack>,
) {
    /** `songHeader`: GBA address of the playing song's header. */
    public var songHeader: Int = 0

    /** `status`: bit per live track; [STATUS_PAUSE] when stopped. */
    public var status: Int = 0

    /** `trackCount` (u8): tracks used by the song. */
    public var trackCount: Int = 0

    /** `priority` (u8). */
    public var priority: Int = 0

    /** `unk_B` (u8): non-zero = song start obeys [priority]. */
    public var unkB: Int = 0

    /** `clock`: ticks played. */
    public var clock: Int = 0

    /** `tempoD` (u16): song tempo x 2. */
    public var tempoD: Int = 0

    /** `tempoU` (u16): external tempo scale (0x100 = 1.0). */
    public var tempoU: Int = 0

    /** `tempoI` (u16): tempo increment per frame (`tempoD * tempoU >> 8`). */
    public var tempoI: Int = 0

    /** `tempoC` (u16): tempo accumulator; one tick per 150. */
    public var tempoC: Int = 0

    /** `fadeOI` (u16): frames per fade step (0 = no fade). */
    public var fadeOI: Int = 0

    /** `fadeOC` (u16): fade step countdown. */
    public var fadeOC: Int = 0

    /** `fadeOV` (u16): fade volume << 2 | `FADE_IN` | `TEMPORARY_FADE`. */
    public var fadeOV: Int = 0

    /** `tone`: GBA address of the song's voice group. */
    public var tone: Int = 0

    /** `ident`: [ID_NUMBER] when idle; other values lock the player. */
    public var ident: Int = ID_NUMBER

    /** `MusicPlayerInfo` constants. */
    public companion object {
        /** `MUSICPLAYER_STATUS_PAUSE`. */
        public const val STATUS_PAUSE: Int = Int.MIN_VALUE

        /** `ID_NUMBER` ("Smsh"). */
        public const val ID_NUMBER: Int = 0x68736D53
    }
}
