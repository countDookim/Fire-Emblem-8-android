package gbafe.extract.fe8.chapters

import gba.extract.Json

/**
 * Extracts `gChapterDataTable` (`struct ROMChapterData`, decomp `include/chapterdata.h`, 0x94 bytes
 * per chapter) into `data/chapters.json` (format `gbafe.chapters`).
 *
 * Every byte of the struct is kept (FE7 leftovers included) so the table can be rebuilt exactly;
 * field names follow the struct, including its spelling (`mapCrackedWallHeath`). `[2]` arrays
 * become two-element lists in struct order; the decomp does not say what the two slots mean (in
 * FE7 they were normal and hard mode). `internalName` is the C string the pointer refers to (null
 * for a null pointer). Enum-typed fields (weather, goal type) stay numeric. The `...InEliwoodStory`
 * / `...InHectorStory` pairs, `hasPrepScreen` and the divination fields are marked "left over
 * from FE7" in the decomp (FE7 had separate Eliwood and Hector routes); FE8 data keeps them but
 * the decomp does not show FE8 reading them. `mapBgmIds` is indexed by the `MAP_BGM_*` constants.
 */
public object ChaptersExtractor {
    /** Size of `struct ROMChapterData`. */
    public const val ENTRY_SIZE: Int = 0x94

    /** Names of the eight map-resource id bytes at struct offsets 0x04..0x0B, in order. */
    private val MAP_FIELDS = listOf(
        "obj1Id", "obj2Id", "paletteId", "tileConfigId",
        "mainLayerId", "objAnimId", "paletteAnimId", "changeLayerId",
    )

    /** Rank letters of the per-rank tables (tactics turns, exp), A first. */
    private val RANKS = listOf("A", "B", "C", "D")

