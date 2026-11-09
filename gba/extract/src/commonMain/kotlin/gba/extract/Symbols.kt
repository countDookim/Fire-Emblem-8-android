package gba.extract

/**
 * Decomp ELF symbols parser for GNU `nm`-style symbol table dumps (`fireemblem8.nm.txt` in the ROM repo):
 * `ADDR [SIZE] TYPE NAME` per line with hexadecimal fields.
 *
 * Used during extraction to locate data tables in ROM by their decomp identifier, avoiding hard-coded
 * memory addresses. When assembly data labels lack an explicit size, [sizeOrSpan] determines size as
 * the distance to the next symbol at a higher address.
 */
public class Symbols(lines: List<String>) {
    /** One parsed symbol entry; [size] is -1 when `nm` emitted no size. */
    public data class Symbol(
        /** Symbol name as written in the decomp. */
        val name: String,
        /** GBA bus address (ROM symbols reside in `0x08000000..0x09FFFFFF`). */
        val address: Int,
        /** Size in bytes from the ELF symbol table, or -1 if unknown. */
        val size: Int,
    )

    /** All symbols parsed from the input lines. */
    private val all: List<Symbol> = lines.mapNotNull { line ->
        val columns = line.trim().split(Regex("""\s+"""))
        when (columns.size) {
            NM_COLUMNS_WITH_SIZE -> Symbol(
                columns[3],
                columns[0].toLong(16).toInt(),
                columns[1].toLong(16).toInt(),
            )
            NM_COLUMNS_WITHOUT_SIZE -> Symbol(
                columns[2],
                columns[0].toLong(16).toInt(),
                -1,
            )
            else -> null
        }
    }

    /** Map of symbol names to all symbol definitions sharing that name. */
    private val byName: Map<String, List<Symbol>> = all.groupBy { it.name }

    /** Sorted unique symbol addresses used for span calculation. */
    private val sortedAddresses: IntArray = all.map { it.address }.distinct().sortedBy { it.toUInt() }.toIntArray()

    /**
     * Returns the unique symbol named [name].
     *
     * @throws IllegalArgumentException if the symbol is missing or defined at multiple distinct addresses.
     */
    public operator fun get(name: String): Symbol {
        val matches = byName[name]?.distinctBy { it.address }
            ?: throw IllegalArgumentException("symbol $name not found")
        require(matches.size == 1) { "symbol $name is ambiguous (${matches.size} addresses)" }
        return byName.getValue(name).maxBy { it.size }
    }

    /** Returns all unique symbol names starting with [prefix], sorted alphabetically. */
    public fun namesWithPrefix(prefix: String): List<String> =
        byName.keys.filter { it.startsWith(prefix) }.sorted()

    /**
     * Maps ROM addresses (`0x08000000..0x09FFFFFF`) to their symbol names.
     * When multiple symbols share the same address (aliases), the alphabetically first name is selected.
     */
    public fun romNamesByAddress(): Map<Int, String> =
        all.filter { it.address ushr 25 == GBA_ROM_PREFIX }
            .groupBy { it.address }
            .mapValues { (_, symbols) -> symbols.minOf { it.name } }

    /**
     * Returns the size of [name] in bytes. If the symbol had no size in the ELF table, computes
     * the span as the distance to the next symbol address.
     */
    public fun sizeOrSpan(name: String): Int {
        val symbol = get(name)
        if (symbol.size >= 0) return symbol.size
        val nextAddress = sortedAddresses.firstOrNull { it.toUInt() > symbol.address.toUInt() }
            ?: throw IllegalArgumentException("no symbol after $name")
        return nextAddress - symbol.address
    }

    /** Symbol address and formatting constants. */
    public companion object {
        /** Top 7 bits of GBA ROM bus addresses: `0x08000000 ushr 25 == 4`. */
        public const val GBA_ROM_PREFIX: Int = 4

        /** Number of whitespace-separated columns in `nm` output when size is present. */
        private const val NM_COLUMNS_WITH_SIZE: Int = 4

        /** Number of whitespace-separated columns in `nm` output when size is omitted. */
        private const val NM_COLUMNS_WITHOUT_SIZE: Int = 3
    }
}
