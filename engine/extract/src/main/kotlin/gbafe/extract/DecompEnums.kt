package gbafe.extract

/**
 * Reads `NAME = value` constants from decomp C headers (e.g. `include/constants/items.h`), to give
 * extracted rows the decomp's names as stable ids. Only plain integer literals (decimal or hex) are
 * understood; expressions are skipped. Comments are stripped first.
 */
public object DecompEnums {
    private val entry = Regex("""\b([A-Z][A-Z0-9_]*)\s*=\s*(0[xX][0-9a-fA-F]+|\d+)\s*,""")

    /** Parses [text] and returns value → first name with [prefix], e.g. `ITEM_`. */
    public fun parse(text: String, prefix: String): Map<Int, String> {
        val clean = text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("//[^\n]*"), "")
        val out = LinkedHashMap<Int, String>()
        for (m in entry.findAll(clean)) {
            val name = m.groupValues[1]
            if (!name.startsWith(prefix)) continue
            val lit = m.groupValues[2]
            val v = if (lit.startsWith("0x") || lit.startsWith("0X")) lit.substring(2).toInt(16) else lit.toInt()
            out.putIfAbsent(v, name)
        }
        return out
    }

    /**
     * Data id for decomp constant [name]: [prefix] removed (`ITEM_SWORD_IRON` → `SWORD_IRON`), unless that
     * would not start with a letter (`ITEM_200G` stays `ITEM_200G`), so ids always match `[A-Z][A-Z0-9_]*`.
     */
    public fun id(name: String, prefix: String): String {
        val short = name.removePrefix(prefix)
        return if (short.firstOrNull()?.isLetter() == true) short else name
    }
}
