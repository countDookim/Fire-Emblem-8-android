package fe8.game.bm

import fe8.game.RomView
import gbafe.text.MsgStrings

/**
 * Read-only access to `gItemData` (ROM 0x08809B10, `struct ItemData`, [ENTRY_SIZE] bytes each)
 * for the item getters of `src/bmitem.c`. An item is `uses << 8 | index`.
 */
public class ItemInfo(private val rom: RomView) {

    /**
     * Ported from src/bmitem.c: GetItemData
     *
     * ROM address of `gItemData[itemIndex]`.
     */
    public fun getItemData(itemIndex: Int): Int = ITEM_DATA + itemIndex * ENTRY_SIZE

    /**
     * Transcribed from src/bmitem.c: GetItemIndex (not verified: a dropped or narrowed mask
     * survives the traced loads)
     *
     * `ITEM_INDEX(item)`: the low byte.
     */
    public fun getItemIndex(item: Int): Int = item and 0xFF

    /**
     * Ported from src/bmitem.c: GetItemType
     *
     * The `weaponType` of [item], 0xFF for no item.
     */
    public fun getItemType(item: Int): Int =
        if (item == 0) 0xFF else rom.u8(getItemData(getItemIndex(item)) + 0x07)

    /**
     * Ported from src/bmitem.c: GetItemAttributes
     *
     * The `attributes` word of [item].
     */
    public fun getItemAttributes(item: Int): Int = rom.u32(getItemData(getItemIndex(item)) + 0x08)

    /**
     * Ported from src/bmitem.c: GetItemMaxUses
     *
     * 0xFF for unbreakable items, otherwise the `maxUses` of [item].
     */
    public fun getItemMaxUses(item: Int): Int =
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
    public fun getItemRequiredExp(item: Int): Int = rom.u8(getItemData(getItemIndex(item)) + 0x1C)

    /**
     * Transcribed from src/bmitem.c: GetItemStatBonuses (not verified: no test checks it against a
     *   trace yet)
     *
     * The `pStatBonuses` pointer of [item] (0 for none).
     */
    public fun getItemStatBonuses(item: Int): Int = rom.u32(getItemData(getItemIndex(item)) + 0x0C)

    /**
     * Transcribed from src/bmitem.c: GetItemHpBonus (not verified: no test checks it against a
     *   trace yet)
     *
     * The (signed) `hpBonus` of [item], 0 for no item or no stat bonuses.
     */
    public fun getItemHpBonus(item: Int): Int {
        if (item == 0) return 0
        val bonuses = getItemStatBonuses(item)
        return if (bonuses != 0) rom.u8(bonuses).toByte().toInt() else 0
    }

    /**
     * Ported from src/bmitem.c: GetItemMight
     *
     * The `might` of [item].
     */
    public fun getItemMight(item: Int): Int = rom.u8(getItemData(getItemIndex(item)) + 0x15)

    /**
     * Ported from src/bmitem.c: GetItemHit
     *
     * The `hit` of [item].
     */
    public fun getItemHit(item: Int): Int = rom.u8(getItemData(getItemIndex(item)) + 0x16)

    /**
     * Transcribed from src/bmitem.c: GetItemWeight (not verified: no trace output depends
     *   on it)
     *
     * The `weight` of [item].
     */
    public fun getItemWeight(item: Int): Int = rom.u8(getItemData(getItemIndex(item)) + 0x17)

    /**
     * Ported from src/bmitem.c: GetItemCrit
     *
     * The `crit` of [item].
     */
    public fun getItemCrit(item: Int): Int = rom.u8(getItemData(getItemIndex(item)) + 0x18)

    /**
     * Ported from src/bmitem.c: GetItemMinRange
     *
     * The minimum range of [item] (the high nibble of `encodedRange`).
     */
    public fun getItemMinRange(item: Int): Int = rom.u8(getItemData(getItemIndex(item)) + 0x19) shr 4

    /**
     * Transcribed from src/bmitem.c: GetItemMaxRange (not verified: a mask narrowed to
     *   3 bits survives the traced weapons)
     *
     * The maximum range of [item] (the low nibble of `encodedRange`).
     */
    public fun getItemMaxRange(item: Int): Int = rom.u8(getItemData(getItemIndex(item)) + 0x19) and 0xF

