package gbafe.extract.fe8.characters

import gba.extract.Json
import gbafe.extract.fe8.classes.ClassesExtractor
import gbafe.extract.fe8.items.ItemsExtractor
import gbafe.extract.hexUpper

/**
 * Extracts the character table (`gCharacterData`, `struct CharacterData` in the decomp's
 * `include/bmunit.h`, values in `src/data_characters.c`) into `data/characters.json` (format
 * `gbafe.characters`, schema `schemas/characters.schema.json`).
 *
 * Like classes there is no row 0: `GetCharacterData(n)` is `gCharacterData[n - 1]`, so position + 1
 * is the character number. The table has 256 rows; the last one (number 0x100) does not fit the
 * `u8 number` field and stores 0. Support data (`struct SupportData`, `include/bmreliance.h`) is
 * resolved and inlined.
 */
public object CharactersExtractor {
    /** `sizeof(struct CharacterData)`. */
    public const val ENTRY_SIZE: Int = 0x34

    /** Format tag written to the file. */
    public const val FORMAT: String = "gbafe.characters"

    /** Format version. */
    public const val VERSION: Int = 1

    /** `UNIT_AFFIN_*` in bmunit.h (0 = none). */
    public val AFFINITIES: List<String> = listOf("NONE", "FIRE", "THUNDER", "WIND", "ICE", "DARK", "LIGHT", "ANIMA")

    /** `UNIT_SUPPORT_MAX_COUNT`. */
    public const val SUPPORT_SLOTS: Int = 7

    /**
     * Builds the `characters.json` document.
     *
     * @param names character number → `CHARACTER_*` constant; id = name without `CHARACTER_`
     *   (fallback `CHARACTER_XX`).
     * @param classNames class number → `CLASS_*` constant, for `defaultClass`.
     */
    public fun extract(
        rom: ByteArray, tableAddress: Int, count: Int, names: Map<Int, String>,
        classNames: Map<Int, String> = emptyMap(),
    ): Map<String, Any?> {
        fun charId(n: Int) = names[n]?.let { gba.extract.DecompEnums.id(it, "CHARACTER_") }
            ?: "CHARACTER_${hexUpper(n, 2)}"
        // Class 0 means "no class".
        fun classId(n: Int) = if (n == 0) null else classNames[n]
            ?.let { gba.extract.DecompEnums.id(it, "CLASS_") } ?: "CLASS_${hexUpper(n, 2)}"
        val entries = ArrayList<Map<String, Any?>>(count)
        for (i in 0 until count) {
            val o = off(tableAddress) + i * ENTRY_SIZE // start of this row
            val number = u8(rom, o + 4)
            // Row 255 holds 0 (0x100 wraps in a byte), hence the mask.
            require(number == (i + 1) and 0xFF) { "gCharacterData[$i].number is $number" }
            val attrs = u32(rom, o + 0x28) // bit set of CA_* flags, see ClassesExtractor.ATTRIBUTES
            entries += linkedMapOf(
                "id" to charId(i + 1),
                "nameText" to u16(rom, o), "descText" to u16(rom, o + 2),
                "defaultClass" to classId(u8(rom, o + 5)),
                "portrait" to u16(rom, o + 6),
                "miniPortrait" to u8(rom, o + 8),
                "affinity" to AFFINITIES.getOrElse(u8(rom, o + 9)) { error("character ${i + 1}: affinity $it") },
                "sortOrder" to u8(rom, o + 0x0A),
                "level" to s8(rom, o + 0x0B),
                "bases" to listOf("hp", "pow", "skl", "spd", "def", "res", "lck", "con").withIndex()
                    .associateTo(LinkedHashMap()) { (k, s) -> s to s8(rom, o + 0x0C + k) },
                "baseRanks" to ItemsExtractor.WEAPON_TYPES.take(8).withIndex()
                    .associateTo(LinkedHashMap()) { (k, t) -> t to u8(rom, o + 0x14 + k) },
                "growths" to listOf("hp", "pow", "skl", "spd", "def", "res", "lck").withIndex()
                    .associateTo(LinkedHashMap()) { (k, s) -> s to u8(rom, o + 0x1C + k) },
                // Bytes 0x23..0x27 are not understood yet; kept raw.
                "unknown23" to List(5) { u8(rom, o + 0x23 + it) },
                "attributes" to ClassesExtractor.ATTRIBUTES.filterIndexed { bit, _ -> attrs and (1 shl bit) != 0 },
                "supports" to supports(rom, u32(rom, o + 0x2C), ::charId),
                "visitGroup" to u8(rom, o + 0x30),
            )
        }
        return linkedMapOf("format" to FORMAT, "game" to "fe8", "version" to VERSION, "entries" to entries)
    }

    /** [extract] serialised with [Json]. */
    public fun extractJson(
        rom: ByteArray, tableAddress: Int, count: Int, names: Map<Int, String>,
        classNames: Map<Int, String> = emptyMap(),
    ): String =
        Json.write(extract(rom, tableAddress, count, names, classNames))

    /** `struct SupportData`: 7 partner numbers, 7 starting exps, 7 per-turn growths, then the used count. */
    private fun supports(rom: ByteArray, ptr: Int, charId: (Int) -> String): List<Map<String, Any?>>? {
        if (ptr == 0) return null
        val o = off(ptr)
        val n = u8(rom, o + 3 * SUPPORT_SLOTS)
        require(n <= SUPPORT_SLOTS) { "support count $n" }
        return List(n) { k ->
            linkedMapOf(
                "partner" to charId(u8(rom, o + k)),
                "baseExp" to u8(rom, o + SUPPORT_SLOTS + k),
                "growth" to u8(rom, o + 2 * SUPPORT_SLOTS + k),
            )
        }
    }

    /** Converts a GBA ROM address (0x08xxxxxx) to a file offset. */
    private fun off(addr: Int): Int {
        require(addr ushr 25 == 4) { "not a ROM address: 0x${addr.toUInt().toString(16)}" }
        return addr - 0x08000000
    }

    private fun s8(b: ByteArray, o: Int) = b[o].toInt()
    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)
}
