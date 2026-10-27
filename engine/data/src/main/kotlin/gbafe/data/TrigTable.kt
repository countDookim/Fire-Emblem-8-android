package gbafe.data

/**
 * The games' sine table (`data/trig.json`, format `gbafe.trig`; FE8 `gSinLookup`): 256 + 64 s16 entries in
 * [fractionBits] fixed point, so `SIN(a) = table[a & 0xFF]` and `COS(a) = table[0x40 + (a & 0xFF)]`
 * (`include/hardware.h`).
 */
public class TrigTable(
    /** Fixed-point fraction bits (12 in FE8). */
    public val fractionBits: Int,
    /** The raw table (320 entries). */
    public val table: ShortArray,
) {
    init {
        require(table.size == 320) { "trig table must have 320 entries, got ${table.size}" }
    }

    /** `SIN(a)`: sine of angle [a] (256 units per turn). */
    public fun sin(a: Int): Int = table[a and 0xFF].toInt()

    /** `COS(a)`: cosine of angle [a] (256 units per turn). */
    public fun cos(a: Int): Int = table[0x40 + (a and 0xFF)].toInt()

    /** Loader. */
    public companion object {
        /** Parses a `gbafe.trig` document. */
        public fun parse(json: String): TrigTable {
            val doc = JsonReader.parse(json) as Map<*, *>
            require(doc["format"] == "gbafe.trig") { "not a gbafe.trig document" }
            val sin = doc["sin"] as List<*>
            return TrigTable((doc["fractionBits"] as Long).toInt(), ShortArray(sin.size) { (sin[it] as Long).toShort() })
        }
    }
}
