package gbafe.extract

/**
 * Everything the extraction steps need besides the ROM: symbol addresses and sizes, ROM
 * address names and decomp enum/face names. Behind an interface so extraction can run either
 * from the decomp ([DecompSources]) or, on device, from a committed manifest of the answers
 * (docs/decisions/0011, M4-03); [RecordingSources] produces that manifest.
 */
public interface ExtractSources {
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

    /** Face names (`FID_*`) to ids from `texts/textdefs.txt` (empty without a decomp). */
    public fun faces(): Map<String, Int>
}

/**
 * Wraps [inner] and records every answer it gives, so [manifest] holds exactly what one
 * extraction run used (ROM names: only the addresses looked up, found or not).
 */
public class RecordingSources(private val inner: ExtractSources) : ExtractSources {
    private val symbols = sortedMapOf<String, Symbols.Symbol>()
    private val spans = sortedMapOf<String, Int>()
    private val prefixes = sortedMapOf<String, List<String>>()
    private val romNames = sortedMapOf<Long, String?>()
    private val enums = sortedMapOf<String, Map<Int, String>>()
    private var faceNames: Map<String, Int>? = null

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

    override fun faces(): Map<String, Int> = inner.faces().also { faceNames = it }

    /** The recorded answers as a JSON-ready tree (see [Json.write]). */
    public fun manifest(): Map<String, Any?> = linkedMapOf(
        "format" to 1,
        "symbols" to symbols.mapValues { (_, s) ->
            linkedMapOf("address" to (s.address.toLong() and 0xFFFFFFFFL), "size" to s.size)
        },
        "spans" to spans,
        "prefixes" to prefixes,
        "romNames" to romNames.mapKeys { "0x%08X".format(it.key) },
        "enums" to enums.mapValues { (_, m) -> m.mapKeys { it.key.toString() } },
        "faces" to faceNames,
    )
}

/**
 * [ExtractSources] answered from a manifest written by [RecordingSources] (no decomp needed): the
 * way extraction runs on device. Asking for something the manifest lacks throws, so a step that
 * changed its queries fails loudly until the manifest is regenerated.
 */
public class ManifestSources(json: String) : ExtractSources {
    /** Bundled manifests. */
    public companion object {
        /**
         * The committed manifest of [game] (its file name, e.g. the FE8 one) bundled as the
         * resource `gbafe/extract/manifest/<game>.json`, or null if absent.
         */
        public fun bundled(game: String): ManifestSources? =
            ManifestSources::class.java.getResource("/gbafe/extract/manifest/$game.json")
                ?.readText()?.let(::ManifestSources)
    }

    private val m = gba.data.JsonReader.parse(json) as Map<*, *>

    init {
        require(m["format"] == 1L) { "unsupported manifest format ${m["format"]}" }
    }

    private fun section(key: String): Map<*, *> = m[key] as Map<*, *>

    private fun missing(what: String): Nothing =
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

    override fun faces(): Map<String, Int> =
        (m["faces"] as? Map<*, *> ?: missing("faces")).entries
            .associate { (k, v) -> k as String to (v as Long).toInt() }
}
