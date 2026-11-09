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
 * Mod flag handles are negative when represented as 16-bit signed integers, which the event virtual
 * machine interprets as referencing event slot [slot]. This expander automatically prepends a
 * `set_slot <slot>, <handle>` command ([setSlot]) before any command using a mod flag.
 */
public class ModFlagSlotExpander(
    private val mods: ModFlagSymbols,
    private val setSlot: CommandSpec,
    private val slot: Int,
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
