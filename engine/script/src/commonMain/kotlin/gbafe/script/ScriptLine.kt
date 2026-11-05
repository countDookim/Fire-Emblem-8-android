package gbafe.script

/** One argument of a command line: an integer or an identifier. */
public sealed class Arg {
    /** An integer written in decimal, `0x` hex or with a leading `-`. */
    public data class IntArg(
        /** The value; hex above `0x7FFFFFFF` (up to `0xFFFFFFFF`) wraps to a negative [Int]. */
        public val value: Int,
    ) : Arg()

    /** A name (constant, or an `event` defined in the same file) as written in the source. */
    public data class IdentArg(
        /** The identifier text. */
        public val name: String,
    ) : Arg()
}

/** Expected kind of one command argument. */
public enum class ArgKind {
    /** Integer or identifier. */
    ANY,

    /** Integer literal only. */
    INT,

    /** Identifier only. */
    IDENT,
}

/** A command known to a [CommandSet]. */
public data class CommandSpec(
    /** Canonical name (aliases are resolved by the [CommandSet] before this is returned). */
    public val name: String,
    /** Exact number of arguments. */
    public val argCount: Int,
    /** Expected kind per argument; must have [argCount] entries. Defaults to [ArgKind.ANY]. */
    public val argKinds: List<ArgKind> = List(argCount) { ArgKind.ANY },
) {
    init {
        require(argCount >= 0) { "argCount < 0" }
        require(argKinds.size == argCount) {
            "argKinds has ${argKinds.size} entries, argCount is $argCount"
        }
    }
}

/** The injected set of commands a script may use. */
public fun interface CommandSet {
    /** Returns the spec for [name] (canonical name or an alias), or null if it is not a command. */
    public fun lookup(name: String): CommandSpec?
}

/** Decides whether an identifier is a known constant; script names of the file are the parser's. */
public fun interface IdentifierResolver {
    /** True if [name] is a known constant. */
    public fun isKnown(name: String): Boolean

    /** Ready-made resolvers. */
    public companion object {
        /** Knows no constants: only script names defined in the file are accepted. */
        public val NONE: IdentifierResolver = IdentifierResolver { false }
    }
}

/** One record of a parsed script; [line] is the 1-based source line. */
public sealed class ScriptLine {
    /** 1-based source line. */
    public abstract val line: Int

    /** Start of an `event <name>` block; it runs to the next [EventStart] or the end of file. */
    public data class EventStart(
        /** Event (script) name. */
        public val name: String,
        /** 1-based source line. */
        override val line: Int,
    ) : ScriptLine()

    /** A `label N` line inside an event block. */
    public data class Label(
        /** Label number, unique within its event. */
        public val number: Int,
        /** 1-based source line. */
        override val line: Int,
    ) : ScriptLine()

    /** A command line. */
    public data class Command(
        /** The resolved command (canonical name). */
        public val spec: CommandSpec,
        /** The arguments, [CommandSpec.argCount] of them. */
        public val args: List<Arg>,
        /** 1-based source line. */
        override val line: Int,
    ) : ScriptLine()
}
