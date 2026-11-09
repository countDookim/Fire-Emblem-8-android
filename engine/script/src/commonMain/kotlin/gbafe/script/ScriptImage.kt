package gbafe.script

/**
 * Compiled event script bytecode container holding 16-bit words and a script directory table.
 *
 * All scripts share a single contiguous [words] array (each element representing a 16-bit word,
 * `0x0000..0xFFFF`). Script lookups by name and address use non-allocating binary searches.
 */
public class ScriptImage(
    /** All assembled 16-bit bytecode words across all scripts. */
    public val words: IntArray,
    entries: List<Entry>,
) {
    /** A compiled event script entry in the image directory. */
    public class Entry(
        /** Unique event script name. */
        public val name: String,
        /** Virtual memory address of the first word (`baseAddress + 2 * k` for word `k`). */
        public val baseAddress: Int,
        /** Offset into [ScriptImage.words] where this script's words begin. */
        public val wordOffset: Int,
        /** Number of 16-bit words making up this script. */
        public val wordCount: Int,
    ) {
        /** Total size of this script in bytes (`wordCount * 2`). */
        public val byteCount: Int get() = 2 * wordCount
    }

    private val byAddress: Array<Entry> = entries.sortedBy { it.baseAddress }.toTypedArray()
    private val bases: IntArray = IntArray(byAddress.size) { byAddress[it].baseAddress }
    private val byName: Array<Entry> = entries.sortedBy { it.name }.toTypedArray()

    init {
        for (w in words) require(w in 0..0xFFFF) { "word 0x${w.toString(16)} is not a u16" }
        for (e in byAddress) {
            require(e.baseAddress >= 0) { "script '${e.name}': address is not below 0x80000000" }
            require(e.wordOffset >= 0 && e.wordCount >= 0) { "script '${e.name}': negative size" }
            require(e.wordOffset + e.wordCount <= words.size) {
                "script '${e.name}' lies outside the word array"
            }
        }
        for (i in 1 until byName.size) {
            require(byName[i - 1].name != byName[i].name) { "duplicate script '${byName[i].name}'" }
        }
        for (i in 1 until byAddress.size) {
            val prev = byAddress[i - 1]
            require(prev.baseAddress + prev.byteCount <= byAddress[i].baseAddress) {
                "scripts '${prev.name}' and '${byAddress[i].name}' overlap"
            }
        }
    }

    /** Number of compiled scripts in this image. */
    public val entryCount: Int get() = byAddress.size

    /** Returns the [index]th script entry in ascending base address order. */
    public fun entry(index: Int): Entry = byAddress[index]

    /** Binary searches for the script named [name], returning null if absent. */
    public fun find(name: String): Entry? {
        var lo = 0
        var hi = byName.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val cmp = byName[mid].name.compareTo(name)
            if (cmp == 0) return byName[mid]
            if (cmp < 0) lo = mid + 1 else hi = mid - 1
        }
        return null
    }

    /** Binary searches for the script spanning virtual [address], returning null if none matches. */
    public fun entryContaining(address: Int): Entry? {
        var lo = 0
        var hi = bases.size - 1
        var foundIndex = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (bases[mid] <= address) {
                foundIndex = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (foundIndex < 0) return null
        val candidate = byAddress[foundIndex]
        return if (address - candidate.baseAddress < candidate.byteCount) candidate else null
    }

    /** Returns the index in [words] of the word at [address] (must be even), or -1 if unmapped. */
    public fun wordIndexOfAddress(address: Int): Int {
        if (address and 1 != 0) return -1
        val e = entryContaining(address) ?: return -1
        return e.wordOffset + (address - e.baseAddress) / 2
    }

    /** Serializes [entry]'s compiled words as little-endian bytes matching GBA ROM memory layout. */
    public fun bytesOf(entry: Entry): ByteArray {
        val out = ByteArray(entry.byteCount)
        for (i in 0 until entry.wordCount) {
            val word = words[entry.wordOffset + i]
            out[2 * i] = word.toByte()
            out[2 * i + 1] = (word shr 8).toByte()
        }
        return out
    }
}
