package fe8.game.bm

/** The unit allegiance helpers of src/bmphase.c. A unit id's top bits are its faction. */
public object BmPhase {
    /**
     * Ported from src/bmphase.c: AreUnitsAllied
     *
     * Whether the unit ids [left] and [right] are on the same side: both player-side (blue,
     * green) or both enemy-side (red, purple), that is the same bit 0x80.
     */
    public fun areUnitsAllied(left: Int, right: Int): Boolean =
        (left and 0x80) == (right and 0x80)

    /**
     * Transcribed from src/bmphase.c: IsSameAllegiance (not verified: no capture checks it; the
     *   truth table restates the C)
     *
     * Whether the unit ids [left] and [right] are of the same faction (bits 0xC0).
     */
    public fun isSameAllegiance(left: Int, right: Int): Boolean =
        (left and 0xC0) == (right and 0xC0)
}
