package gbafe.extract

import java.io.File

/** [ExtractSources] read from the decomp's symbol list and (optional) checkout [decomp]. */
public class DecompSources(
    private val symbols: Symbols,
    private val decomp: File?,
) : ExtractSources {
    /** [ExtractSources.get] from the `nm` symbols. */
    override fun get(name: String): Symbols.Symbol = symbols[name]

    /** [ExtractSources.sizeOrSpan] from the `nm` symbols. */
    override fun sizeOrSpan(name: String): Int = symbols.sizeOrSpan(name)

    /** [ExtractSources.namesWithPrefix] from the `nm` symbols. */
    override fun namesWithPrefix(prefix: String): List<String> = symbols.namesWithPrefix(prefix)

    /** [ExtractSources.romNamesByAddress] from the `nm` symbols. */
    override fun romNamesByAddress(): Map<Int, String> = symbols.romNamesByAddress()

    /** [ExtractSources.enumNames] parsed from the decomp header. */
    override fun enumNames(relPath: String, prefix: String): Map<Int, String> =
        decomp?.let { DecompEnums.parse(File(it, relPath).readText(), prefix) } ?: emptyMap()

    /** [ExtractSources.faces] parsed from the decomp `texts/textdefs.txt`. */
    override fun faces(): Map<String, Int> =
        decomp?.let { File(it, "texts/textdefs.txt") }?.takeIf { it.isFile }
            ?.let { gbafe.extract.text.MessagesExtractor.parseFaces(it.readText()) } ?: emptyMap()
}
