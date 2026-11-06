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
     * Transcribed from src/bmitem.c: GetItemType (not verified: no test checks it against a trace
     *   yet)
     *
     * The `weaponType` of [item], 0xFF for no item.
     */
    fun getItemType(item: Int): Int =
        if (item == 0) 0xFF else rom.u8(getItemData(getItemIndex(item)) + 0x07)

    /**
     * Transcribed from src/bmitem.c: GetItemAttributes (not verified: no test checks it against a
     *   trace yet)
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
     * Transcribed from src/bmitem.c: GetItemRequiredExp (not verified: no test checks it against a
     *   trace yet)
     *
     * The `weaponRank` of [item].
     */
    fun getItemRequiredExp(item: Int): Int = rom.u8(getItemData(getItemIndex(item)) + 0x1C)

    /**
     * Transcribed from src/bmitem.c: GetItemStatBonuses (not verified: no test checks it against a
     *   trace yet)
     *
     * The `pStatBonuses` pointer of [item] (0 for none).
     */
    fun getItemStatBonuses(item: Int): Int = rom.u32(getItemData(getItemIndex(item)) + 0x0C)

    /**
     * Transcribed from src/bmitem.c: GetItemHpBonus (not verified: no test checks it against a
     *   trace yet)
     *
     * The (signed) `hpBonus` of [item], 0 for no item or no stat bonuses.
     */
    fun getItemHpBonus(item: Int): Int {
        if (item == 0) return 0
        val bonuses = getItemStatBonuses(item)
        return if (bonuses != 0) rom.u8(bonuses).toByte().toInt() else 0
    }

    /**
     * Ported from src/bmitem.c: GetItemMight
     *
     * The `might` of [item].
     */
    fun getItemMight(item: Int): Int = rom.u8(getItemData(getItemIndex(item)) + 0x15)

    /**
     * Ported from src/bmitem.c: GetItemHit
     *
     * The `hit` of [item].
     */
    fun getItemHit(item: Int): Int = rom.u8(getItemData(getItemIndex(item)) + 0x16)

    /**
     * Transcribed from src/bmitem.c: GetItemWeight (not verified: no trace output depends
     *   on it)
     *
     * The `weight` of [item].
     */
    fun getItemWeight(item: Int): Int = rom.u8(getItemData(getItemIndex(item)) + 0x17)

    /**
     * Ported from src/bmitem.c: GetItemCrit
     *
     * The `crit` of [item].
     */
    fun getItemCrit(item: Int): Int = rom.u8(getItemData(getItemIndex(item)) + 0x18)

    /**
     * Ported from src/bmitem.c: GetItemMinRange
     *
     * The minimum range of [item] (the high nibble of `encodedRange`).
     */
    fun getItemMinRange(item: Int): Int = rom.u8(getItemData(getItemIndex(item)) + 0x19) shr 4

    /**
     * Transcribed from src/bmitem.c: GetItemMaxRange (not verified: a mask narrowed to
     *   3 bits survives the traced weapons)
     *
     * The maximum range of [item] (the low nibble of `encodedRange`).
     */
    fun getItemMaxRange(item: Int): Int = rom.u8(getItemData(getItemIndex(item)) + 0x19) and 0xF

    /**
     * Transcribed from src/bmitem.c: GetItemWeaponEffect (not verified: no trace output depends
     *   on it)
     *
     * The `weaponEffectId` of [item].
     */
    fun getItemWeaponEffect(item: Int): Int = rom.u8(getItemData(getItemIndex(item)) + 0x1F)

    /**
     * Transcribed from src/bmitem.c: GetItemEffectiveness (not verified: no trace output depends
     *   on it)
     *
     * The `pEffectiveness` pointer of [item] (0 for none).
     */
    fun getItemEffectiveness(item: Int): Int = rom.u32(getItemData(getItemIndex(item)) + 0x10)

    /**
     * Transcribed from src/bmitem.c: GetItemPowBonus (not verified: the traced weapons have no
     *   stat bonuses, so a wrong field offset survives)
     *
     * The (signed) `powBonus` of [item], 0 for no item or no stat bonuses.
     */
    fun getItemPowBonus(item: Int): Int = statBonus(item, 1)

    /**
     * Transcribed from src/bmitem.c: GetItemSklBonus (not verified: see [getItemPowBonus])
     *
     * The (signed) `sklBonus` of [item].
     */
    fun getItemSklBonus(item: Int): Int = statBonus(item, 2)

    /**
     * Transcribed from src/bmitem.c: GetItemSpdBonus (not verified: see [getItemPowBonus])
     *
     * The (signed) `spdBonus` of [item].
     */
    fun getItemSpdBonus(item: Int): Int = statBonus(item, 3)

    /**
     * Transcribed from src/bmitem.c: GetItemDefBonus (not verified: see [getItemPowBonus])
     *
     * The (signed) `defBonus` of [item].
     */
    fun getItemDefBonus(item: Int): Int = statBonus(item, 4)

    /**
     * Transcribed from src/bmitem.c: GetItemResBonus (not verified: see [getItemPowBonus])
     *
     * The (signed) `resBonus` of [item].
     */
    fun getItemResBonus(item: Int): Int = statBonus(item, 5)

    /**
     * Transcribed from src/bmitem.c: GetItemLckBonus (not verified: see [getItemPowBonus])
     *
     * The (signed) `lckBonus` of [item].
     */
    fun getItemLckBonus(item: Int): Int = statBonus(item, 6)

    private fun statBonus(item: Int, field: Int): Int {
        if (item == 0) return 0
        val bonuses = getItemStatBonuses(item)
        return if (bonuses != 0) rom.u8(bonuses + field).toByte().toInt() else 0
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
        const val IA_MAGICDAMAGE: Int = 1 shl 6
        const val IA_UNCOUNTERABLE: Int = 1 shl 7
        const val IA_REVERTTRIANGLE: Int = 1 shl 8
        const val IA_NEGATE_FLYING: Int = 1 shl 14
        const val IA_NEGATE_CRIT: Int = 1 shl 15
        const val IA_NEGATE_DEFENSE: Int = 1 shl 17
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
