package gbafe.script

/** Thrown by [ScriptAssembler.assemble]; [line] is the 1-based source line (0 if unknown). */
public class ScriptAssemblyException(
    /** 1-based source line of the offending record, or 0. */
    public val line: Int,
    /** What is wrong. */
    public override val message: String,
) : RuntimeException("line $line: $message")

/**
 * Resolves an identifier to a number: a constant, or a script or symbol address. Several are
 * combined with [chain].
 */
public fun interface SymbolResolver {
    /**
     * The value of [name], or [UNRESOLVED]. [kind] is the constant kind the argument's field takes
     * values from (`"character"`...), or null for plain fields and addresses.
     */
    public fun resolve(name: String, kind: String?): Long

    /** Shared resolvers and the not-found marker. */
    public companion object {
        /** Returned for an unknown name; no 32-bit value (signed or unsigned) equals it. */
        public const val UNRESOLVED: Long = Long.MIN_VALUE

        /** Knows nothing. */
        public val NONE: SymbolResolver = SymbolResolver { _, _ -> UNRESOLVED }

        /** The first of [resolvers] that knows a name wins. */
        public fun chain(vararg resolvers: SymbolResolver): SymbolResolver =
            SymbolResolver { name, kind ->
                var v = UNRESOLVED
                for (r in resolvers) {
                    v = r.resolve(name, kind)
                    if (v != UNRESOLVED) break
                }
                v
            }
    }
}

/**
 * Resolves [name], which may be several names joined by `|` (a bit mask), to the OR of their
 * values; [SymbolResolver.UNRESOLVED] if any part is unknown.
 */
public fun SymbolResolver.resolveMask(name: String, kind: String?): Long {
    if (name.indexOf('|') < 0) return resolve(name, kind)
    var acc = 0L
    for (part in name.split('|')) {
        val v = resolve(part, kind)
        if (v == SymbolResolver.UNRESOLVED) return v
        acc = acc or v
    }
    return acc
}

/**
 * The game-specific half of the assembler: the word layout of each command. Implemented per game
 * (for FE8 from the decomp's `include/eventscript.h` macros).
 */
public interface CommandEncoder {
    /** Number of u16 words of `label N`. */
    public val labelWordCount: Int

    /** Number of u16 words (header included) of a command of [spec]. */
    public fun wordCount(spec: CommandSpec): Int

    /**
     * Writes the [wordCount] words of the command to [out] from index [pos]. Identifiers in [args]
     * are resolved through [symbols]; throws [IllegalArgumentException] on an unknown name or an
     * argument that does not fit its field. [out] is zero-filled beforehand.
     */
    public fun encode(
        spec: CommandSpec,
        args: List<Arg>,
        symbols: SymbolResolver,
        out: IntArray,
        pos: Int,
    )

    /** Writes the [labelWordCount] words of `label` [number] to [out] from index [pos]. */
    public fun encodeLabel(number: Int, out: IntArray, pos: Int)
}

/** Chooses the virtual base address of a script (see [ScriptAssembler.assemble]). */
public fun interface ScriptBases {
    /** The base address of script [name], or [NONE] to let the assembler pick a synthetic one. */
    public fun baseOf(name: String): Int

    /** Ready-made choices. */
    public companion object {
        /** [baseOf] value for "no fixed address". */
        public const val NONE: Int = -1

        /** Every script gets a synthetic address. */
        public val SYNTHETIC: ScriptBases = ScriptBases { NONE }
    }
}

/**
 * Compiles parsed `.fe8s` records ([FeScriptParser]) to a [ScriptImage]: the original game's event
 * words, laid out per [encoder]. It runs at load time (allocation is fine).
 *
 * Identifiers resolve through a chain: scripts of the image (to their base address), then
 * [constants], then [external] names (such as decomp symbols, via an address index).
 */
