package gbafe.extract.fe8.units

import gba.extract.Json
import gbafe.extract.hexUpper

/**
 * Reads FE8 unit placement arrays (`struct UnitDefinition`, `include/bmunit.h`, 20 bytes,
 * BITPACKED) and their move scripts (`struct REDA`, `include/muctrl.h`, 8 bytes), then turns each
 * array into a Tiled object layer. An entry with `charIndex` 0 ends the array, as in
 * `src/events/prologue-eventudefs.h`.
 *
 * Each unit retains byte 3 (`autolevel:1, allegiance:2, level:5`), the u16 at 4 (`x:6, y:6,
 * genMonster:1, itemDrop:1, sumFlag:1, unk_05_7:1`), byte 6 `extraData`, the decoded `redas`
 * referenced by the pointer and count, four items and four AI bytes. A REDA has a u32 containing
 * `x:6, y:6, flags:6`, with `a` at byte 3, `b` at 4 and `delayFrames` at 6.
 */
public object UnitsExtractor {
    /** Size of `UnitDefinition`. */
    public const val ENTRY_SIZE: Int = 20

    /** Size of `REDA`. */
    public const val REDA_SIZE: Int = 8

    /**
     * Reads the array at [offset] up to its terminator (charIndex 0), at most [maxEntries]
     * entries; one map per unit with every field (keys as in the decomp struct, `redas` decoded).
     */
    public fun readGroup(
        rom: ByteArray,
        offset: Int,
        maxEntries: Int = Int.MAX_VALUE,
    ): List<Map<String, Any>> {
        val units = ArrayList<Map<String, Any>>()
        var entryOffset = offset
        while (units.size < maxEntries && u8(rom, entryOffset) != 0) {
            val levelAndAllegiance = u8(rom, entryOffset + 3)
            val positionAndFlags = u16(rom, entryOffset + 4)
            val redaCount = u8(rom, entryOffset + 7)
            val redaAddress = u32(rom, entryOffset + 8)
            require(redaCount == 0 || redaAddress != 0) {
                "unit at 0x${hexUpper(entryOffset)}: $redaCount REDAs, null pointer"
            }
            units += linkedMapOf(
                "charIndex" to u8(rom, entryOffset), "classIndex" to u8(rom, entryOffset + 1),
                "leaderCharIndex" to u8(rom, entryOffset + 2),
                "autolevel" to (levelAndAllegiance and 1),
                "allegiance" to ((levelAndAllegiance shr 1) and 3),
                "level" to (levelAndAllegiance shr 3),
                "xPosition" to (positionAndFlags and 0x3F),
                "yPosition" to ((positionAndFlags shr 6) and 0x3F),
                "genMonster" to ((positionAndFlags shr 12) and 1),
                "itemDrop" to ((positionAndFlags shr 13) and 1),
                "sumFlag" to ((positionAndFlags shr 14) and 1),
                "unk_05_7" to ((positionAndFlags shr 15) and 1),
                "extraData" to u8(rom, entryOffset + 6),
                "items" to List(4) { u8(rom, entryOffset + 12 + it) },
                "ai" to List(4) { u8(rom, entryOffset + 16 + it) },
                "redas" to List(redaCount) { readReda(rom, redaAddress - ROM + it * REDA_SIZE) },
            )
            entryOffset += ENTRY_SIZE
        }
        return units
    }

    /** Decodes one REDA at a byte offset in the ROM. */
    private fun readReda(rom: ByteArray, offset: Int): Map<String, Int> {
        val positionAndFlags = u32(rom, offset)
        return linkedMapOf(
            "x" to (positionAndFlags and 0x3F),
            "y" to ((positionAndFlags shr 6) and 0x3F),
            "flags" to ((positionAndFlags shr 12) and 0x3F),
            "a" to u8(rom, offset + 3), "b" to u16(rom, offset + 4),
            "delayFrames" to u16(rom, offset + 6),
        )
    }

    /**
     * Hidden Tiled object layer named [name], holding one 16×16 object per unit at its tile
     * position. Object names are `char_<charIndex>`, fields are int properties, and
     * `items`/`ai`/`redas` are JSON-string properties. Object ids start at [firstObjectId].
     */
    public fun objectLayer(
        id: Int,
        name: String,
        units: List<Map<String, Any>>,
        firstObjectId: Int,
    ): Map<String, Any> = linkedMapOf(
        "id" to id, "name" to name, "type" to "objectgroup", "x" to 0, "y" to 0, "opacity" to 1,
        "visible" to false, "draworder" to "index",
        "objects" to units.mapIndexed { index, unit ->
            linkedMapOf(
                "id" to firstObjectId + index, "name" to "char_${unit["charIndex"]}",
                "type" to "unit", "x" to (unit["xPosition"] as Int) * 16,
                "y" to (unit["yPosition"] as Int) * 16, "width" to 16, "height" to 16,
                "rotation" to 0, "visible" to true,
                "properties" to unit.map { (key, value) ->
                    if (value is Int) linkedMapOf("name" to key, "type" to "int", "value" to value)
                    else linkedMapOf(
                        "name" to key, "type" to "string", "value" to Json.write(value).trim(),
                    )
                },
            )
        },
    )

    /** Base address used to convert GBA ROM pointers to byte offsets. */
    private const val ROM = 0x08000000

    /** Reads an unsigned byte. */
    private fun u8(bytes: ByteArray, offset: Int) = bytes[offset].toInt() and 0xFF

    /** Reads a little-endian unsigned halfword. */
    private fun u16(bytes: ByteArray, offset: Int) =
        u8(bytes, offset) or (u8(bytes, offset + 1) shl 8)

    /** Reads a little-endian word. */
    private fun u32(bytes: ByteArray, offset: Int) =
        u16(bytes, offset) or (u16(bytes, offset + 2) shl 16)
}
