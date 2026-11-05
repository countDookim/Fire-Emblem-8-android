package gbafe.script

/**
 * The compiled form of a set of event scripts (docs/decisions/0001, Amendment M5): all scripts'
 * u16 words in one [IntArray] (each element 0..0xFFFF), plus a table of entries. Every entry (one
 * script) has a name, a 32-bit virtual [Entry.baseAddress] (vanilla scripts use their ROM address,
 * mod scripts synthetic ones from [ScriptAssembler.SYNTHETIC_BASE]) and the position of its words.
 *
 * Look-ups never allocate: names are found by binary search in an array sorted by name, addresses
 * by binary search in a sorted [IntArray] of base addresses. Addresses must be below `0x80000000`.
 * The image is immutable as long as nobody writes into [words].
 */
public class ScriptImage(
    /** The u16 words of all scripts, one per element (0..0xFFFF); do not modify. */
    public val words: IntArray,
    entries: List<Entry>,
) {
    /** One script of the image. */
    public class Entry(
        /** Script (event) name, unique in the image. */
        public val name: String,
        /** Virtual address of the first word; the address of word `k` is `base + 2 * k`. */
        public val baseAddress: Int,
        /** Index in [ScriptImage.words] of the script's first word. */
        public val wordOffset: Int,
        /** Number of words. */
        public val wordCount: Int,
    ) {
        /** Size in bytes. */
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

    /** Number of scripts. */
    public val entryCount: Int get() = byAddress.size

    /** The [index]th script in ascending address order. */
    public fun entry(index: Int): Entry = byAddress[index]

    /** The script called [name], or null. */
    public fun find(name: String): Entry? {
        var lo = 0
        var hi = byName.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = byName[mid].name.compareTo(name)
            if (c == 0) return byName[mid]
            if (c < 0) lo = mid + 1 else hi = mid - 1
        }
        return null
    }

    /** The script whose address range contains [address], or null. */
    public fun entryContaining(address: Int): Entry? {
        // Last entry whose base is <= address.
        var lo = 0
        var hi = bases.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (bases[mid] <= address) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (found < 0) return null
        val e = byAddress[found]
        return if (address - e.baseAddress < e.byteCount) e else null
    }

    /**
     * Index in [words] of the word at virtual [address] (which must be even), or -1 when no script
     * contains it.
     */
    public fun wordIndexOfAddress(address: Int): Int {
        if (address and 1 != 0) return -1
        val e = entryContaining(address) ?: return -1
        return e.wordOffset + (address - e.baseAddress) / 2
    }

    /** The script's words as little-endian bytes (the layout in the ROM). */
    public fun bytesOf(entry: Entry): ByteArray {
        val out = ByteArray(entry.byteCount)
        for (i in 0 until entry.wordCount) {
            val w = words[entry.wordOffset + i]
            out[2 * i] = w.toByte()
            out[2 * i + 1] = (w shr 8).toByte()
        }
        return out
    }
}
