package gbafe.extract.fe8.terrains

import gba.extract.Json
import gbafe.extract.fe8.classes.ClassesExtractor

/**
 * Extracts the terrain-indexed tables of the decomp's `src/data_terrains.c` into `data/terrains.json`
 * (format `gbafe.terrains`, schema `schemas/terrains.schema.json`).
 *
 * Every table there is `s8[TERRAIN_COUNT]` (65 bytes). `entries` has one row per terrain id with the
 * per-terrain properties read by map code (`GetTerrainHealAmount`/`GetTerrainHealsStatus`, src/bmmap.c);
 * `tables` holds every other terrain table (movement costs, avoid/def/res, battle-animation grounds and
 * backgrounds, unknown ones) keyed by its decomp symbol, values from the ROM. Classes currently inline
 * copies of their tables (see ClassesExtractor); item stat-bonus tables are inlined into items.json.
 */
public object TerrainsExtractor {
    /** Format tag written to the file. */
    public const val FORMAT: String = "gbafe.terrains"

    /** Format version. */
    public const val VERSION: Int = 1

    /** Symbol of the heal-per-turn table (HP %), folded into [extract]'s `entries`. */
    public const val HEAL_AMOUNT: String = "TerrainTable_HealAmount"

    /** Symbol of the "heals status ailments" table, folded into `entries`. */
    public const val HEALS_STATUS: String = "TerrainTable_HealsStatus"

    /** Symbol-name prefixes of the 65-byte terrain tables in data_terrains.c. */
    public val TABLE_PREFIXES: List<String> = listOf("TerrainTable_", "Unk_TerrainTable_", "BanimTerrainGround", "gBanimBGLut")

    /**
     * Builds the `terrains.json` document.
     *
     * @param tables symbol name → GBA address for every terrain table to export; must contain [HEAL_AMOUNT]
     *   and [HEALS_STATUS]. Written in ascending address order (= source order).
     * @param names terrain id → `TERRAIN_*` constant; id = name without `TERRAIN_` (fallback `TERRAIN_XX`).
     */
    public fun extract(rom: ByteArray, tables: Map<String, Int>, names: Map<Int, String>): Map<String, Any?> {
        fun table(addr: Int) = List(ClassesExtractor.TERRAIN_COUNT) { rom[off(addr) + it].toInt() }
        val heal = table(tables[HEAL_AMOUNT] ?: error("missing $HEAL_AMOUNT"))
        val status = table(tables[HEALS_STATUS] ?: error("missing $HEALS_STATUS"))
        val entries = List(ClassesExtractor.TERRAIN_COUNT) { t ->
            linkedMapOf<String, Any?>(
                "id" to (names[t]?.let { gba.extract.DecompEnums.id(it, "TERRAIN_") } ?: "TERRAIN_%02X".format(t)),
                "healAmount" to heal[t],
                "healsStatus" to (status[t] != 0),
            )
        }
        val rest = tables.filterKeys { it != HEAL_AMOUNT && it != HEALS_STATUS }.entries
            .sortedBy { it.value.toUInt() }
            .associateTo(LinkedHashMap()) { (name, addr) -> name to table(addr) }
        return linkedMapOf("format" to FORMAT, "game" to "fe8", "version" to VERSION, "entries" to entries, "tables" to rest)
    }

    /** [extract] serialised with [Json]. */
    public fun extractJson(rom: ByteArray, tables: Map<String, Int>, names: Map<Int, String>): String =
        Json.write(extract(rom, tables, names))

    private fun off(addr: Int): Int {
        require(addr ushr 25 == 4) { "not a ROM address: 0x${addr.toUInt().toString(16)}" }
        return addr - 0x08000000
    }
}
