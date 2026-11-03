package gbafe.extract

import java.io.File

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
        decomp?.let { DecompEnums.parse(File(it, relPath).readText(), prefix) } ?: emptyMap()

    override fun faces(): Map<String, Int> =
        decomp?.let { File(it, "texts/textdefs.txt") }?.takeIf { it.isFile }
            ?.let { gbafe.extract.text.MessagesExtractor.parseFaces(it.readText()) } ?: emptyMap()
}