    /**
     * Transcribed from src/bmitem.c: GetItemWeaponEffect (not verified: no trace output depends
     *   on it)
     *
     * The `weaponEffectId` of [item].
     */
    public fun getItemWeaponEffect(item: Int): Int = rom.u8(getItemData(getItemIndex(item)) + 0x1F)

    /**
     * Transcribed from src/bmitem.c: GetItemEffectiveness (not verified: no trace output depends
     *   on it)
     *
     * The `pEffectiveness` pointer of [item] (0 for none).
     */
    public fun getItemEffectiveness(item: Int): Int = rom.u32(getItemData(getItemIndex(item)) + 0x10)

    /**
     * Transcribed from src/bmitem.c: GetItemPowBonus (not verified: the traced weapons have no
     *   stat bonuses, so a wrong field offset survives)
     *
     * The (signed) `powBonus` of [item], 0 for no item or no stat bonuses.
     */
    public fun getItemPowBonus(item: Int): Int = statBonus(item, 1)

    /**
     * Transcribed from src/bmitem.c: GetItemSklBonus (not verified: see [getItemPowBonus])
     *
     * The (signed) `sklBonus` of [item].
     */
    public fun getItemSklBonus(item: Int): Int = statBonus(item, 2)

    /**
     * Transcribed from src/bmitem.c: GetItemSpdBonus (not verified: see [getItemPowBonus])
     *
     * The (signed) `spdBonus` of [item].
     */
    public fun getItemSpdBonus(item: Int): Int = statBonus(item, 3)

    /**
     * Transcribed from src/bmitem.c: GetItemDefBonus (not verified: see [getItemPowBonus])
     *
     * The (signed) `defBonus` of [item].
     */
    public fun getItemDefBonus(item: Int): Int = statBonus(item, 4)

    /**
     * Transcribed from src/bmitem.c: GetItemResBonus (not verified: see [getItemPowBonus])
     *
     * The (signed) `resBonus` of [item].
     */
    public fun getItemResBonus(item: Int): Int = statBonus(item, 5)

    /**
     * Transcribed from src/bmitem.c: GetItemLckBonus (not verified: see [getItemPowBonus])
     *
     * The (signed) `lckBonus` of [item].
     */
    public fun getItemLckBonus(item: Int): Int = statBonus(item, 6)

    /**
     * The signed stat bonus at byte [field] (1 pow, 2 skl, 3 spd, 4 def, 5 res, 6 lck) of the
     * item's `pStatBonuses` record, 0 for no item or no record.
     */
    private fun statBonus(item: Int, field: Int): Int {
        if (item == 0) return 0
        val bonuses = getItemStatBonuses(item)
        return if (bonuses != 0) rom.u8(bonuses + field).toByte().toInt() else 0
    }

    /**
     * Ported from src/bmitem.c: GetItemIconId
     *
     * The `iconId` of [item], -1 for no item.
     */
    public fun getItemIconId(item: Int): Int =
        if (item == 0) -1 else rom.u8(getItemData(getItemIndex(item)) + 0x1D)

    /**
     * Ported from src/bmitem.c: GetItemName
     *
     * A fresh 0-terminated copy of the name of [item] after `StrInsertTact`; the `[Tact]` code
     * (0x80 0x20) is replaced by [tactician] (the original reads the save's name; item names
     * have no such code).
     */
    public fun getItemName(
        item: Int,
        messages: MsgStrings,
        tactician: ByteArray = ByteArray(0),
    ): ByteArray {
        val rawName = messages.getStringFromIndex(rom.u16(getItemData(getItemIndex(item))))
        val resultBytes = ArrayList<Byte>()
        var i = 0
        while (rawName[i].toInt() != 0) {
            // 0x80 starts a text control code; the byte after it is either the tactician-name
            // code (0x20) or a code that is copied through unchanged.
            if ((rawName[i].toInt() and 0xFF) != 0x80) {
                resultBytes.add(rawName[i])
                i++
            } else {
                i++
                if (rawName[i].toInt() != 0x20) {
                    resultBytes.add(0x80.toByte())
                    resultBytes.add(rawName[i])
                } else {
                    tactician.forEach { resultBytes.add(it) }
                }
                i++
            }
        }
        resultBytes.add(0)
        return resultBytes.toByteArray()
    }

