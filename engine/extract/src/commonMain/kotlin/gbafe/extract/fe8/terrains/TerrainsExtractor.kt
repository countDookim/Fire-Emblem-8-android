package gbafe.extract.fe8.terrains

import gba.extract.Json
import gbafe.extract.fe8.classes.ClassesExtractor
import gbafe.extract.hexUpper

/**
 * Extracts the terrain-indexed `s8` tables of the decomp's `src/data_terrains.c` into
 * `data/terrains.json` (format `gbafe.terrains`, schema `schemas/terrains.schema.json`).
 *
 * Each exported table is `s8[TERRAIN_COUNT]` (65 bytes, `include/constants/terrains.h`), indexed by
 * terrain id. (The file also holds one `u16[TERRAIN_COUNT]` table, `gUnknown_0880D374`, whose
 * meaning is unknown; it matches none of [TABLE_PREFIXES] and is not exported.) `entries` has one
 * row per terrain id with the properties read by `GetTerrainHealAmount` / `GetTerrainHealsStatus`
 * (src/bmmap.c): HP healed per turn as a percentage of max HP, and whether the terrain cures
 * status ailments (src/bmtarget.c). `tables` holds every other terrain table (movement costs,
 * avoid/def/res bonuses, battle-animation grounds and backgrounds, and `Unk_` tables whose meaning
 * is unknown) keyed by its decomp symbol, values from the ROM. Classes inline their own copies of
 * the movement and bonus tables (see ClassesExtractor).
 */
public object TerrainsExtractor {
    /** Format tag written to the file. */
    public const val FORMAT: String = "gbafe.terrains"

    /** Format version. */
    public const val VERSION: Int = 1

    /** Symbol of the heal-per-turn table (percent of max HP), folded into `entries`. */
    public const val HEAL_AMOUNT: String = "TerrainTable_HealAmount"

    /** Symbol of the table flagging terrains that cure status ailments, folded into `entries`. */
    public const val HEALS_STATUS: String = "TerrainTable_HealsStatus"

    /**
     * Symbol-name prefixes that select the 65-byte terrain tables in data_terrains.c (the caller
     * also keeps only symbols whose size is [ClassesExtractor.TERRAIN_COUNT]).
     */
    public val TABLE_PREFIXES: List<String> = listOf(
        "TerrainTable_",
        "Unk_TerrainTable_",
        "BanimTerrainGround",
        "gBanimBGLut",
    )

    /**
     * Builds the `terrains.json` document.
     *
     * @param tables symbol name → GBA address for every terrain table to export; must contain
     *   [HEAL_AMOUNT] and [HEALS_STATUS]. Written in ascending address order (= source order).
     * @param names terrain id → `TERRAIN_*` constant; a row's `id` is the name without `TERRAIN_`
     *   (via `DecompEnums.id`); terrain ids with no name get `TERRAIN_XX` (two hex digits).
     */
    public fun extract(
        rom: ByteArray,
        tables: Map<String, Int>,
        names: Map<Int, String>,
    ): Map<String, Any?> {
        /** Reads one signed-byte terrain table from the ROM in terrain-id order. */
        fun readTerrainTable(address: Int) = List(ClassesExtractor.TERRAIN_COUNT) {
            rom[romOffset(address) + it].toInt()
        }

        val healAmounts = readTerrainTable(tables[HEAL_AMOUNT] ?: error("missing $HEAL_AMOUNT"))
        val healsStatus = readTerrainTable(tables[HEALS_STATUS] ?: error("missing $HEALS_STATUS"))
        val entries = List(ClassesExtractor.TERRAIN_COUNT) { terrainId ->
            val terrainName = names[terrainId]?.let { gba.extract.DecompEnums.id(it, "TERRAIN_") }
                ?: "TERRAIN_${hexUpper(terrainId, 2)}"
            linkedMapOf<String, Any?>(
                "id" to terrainName,
                "healAmount" to healAmounts[terrainId],
                "healsStatus" to (healsStatus[terrainId] != 0),
            )
        }
        val remainingTables = tables.filterKeys { it != HEAL_AMOUNT && it != HEALS_STATUS }.entries
            .sortedBy { it.value.toUInt() }
            .associateTo(LinkedHashMap()) { (name, address) -> name to readTerrainTable(address) }
        return linkedMapOf(
            "format" to FORMAT,
            "game" to "fe8",
            "version" to VERSION,
            "entries" to entries,
            "tables" to remainingTables,
        )
    }

    /** [extract] serialised with [Json]. */
    public fun extractJson(
        rom: ByteArray,
        tables: Map<String, Int>,
        names: Map<Int, String>,
    ): String = Json.write(extract(rom, tables, names))

    /** Converts a GBA ROM address (0x08000000..0x09FFFFFF) to a file offset; rejects others. */
    private fun romOffset(address: Int): Int {
        require(address ushr 25 == 4) { "not a ROM address: 0x${address.toUInt().toString(16)}" }
        return address - 0x08000000
    }
}
