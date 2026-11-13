/**
 * The RAM that starting a new game touches: `WriteNewGameSave` (src/bmsave.c, run when a new
 * file is started from the title screen) resets it before writing the first chapter save.
 */

package fe8.game.save

import fe8.game.bm.PlaySt
import gbafe.core.flags.EventFlags

/**
 * The RAM state `WriteNewGameSave` reads and resets besides the save RAM: `gPlaySt`, the
 * permanent flags, the convoy, the game clock and the unit array.
 *
 * @property playSt `gPlaySt`: chapter, mode, difficulty and settings state; reinitialised for
 *   the new game (`InitPlayConfig`, chapter 0, new playthrough id).
 * @property flags The permanent event flags (`gPermanentFlagBits`), cleared by
 *   `ResetPermanentFlags`.
 * @property convoy The supply convoy (`gConvoyItemArray`), 100 raw little-endian u16 item words
 *   (200 bytes), emptied by `ClearSupplyItems`.
 * @property gameClock `gGameClock`, the play time in frames, set to 0 by `SetGameTime(0)`.
 * @property initUnits Stands for `InitUnits`, which clears the unit arrays; the units are not
 *   modelled here.
 */
public class NewGameRam(
    public val playSt: PlaySt,
    public val flags: EventFlags,
    public val convoy: ByteArray,
    public var gameClock: Int = 0,
    public val initUnits: () -> Unit = {},
) {
    /**
     * The 100 convoy item words as ints, the form `WriteSupplyItems` packs into the save's 0xB0
     * bytes; allocated once so that writing a save does not allocate.
     */
    internal val supplyScratch: IntArray = IntArray(convoy.size / 2)
}