public class ScriptAssembler(
    /** Word layouts of the commands. */
    private val encoder: CommandEncoder,
    /** Constant names; receives the field's constant kind. */
    private val constants: SymbolResolver = SymbolResolver.NONE,
    /** Names outside the image, for example ROM symbols. */
    private val external: SymbolResolver = SymbolResolver.NONE,
) {
    /** Address constants of the assembler. */
    public companion object {
        /** First synthetic script address, above the 32 MiB ROM window. */
        public const val SYNTHETIC_BASE: Int = 0x0A000000

        /** Synthetic addresses are multiples of this many bytes. */
        public const val ALIGNMENT: Int = 4
    }

    private class Block(val name: String, val line: Int) {
        val lines = ArrayList<ScriptLine>()
        var wordCount = 0
        var base = 0
        var wordOffset = 0
    }

    /**
     * Assembles [lines] (the records of one or more parsed files, in order). A script gets
     * [bases]'s address, else a synthetic one from [SYNTHETIC_BASE] up; every script starts at an
     * even word index of the image. Throws [ScriptAssemblyException].
     */
    public fun assemble(
        lines: List<ScriptLine>,
        bases: ScriptBases = ScriptBases.SYNTHETIC,
    ): ScriptImage {
        val blocks = split(lines)
        var total = 0
        var synthetic = SYNTHETIC_BASE
        val byName = HashMap<String, Block>()
        for (b in blocks) {
            if (byName.put(b.name, b) != null) fail(b.line, "duplicate event '${b.name}'")
            b.wordCount = sizeOf(b)
            total = (total + 1) and 1.inv()
            b.wordOffset = total
            total += b.wordCount
            val fixed = bases.baseOf(b.name)
            if (fixed != ScriptBases.NONE) {
                if (fixed < 0 || fixed and 1 != 0) fail(b.line, "bad address for '${b.name}'")
                b.base = fixed
            } else {
                b.base = synthetic
                synthetic += (b.wordCount * 2 + ALIGNMENT - 1) / ALIGNMENT * ALIGNMENT
            }
        }
        val own = SymbolResolver { name, _ ->
            byName[name]?.base?.toLong() ?: SymbolResolver.UNRESOLVED
        }
        val symbols = SymbolResolver.chain(own, constants, external)
        val words = IntArray(total)
        for (b in blocks) encodeBlock(b, symbols, words)
        val entries = blocks.map {
            ScriptImage.Entry(it.name, it.base, it.wordOffset, it.wordCount)
        }
        try {
            return ScriptImage(words, entries)
        } catch (e: IllegalArgumentException) {
            throw ScriptAssemblyException(0, e.message ?: "invalid image")
        }
    }

    private fun split(lines: List<ScriptLine>): List<Block> {
        val blocks = ArrayList<Block>()
        for (l in lines) {
            if (l is ScriptLine.EventStart) {
                blocks += Block(l.name, l.line)
            } else {
                val b = blocks.lastOrNull() ?: fail(l.line, "record outside an event block")
                b.lines += l
            }
        }
        return blocks
    }

    private fun sizeOf(b: Block): Int {
        var n = 0
        for (l in b.lines) {
            n += when (l) {
                is ScriptLine.Label -> encoder.labelWordCount
                is ScriptLine.Command -> encoder.wordCount(l.spec)
                is ScriptLine.EventStart -> 0
            }
        }
        return n
    }

    private fun encodeBlock(b: Block, symbols: SymbolResolver, words: IntArray) {
        var pos = b.wordOffset
        for (l in b.lines) {
            try {
                when (l) {
                    is ScriptLine.Label -> {
                        encoder.encodeLabel(l.number, words, pos)
                        pos += encoder.labelWordCount
                    }
                    is ScriptLine.Command -> {
                        encoder.encode(l.spec, l.args, symbols, words, pos)
                        pos += encoder.wordCount(l.spec)
                    }
                    is ScriptLine.EventStart -> Unit
                }
            } catch (e: IllegalArgumentException) {
                fail(l.line, e.message ?: "cannot encode")
            }
        }
    }

    private fun fail(line: Int, message: String): Nothing =
        throw ScriptAssemblyException(line, message)
}
