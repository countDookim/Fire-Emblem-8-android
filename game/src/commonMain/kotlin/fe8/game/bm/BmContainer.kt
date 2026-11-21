/**
 * Supply convoy backing array (`gConvoyItemArray`) of `src/bmcontainer.c`.
 */

package fe8.game.bm

/**
 * The supply convoy (`gConvoyItemArray`, `CONVOY_ITEM_COUNT` = 100 little-endian `u16` item
 * words, 200 bytes) of `src/bmcontainer.c`.
 *
 * @property items Raw 200-byte backing buffer of little-endian `u16` item words.
 */
public class BmContainer(
    public val items: ByteArray = ByteArray(CONVOY_BYTES),
) {
    /**
     * Ported from src/bmcontainer.c: GetConvoyItemArray
     *
     * Returns the raw `gConvoyItemArray` backing buffer (100 little-endian `u16` item words).
     */
    public fun getConvoyItemArray(): ByteArray = items

    /** Size constants for `gConvoyItemArray`. */
    public companion object {
        /** Number of item slots in the convoy (`CONVOY_ITEM_COUNT`). */
        public const val CONVOY_ITEM_COUNT: Int = 100

        /** Total size in bytes of `gConvoyItemArray` (100 item entries * 2 bytes). */
        public const val CONVOY_BYTES: Int = CONVOY_ITEM_COUNT * 2
    }
}
