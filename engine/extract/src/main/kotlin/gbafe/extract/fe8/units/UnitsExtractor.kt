package gbafe.extract.fe8.units

import gba.extract.Json


/**
 * Reads FE8 unit placement arrays (`struct UnitDefinition`, decomp `include/bmunit.h`, 20 bytes, BITPACKED;
 * arrays end with an all-zero entry, e.g. `src/events/prologue-eventudefs.h`) and their move scripts
 * (`struct REDA`, `include/muctrl.h`, 8 bytes) and turns each array into a Tiled object layer.
 *
 * Every struct field is kept: byte 3 = `autolevel:1, allegiance:2, level:5`; u16 at 4 = `x:6, y:6, genMonster:1,
 * itemDrop:1, sumFlag:1, unk_05_7:1`; byte 6 `extraData`, byte 7 `redaCount`; `redas` pointer; 4 items; 4 AI bytes.
 * A REDA is u32 `x:6, y:6, flags:6` with `a` at byte 3, `b` at 4, `delayFrames` at 6.
 */
public object UnitsExtractor {
    /** Size of `UnitDefinition`. */
    public const val ENTRY_SIZE: Int = 20

    /** Size of `REDA`. */
    public const val REDA_SIZE: Int = 8

    /**
     * Reads the array at [offset] up to its terminator (charIndex 0), at most [maxEntries] entries; one map per
     * unit with every field (keys as in the decomp struct, `redas` decoded).
     */
    public fun readGroup(rom: ByteArray, offset: Int, maxEntries: Int = Int.MAX_VALUE): List<Map<String, Any>> {
        val out = ArrayList<Map<String, Any>>()
        var o = offset
        while (out.size < maxEntries && u8(rom, o) != 0) {
            val b3 = u8(rom, o + 3)
            val pos = u16(rom, o + 4)
            val redaCount = u8(rom, o + 7)
            val redaPtr = u32(rom, o + 8)
            require(redaCount == 0 || redaPtr != 0) { "unit at 0x%X: %d REDAs, null pointer".format(o, redaCount) }
            out += linkedMapOf(
                "charIndex" to u8(rom, o), "classIndex" to u8(rom, o + 1), "leaderCharIndex" to u8(rom, o + 2),
                "autolevel" to (b3 and 1), "allegiance" to ((b3 shr 1) and 3), "level" to (b3 shr 3),
                "xPosition" to (pos and 0x3F), "yPosition" to ((pos shr 6) and 0x3F),
                "genMonster" to ((pos shr 12) and 1), "itemDrop" to ((pos shr 13) and 1),
                "sumFlag" to ((pos shr 14) and 1), "unk_05_7" to ((pos shr 15) and 1),
                "extraData" to u8(rom, o + 6),
                "items" to List(4) { u8(rom, o + 12 + it) }, "ai" to List(4) { u8(rom, o + 16 + it) },
                "redas" to List(redaCount) { readReda(rom, redaPtr - ROM + it * REDA_SIZE) },
            )
            o += ENTRY_SIZE
        }
        return out
    }

    private fun readReda(rom: ByteArray, o: Int): Map<String, Int> {
        val bits = u32(rom, o)
        return linkedMapOf(
            "x" to (bits and 0x3F), "y" to ((bits shr 6) and 0x3F), "flags" to ((bits shr 12) and 0x3F),
            "a" to u8(rom, o + 3), "b" to u16(rom, o + 4), "delayFrames" to u16(rom, o + 6),
        )
    }

    /**
     * Tiled object layer (hidden) named [name] holding one 16×16 object per unit at its tile position; object
     * names are `char_<charIndex>`, fields are int properties, and `items`/`ai`/`redas` are JSON-string
     * properties. Object ids start at [firstObjectId].
     */
    public fun objectLayer(id: Int, name: String, units: List<Map<String, Any>>, firstObjectId: Int): Map<String, Any> =
        linkedMapOf(
            "id" to id, "name" to name, "type" to "objectgroup", "x" to 0, "y" to 0, "opacity" to 1, "visible" to false,
            "draworder" to "index",
            "objects" to units.mapIndexed { i, u ->
                linkedMapOf(
                    "id" to firstObjectId + i, "name" to "char_${u["charIndex"]}", "type" to "unit",
                    "x" to (u["xPosition"] as Int) * 16, "y" to (u["yPosition"] as Int) * 16, "width" to 16, "height" to 16,
                    "rotation" to 0, "visible" to true,
                    "properties" to u.map { (k, v) ->
                        if (v is Int) linkedMapOf("name" to k, "type" to "int", "value" to v)
                        else linkedMapOf("name" to k, "type" to "string", "value" to Json.write(v).trim())
                    },
                )
            },
        )

    private const val ROM = 0x08000000

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)
}
