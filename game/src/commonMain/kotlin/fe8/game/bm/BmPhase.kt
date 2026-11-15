package fe8.game.bm

/**
 * The unit allegiance helpers of src/bmphase.c. A unit id's top two bits are its faction:
 * `FACTION_BLUE` 0x00 (player units), `FACTION_GREEN` 0x40 (allied NPCs), `FACTION_RED` 0x80
 * (enemies), `FACTION_PURPLE` 0xC0 (the link arena's fourth team).
 */
public object BmPhase {
    /**
     * Ported from src/bmphase.c: AreUnitsAllied
     *
     * Whether the unit ids [left] and [right] are on the same side: both on the player's side
     * (blue, green) or both not (red, purple), that is the same bit 0x80. Used to decide whether
     * a unit may attack or target another.
     */
    public fun areUnitsAllied(left: Int, right: Int): Boolean =
        (left and 0x80) == (right and 0x80)

    /**
     * Transcribed from src/bmphase.c: IsSameAllegiance (not verified: no capture checks it; the
     *   truth table restates the C)
     *
     * Whether the unit ids [left] and [right] are of the same faction (the same bits 0xC0).
     */
    public fun isSameAllegiance(left: Int, right: Int): Boolean =
        (left and 0xC0) == (right and 0xC0)
}
