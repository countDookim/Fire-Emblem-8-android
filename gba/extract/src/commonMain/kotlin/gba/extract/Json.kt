package gba.extract

/**
 * Tiny deterministic JSON writer for extractor output (no serialization dependency in sessions).
 *
 * Values: `null`, [Boolean], [Int]/[Long], [String], [List], and [Map] with [String] keys (insertion
 * order is kept, so output diffs cleanly between extractor runs). Objects whose values are all scalars
 * are written on one line; everything else is indented by two spaces.
 */
public object Json {
    /** Serialises [value] with a trailing newline. */
    public fun write(value: Any?): String = StringBuilder().also { emit(it, value, 0); it.append('\n') }.toString()

    private fun isScalar(v: Any?) = v !is Map<*, *> && v !is List<*>

    private fun emit(sb: StringBuilder, v: Any?, indent: Int) {
        when (v) {
            null -> sb.append("null")
            is Boolean, is Int, is Long -> sb.append(v.toString())
            is String -> string(sb, v)
            is List<*> -> {
                if (v.all(::isScalar)) {
                    sb.append('[')
                    v.forEachIndexed { i, e -> if (i > 0) sb.append(", "); emit(sb, e, indent) }
                    sb.append(']')
                } else {
                    sb.append("[\n")
                    v.forEachIndexed { i, e ->
                        pad(sb, indent + 2); emit(sb, e, indent + 2)
                        sb.append(if (i < v.size - 1) ",\n" else "\n")
                    }
                    pad(sb, indent); sb.append(']')
                }
            }
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                val oneLine = v.values.all(::isScalar)
                sb.append(if (oneLine) "{ " else "{\n")
                var i = 0
                for ((k, e) in v) {
                    if (!oneLine) pad(sb, indent + 2)
                    string(sb, k as String); sb.append(": "); emit(sb, e, indent + 2)
                    i++
                    if (i < v.size) sb.append(if (oneLine) ", " else ",\n") else if (!oneLine) sb.append('\n')
                }
                if (oneLine) sb.append(" }") else { pad(sb, indent); sb.append('}') }
            }
            else -> throw IllegalArgumentException("unsupported JSON value ${v::class}")
        }
    }

    private fun pad(sb: StringBuilder, n: Int) { repeat(n) { sb.append(' ') } }

    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> sb.append(c)
        }
        sb.append('"')
    }
}
