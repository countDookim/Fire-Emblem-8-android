package gbafe.extract

import gba.extract.DecompEnums
import gba.extract.Symbols
import java.io.File

/**
 * [ExtractSources] for desktop runs: symbol addresses and sizes come from an `nm` listing, enum
 * names and face ids from files of the decomp checkout. Without a checkout [enumNames] and
 * [faces] return empty maps, so steps that need names fall back to their unnamed output.
 * Device runs use `ManifestSources` instead.
 */
public class DecompSources(
    /** Parsed `nm` symbol list: name to address and size (size may be missing). */
    private val symbols: Symbols,
    /** Optional fireemblem8u decomp checkout; null means no enum names or faces. */
    private val decomp: File?,
) : ExtractSources {
    /** Looks up symbol metadata (address and size) by its decomp [name]. */
    override fun get(name: String): Symbols.Symbol = symbols[name]

    /**
     * Returns the size `nm` gave for [name]; if it gave none, the distance to the next higher
     * symbol address (which also counts padding between them).
     */
    override fun sizeOrSpan(name: String): Int = symbols.sizeOrSpan(name)

    /** Returns all known symbol names starting with [prefix]. */
    override fun namesWithPrefix(prefix: String): List<String> = symbols.namesWithPrefix(prefix)

    /** Maps each ROM address to one symbol name (the alphabetically first if several share it). */
    override fun romNamesByAddress(): Map<Int, String> = symbols.romNamesByAddress()

    /**
     * Parses the enum constants starting with [prefix] in the decomp header [relPath] (e.g.
     * `include/constants/items.h`) into value to name. Empty if there is no decomp checkout; a
     * missing header inside a checkout throws.
     */
    override fun enumNames(relPath: String, prefix: String): Map<Int, String> =
        decomp?.let { DecompEnums.parse(File(it, relPath).readText(), prefix) } ?: emptyMap()

    /**
     * Face name (`FID_*`) to face id from the decomp's `texts/textdefs.txt`; empty if there is no
     * checkout or the file is absent.
     */
    override fun faces(): Map<String, Int> =
        decomp?.let { File(it, "texts/textdefs.txt") }?.takeIf { it.isFile }
            ?.let { gbafe.extract.text.MessagesExtractor.parseFaces(it.readText()) } ?: emptyMap()
}
