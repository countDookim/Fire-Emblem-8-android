package gbafe.script

import gbafe.core.flags.FlagRegistry
import gbafe.core.flags.MOD_BASE

/**
 * Adapts mod-registered flag names into script symbol identifiers.
 *
 * Resolves flag names into runtime handles (`>= MOD_BASE`) when queried for category [flagKind],
 * and acts as an [IdentifierResolver] for the script parser.
 *
 * @property flags registry containing mod flag declarations.
 * @property flagKind constant category name representing event flags (`"event_flag"` in FE8).
 */
public class ModFlagSymbols(
    private val flags: FlagRegistry,
    public val flagKind: String = DEFAULT_FLAG_KIND,
) : SymbolResolver, IdentifierResolver {
    /** Default constant kinds. */
    public companion object {
        /** Default category kind for event flags. */
        public const val DEFAULT_FLAG_KIND: String = "event_flag"
    }

    override fun resolve(name: String, kind: String?): Long {
        if (kind != flagKind) return SymbolResolver.UNRESOLVED
        val handle = flags.resolve(name)
        return if (handle >= MOD_BASE) handle.toLong() else SymbolResolver.UNRESOLVED
    }

    override fun isKnown(name: String): Boolean = flags.resolve(name) >= MOD_BASE
}

/**
 * Command expander for script commands referencing mod-defined flags.
 *
 * A mod flag handle is at least [MOD_BASE] (0x8000), so as a 16-bit event argument it is negative.
 * The original flag commands (`EVSET`, src/eventscr.c:116-117) read a negative flag argument as
 * "use the value of event slot 2" (`gEventSlots[2]`) instead of the argument, so a mod flag can
 * be reached by loading its handle into that slot first. Before any command that names a mod flag
 * this expander puts a `set_slot <slot>, <handle>` command ([setSlot]); FE8 passes slot 2
 * (Fe8ScriptSymbols.kt). Side effect: the script's own slot-2 value is overwritten there.
 * Only the flag commands in [flagArgs] are checked for this.
 */
public class ModFlagSlotExpander(
    private val mods: ModFlagSymbols,
    private val setSlot: CommandSpec,
    private val slot: Int,
    /** Argument positions of a command that hold an event flag id (FE8: `flagArgIndices`). */
    private val flagArgs: (CommandSpec) -> List<Int>,
) : CommandExpander {
    override fun expand(cmd: ScriptLine.Command): List<ScriptLine.Command>? {
        var handle = SymbolResolver.UNRESOLVED
        for (i in flagArgs(cmd.spec)) {
            val a = cmd.args.getOrNull(i) as? Arg.IdentArg ?: continue
            val h = mods.resolve(a.name, mods.flagKind)
            if (h == SymbolResolver.UNRESOLVED) continue
            if (handle != SymbolResolver.UNRESOLVED) {
                throw ScriptAssemblyException(
                    cmd.line,
                    "'${cmd.spec.name}' names two mod flags; only one fits event slot $slot",
                )
            }
            handle = h
        }
        if (handle == SymbolResolver.UNRESOLVED) return null
        val setCommand = ScriptLine.Command(
            setSlot,
            listOf(Arg.IntArg(slot), Arg.IntArg(handle.toInt())),
            cmd.line,
        )
        return listOf(setCommand, cmd)
    }
}
