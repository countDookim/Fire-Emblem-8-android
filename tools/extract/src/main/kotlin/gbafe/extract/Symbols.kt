package gbafe.extract

import java.io.File

/**
 * Decomp ELF symbols from `nm`-style output (`fireemblem8.nm.txt` in the ROM repo):
 * `ADDR [SIZE] TYPE NAME` per line, hex fields. Used to locate ROM data tables by name, so the extractor
 * has no hard-coded addresses.
 *
 * Many asm data labels carry no size; [sizeOrSpan] then falls back to the distance to the next symbol at
 * a higher address (the convention documented in HANDOFF's caveats).
 */
public class Symbols(lines: List<String>) {
    /** One symbol; [size] is -1 when `nm` printed none. */
    public data class Symbol(
        /** Symbol name as in the decomp. */
        val name: String,
        /** GBA address (ROM symbols are 0x08xxxxxx). */
        val address: Int,
        /** Size in bytes, or -1 if unknown. */
        val size: Int,
    )

    private val all: List<Symbol> = lines.mapNotNull { line ->
        val c = line.trim().split(Regex("\\s+"))
        when (c.size) {
            4 -> Symbol(c[3], c[0].toLong(16).toInt(), c[1].toLong(16).toInt())
            3 -> Symbol(c[2], c[0].toLong(16).toInt(), -1)
            else -> null
        }
    }
    private val byName: Map<String, List<Symbol>> = all.groupBy { it.name }
    private val sortedAddresses: IntArray = all.map { it.address }.distinct().sortedBy { it.toUInt() }.toIntArray()

    /** Reads [file]. */
    public constructor(file: File) : this(file.readLines())

    /**
     * The unique symbol [name].
     *
     * @throws IllegalArgumentException if it is missing or defined at more than one address.
     */
    public operator fun get(name: String): Symbol {
        val s = byName[name]?.distinctBy { it.address } ?: throw IllegalArgumentException("symbol $name not found")
        require(s.size == 1) { "symbol $name is ambiguous (${s.size} addresses)" }
        return byName.getValue(name).maxBy { it.size }
    }

    /** Names of all symbols starting with [prefix], each once. */
    public fun namesWithPrefix(prefix: String): List<String> = byName.keys.filter { it.startsWith(prefix) }.sorted()

    /** Size of [name], or the gap to the next higher symbol address if it has none. */
    public fun sizeOrSpan(name: String): Int {
        val s = get(name)
        if (s.size >= 0) return s.size
        val next = sortedAddresses.firstOrNull { it.toUInt() > s.address.toUInt() }
            ?: throw IllegalArgumentException("no symbol after $name")
        return next - s.address
    }
}
