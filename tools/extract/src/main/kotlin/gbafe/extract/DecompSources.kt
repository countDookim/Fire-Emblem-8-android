package gbafe.extract

import gba.extract.DecompEnums
import gba.extract.Symbols
import java.io.File

/**
 * Extraction source provider resolving ROM symbol locations, table dimensions, and enum names
 * from an `nm` symbol listing and an optional decomp source tree checkout.
 *
 * Used during desktop builds when decomp sources (`fireemblem8u`) and symbols (`fireemblem8.nm.txt`)
 * are directly available on the filesystem.
 */
public class DecompSources(
    /** Parsed `nm` symbol table mapping symbol names to ROM/RAM addresses and byte sizes. */
    private val symbols: Symbols,
    /** Optional path to the fireemblem8u decomp repository checkout. */
    private val decomp: File?,
) : ExtractSources {
    /** Looks up symbol metadata (address and size) by its decomp [name]. */
    override fun get(name: String): Symbols.Symbol = symbols[name]

    /**
     * Returns the explicit byte length of [name], or the address span to the next symbol in ROM.
     */
    override fun sizeOrSpan(name: String): Int = symbols.sizeOrSpan(name)

    /** Returns all known symbol names starting with [prefix]. */
    override fun namesWithPrefix(prefix: String): List<String> = symbols.namesWithPrefix(prefix)

    /** Builds an inverted index mapping ROM addresses to their primary symbol names. */
    override fun romNamesByAddress(): Map<Int, String> = symbols.romNamesByAddress()

    /**
     * Parses C enum declarations from a header file at [relPath] inside the decomp directory,
     * collecting identifiers prefixed with [prefix] mapped to their integer values.
     */
    override fun enumNames(relPath: String, prefix: String): Map<Int, String> =
        decomp?.let { DecompEnums.parse(File(it, relPath).readText(), prefix) } ?: emptyMap()

    /**
     * Parses portrait face definitions (`[FID_Name] = lo, hi`) from the decomp's `texts/textdefs.txt`.
     */
    override fun faces(): Map<String, Int> =
        decomp?.let { File(it, "texts/textdefs.txt") }?.takeIf { it.isFile }
            ?.let { gbafe.extract.text.MessagesExtractor.parseFaces(it.readText()) } ?: emptyMap()
}
