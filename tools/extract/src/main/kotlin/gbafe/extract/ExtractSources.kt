package gbafe.extract

import java.io.File

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

/** [ExtractSources] read from the decomp's symbol list and (optional) checkout [decomp]. */
public class DecompSources(
    private val symbols: Symbols,
    private val decomp: File?,
) : ExtractSources {
    override fun get(name: String): Symbols.Symbol = symbols[name]

    override fun sizeOrSpan(name: String): Int = symbols.sizeOrSpan(name)

    override fun namesWithPrefix(prefix: String): List<String> = symbols.namesWithPrefix(prefix)

    override fun romNamesByAddress(): Map<Int, String> = symbols.romNamesByAddress()

    override fun enumNames(relPath: String, prefix: String): Map<Int, String> =
        decomp?.let { DecompEnums.read(it, relPath, prefix) } ?: emptyMap()

    override fun faces(): Map<String, Int> =
        decomp?.let { File(it, "texts/textdefs.txt") }?.takeIf { it.isFile }
            ?.let(gbafe.extract.text.MessagesExtractor::readFaces) ?: emptyMap()
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
