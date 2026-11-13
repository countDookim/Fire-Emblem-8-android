package gbafe.script

/** Exception thrown by [ScriptAssembler.assemble] when script compilation fails. */
public class ScriptAssemblyException(
    /** 1-based source line of the offending record, or 0 if unknown. */
    public val line: Int,
    /** Error message describing the assembly failure. */
    public override val message: String,
) : RuntimeException("line $line: $message")

/** Functional interface for resolving identifier names to numeric constants or memory addresses. */
public fun interface SymbolResolver {
    /**
     * Resolves [name] in category [kind] to an integer address or constant value, or returns [UNRESOLVED].
     */
    public fun resolve(name: String, kind: String?): Long

    /** Standard resolvers and sentinel values. */
    public companion object {
        /** Sentinel value indicating that an identifier could not be resolved. */
        public const val UNRESOLVED: Long = Long.MIN_VALUE

        /** Resolver that recognizes zero names. */
        public val NONE: SymbolResolver = SymbolResolver { _, _ -> UNRESOLVED }

        /** Chains multiple resolvers together in priority order. */
        public fun chain(vararg resolvers: SymbolResolver): SymbolResolver =
            SymbolResolver { name, kind ->
                var value = UNRESOLVED
                for (resolver in resolvers) {
                    value = resolver.resolve(name, kind)
                    if (value != UNRESOLVED) break
                }
                value
            }
    }
}

/** Resolves an identifier which may be composed of multiple names bitwise OR-ed with `|`. */
public fun SymbolResolver.resolveMask(name: String, kind: String?): Long {
    if (name.indexOf('|') < 0) return resolve(name, kind)
    var accumulator = 0L
    for (part in name.split('|')) {
        val v = resolve(part, kind)
        if (v == SymbolResolver.UNRESOLVED) return v
        accumulator = accumulator or v
    }
    return accumulator
}

/** Game-specific encoding rules mapping command specifications to compiled 16-bit bytecode words. */
public interface CommandEncoder {
    /** Number of 16-bit words emitted for a `label N` statement (2 for FE8's `EvtLabel`). */
    public val labelWordCount: Int

    /** Number of 16-bit words emitted for command [spec]. */
    public fun wordCount(spec: CommandSpec): Int

    /**
     * Encodes command [spec] with [args] into [out] beginning at word offset [pos].
     */
    public fun encode(
        spec: CommandSpec,
        args: List<Arg>,
        symbols: SymbolResolver,
        out: IntArray,
        pos: Int,
    )

    /** Encodes a jump label with number [number] into [out] beginning at word offset [pos]. */
    public fun encodeLabel(number: Int, out: IntArray, pos: Int)
}

/** Supplies or chooses the virtual base address for an event script. */
public fun interface ScriptBases {
    /** Returns the base address of script [name], or [NONE] for an automatic synthetic address. */
    public fun baseOf(name: String): Int

    /** Predefined base address strategies. */
    public companion object {
        /** Sentinel indicating no fixed address; the assembler assigns a synthetic address. */
        public const val NONE: Int = -1

        /** Strategy assigning synthetic addresses to all scripts. */
        public val SYNTHETIC: ScriptBases = ScriptBases { NONE }
    }
}

/** Interceptor allowing command transformation or expansion prior to bytecode assembly. */
public fun interface CommandExpander {
    /** Expands [cmd] into one or more replacement commands, or null to leave unchanged. */
    public fun expand(cmd: ScriptLine.Command): List<ScriptLine.Command>?
}

/**
 * Assembles parsed script lines (from [FeScriptParser]) into a [ScriptImage] of event words. Two
 * passes: first every script's size and address are fixed, so a script can name another (or
 * itself) as a pointer regardless of order, then the words are encoded. Name lookup order for
 * identifier arguments is the file's own scripts, then [constants], then [external].
 */
