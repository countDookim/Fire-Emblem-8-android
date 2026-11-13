package fe8.game.event

import gbafe.extract.fe8.events.Fe8ScriptConstants
import gbafe.script.CommandEncoder
import gbafe.script.FeScriptParser
import gbafe.script.IdentifierResolver
import gbafe.script.ModFlagSlotExpander
import gbafe.script.ModFlagSymbols
import gbafe.script.ScriptAssembler
import gbafe.script.ScriptConstants
import gbafe.script.SymbolResolver

/**
 * Names outside the scripts of the FE8 ROM, read from the decomp symbol list (`nm` output:
 * `<addr> [<size>] <type> <name>`, such as `fireemblem8.nm.txt`): name to address, with a sorted
 * address array behind [nameOfAddress]. Absolute (`a`) and zero-address symbols are skipped; the
 * first symbol of a name wins.
 */
public class NmSymbolIndex(nmText: String) : SymbolResolver, IdentifierResolver {
    private val byName = HashMap<String, Int>()
    private val sortedAddresses: IntArray
    private val namesByAddress: Array<String>

    init {
        var start = 0
        val textLength = nmText.length
        while (start < textLength) {
            var end = nmText.indexOf('\n', start)
            if (end < 0) end = textLength
            parse(nmText.substring(start, end))
            start = end + 1
        }
        val order = byName.entries.sortedBy { it.value.toLong() and 0xFFFFFFFFL }
        sortedAddresses = IntArray(order.size) { order[it].value }
        namesByAddress = Array(order.size) { order[it].key }
    }

    /**
     * Adds the symbol of one `nm` line. Ignored: lines without an 8-digit address and a one-letter
     * type, absolute symbols (`a`/`A`) and address 0.
     */
    private fun parse(row: String) {
        val parts = row.trimEnd().split(' ')
        if (parts.size < 3 || parts[0].length != 8) return
        val type = parts[parts.size - 2]
        if (type.length != 1 || type == "a" || type == "A") return
        val addr = parts[0].toLongOrNull(16)?.toInt() ?: return
        if (addr == 0) return
        val name = parts[parts.size - 1]
        if (!byName.containsKey(name)) byName[name] = addr
    }

    /** Number of distinct names. */
    public val size: Int get() = byName.size

    /** The address of [name], or null. */
    public fun addressOf(name: String): Int? = byName[name]

    /** The name of the symbol at exactly [address], or null (binary search, unsigned order). */
    public fun nameOfAddress(address: Int): String? {
        var lo = 0
        var hi = sortedAddresses.size - 1
        val key = address.toLong() and 0xFFFFFFFFL
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = sortedAddresses[mid].toLong() and 0xFFFFFFFFL
            if (v == key) return namesByAddress[mid]
            if (v < key) lo = mid + 1 else hi = mid - 1
        }
        return null
    }

    /** Value of [name] for a field of [kind], or `SymbolResolver.UNRESOLVED`. */
    override fun resolve(name: String, kind: String?): Long =
        byName[name]?.let { it.toLong() and 0xFFFFFFFFL } ?: SymbolResolver.UNRESOLVED

    /** True if [name] is a known symbol. */
    override fun isKnown(name: String): Boolean = byName.containsKey(name)
}

/**
 * FE8 constants ([Fe8ScriptConstants]) as a [SymbolResolver] (by the constant kind of the field)
 * and as the parser's [IdentifierResolver] (any kind). It adds [FLAG_AT_SLOT2], the event flag
 * argument `0xFFFF` meaning "the flag number is in slot 2" (the flag commands read a negative
 * flag argument as `gEventSlots[2]`, src/eventscr.c:116-117). That name is ours, not the decomp's.
 */
