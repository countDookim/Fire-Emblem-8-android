package gbafe.extract.fe8.classes

import gbafe.extract.Json
import gbafe.extract.fe8.items.ItemsExtractor

/**
 * Extracts the class table (`gClassData`, `struct ClassData` in the decomp's `include/bmunit.h`, values in
 * `src/data_classes.c`) into `data/classes.json` (format `gbafe.classes`, schema `schemas/classes.schema.json`).
 *
 * Unlike items, the table has **no row for class 0**: `GetClassData(n)` returns `gClassData[n - 1]`, so an
 * entry's position + 1 is its class number. Values come from the ROM; terrain tables (65 bytes,
 * `TERRAIN_COUNT`) and battle-animation lists are resolved and inlined, so the JSON has no addresses.
 */
public object ClassesExtractor {
    /** `sizeof(struct ClassData)`. */
    public const val ENTRY_SIZE: Int = 0x54

    /** Format tag written to the file. */
    public const val FORMAT: String = "gbafe.classes"

    /** Format version. */
    public const val VERSION: Int = 1

    /** `TERRAIN_COUNT` in `include/constants/terrains.h`: length of every terrain lookup table. */
    public const val TERRAIN_COUNT: Int = 0x41

    /** `CA_*` attribute bits in bmunit.h, bit 0 first (lower-cased, `CA_` dropped). */
    public val ATTRIBUTES: List<String> = listOf(
        "mounted_aid", "canto", "steal", "thief", "dance", "play", "crit_bonus", "ballistae", "promoted",
        "supply", "mounted", "wyvern", "pegasus", "lord", "female", "boss", "lock_1", "lock_2", "lock_3",
        "max_level_10", "unselectable", "triangle_attack_pegasi", "triangle_attack_armors", "bit_23",
        "negate_lethality", "assassin", "magic_seal", "summon", "lock_4", "lock_5", "lock_6", "lock_7",
    )

    /** Stat field order shared by bases, caps, growths and promotion gains. */
    public val STATS: List<String> = listOf("hp", "pow", "skl", "spd", "def", "res", "con", "mov")

    /**
     * Builds the `classes.json` document.
     *
     * @param names class number → decomp constant (`CLASS_EPHRAIM_LORD`); id = name without `CLASS_`.
     *   Missing names fall back to `CLASS_XX`.
     * @param itemNames item number → item id, used for battle animations keyed by a specific item.
     */
    public fun extract(
        rom: ByteArray, tableAddress: Int, count: Int, names: Map<Int, String>, itemNames: Map<Int, String> = emptyMap(),
    ): Map<String, Any?> {
        fun classId(n: Int) = names[n]?.let { gbafe.extract.DecompEnums.id(it, "CLASS_") } ?: "CLASS_%02X".format(n)
        val entries = ArrayList<Map<String, Any?>>(count)
        for (i in 0 until count) {
            val o = off(tableAddress) + i * ENTRY_SIZE
            val number = u8(rom, o + 4)
            require(number == i + 1) { "gClassData[$i].number is $number" }
            val attrs = u32(rom, o + 0x28)
            val promotion = u8(rom, o + 5)
            entries += linkedMapOf(
                "id" to classId(number),
                "nameText" to u16(rom, o), "descText" to u16(rom, o + 2),
                "promotion" to if (promotion == 0) null else classId(promotion),
                "mapSprite" to u8(rom, o + 6),
                "slowWalking" to (u8(rom, o + 7) != 0),
                "defaultPortrait" to u16(rom, o + 8),
                "sortOrder" to u8(rom, o + 0x0A),
                "bases" to stats(rom, o + 0x0B, STATS),
                "caps" to stats(rom, o + 0x13, STATS.take(7)),
                "relativePower" to s8(rom, o + 0x1A),
                "growths" to stats(rom, o + 0x1B, listOf("hp", "pow", "skl", "spd", "def", "res", "lck")),
                "promotionGains" to stats(rom, o + 0x22, STATS.take(6)),
                "attributes" to ATTRIBUTES.filterIndexed { bit, _ -> attrs and (1 shl bit) != 0 },
                "baseRanks" to ItemsExtractor.WEAPON_TYPES.take(8).withIndex()
                    .associateTo(LinkedHashMap()) { (k, t) -> t to u8(rom, o + 0x2C + k) },
                "battleAnims" to battleAnims(rom, u32(rom, o + 0x34), itemNames),
                "moveCost" to linkedMapOf(
                    "normal" to terrain(rom, u32(rom, o + 0x38)),
                    "rain" to terrain(rom, u32(rom, o + 0x3C)),
                    "snow" to terrain(rom, u32(rom, o + 0x40)),
                ),
                "terrainAvoid" to terrain(rom, u32(rom, o + 0x44)),
                "terrainDefense" to terrain(rom, u32(rom, o + 0x48)),
                "terrainResistance" to terrain(rom, u32(rom, o + 0x4C)),
                "terrainUnknown50" to terrain(rom, u32(rom, o + 0x50)),
            )
        }
        return linkedMapOf("format" to FORMAT, "game" to "fe8", "version" to VERSION, "entries" to entries)
    }

    /** [extract] serialised with [Json]. */
    public fun extractJson(rom: ByteArray, tableAddress: Int, count: Int, names: Map<Int, String>, itemNames: Map<Int, String> = emptyMap()): String =
        Json.write(extract(rom, tableAddress, count, names, itemNames))

    private fun stats(rom: ByteArray, o: Int, keys: List<String>): Map<String, Any?> =
        keys.withIndex().associateTo(LinkedHashMap()) { (k, name) -> name to s8(rom, o + k) }

    private fun terrain(rom: ByteArray, ptr: Int): List<Int>? {
        if (ptr == 0) return null
        val o = off(ptr)
        return List(TERRAIN_COUNT) { s8(rom, o + it) }
    }

    /**
     * `struct BattleAnimDef` list, terminated by `wtype == 0`: `wtype >= 0x100` matches a weapon type
     * (`wtype - 0x100`), otherwise a specific item number (see `src/banim-ekrcmd.c`).
     */
    private fun battleAnims(rom: ByteArray, ptr: Int, itemNames: Map<Int, String>): List<Map<String, Any?>>? {
        if (ptr == 0) return null
        var o = off(ptr)
        val out = ArrayList<Map<String, Any?>>()
        while (true) {
            val wtype = u16(rom, o)
            if (wtype == 0) break
            val anim = u16(rom, o + 2)
            out += if (wtype >= 0x100) {
                linkedMapOf("weaponType" to ItemsExtractor.WEAPON_TYPES.getOrElse(wtype - 0x100) { error("banim wtype $wtype") }, "anim" to anim)
            } else {
                linkedMapOf("item" to (itemNames[wtype]?.let { gbafe.extract.DecompEnums.id(it, "ITEM_") } ?: "ITEM_%02X".format(wtype)), "anim" to anim)
            }
            o += 4
        }
        return out
    }

    private fun off(addr: Int): Int {
        require(addr ushr 25 == 4) { "not a ROM address: 0x${addr.toUInt().toString(16)}" }
        return addr - 0x08000000
    }

    private fun s8(b: ByteArray, o: Int) = b[o].toInt()
    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)
}
