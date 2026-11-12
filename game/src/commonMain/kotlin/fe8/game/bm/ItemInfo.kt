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
     * The `weaponType` of [item], an `ITYPE_*` id (0 sword, 1 lance, 2 axe, 3 bow, 4 staff,
     * 5 anima, 6 light, 7 dark, 8 ballista, ...), 0xFF for no item.
     */
    public fun getItemType(item: Int): Int =
        if (item == 0) 0xFF else rom.u8(getItemData(getItemIndex(item)) + 0x07)

    /**
     * Ported from src/bmitem.c: GetItemAttributes
     *
     * The `attributes` word of [item]: its `IA_*` flags (weapon, magic, staff, unbreakable,
     * uncounterable, weapon locks ...).
     */
    public fun getItemAttributes(item: Int): Int = rom.u32(getItemData(getItemIndex(item)) + 0x08)

    /**
     * Ported from src/bmitem.c: GetItemMaxUses
     *
     * The `maxUses` of [item] (the uses of a new one), or 0xFF for unbreakable items.
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
     * The `weaponRank` of [item]: the weapon exp a unit needs to wield it (the `WPN_EXP_*` rank
     * thresholds, e.g. 31 for D, 251 for S).
     */
    public fun getItemRequiredExp(item: Int): Int = rom.u8(getItemData(getItemIndex(item)) + 0x1C)

    /**
     * Transcribed from src/bmitem.c: GetItemStatBonuses (not verified: no test checks it against a
     *   trace yet)
     *
     * The `pStatBonuses` pointer of [item], a `struct ItemStatBonuses` (s8 hp, pow, skl, spd,
     * def, res, lck, mov, con) the item grants while carried; 0 for none.
     */
    public fun getItemStatBonuses(item: Int): Int = rom.u32(getItemData(getItemIndex(item)) + 0x0C)

    /**
     * Transcribed from src/bmitem.c: GetItemHpBonus (not verified: no test checks it against a
     *   trace yet)
     *
     * The (signed) `hpBonus` (first byte of the stat bonus record) of [item], 0 for no item or
     * no stat bonuses.
     */
    public fun getItemHpBonus(item: Int): Int {
        if (item == 0) return 0
        val bonuses = getItemStatBonuses(item)
        return if (bonuses != 0) rom.u8(bonuses).toByte().toInt() else 0
    }

    /**
     * Ported from src/bmitem.c: GetItemMight
     *
     * The `might` of [item]: the weapon's base damage.
     */
    public fun getItemMight(item: Int): Int = rom.u8(getItemData(getItemIndex(item)) + 0x15)

    /**
     * Ported from src/bmitem.c: GetItemHit
     *
     * The `hit` of [item]: the weapon's base accuracy.
     */
    public fun getItemHit(item: Int): Int = rom.u8(getItemData(getItemIndex(item)) + 0x16)

    /**
     * Transcribed from src/bmitem.c: GetItemWeight (not verified: no trace output depends
     *   on it)
     *
     * The `weight` of [item]; weight beyond the wielder's Con slows the attack speed.
     */
    public fun getItemWeight(item: Int): Int = rom.u8(getItemData(getItemIndex(item)) + 0x17)

    /**
     * Ported from src/bmitem.c: GetItemCrit
     *
     * The `crit` of [item]: the weapon's base critical chance.
     */
    public fun getItemCrit(item: Int): Int = rom.u8(getItemData(getItemIndex(item)) + 0x18)

    /**
     * Ported from src/bmitem.c: GetItemEncodedRange (inline)
     *
     * The `encodedRange` byte of [item]: the minimum range in the high nibble and the maximum in
     * the low nibble.
     */
    public fun getItemEncodedRange(item: Int): Int =
        rom.u8(getItemData(getItemIndex(item)) + 0x19)

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
     * The maximum range of [item] (the low nibble of `encodedRange`; an encoded range of 0x10
     * is shown as "1-mag/2", a range that scales with the unit's magic).
     */
    public fun getItemMaxRange(item: Int): Int = rom.u8(getItemData(getItemIndex(item)) + 0x19) and 0xF

    /**
     * Transcribed from src/bmitem.c: GetItemWeaponEffect (not verified: no trace output depends
     *   on it)
     *
     * The `weaponEffectId` of [item]: a `WPN_EFFECT_*` id (0 none, 1 poison, 2 HP drain,
     * 3 HP halve, 4 devil, 5 petrify).
     */
    public fun getItemWeaponEffect(item: Int): Int = rom.u8(getItemData(getItemIndex(item)) + 0x1F)

    /**
     * Transcribed from src/bmitem.c: GetItemEffectiveness (not verified: no trace output depends
     *   on it)
     *
     * The `pEffectiveness` pointer of [item]: a list of class ids (ending in 0) the weapon is
     * effective against; 0 for none.
     */
    public fun getItemEffectiveness(item: Int): Int = rom.u32(getItemData(getItemIndex(item)) + 0x10)

    /**
     * Transcribed from src/bmitem.c: GetItemPowBonus (not verified: the traced weapons have no
     *   stat bonuses, so a wrong field offset survives)
     *
     * The (signed) `powBonus` (Str / Mag) of [item], 0 for no item or no stat bonuses.
     */
    public fun getItemPowBonus(item: Int): Int = statBonus(item, 1)

    /**
     * Transcribed from src/bmitem.c: GetItemSklBonus (not verified: see [getItemPowBonus])
     *
     * The (signed) `sklBonus` (Skl) of [item].
     */
    public fun getItemSklBonus(item: Int): Int = statBonus(item, 2)

    /**
     * Transcribed from src/bmitem.c: GetItemSpdBonus (not verified: see [getItemPowBonus])
     *
     * The (signed) `spdBonus` (Spd) of [item].
     */
    public fun getItemSpdBonus(item: Int): Int = statBonus(item, 3)

    /**
     * Transcribed from src/bmitem.c: GetItemDefBonus (not verified: see [getItemPowBonus])
     *
     * The (signed) `defBonus` (Def) of [item].
     */
    public fun getItemDefBonus(item: Int): Int = statBonus(item, 4)

    /**
     * Transcribed from src/bmitem.c: GetItemResBonus (not verified: see [getItemPowBonus])
     *
     * The (signed) `resBonus` (Res) of [item].
     */
    public fun getItemResBonus(item: Int): Int = statBonus(item, 5)

    /**
     * Transcribed from src/bmitem.c: GetItemLckBonus (not verified: see [getItemPowBonus])
     *
     * The (signed) `lckBonus` (Lck) of [item].
     */
    public fun getItemLckBonus(item: Int): Int = statBonus(item, 6)

    /**
     * The signed stat bonus at byte [field] (`ItemStatBonuses`: 1 pow, 2 skl, 3 spd, 4 def,
     * 5 res, 6 lck) of the item's `pStatBonuses` record, 0 for no item or no record.
     */
    private fun statBonus(item: Int, field: Int): Int {
        if (item == 0) return 0
        val bonuses = getItemStatBonuses(item)
        return if (bonuses != 0) rom.u8(bonuses + field).toByte().toInt() else 0
    }

    /**
     * Ported from src/bmitem.c: GetItemUses
     *
     * The uses left of [item] (the high byte), 0xFF for an unbreakable item (that branch is
     * not verified: the captured weapon breaks).
     */
    public fun getItemUses(item: Int): Int =
        if ((getItemAttributes(item) and IA_UNBREAKABLE) != 0) 0xFF else (item shr 8) and 0xFF

    /**
     * Ported from src/bmitem.c: GetItemIconId
     *
     * The `iconId` of [item]: its icon in the item icon sheet, -1 for no item.
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
            // 0x80 starts a two-byte text control code; `[Tact]` (0x80 0x20) is the tactician's
            // name, any other code is copied through unchanged (`StrInsertTact`, src/msg.c).
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
     * The name of [item] with "a "/"an " in front ("an" before a vowel; a fresh 0-terminated
     * array, the original inserts into its shared buffer); "Some " for the Junafruit and no
     * article for the items of `NO_ARTICLE`. [capitalize] selects the capitalized forms.
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
     * A fresh item word for [item]: full uses in the high byte (0 for unbreakable items, which
     * do not count uses) and its index in the low byte.
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

        /**
         * Items for which `GetItemNameWithArticle` adds no article: the named legendary weapons
         * (Reginleif, Fimbulvetr, Aircalibur, Fenrir, Sieglinde, Siegmund, Naglfar, Audhulma,
         * Vidofnir, Garm, Nidhogg, Excalibur, Ivaldi, Gleipnir, Latona), Gold, Metis's Tome
         * (`ITEM_METISSTOME`) and the gold-amount items.
         */
        private val NO_ARTICLE: Set<Int> = setOf(
            0x78, 0x3C, 0xB6, 0x49, 0x85, 0x92, 0x8F, 0x91, 0x8E, 0x93, 0x94, 0x3E, 0x87, 0x4A,
            0x8C, 0x77, 0x89, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0xB8, 0xB9, 0x9F, 0xA0,
        )

        // `IA_*` item attribute bits (include/bmitem.h). `IA_MAGIC`: a magic weapon, refused while
        // the user is silenced or magic-sealed; `IA_MAGICDAMAGE`: hits against resistance;
        // `IA_NEGATE_*`: held in the inventory to cancel flier effectiveness / enemy crits, or
        // (`_DEFENSE`) set on a weapon that ignores defense. `IA_LOCK_n`: usable only by units
        // whose character or class has `CA_LOCK_n` (`IA_LOCK_3` is the monster lock;
        // `CanUnitUseWeapon` does not test `IA_LOCK_0`); `IA_UNUSABLE` additionally needs the
        // item to be unsealed for the unit.
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
        internal const val IA_BRAVE: Int = 1 shl 5
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
