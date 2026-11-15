package fe8.game.bm

/**
 * `gActionData` (`struct ActionData`, bmmind.h, 0x0203A958): the action the player or the AI
 * is building for the active unit. Only the byte fields from `subjectIndex` (0x0C) on are
 * modelled; the other members of the C struct (the 6 bytes of random-number state at the
 * start, `item`, `unk08`, `unk0A`, `scriptedBattleHits` and `script_hits`) are added by the
 * ports that use them. `struct ActionData` is 0x38 bytes.
 */
public class ActionData {
    /** `subjectIndex` (u8, 0x0C): the `index` of the unit acting. */
    public var subjectIndex: Int = 0

    /** `targetIndex` (u8, 0x0D): the `index` of the unit acted on. */
    public var targetIndex: Int = 0

    /** `xMove` (u8, 0x0E): the tile the unit moves to. */
    public var xMove: Int = 0

    /** `yMove` (u8, 0x0F). */
    public var yMove: Int = 0

    /** `moveCount` (u8, 0x10): the movement spent so far. */
    public var moveCount: Int = 0

    /** `unitActionType` (u8, 0x11): `UNIT_ACTION_*`, 0 while none is chosen. */
    public var unitActionType: Int = 0

    /** `itemSlotIndex` (u8, 0x12): the inventory slot of the item the action uses. */
    public var itemSlotIndex: Int = 0

    /** `xOther` (u8, 0x13): the tile an action without a unit target (a wall or trap) aims at. */
    public var xOther: Int = 0

    /** `yOther` (u8, 0x14). */
    public var yOther: Int = 0

    /** `trapType` (u8, 0x15). */
    public var trapType: Int = 0

    /** `suspendPointType` (u8, 0x16). */
    public var suspendPointType: Int = 0
}

/**
 * The unit whose turn it is in src/bmunit.c, shared by the player phase, the AI and the
 * battle code: `gActiveUnit`, `gActiveUnitId` and `gActiveUnitMoveOrigin`, with [action]
 * (`gActionData`). The unit is held as the id [UnitTable.unitOffset] takes (0 for NULL), not
 * as a pointer.
 */
public class ActiveUnit {
    /** `gActionData`. */
    public val action: ActionData = ActionData()

    /** `gActiveUnit` as a unit id, or 0 for NULL. */
    public var unit: Int = 0

    /** `gActiveUnitId` (u8): the unit's `index` (faction and slot, as in `Unit.index`). */
    public var unitId: Int = 0

    /**
     * `gActiveUnitMoveOrigin.x` (s16): where the unit stood when its action began (set by
     * `UnitBeginAction` and `UnitBeginCantoAction`).
     */
    public var moveOriginX: Int = 0

    /** `gActiveUnitMoveOrigin.y` (s16). */
    public var moveOriginY: Int = 0
}
