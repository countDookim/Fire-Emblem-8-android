package gbafe.script

/** A parse error; [toString] is `<file>:<line>: <message>` (1-based line). */
public data class ScriptError(
    /** Source file name as given to the parser. */
    public val file: String,
    /** 1-based line. */
    public val line: Int,
    /** What is wrong. */
    public val message: String,
) {
    /** Formats the error as `<file>:<line>: <message>`. */
    override fun toString(): String = "$file:$line: $message"
}

/** Result of [FeScriptParser.parse]. */
public class ParseResult(
    /** Records of all lines that parsed, in source order (immutable). */
    public val lines: List<ScriptLine>,
    /** Errors in source order, at most one per source line (immutable). */
    public val errors: List<ScriptError>,
) {
    /** True when there are no errors. */
    public val ok: Boolean get() = errors.isEmpty()
}

/** Thrown by [FeScriptParser.parseOrThrow]; the message has one `<file>:<line>: <message>` each. */
public class ScriptParseException(
    /** All errors, in source order. */
    public val errors: List<ScriptError>,
) : RuntimeException(errors.joinToString("\n")) {
    init {
        require(errors.isNotEmpty()) { "no errors" }
    }
}

/**
 * Parser for the `.fe8s` text format (docs/decisions/0001-script-format.md), run at load time
 * (allocation is fine).
 *
 * Line-oriented. `#` starts a comment (anywhere on a line); blank lines are ignored. An
 * `event <Name>` line starts a block that ends at the next `event` line or the end of the file.
 * Inside a block, `label N` declares a numeric label and every other line is
 * `command arg arg ...`. Arguments are integers (decimal, `0x` hex, optional `-`) or identifiers
 * (`[A-Za-z_][A-Za-z0-9_]*`, or several joined by `|` as a bit mask: `A|B`). Commands come from
 * [commands]; identifiers must be known to [identifiers] or be an `event` name defined anywhere in
 * the same file.
 */