    /**
     * Ported from src/bmitem.c: GetItemNameWithArticle
     *
     * The name of [item] with "a "/"an " in front (a fresh 0-terminated array; the original
     * inserts into its shared buffer); "Some " for the Junafruit and no article for the unique
     * weapons and gold items. [capitalize] selects the capitalized forms.
     */
    public fun getItemNameWithArticle(item: Int, capitalize: Boolean, messages: MsgStrings): ByteArray {
        val name = getItemName(item, messages)
        val index = getItemIndex(item)
        if (index in NO_ARTICLE) return name
        val article = when {
            index == ITEM_JUNAFRUIT -> if (capitalize) "Some " else "some "
            name[0].toInt().toChar() in "AEIOUaeiou" -> if (capitalize) "An " else "an "
            else -> if (capitalize) "A " else "a "
        }
        return article.toByteArray(Charsets.US_ASCII) + name
    }

    /**
     * Ported from src/bmitem.c: MakeNewItem
     *
     * A fresh item word for [item]: full uses (0 for unbreakable items) and its index.
     */
    public fun makeNewItem(item: Int): Int {
        var uses = getItemMaxUses(item)
        if ((getItemAttributes(item) and IA_UNBREAKABLE) != 0) uses = 0
        return (uses shl 8) + getItemIndex(item)
    }

    internal companion object {
        /** `gItemData`. */
        internal const val ITEM_DATA: Int = 0x08809B10

        /** `sizeof(struct ItemData)`. */
        internal const val ENTRY_SIZE: Int = 0x24

        /** `ITEM_JUNAFRUIT`. */
        internal const val ITEM_JUNAFRUIT: Int = 0xB7

        /** Items for which `GetItemNameWithArticle` adds no article. */
        private val NO_ARTICLE: Set<Int> = setOf(
            0x78, 0x3C, 0xB6, 0x49, 0x85, 0x92, 0x8F, 0x91, 0x8E, 0x93, 0x94, 0x3E, 0x87, 0x4A,
            0x8C, 0x77, 0x89, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0xB8, 0xB9, 0x9F, 0xA0,
        )

        internal const val IA_WEAPON: Int = 1 shl 0
        internal const val IA_MAGIC: Int = 1 shl 1
        internal const val IA_MAGICDAMAGE: Int = 1 shl 6
        internal const val IA_UNCOUNTERABLE: Int = 1 shl 7
        internal const val IA_REVERTTRIANGLE: Int = 1 shl 8
        internal const val IA_NEGATE_FLYING: Int = 1 shl 14
        internal const val IA_NEGATE_CRIT: Int = 1 shl 15
        internal const val IA_NEGATE_DEFENSE: Int = 1 shl 17
        internal const val IA_STAFF: Int = 1 shl 2
        internal const val IA_UNBREAKABLE: Int = 1 shl 3
        internal const val IA_LOCK_3: Int = 1 shl 10
        internal const val IA_LOCK_1: Int = 1 shl 11
        internal const val IA_LOCK_2: Int = 1 shl 12
        internal const val IA_LOCK_0: Int = 1 shl 13
        internal const val IA_UNUSABLE: Int = 1 shl 16
        internal const val IA_LOCK_4: Int = 1 shl 18
        internal const val IA_LOCK_5: Int = 1 shl 19
        internal const val IA_LOCK_6: Int = 1 shl 20
        internal const val IA_LOCK_7: Int = 1 shl 21
        internal const val IA_LOCK_ANY: Int =
            IA_LOCK_0 or IA_LOCK_1 or IA_LOCK_2 or IA_LOCK_3 or IA_LOCK_4 or IA_LOCK_5 or
                IA_LOCK_6 or IA_LOCK_7 or IA_UNUSABLE
    }
}
