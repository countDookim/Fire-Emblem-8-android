package gbafe.script

import gbafe.core.flags.FlagRegistry
import gbafe.core.flags.MOD_BASE

/**
 * Mod-registered flag names as script identifiers: a [SymbolResolver] (value = the flag handle,
 * `>= MOD_BASE`) for fields whose constant kind is [flagKind], and the matching
 * [IdentifierResolver] for the parser. Put it after the game's own constants in the resolver
 * chain; vanilla flag names stay with the game's constants (this resolver only answers for mod
 * names), and an unknown name stays unresolved so the caller's `file:line:` error is unchanged.
 *
 * Variables are not resolved here: no script command takes a [gbafe.core.flags.VarRegistry]
 * handle yet (the event slots are a separate mechanism).
 *
 * @property flags the registry mods register their flag names in (before scripts are assembled).
 * @property flagKind the constant kind of event-flag fields (`"event_flag"` for FE8).
 */
public class ModFlagSymbols(
    private val flags: FlagRegistry,
    public val flagKind: String = DEFAULT_FLAG_KIND,
) : SymbolResolver, IdentifierResolver {
    /** Defaults. */
    public companion object {
        /** Constant kind of event-flag fields. */
        public const val DEFAULT_FLAG_KIND: String = "event_flag"
    }

    override fun resolve(name: String, kind: String?): Long {
        if (kind != flagKind) return SymbolResolver.UNRESOLVED
        val h = flags.resolve(name)
        return if (h >= MOD_BASE) h.toLong() else SymbolResolver.UNRESOLVED
    }

    override fun isKnown(name: String): Boolean = flags.resolve(name) >= MOD_BASE
}

/**
 * A [CommandExpander] for scripts that name mod flags. A mod flag handle is negative as u16, which
 * the event VM reads as "flag id in event slot [slot]"; so before a command with a mod flag name in
 * a flag argument this inserts `set_slot <slot>, <handle>` ([setSlot]), copying the command's line.
 * Side effect visible to the script: the expansion overwrites event slot [slot]. Commands without
 * a mod flag are unchanged, so vanilla scripts assemble byte-exact.
 *
 * @property mods resolves mod flag names.
 * @property setSlot the game's `set_slot` spec (arguments: slot, value).
 * @property slot the slot the VM reads flag ids from.
 * @property flagArgs indices of the flag arguments of a command spec.
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
        val set = ScriptLine.Command(
            setSlot, listOf(Arg.IntArg(slot), Arg.IntArg(handle.toInt())), cmd.line,
        )
        return listOf(set, cmd)
    }
}