public class FeScriptParser(
    /** The commands a script may use (aliases resolved here). */
    private val commands: CommandSet,
    /** Known constants; script names of the file are accepted in addition. */
    private val identifiers: IdentifierResolver = IdentifierResolver.NONE,
) {
    /** Parses [text] (named [file] in messages); collects all errors instead of stopping at one. */
    public fun parse(file: String, text: String): ParseResult {
        val tokens = text.split('\n').map { tokenize(it) }
        val errors = ArrayList<ScriptError>()
        val out = ArrayList<ScriptLine>()
        val events = HashSet<String>()
        for (t in tokens) {
            if (t.size == 2 && t[0] == "event" && isIdentifier(t[1])) events += t[1]
        }
        val seenEvents = HashSet<String>()
        val labels = HashSet<Int>()
        var inEvent = false
        for ((i, t) in tokens.withIndex()) {
            if (t.isEmpty()) continue
            val line = i + 1
            val err = { msg: String -> errors += ScriptError(file, line, msg); Unit }
            when (t[0]) {
                "event" -> {
                    if (t.size != 2 || !isIdentifier(t[1])) {
                        err("malformed event header, expected 'event <Name>'")
                    } else if (!seenEvents.add(t[1])) {
                        err("duplicate event '${t[1]}'")
                    } else {
                        inEvent = true
                        labels.clear()
                        out += ScriptLine.EventStart(t[1], line)
                    }
                }
                "label" -> {
                    val n = if (t.size == 2) parseNumber(t[1]) else null
                    if (!inEvent) {
                        err("label outside an event block")
                    } else if (t.size != 2) {
                        err("malformed label, expected 'label <number>'")
                    } else if (n == null) {
                        err("malformed number '${t[1]}'")
                    } else if (n < 0) {
                        err("label number must not be negative: '${t[1]}'")
                    } else if (!labels.add(n)) {
                        err("duplicate label $n")
                    } else {
                        out += ScriptLine.Label(n, line)
                    }
                }
                else -> parseCommand(t, line, inEvent, events, err)?.let { out += it }
            }
        }
        return ParseResult(out.toList(), errors.toList())
    }

    /** Like [parse] but returns the records, or throws [ScriptParseException] on any error. */
    public fun parseOrThrow(file: String, text: String): List<ScriptLine> {
        val r = parse(file, text)
        if (!r.ok) throw ScriptParseException(r.errors)
        return r.lines
    }

    /** Parses a command line [t] at source line [line], reporting syntax errors to [err]. */
    private fun parseCommand(
        t: List<String>,
        line: Int,
        inEvent: Boolean,
        events: Set<String>,
        err: (String) -> Unit,
    ): ScriptLine.Command? {
        val spec = commands.lookup(t[0])
        if (spec == null) {
            err("unknown command '${t[0]}'")
            return null
        }
        if (!inEvent) {
            err("command '${t[0]}' outside an event block")
            return null
        }
        val actual = t.size - 1
        if (actual != spec.argCount) {
            err("wrong argument count for '${t[0]}': expected ${spec.argCount}, got $actual")
            return null
        }
        val args = ArrayList<Arg>(actual)
        for (k in 1..actual) {
            val s = t[k]
            val kind = spec.argKinds[k - 1]
            if (looksNumeric(s)) {
                val v = parseNumber(s)
                if (v == null) {
                    err("malformed number '$s'")
                    return null
                }
                if (kind == ArgKind.IDENT) {
                    err("argument $k of '${t[0]}' must be an identifier, got '$s'")
                    return null
                }
                args += Arg.IntArg(v)
            } else if (!isIdentifierMask(s)) {
                err("malformed identifier '$s'")
                return null
            } else if (kind == ArgKind.INT) {
                err("argument $k of '${t[0]}' must be a number, got '$s'")
                return null
            } else if (!isKnownMask(s, events)) {
                err("unknown identifier '$s'")
                return null
            } else {
                args += Arg.IdentArg(s)
            }
        }
        return ScriptLine.Command(spec, args.toList(), line)
    }

    /**
     * Strips the `#` comment from [row] and splits the rest at spaces, tabs and `\r` (so CRLF
     * files parse).
     */
    private fun tokenize(row: String): List<String> {
        val hash = row.indexOf('#')
        val code = if (hash >= 0) row.substring(0, hash) else row
        val out = ArrayList<String>()
        var start = -1
        for (i in code.indices) {
            val blank = code[i] == ' ' || code[i] == '\t' || code[i] == '\r'
            if (blank && start >= 0) {
                out += code.substring(start, i)
                start = -1
            } else if (!blank && start < 0) {
                start = i
            }
        }
        if (start >= 0) out += code.substring(start)
        return out
    }

    /**
     * True if [s] begins with a digit or a sign, so it is read as a number. A leading `+` passes
     * here but fails in [parseNumber], giving "malformed number" rather than "unknown identifier".
     */
    private fun looksNumeric(s: String): Boolean = isDigit(s[0]) || s[0] == '-' || s[0] == '+'

    /** Returns true if char [c] is an ASCII decimal digit. */
    private fun isDigit(c: Char): Boolean = c in '0'..'9'

    /** An identifier, or identifiers joined by `|` (a bit mask such as `A|B`). */
    private fun isIdentifierMask(s: String): Boolean = s.split('|').all { isIdentifier(it) }

    /**
     * True if every `|`-joined part of [s] is an `event` name of this file or known to
     * [identifiers].
     */
    private fun isKnownMask(s: String, events: Set<String>): Boolean =
        s.split('|').all { it in events || identifiers.isKnown(it) }

    /** Returns true if [s] is a valid C identifier (`[A-Za-z_][A-Za-z0-9_]*`). */
    private fun isIdentifier(s: String): Boolean {
        if (s.isEmpty() || isDigit(s[0])) return false
        return s.all { it == '_' || it in 'a'..'z' || it in 'A'..'Z' || isDigit(it) }
    }

    /**
     * Decimal (up to [Int.MAX_VALUE], or down to [Int.MIN_VALUE] with `-`) or `0x` hex (up to 32
     * bits, so `0xFFFFFFFF` is -1; GBA ROM pointers have bit 31 clear, but event words are
     * u16/u32 values). Null if malformed or out of range.
     */
    private fun parseNumber(s: String): Int? {
        val neg = s.startsWith("-")
        val body = if (neg) s.substring(1) else s
        val hex = body.startsWith("0x") || body.startsWith("0X")
        val digits = if (hex) body.substring(2) else body
        if (digits.isEmpty()) return null
        val radix = if (hex) 16 else 10
        var v = 0L
        for (c in digits) {
            val d = when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + 10
                in 'A'..'F' -> c - 'A' + 10
                else -> return null
            }
            if (d >= radix) return null
            v = v * radix + d
            if (v > 0xFFFFFFFFL) return null
        }
        return when {
            neg -> if (v <= 0x80000000L) (-v).toInt() else null
            hex || v <= Int.MAX_VALUE -> v.toInt()
            else -> null
        }
    }
}
