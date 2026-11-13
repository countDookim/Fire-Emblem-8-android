package gba.extract

/**
 * Reads `NAME = value` constants from decomp C headers (e.g. `include/constants/items.h`,
 * `include/constants/characters.h`), to give extracted rows the decomp's names as stable ids.
 *
 * Only `NAME = literal,` entries with a decimal or hexadecimal literal followed by a comma are
 * parsed; expressions, `#define`s and an entry without a trailing comma are skipped silently.
 * Comments (block `/* ... */` and line `// ...`) are stripped before parsing.
 */
public object DecompEnums {
    /** Matches one `IDENTIFIER = INTEGER,` enum entry; group 1 is the name, group 2 the literal. */
    private val ENUM_ENTRY_REGEX = Regex("""\b([A-Z][A-Z0-9_]*)\s*=\s*(0[xX][0-9a-fA-F]+|\d+)\s*,""")

    /** Matches a C block comment, across lines, non-greedy. */
    private val BLOCK_COMMENT_REGEX = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

    /** Matches a C `//` comment up to the end of its line. */
    private val LINE_COMMENT_REGEX = Regex("""//[^\n]*""")

    /**
     * Parses C header text [text] and returns a map from numeric value to the first enum identifier
     * whose name begins with [prefix] (e.g. `ITEM_`, `CHARACTER_`, `CLASS_`); when several names
     * share a value, the one listed first in the header wins. Iteration order is header order.
     */
    public fun parse(text: String, prefix: String): Map<Int, String> {
        val clean = text.replace(BLOCK_COMMENT_REGEX, "").replace(LINE_COMMENT_REGEX, "")
        val out = LinkedHashMap<Int, String>()
        for (m in ENUM_ENTRY_REGEX.findAll(clean)) {
            val name = m.groupValues[1]
            if (!name.startsWith(prefix)) continue
            val lit = m.groupValues[2]
            val value = if (lit.startsWith("0x") || lit.startsWith("0X")) {
                lit.substring(2).toInt(16)
            } else {
                lit.toInt()
            }
            out.putIfAbsent(value, name)
        }
        return out
    }

    /**
     * Generates a stable data id for decomp constant [name]: [prefix] is removed
     * (`ITEM_SWORD_IRON` → `SWORD_IRON`), unless the remainder would not start with a letter
     * (`ITEM_200G` stays `ITEM_200G`), so an id never starts with a digit and, for names that
     * [parse] returned, always matches `[A-Z][A-Z0-9_]*`. Examples are from
     * include/constants/items.h.
     */
    public fun id(name: String, prefix: String): String {
        val short = name.removePrefix(prefix)
        return if (short.firstOrNull()?.isLetter() == true) short else name
    }
}
