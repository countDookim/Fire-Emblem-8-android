/**
 * New game RAM initialization state container.
 *
 * Holds references to the global in-memory state that `WriteNewGameSave` reads and reinitializes
 * when creating a fresh save file: `gPlaySt`, the permanent event flags (`gPermanentFlagBits`),
 * the supply convoy (`gConvoyItemArray`), game time clock (`gGameClock`), and unit initialization.
 */

package fe8.game.save

import fe8.game.bm.PlaySt
import gbafe.core.flags.EventFlags

/**
 * The RAM state `WriteNewGameSave` reads and resets besides the save RAM: `gPlaySt`, the
 * permanent flags, the convoy, the game clock and the unit array.
 *
 * @property playSt In-memory player and chapter state (`gPlaySt`).
 * @property flags The permanent event flag bit array (`gPermanentFlagBits`).
 * @property convoy Supply convoy item array (`gConvoyItemArray`), 100 raw little-endian u16 entries (200 bytes).
 * @property gameClock Global game frame counter (`gGameClock`), reset to 0 by `SetGameTime(0)`.
 * @property initUnits Hook representing `InitUnits` to reinitialize player unit slots.
 */
public class NewGameRam(
    public val playSt: PlaySt,
    public val flags: EventFlags,
    public val convoy: ByteArray,
    public var gameClock: Int = 0,
    public val initUnits: () -> Unit = {},
) {
    /** Pre-allocated scratch buffer for unpacking convoy items as 16-bit integers without heap allocations. */
    internal val supplyScratch: IntArray = IntArray(convoy.size / 2)
}