public class Fe8ConstantSymbols(
    private val constants: ScriptConstants = Fe8ScriptConstants,
) : SymbolResolver, IdentifierResolver {
    /** The extra flag name and the constant kinds searched by [isKnown]. */
    public companion object {
        /** `EVFLAG_AT_SLOT2`: the flag is taken from event slot 2. */
        public const val FLAG_AT_SLOT2: String = "EVFLAG_AT_SLOT2"

        /** The value of [FLAG_AT_SLOT2]: -1 as a u16 flag argument. */
        private const val SLOT2_VALUE = 0xFFFFL

        /** Every constant kind of [Fe8ScriptConstants]; a name is known if any kind has it. */
        private val KINDS = listOf(
            Fe8ScriptConstants.KIND_CHARACTER, Fe8ScriptConstants.KIND_CLASS,
            Fe8ScriptConstants.KIND_ITEM, Fe8ScriptConstants.KIND_SONG,
            Fe8ScriptConstants.KIND_CHAPTER, Fe8ScriptConstants.KIND_BACKGROUND,
            Fe8ScriptConstants.KIND_EVENT_FLAG, Fe8ScriptConstants.KIND_WM_NODE,
            Fe8ScriptConstants.KIND_WM_PATH, Fe8ScriptConstants.KIND_WM_NATION,
            Fe8ScriptConstants.KIND_WM_MU, Fe8ScriptConstants.KIND_SLOT,
            Fe8ScriptConstants.KIND_MENU_OVERRIDE,
        )
    }

    /**
     * Value of [name] for a field of constant [kind] (`Fe8ScriptConstants.KIND_*`), or
     * `SymbolResolver.UNRESOLVED` (also for a null kind: a plain number field has no names).
     * [FLAG_AT_SLOT2] only resolves for the event flag kind.
     */
    override fun resolve(name: String, kind: String?): Long {
        if (kind == null) return SymbolResolver.UNRESOLVED
        if (kind == Fe8ScriptConstants.KIND_EVENT_FLAG && name == FLAG_AT_SLOT2) return SLOT2_VALUE
        val v = constants.valueOf(kind, name)
        return if (v == ScriptConstants.NONE) SymbolResolver.UNRESOLVED else v.toLong()
    }

    /** True if [name] is a known symbol. */
    override fun isKnown(name: String): Boolean =
        name == FLAG_AT_SLOT2 || KINDS.any { constants.valueOf(it, name) != ScriptConstants.NONE }
}

/**
 * The FE8 `.fe8s` toolchain: a parser and an assembler over [Fe8CommandSet], the FE8 constants and
 * the [external] symbols (names of ROM data and functions from the decomp symbol list). [mods]
 * adds mod-registered flag names after the FE8 constants. A mod flag handle is 0x8000 or more,
 * negative as a 16-bit argument, which the flag commands read as "use event slot 2"; so a command
 * using one gets a `set_slot 2, <handle>` before it ([ModFlagSlotExpander]), overwriting the
 * script's own slot 2 there.
 *
 * @param external ROM data and function names.
 * @param constants the FE8 constants (characters, items, flags and so on).
 * @param encoder how commands are encoded to event words.
 * @param mods mod-registered flag names, or null.
 */
public class Fe8ScriptTools(
    external: NmSymbolIndex,
    constants: ScriptConstants = Fe8ScriptConstants,
    encoder: CommandEncoder = Fe8CommandEncoder,
    mods: ModFlagSymbols? = null,
) {
    private val constantSymbols = Fe8ConstantSymbols(constants)

    /** Parser for FE8 scripts: FE8 commands, constants and external symbol names. */
    public val parser: FeScriptParser = FeScriptParser(
        Fe8CommandSet,
        IdentifierResolver {
            constantSymbols.isKnown(it) || external.isKnown(it) || mods?.isKnown(it) == true
        },
    )

    /** Assembler for FE8 scripts. */
    public val assembler: ScriptAssembler = ScriptAssembler(
        encoder,
        if (mods == null) constantSymbols else SymbolResolver.chain(constantSymbols, mods),
        external,
        mods?.let { m ->
            Fe8CommandSet.lookup("set_slot")?.let { setSlot ->
                ModFlagSlotExpander(m, setSlot, 2, Fe8CommandSet::flagArgIndices)
            }
        },
    )
}
