package gba.extract

/**
 * Reads `NAME = value` constants from decomp C headers (e.g. `include/constants/items.h`,
 * `include/constants/characters.h`), to give extracted rows the decomp's names as stable ids.
 *
 * Only plain integer literals (decimal or hexadecimal) are parsed; expressions and macro expansions
 * are ignored. Comments (both block `/* ... */` and line `// ...`) are stripped before parsing.
 */
public object DecompEnums {
    /** Regex matching `IDENTIFIER = INTEGER,` enum entries in decomp C headers. */
    private val ENUM_ENTRY_REGEX = Regex("""\b([A-Z][A-Z0-9_]*)\s*=\s*(0[xX][0-9a-fA-F]+|\d+)\s*,""")

    /** Regex matching C block comments `/* ... */`. */
    private val BLOCK_COMMENT_REGEX = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

    /** Regex matching C single-line comments `// ...`. */
    private val LINE_COMMENT_REGEX = Regex("""//[^\n]*""")

    /**
     * Parses C header text [text] and returns a map from numeric value to the first enum identifier
     * whose name begins with [prefix] (e.g. `ITEM_`, `CHARACTER_`, `CLASS_`).
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
     * Generates a stable data id for decomp constant [name]: [prefix] is removed (`ITEM_SWORD_IRON` → `SWORD_IRON`),
     * unless the remainder would not start with an alphabetic letter (`ITEM_200G` stays `ITEM_200G`),
     * ensuring all ids match `[A-Z][A-Z0-9_]*`.
     */
    public fun id(name: String, prefix: String): String {
        val short = name.removePrefix(prefix)
        return if (short.firstOrNull()?.isLetter() == true) short else name
    }
}
