package gba.extract

/**
 * Tiny deterministic JSON writer for extractor output (zero external serialization dependencies).
 *
 * Supported values: `null`, [Boolean], [Int]/[Long], [String], [List], and [Map] with [String] keys.
 * Map insertion order is strictly preserved so extractor output produces clean, deterministic diffs.
 * Objects and lists whose elements are all scalars are written compactly on a single line;
 * complex structures are indented by two spaces per nesting level.
 */
public object Json {
    /** Indentation step in spaces per level. */
    private const val INDENT_SPACES = 2

    /** Serialises [value] to a deterministic JSON string with a trailing newline. */
    public fun write(value: Any?): String =
        StringBuilder().also { sb ->
            emit(sb, value, 0)
            sb.append('\n')
        }.toString()

    /** Returns true if [v] is a scalar value (not a Map or List). */
    private fun isScalar(v: Any?): Boolean = v !is Map<*, *> && v !is List<*>

    /** Recursively emits [v] into [sb] at indentation level [indent]. */
    private fun emit(sb: StringBuilder, v: Any?, indent: Int) {
        when (v) {
            null -> sb.append("null")
            is Boolean, is Int, is Long -> sb.append(v.toString())
            is String -> emitString(sb, v)
            is List<*> -> {
                if (v.all(::isScalar)) {
                    sb.append('[')
                    v.forEachIndexed { i, e ->
                        if (i > 0) sb.append(", ")
                        emit(sb, e, indent)
                    }
                    sb.append(']')
                } else {
                    sb.append("[\n")
                    v.forEachIndexed { i, e ->
                        pad(sb, indent + INDENT_SPACES)
                        emit(sb, e, indent + INDENT_SPACES)
                        sb.append(if (i < v.size - 1) ",\n" else "\n")
                    }
                    pad(sb, indent)
                    sb.append(']')
                }
            }
            is Map<*, *> -> {
                if (v.isEmpty()) {
                    sb.append("{}")
                    return
                }
                val oneLine = v.values.all(::isScalar)
                sb.append(if (oneLine) "{ " else "{\n")
                var count = 0
                for ((k, e) in v) {
                    if (!oneLine) pad(sb, indent + INDENT_SPACES)
                    emitString(sb, k as String)
                    sb.append(": ")
                    emit(sb, e, indent + INDENT_SPACES)
                    count++
                    if (count < v.size) {
                        sb.append(if (oneLine) ", " else ",\n")
                    } else if (!oneLine) {
                        sb.append('\n')
                    }
                }
                if (oneLine) sb.append(" }") else {
                    pad(sb, indent)
                    sb.append('}')
                }
            }
            else -> throw IllegalArgumentException("unsupported JSON value type: ${v::class}")
        }
    }

    /** Appends [n] space characters to [sb]. */
    private fun pad(sb: StringBuilder, n: Int) {
        repeat(n) { sb.append(' ') }
    }

    /** Appends quoted and escaped string [s] to [sb]. */
    private fun emitString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
