package gbafe.script

/**
 * One argument of a script command: an integer value or an identifier.
 */
public sealed class Arg {
    /**
     * An integer argument written in decimal or hexadecimal (`0x...`), with an optional leading
     * `-`. A leading `+` is not accepted ([FeScriptParser] reports "malformed number").
     */
    public data class IntArg(
        /** The resolved integer value (hex values in `0x80000000..0xFFFFFFFF` wrap to negative [Int]). */
        public val value: Int,
    ) : Arg()

    /**
     * An identifier argument: a named constant or flag (`CHARACTER_*`, `EVFLAG_*`...), a name from
     * the game's symbol list (ROM data or functions), or the name of an `event` block in the same
     * file (a pointer to that script). It may be several names joined by `|` (a bit mask). Numeric
     * labels (`label N`) are not identifiers; they are plain numbers.
     */
    public data class IdentArg(
        /** The literal identifier text as written in source code. */
        public val name: String,
    ) : Arg()
}

/** Expected argument type for a command parameter in [CommandSpec]. */
public enum class ArgKind {
    /** Argument may be either an integer literal or a named identifier. */
    ANY,

    /** Argument must be an integer literal. */
    INT,

    /** Argument must be an identifier name. */
    IDENT,
}

/** Specification and signature for a script command recognized by a [CommandSet]. */
public data class CommandSpec(
    /** Canonical command mnemonic name (aliases are resolved prior to constructing this spec). */
    public val name: String,
    /** Number of required arguments for this command. */
    public val argCount: Int,
    /** Expected argument kind for each parameter position; must have length [argCount]. Defaults to [ArgKind.ANY]. */
    public val argKinds: List<ArgKind> = List(argCount) { ArgKind.ANY },
) {
    init {
        require(argCount >= 0) { "argCount < 0" }
        require(argKinds.size == argCount) {
            "argKinds has ${argKinds.size} entries, argCount is $argCount"
        }
    }
}

/** Injected set of supported commands for a script dialect. */
public fun interface CommandSet {
    /** Returns the [CommandSpec] for [name] (canonical name or alias), or null if unrecognized. */
    public fun lookup(name: String): CommandSpec?
}

/** Resolves whether an identifier string is a known constant or defined label. */
public fun interface IdentifierResolver {
    /** Returns true if [name] is a recognized identifier. */
    public fun isKnown(name: String): Boolean

    /** Predefined identifier resolvers. */
    public companion object {
        /** Resolver recognizing zero constants; only event labels declared in the file are accepted. */
        public val NONE: IdentifierResolver = IdentifierResolver { false }
    }
}

/** One record in a parsed event script file; [line] indicates the 1-based source line. */
public sealed class ScriptLine {
    /** 1-based line number in the source file. */
    public abstract val line: Int

    /** Header declaring the start of a named event block (`event <Name>`). */
    public data class EventStart(
        /** Name of the event script. */
        public val name: String,
        /** 1-based source line. */
        override val line: Int,
    ) : ScriptLine()

    /**
     * Jump destination declaration (`label N`) within an event block; assembled to one label
     * command (FE8 `EvtLabel`, 2 words, include/eventscript.h:605) that `goto` finds by number.
     */
    public data class Label(
        /** Label number, unique within the enclosing event script. */
        public val number: Int,
        /** 1-based source line. */
        override val line: Int,
    ) : ScriptLine()

    /** An executable script command invocation with its arguments. */
    public data class Command(
        /** The command specification. */
        public val spec: CommandSpec,
        /** Arguments supplied to the command. */
        public val args: List<Arg>,
        /** 1-based source line. */
        override val line: Int,
    ) : ScriptLine()
}
