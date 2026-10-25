package gbafe.extract.fe8.items

import gbafe.extract.Json

/**
 * Extracts the item table (`gItemData`, `struct ItemData` in the decomp's `include/bmitem.h`, values in
 * `src/data_items.c`) from the ROM into `data/items.json` (format `gbafe.items`, schema
 * `schemas/items.schema.json`).
 *
 * The ROM is the ground truth: values are read from the ROM bytes, the decomp only supplies names. Rows
 * stay in ROM order, so a row's position is its vanilla item number (`number` is also written and checked).
 * Pointers (stat bonuses, effectiveness lists) are resolved and inlined so the JSON has no addresses.
 */
public object ItemsExtractor {
    /** `sizeof(struct ItemData)` (0x21 bytes of fields, padded to 4). */
    public const val ENTRY_SIZE: Int = 0x24

    /** Format tag written to the file. */
    public const val FORMAT: String = "gbafe.items"

    /** Format version (bump with a loader migration, see docs/decisions/0002-data-schemas.md). */
    public const val VERSION: Int = 1

    /** `ITYPE_*` in bmitem.h, in value order. */
    public val WEAPON_TYPES: List<String> =
        listOf("SWORD", "LANCE", "AXE", "BOW", "STAFF", "ANIMA", "LIGHT", "DARK", "BALLISTA", "ITEM", "DRAGONSTONE", "MONSTER", "DANCE")

    /** `IA_*` attribute bits in bmitem.h, bit 0 first (names lower-cased, `IA_` dropped). */
    public val ATTRIBUTES: List<String> = listOf(
        "weapon", "magic", "staff", "unbreakable", "unsellable", "brave", "magic_damage", "uncounterable",
        "reverse_triangle", "hammerne", "lock_3", "lock_1", "lock_2", "lock_0", "negate_flying", "negate_crit",
        "unusable", "negate_defense", "lock_4", "lock_5", "lock_6", "lock_7",
    )

    /** `WPN_EFFECT_*` in bmitem.h. */
    public val WEAPON_EFFECTS: List<String> = listOf("NONE", "POISON", "HP_DRAIN", "HP_HALVE", "DEVIL", "PETRIFY")

    /** `struct ItemStatBonuses` fields, in byte order. */
    public val STAT_BONUS_FIELDS: List<String> = listOf("hp", "pow", "skl", "spd", "def", "res", "lck", "mov", "con")

    /**
     * Builds the `items.json` document.
     *
     * @param rom whole ROM image.
     * @param tableAddress GBA address of `gItemData` (from the decomp symbol table).
     * @param count number of rows (`ITEM_*` count; the symbol size divided by [ENTRY_SIZE]).
     * @param names item number → decomp constant (e.g. `ITEM_SWORD_IRON`); the id is the name without
     *   `ITEM_`. Missing names fall back to `ITEM_XX` hex ids.
     */
    public fun extract(rom: ByteArray, tableAddress: Int, count: Int, names: Map<Int, String>): Map<String, Any?> {
        val entries = ArrayList<Map<String, Any?>>(count)
        for (i in 0 until count) {
            val o = romOffset(tableAddress) + i * ENTRY_SIZE
            val number = u8(rom, o + 0x06)
            require(number == i) { "gItemData[$i].number is $number" }
            val attrs = u32(rom, o + 0x08)
            val attrNames = ATTRIBUTES.filterIndexed { bit, _ -> attrs and (1 shl bit) != 0 }
            require(attrs ushr ATTRIBUTES.size == 0) { "item $i has unknown attribute bits 0x${attrs.toString(16)}" }
            val range = u8(rom, o + 0x19)
            entries += linkedMapOf(
                "id" to (names[i]?.let { gbafe.extract.DecompEnums.id(it, "ITEM_") } ?: "ITEM_%02X".format(i)),
                "nameText" to u16(rom, o), "descText" to u16(rom, o + 2), "useDescText" to u16(rom, o + 4),
                "weaponType" to WEAPON_TYPES.getOrElse(u8(rom, o + 0x07)) { error("item $i: weapon type $it") },
                "attributes" to attrNames,
                "statBonuses" to statBonuses(rom, u32(rom, o + 0x0C)),
                "effectiveAgainst" to effectiveness(rom, u32(rom, o + 0x10)),
                "uses" to u8(rom, o + 0x14),
                "might" to u8(rom, o + 0x15), "hit" to u8(rom, o + 0x16),
                "weight" to u8(rom, o + 0x17), "crit" to u8(rom, o + 0x18),
                "minRange" to (range shr 4), "maxRange" to (range and 0xF),
                "costPerUse" to u16(rom, o + 0x1A),
                "rankExp" to u8(rom, o + 0x1C),
                "icon" to u8(rom, o + 0x1D),
                "useEffect" to u8(rom, o + 0x1E),
                "weaponEffect" to WEAPON_EFFECTS.getOrElse(u8(rom, o + 0x1F)) { error("item $i: weapon effect $it") },
                "weaponExp" to u8(rom, o + 0x20),
            )
        }
        return linkedMapOf("format" to FORMAT, "game" to "fe8", "version" to VERSION, "entries" to entries)
    }

    /** [extract] serialised with [Json]. */
    public fun extractJson(rom: ByteArray, tableAddress: Int, count: Int, names: Map<Int, String>): String =
        Json.write(extract(rom, tableAddress, count, names))

    private fun statBonuses(rom: ByteArray, ptr: Int): Map<String, Any?>? {
        if (ptr == 0) return null
        val o = romOffset(ptr)
        return STAT_BONUS_FIELDS.withIndex().associateTo(LinkedHashMap()) { (k, name) -> name to rom[o + k].toInt() }
    }

    /** Effectiveness lists are 0-terminated `u8` class numbers. Class names arrive with M2-05. */
    private fun effectiveness(rom: ByteArray, ptr: Int): List<Int>? {
        if (ptr == 0) return null
        var o = romOffset(ptr)
        val out = ArrayList<Int>()
        while (rom[o].toInt() != 0) out += u8(rom, o++)
        return out
    }

    private fun romOffset(addr: Int): Int {
        require(addr ushr 25 == 0x08000000 ushr 25) { "not a ROM address: 0x${addr.toUInt().toString(16)}" }
        return addr - 0x08000000
    }

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)
}
