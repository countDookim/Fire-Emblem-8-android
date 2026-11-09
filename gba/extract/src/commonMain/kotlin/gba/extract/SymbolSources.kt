package gba.extract

/**
 * Interface providing symbol addresses, table sizes, and decomp enum names needed during extraction.
 *
 * Decouples extraction logic from the underlying source:
 * - Desktop development: [RecordingSymbolSources] queries the decomp ELF directly and records
 *   every query into a compact manifest JSON.
 * - Device / runtime: [ManifestSymbolSources] answers queries from the pre-generated manifest JSON,
 *   enabling on-device extraction without shipping decomp headers or symbol tables.
 */
public interface SymbolSources {
    /** Looks up the unique symbol [name]. */
    public operator fun get(name: String): Symbols.Symbol

    /** Returns the size in bytes of [name], or the span to the next higher symbol. */
    public fun sizeOrSpan(name: String): Int

    /** Returns names of all symbols starting with [prefix], sorted alphabetically. */
    public fun namesWithPrefix(prefix: String): List<String>

    /** Returns address-to-name lookup map for ROM symbols. */
    public fun romNamesByAddress(): Map<Int, String>

    /** Returns decomp enum value-to-name mapping from header [relPath] for enum constants matching [prefix]. */
    public fun enumNames(relPath: String, prefix: String): Map<Int, String>
}

/**
 * Wraps [inner] and records every query and result into internal maps, allowing [manifest]
 * to produce a minimal JSON manifest of everything used during an extraction run.
 */
public open class RecordingSymbolSources(private val inner: SymbolSources) : SymbolSources {
    private val symbols = sortedMapOf<String, Symbols.Symbol>()
    private val spans = sortedMapOf<String, Int>()
    private val prefixes = sortedMapOf<String, List<String>>()
    private val romNames = sortedMapOf<Long, String?>()
    private val enums = sortedMapOf<String, Map<Int, String>>()

    override fun get(name: String): Symbols.Symbol = inner[name].also { s -> symbols[name] = s }

    override fun sizeOrSpan(name: String): Int = inner.sizeOrSpan(name).also { spans[name] = it }

    override fun namesWithPrefix(prefix: String): List<String> =
        inner.namesWithPrefix(prefix).also { prefixes[prefix] = it }

    override fun romNamesByAddress(): Map<Int, String> {
        val underlying = inner.romNamesByAddress()
        return object : AbstractMap<Int, String>() {
            override val entries: Set<Map.Entry<Int, String>> get() = underlying.entries
            override fun get(key: Int): String? {
                val addr = key.toLong() and 0xFFFFFFFFL
                val name = underlying[key]
                romNames[addr] = name
                return name
            }
        }
    }

    override fun enumNames(relPath: String, prefix: String): Map<Int, String> =
        inner.enumNames(relPath, prefix).also { enums["$relPath $prefix"] = it }

    /**
     * Serializes the recorded symbol and enum lookups as a deterministic JSON structure.
     * Subclasses can extend this map with game-specific sections.
     */
    public open fun manifest(): MutableMap<String, Any?> = linkedMapOf(
        "format" to MANIFEST_FORMAT_VERSION,
        "symbols" to symbols.mapValues { (_, s) ->
            linkedMapOf("address" to (s.address.toLong() and 0xFFFFFFFFL), "size" to s.size)
        },
        "spans" to spans,
        "prefixes" to prefixes,
        "romNames" to romNames.mapKeys { hex32(it.key) },
        "enums" to enums.mapValues { (_, m) -> m.mapKeys { it.key.toString() } },
    )

    /** Manifest version constants. */
    public companion object {
        /** Schema version of the generated manifest. */
        public const val MANIFEST_FORMAT_VERSION: Int = 1
    }
}

/**
 * [SymbolSources] implementation backed by a pre-computed JSON manifest created by [RecordingSymbolSources].
 * Throws an explicit [IllegalArgumentException] if an extractor queries a symbol or enum not present
 * in the manifest, signaling that the manifest needs regeneration.
 */
public open class ManifestSymbolSources(json: String) : SymbolSources {
    /** Parsed JSON manifest root object. */
    protected val root: Map<*, *> = gba.data.JsonReader.parse(json) as Map<*, *>

    init {
        require(root["format"] == 1L) { "unsupported manifest format ${root["format"]}" }
    }

    /** Extracts a subsection map by [key] from the manifest root. */
    private fun section(key: String): Map<*, *> = root[key] as Map<*, *>

    /** Throws an error indicating that [what] was missing from the manifest. */
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
                val formattedAddress = hex32(key.toLong() and 0xFFFFFFFFL)
                if (!names.containsKey(formattedAddress)) missing("ROM name of $formattedAddress")
                return names[formattedAddress] as String?
            }
        }
    }

    override fun enumNames(relPath: String, prefix: String): Map<Int, String> {
        val e = section("enums")["$relPath $prefix"] as? Map<*, *> ?: missing("enum $relPath $prefix")
        return e.entries.associate { (k, v) -> (k as String).toInt() to v as String }
    }
}

/** Formats 32-bit unsigned [v] as an 8-character uppercase hex address prefixed with `0x`. */
private fun hex32(v: Long): String =
    "0x" + v.toString(16).uppercase().padStart(8, '0')
