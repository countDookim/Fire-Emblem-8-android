package fe8.game.save

import fe8.game.bm.PlaySt
import gbafe.core.flags.EventFlags

/**
 * The RAM state `WriteNewGameSave` reads and resets besides the save RAM: `gPlaySt`, the
 * permanent flags, the convoy, the game clock and the unit array.
 *
 * @property playSt `gPlaySt`.
 * @property flags the event flag memory (`gPermanentFlagBits`).
 * @property convoy `gConvoyItemArray` as 100 raw little-endian u16 values.
 * @property gameClock `gGameClock`, set by `SetGameTime(0)`.
 * @property initUnits hook standing in for `InitUnits` (the unit array is not ported yet).
 */
public class NewGameRam(
    public val playSt: PlaySt,
    public val flags: EventFlags,
    public val convoy: ByteArray,
    public var gameClock: Int = 0,
    public val initUnits: () -> Unit = {},
) {
    /** Scratch for the convoy as ints, so a save does not allocate. */
    internal val supplyScratch: IntArray = IntArray(convoy.size / 2)
}
