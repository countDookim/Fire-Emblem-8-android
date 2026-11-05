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
