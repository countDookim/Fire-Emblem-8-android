package fe8.game.bm

import fe8.game.RomView

/**
 * Read-only access to `gItemData` (ROM 0x08809B10, `struct ItemData`, [ENTRY_SIZE] bytes each)
 * for the item getters of `src/bmitem.c`. An item is `uses << 8 | index`.
 */
internal class ItemInfo(private val rom: RomView) {

    /**
     * Ported from src/bmitem.c: GetItemData
     *
     * ROM address of `gItemData[itemIndex]`.
     */
    fun getItemData(itemIndex: Int): Int = ITEM_DATA + itemIndex * ENTRY_SIZE

    /**
     * Transcribed from src/bmitem.c: GetItemIndex (not verified: a dropped or narrowed mask
     * survives the traced loads)
     *
     * `ITEM_INDEX(item)`: the low byte.
     */
    fun getItemIndex(item: Int): Int = item and 0xFF

    /**
     * Ported from src/bmitem.c: GetItemType
     *
     * The `weaponType` of [item], 0xFF for no item.
     */
    fun getItemType(item: Int): Int =
        if (item == 0) 0xFF else rom.u8(getItemData(getItemIndex(item)) + 0x07)

    /**
     * Ported from src/bmitem.c: GetItemAttributes
     *
     * The `attributes` word of [item].
     */
    fun getItemAttributes(item: Int): Int = rom.u32(getItemData(getItemIndex(item)) + 0x08)

    /**
     * Ported from src/bmitem.c: GetItemMaxUses
     *
     * 0xFF for unbreakable items, otherwise the `maxUses` of [item].
     */
    fun getItemMaxUses(item: Int): Int =
        if ((getItemAttributes(item) and IA_UNBREAKABLE) != 0) {
            0xFF
        } else {
            rom.u8(getItemData(getItemIndex(item)) + 0x14)
        }

    /**
     * Ported from src/bmitem.c: GetItemRequiredExp
     *
     * The `weaponRank` of [item].
     */
    fun getItemRequiredExp(item: Int): Int = rom.u8(getItemData(getItemIndex(item)) + 0x1C)

    /**
     * Ported from src/bmitem.c: GetItemStatBonuses
     *
     * The `pStatBonuses` pointer of [item] (0 for none).
     */
    fun getItemStatBonuses(item: Int): Int = rom.u32(getItemData(getItemIndex(item)) + 0x0C)

    /**
     * Ported from src/bmitem.c: GetItemHpBonus
     *
     * The (signed) `hpBonus` of [item], 0 for no item or no stat bonuses.
     */
    fun getItemHpBonus(item: Int): Int {
        if (item == 0) return 0
        val bonuses = getItemStatBonuses(item)
        return if (bonuses != 0) rom.u8(bonuses).toByte().toInt() else 0
    }

    /**
     * Ported from src/bmitem.c: MakeNewItem
     *
     * A fresh item word for [item]: full uses (0 for unbreakable items) and its index.
     */
    fun makeNewItem(item: Int): Int {
        var uses = getItemMaxUses(item)
        if ((getItemAttributes(item) and IA_UNBREAKABLE) != 0) uses = 0
        return (uses shl 8) + getItemIndex(item)
    }

    companion object {
        /** `gItemData`. */
        const val ITEM_DATA: Int = 0x08809B10

        /** `sizeof(struct ItemData)`. */
        const val ENTRY_SIZE: Int = 0x24

        const val IA_WEAPON: Int = 1 shl 0
        const val IA_MAGIC: Int = 1 shl 1
        const val IA_STAFF: Int = 1 shl 2
        const val IA_UNBREAKABLE: Int = 1 shl 3
        const val IA_LOCK_3: Int = 1 shl 10
        const val IA_LOCK_1: Int = 1 shl 11
        const val IA_LOCK_2: Int = 1 shl 12
        const val IA_LOCK_0: Int = 1 shl 13
        const val IA_UNUSABLE: Int = 1 shl 16
        const val IA_LOCK_4: Int = 1 shl 18
        const val IA_LOCK_5: Int = 1 shl 19
        const val IA_LOCK_6: Int = 1 shl 20
        const val IA_LOCK_7: Int = 1 shl 21
        const val IA_LOCK_ANY: Int =
            IA_LOCK_0 or IA_LOCK_1 or IA_LOCK_2 or IA_LOCK_3 or IA_LOCK_4 or IA_LOCK_5 or
                IA_LOCK_6 or IA_LOCK_7 or IA_UNUSABLE
    }
}