public class ScriptAssembler(
    private val encoder: CommandEncoder,
    private val constants: SymbolResolver = SymbolResolver.NONE,
    private val external: SymbolResolver = SymbolResolver.NONE,
    private val expander: CommandExpander? = null,
) {
    /** Synthetic address and alignment constants. */
    public companion object {
        /**
         * First synthetic virtual address for scripts without a fixed one (mod scripts): above the
         * 32 MiB ROM window 0x08000000-0x09FFFFFF (ADR 0001, M5 amendment).
         */
        public const val SYNTHETIC_BASE: Int = 0x0A000000

        /** Synthetic addresses advance in multiples of this many bytes (size rounded up). */
        public const val ALIGNMENT: Int = 4
    }

    /** In-flight assembly block representing one event script. */
    private class Block(val name: String, val line: Int) {
        val lines = ArrayList<ScriptLine>()
        var wordCount = 0
        var base = 0
        var wordOffset = 0
    }

    /**
     * Compiles [lines] into a [ScriptImage]. Scripts receive addresses from [bases] or [SYNTHETIC_BASE].
     */
    public fun assemble(
        lines: List<ScriptLine>,
        bases: ScriptBases = ScriptBases.SYNTHETIC,
    ): ScriptImage {
        val blocks = split(lines)
        var totalWords = 0
        var syntheticAddr = SYNTHETIC_BASE
        val byName = HashMap<String, Block>()

        for (block in blocks) {
            if (byName.put(block.name, block) != null) fail(block.line, "duplicate event '${block.name}'")
            block.wordCount = sizeOf(block)
            // Start each script on an even word, i.e. a 4-byte boundary in the word array.
            totalWords = (totalWords + 1) and 1.inv()
            block.wordOffset = totalWords
            totalWords += block.wordCount
            val fixed = bases.baseOf(block.name)
            if (fixed != ScriptBases.NONE) {
                // Event words are u16: an address must be even; negative means bit 31 set, which
                // ScriptImage also rejects.
                if (fixed < 0 || fixed and 1 != 0) fail(block.line, "bad address for '${block.name}'")
                block.base = fixed
            } else {
                block.base = syntheticAddr
                syntheticAddr += (block.wordCount * 2 + ALIGNMENT - 1) / ALIGNMENT * ALIGNMENT
            }
        }

        val ownResolver = SymbolResolver { name, _ ->
            byName[name]?.base?.toLong() ?: SymbolResolver.UNRESOLVED
        }
        val symbols = SymbolResolver.chain(ownResolver, constants, external)
        val words = IntArray(totalWords)

        for (block in blocks) {
            encodeBlock(block, symbols, words)
        }

        val entries = blocks.map {
            ScriptImage.Entry(it.name, it.base, it.wordOffset, it.wordCount)
        }
        try {
            return ScriptImage(words, entries)
        } catch (e: IllegalArgumentException) {
            throw ScriptAssemblyException(0, e.message ?: "invalid image")
        }
    }

    /** Groups input lines into blocks demarcated by [ScriptLine.EventStart]. */
    private fun split(lines: List<ScriptLine>): List<Block> {
        val blocks = ArrayList<Block>()
        for (line in lines) {
            if (line is ScriptLine.EventStart) {
                blocks += Block(line.name, line.line)
            } else {
                val currentBlock = blocks.lastOrNull() ?: fail(line.line, "record outside an event block")
                if (line is ScriptLine.Command && expander != null) {
                    currentBlock.lines += expander.expand(line) ?: listOf(line)
                } else {
                    currentBlock.lines += line
                }
            }
        }
        return blocks
    }

    /** Computes the total 16-bit word length of [block]. */
    private fun sizeOf(block: Block): Int {
        var wordSum = 0
        for (line in block.lines) {
            wordSum += when (line) {
                is ScriptLine.Label -> encoder.labelWordCount
                is ScriptLine.Command -> encoder.wordCount(line.spec)
                is ScriptLine.EventStart -> 0
            }
        }
        return wordSum
    }

    /** Encodes all statements within [block] into the shared bytecode array [words]. */
    private fun encodeBlock(block: Block, symbols: SymbolResolver, words: IntArray) {
        var pos = block.wordOffset
        for (line in block.lines) {
            try {
                when (line) {
                    is ScriptLine.Label -> {
                        encoder.encodeLabel(line.number, words, pos)
                        pos += encoder.labelWordCount
                    }
                    is ScriptLine.Command -> {
                        encoder.encode(line.spec, line.args, symbols, words, pos)
                        pos += encoder.wordCount(line.spec)
                    }
                    is ScriptLine.EventStart -> Unit
                }
            } catch (e: Exception) {
                fail(line.line, e.message ?: "encode failed")
            }
        }
    }

    /** Throws a [ScriptAssemblyException] formatted with source line [line]. */
    private fun fail(line: Int, message: String): Nothing =
        throw ScriptAssemblyException(line, message)
}
