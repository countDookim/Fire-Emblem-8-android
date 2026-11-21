/**
 * Supply convoy backing array (`gConvoyItemArray`) of `src/bmcontainer.c`.
 */

package fe8.game.bm

/**
 * The supply convoy (`gConvoyItemArray`, `CONVOY_ITEM_COUNT` = 100 little-endian `u16` item
 * words, 200 bytes) of `src/bmcontainer.c`.
 *
 * @property items Raw 200-byte backing buffer of little-endian `u16` item words.
 * @param playSt the play state (`gPlaySt`) that [hasConvoyAccess] reads.
 */
public class BmContainer(
    public val items: ByteArray = ByteArray(CONVOY_BYTES),
    private val playSt: PlaySt = PlaySt(),
) {
    /** Calls [hasConvoyAccess] makes into the world map code. */
    public class Hooks {
        /**
         * `gGMData.state.bits.state_0` (`GM_STATE_0`, bit 0 of
         * `GmData.stateRaw`). The game wires it to `gmData`; false (a battle
         * map outside the world map) until then.
         */
        public var isGmState0: () -> Boolean = { false }

        /**
         * `GetNextUnclearedChapter()` (src/worldmap_path.c): the chapter id of the next
         * uncleared world map node, or -1 if there is none.
         * Stand-in until the game wires `WorldMapPath.getNextUnclearedNode`.
         */
        public var getNextUnclearedChapter: () -> Int = { -1 }
    }

    /** The world map calls of this container. */
    public val hooks: Hooks = Hooks()

    /**
     * Transcribed from src/bmcontainer.c: HasConvoyAccess (not verified: at f4181 the Supply item
     *   is hidden whatever this returns, so the capture does not show its value)
     *
     * Whether the convoy can be opened. With `GM_STATE_0` set the next uncleared chapter
     * decides (no access for `CHAPTER_L_PROLOGUE`, `CHAPTER_L_1` and `CHAPTER_CASTLE_FRELIA`);
     * otherwise `gPlaySt.chapterIndex` does (no access up to `CHAPTER_L_1`, at
     * `CHAPTER_CASTLE_FRELIA` and at `CHAPTER_L_5X`).
     * At f4181 `GM_STATE_0` is clear and `chapterIndex` is `CHAPTER_L_PROLOGUE`, so it returns
     * false. The `GM_STATE_0` branch and the chapters past `CHAPTER_L_1` are unexercised at
     * f4181.
     */
    public fun hasConvoyAccess(): Boolean {
        if (hooks.isGmState0()) {
            val chapterId = hooks.getNextUnclearedChapter()
            return !(chapterId == CHAPTER_L_PROLOGUE || chapterId == CHAPTER_L_1 ||
                chapterId == CHAPTER_CASTLE_FRELIA)
        }

        val chapter = playSt.chapterIndex
        if (chapter <= CHAPTER_L_1) return false

        if (chapter == CHAPTER_CASTLE_FRELIA) return false

        return chapter != CHAPTER_L_5X
    }

    /**
     * Ported from src/bmcontainer.c: GetConvoyItemArray
     *
     * Returns the raw `gConvoyItemArray` backing buffer (100 little-endian `u16` item words).
     */
    public fun getConvoyItemArray(): ByteArray = items

    /** Size constants for `gConvoyItemArray`. */
    public companion object {
        /** `CHAPTER_L_PROLOGUE`. */
        public const val CHAPTER_L_PROLOGUE: Int = 0x00

        /** `CHAPTER_L_1`. */
        public const val CHAPTER_L_1: Int = 0x01

        /** `CHAPTER_L_5X`. */
        public const val CHAPTER_L_5X: Int = 0x05

        /** `CHAPTER_CASTLE_FRELIA`. */
        public const val CHAPTER_CASTLE_FRELIA: Int = 0x38

        /** Number of item slots in the convoy (`CONVOY_ITEM_COUNT`). */
        public const val CONVOY_ITEM_COUNT: Int = 100

        /** Total size in bytes of `gConvoyItemArray` (100 item entries * 2 bytes). */
        public const val CONVOY_BYTES: Int = CONVOY_ITEM_COUNT * 2
    }
}
