package gba.extract

/**
 * The game-neutral answers extraction needs besides the ROM: symbol addresses and sizes, ROM
 * address names and decomp enum names. Behind an interface so extraction can run either from a
 * decomp or, on device, from a committed manifest of the answers (docs/decisions/0011);
 * [RecordingSymbolSources] produces that manifest and [ManifestSymbolSources] reads it.
 */
public interface SymbolSources {
    /** The unique symbol [name] (see [Symbols.get]). */
    public operator fun get(name: String): Symbols.Symbol

    /** Size of [name], or the gap to the next symbol (see [Symbols.sizeOrSpan]). */
    public fun sizeOrSpan(name: String): Int

    /** Names of all symbols starting with [prefix], sorted. */
    public fun namesWithPrefix(prefix: String): List<String>

    /** Address to name for ROM symbols; extraction only looks names up (`get`). */
    public fun romNamesByAddress(): Map<Int, String>

    /** Decomp enum values to names from header [relPath] (empty without a decomp). */
    public fun enumNames(relPath: String, prefix: String): Map<Int, String>
}

/**
 * Wraps [inner] and records every answer it gives, so [manifest] holds exactly what one
 * extraction run used (ROM names: only the addresses looked up, found or not).
 */
public open class RecordingSymbolSources(private val inner: SymbolSources) : SymbolSources {
    private val symbols = sortedMapOf<String, Symbols.Symbol>()
    private val spans = sortedMapOf<String, Int>()
    private val prefixes = sortedMapOf<String, List<String>>()
    private val romNames = sortedMapOf<Long, String?>()
    private val enums = sortedMapOf<String, Map<Int, String>>()

    override fun get(name: String): Symbols.Symbol = inner[name].also { symbols[name] = it }

    override fun sizeOrSpan(name: String): Int = inner.sizeOrSpan(name).also { spans[name] = it }

    override fun namesWithPrefix(prefix: String): List<String> =
        inner.namesWithPrefix(prefix).also { prefixes[prefix] = it }

    override fun romNamesByAddress(): Map<Int, String> {
        val all = inner.romNamesByAddress()
        return object : Map<Int, String> by all {
            override fun get(key: Int): String? =
                all[key].also { romNames[key.toLong() and 0xFFFFFFFFL] = it }
        }
    }

    override fun enumNames(relPath: String, prefix: String): Map<Int, String> =
        inner.enumNames(relPath, prefix).also { enums["$relPath $prefix"] = it }

    /**
     * The recorded answers as a JSON-ready tree (see [Json.write]); subclasses may append
     * their own sections.
     */
    public open fun manifest(): MutableMap<String, Any?> = linkedMapOf(
        "format" to 1,
        "symbols" to symbols.mapValues { (_, s) ->
            linkedMapOf("address" to (s.address.toLong() and 0xFFFFFFFFL), "size" to s.size)
        },
        "spans" to spans,
        "prefixes" to prefixes,
        "romNames" to romNames.mapKeys { "0x%08X".format(it.key) },
        "enums" to enums.mapValues { (_, m) -> m.mapKeys { it.key.toString() } },
    )
}

/**
 * [SymbolSources] answered from a manifest written by [RecordingSymbolSources] (no decomp
 * needed): the way extraction runs on device. Asking for something the manifest lacks throws,
 * so a step that changed its queries fails loudly until the manifest is regenerated.
 */
public open class ManifestSymbolSources(json: String) : SymbolSources {
    /** The parsed manifest root, for subclasses with extra sections. */
    protected val root: Map<*, *> = gba.data.JsonReader.parse(json) as Map<*, *>

    init {
        require(root["format"] == 1L) { "unsupported manifest format ${root["format"]}" }
    }

    private fun section(key: String): Map<*, *> = root[key] as Map<*, *>

    /** Throws: [what] is not in the manifest. */
    protected fun missing(what: String): Nothing =
        throw IllegalArgumentException("$what not in the extraction manifest (regenerate it)")

    override fun get(name: String): Symbols.Symbol {
        val s = section("symbols")[name] as? Map<*, *> ?: missing("symbol $name")
        return Symbols.Symbol(name, (s["address"] as Long).toInt(), (s["size"] as Long).toInt())
    }

    override fun sizeOrSpan(name: String): Int =
        (section("spans")[name] as? Long ?: missing("span of $name")).toInt()

    override fun namesWithPrefix(prefix: String): List<String> =
        (section("prefixes")[prefix] as? List<*> ?: missing("prefix $prefix")).map { it as String }

    override fun romNamesByAddress(): Map<Int, String> {
        val names = section("romNames")
        return object : AbstractMap<Int, String>() {
            override val entries: Set<Map.Entry<Int, String>>
                get() = throw UnsupportedOperationException("manifest ROM names are lookup-only")

            override fun get(key: Int): String? {
                val k = "0x%08X".format(key.toLong() and 0xFFFFFFFFL)
                if (!names.containsKey(k)) missing("ROM name of $k")
                return names[k] as String?
            }
        }
    }

    override fun enumNames(relPath: String, prefix: String): Map<Int, String> {
        val e = section("enums")["$relPath $prefix"] as? Map<*, *> ?: missing("enum $relPath $prefix")
        return e.entries.associate { (k, v) -> (k as String).toInt() to v as String }
    }
}