    /**
     * Reads [count] chapters from ROM offset [offset] (not a GBA address), one field map per
     * chapter in table order, `index` being the chapter id.
     */
    public fun extract(
        rom: ByteArray, offset: Int, count: Int,
    ): List<Map<String, Any?>> = List(count) { i ->
        // `r.uN(x)` reads at byte offset x inside this chapter's struct (little-endian).
        val r = Reader(rom, offset + i * ENTRY_SIZE)
        val m = LinkedHashMap<String, Any?>()
        m["index"] = i
        // Pointer into ROM (0x08000000 base); 0 means no name.
        m["internalName"] = r.u32(0x00).let {
            if (it == 0) null else cString(rom, it - 0x08000000)
        }
        // `struct ChapterMap` at 0x04: ids into the chapter asset table (see Fe8Steps).
        m["map"] = MAP_FIELDS.withIndex()
            .associateTo(LinkedHashMap()) { (k, name) -> name to r.u8(0x04 + k) }
        m["initialFogLevel"] = r.u8(0x0C)
        m["hasPrepScreen"] = r.u8(0x0D)
        m["chapTitleId"] = r.u8(0x0E)
        m["chapTitleIdInHectorStory"] = r.u8(0x0F)
        m["initialPosX"] = r.u8(0x10)
        m["initialPosY"] = r.u8(0x11)
        m["initialWeather"] = r.u8(0x12)
        m["battleTileSet"] = r.u8(0x13)
        // Three 4-bit level adjustments packed in one halfword (low nibble first); the top nibble
        // is not named in the decomp.
        val levels = r.u16(0x14)
        m["easyModeLevelMalus"] = levels and 0xF
        m["difficultModeLevelBonus"] = levels ushr 4 and 0xF
        m["normalModeLevelMalus"] = levels ushr 8 and 0xF
        m["levelBitsUnused"] = levels ushr 12
        m["mapBgmIds"] = List(11) { r.u16(0x16 + 2 * it) }
        m["mapCrackedWallHeath"] = r.u8(0x2C)
        // Tactics-rank turn counts (FE7 leftover): per rank 4 bytes, a pair for each story.
        for ((k, rank) in RANKS.withIndex()) {
            m["turnsForTacticsRank${rank}InEliwoodStory"] = List(2) { r.u8(0x2D + 4 * k + it) }
            m["turnsForTacticsRank${rank}InHectorStory"] = List(2) { r.u8(0x2F + 4 * k + it) }
        }
        m["unk3D"] = r.u8(0x3D)
        // Exp-rank values (FE7 leftover): per rank 8 bytes, a u16 pair for each story.
        for ((k, rank) in RANKS.withIndex()) {
            m["gainedExpForExpRank${rank}InEliwoodStory"] =
                List(2) { r.u16(0x3E + 8 * k + 2 * it) }
            m["gainedExpForExpRank${rank}InHectorStory"] =
                List(2) { r.u16(0x42 + 8 * k + 2 * it) }
        }
        m["unk5E"] = r.u16(0x5E)
        // Unsigned 32-bit gold values, widened to Long so they do not go negative.
        m["goldForFundsRankInEliwoodStory"] =
            List(2) { r.u32(0x60 + 4 * it).toLong() and 0xFFFFFFFFL }
        m["goldForFundsRankInHectorStory"] =
            List(2) { r.u32(0x68 + 4 * it).toLong() and 0xFFFFFFFFL }
        m["chapTitleTextId"] = r.u16(0x70)
        m["chapTitleTextIdInHectorStory"] = r.u16(0x72)
        m["mapEventDataId"] = r.u8(0x74)
        m["gmapEventId"] = r.u8(0x75)
        m["divinationTextIdBeginning"] = r.u16(0x76)
        m["divinationTextIdInEliwoodStory"] = r.u16(0x78)
        m["divinationTextIdInHectorStory"] = r.u16(0x7A)
        m["divinationTextIdEnding"] = r.u16(0x7C)
        m["divinationPortrait"] = r.u8(0x7E)
        m["divinationFee"] = r.u8(0x7F)
        m["prepScreenNumber"] = r.u8(0x80)
        m["prepScreenNumberInHectorStory"] = r.u8(0x81)
        m["merchantPosX"] = r.u8(0x82)
        m["merchantPosXInHectorStory"] = r.u8(0x83)
        m["merchantPosY"] = r.u8(0x84)
        m["merchantPosYInHectorStory"] = r.u8(0x85)
        // The only signed field in the struct, so it is read directly instead of via u8.
        m["victorySongEnemyThreshold"] = rom[offset + i * ENTRY_SIZE + 0x86].toInt()
        m["fadeToBlack"] = r.u8(0x87)
        m["statusObjectiveTextId"] = r.u16(0x88)
        m["goalWindowTextId"] = r.u16(0x8A)
        m["goalWindowDataType"] = r.u8(0x8C)
        m["goalWindowEndTurnNumber"] = r.u8(0x8D)
        m["protectCharacterIndex"] = r.u8(0x8E)
        m["destPosX"] = r.u8(0x8F)
        m["destPosY"] = r.u8(0x90)
        m["unk91"] = r.u8(0x91)
        m["unk92"] = r.u8(0x92)
        m["unk93"] = r.u8(0x93)
        m
    }

    /** The `gbafe.chapters` document. */
    public fun extractJson(rom: ByteArray, offset: Int, count: Int): String =
        Json.write(
            linkedMapOf(
                "format" to "gbafe.chapters", "game" to "fe8", "version" to 1,
                "entries" to extract(rom, offset, count),
            ),
        )

    /** Reads the NUL-terminated string at ROM file offset [o]. */
    private fun cString(rom: ByteArray, o: Int): String {
        var e = o
        while (rom[e].toInt() != 0) e++
        // ISO-8859-1: each byte is the code point of the same value.
        return CharArray(e - o) { (rom[o + it].toInt() and 0xFF).toChar() }.concatToString()
    }

    /** Little-endian reader for one struct; offsets are relative to [base] (the struct start). */
    private class Reader(val b: ByteArray, val base: Int) {
        fun u8(o: Int) = b[base + o].toInt() and 0xFF
        fun u16(o: Int) = u8(o) or (u8(o + 1) shl 8)
        fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
    }
}
