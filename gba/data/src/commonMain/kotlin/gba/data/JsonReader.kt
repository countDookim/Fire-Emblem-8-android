package gba.data

/**
 * Minimal JSON reader for the engine's data files (no reflection, no dependencies): objects become
 * `Map<String, Any?>` (insertion order), arrays `List<Any?>`, integers `Long`, other numbers `Double`, plus
 * `String`, `Boolean` and null.
 */
public object JsonReader {
    /** Parses one JSON document; throws [IllegalArgumentException] on malformed input or trailing text. */
    public fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.ws()
        require(p.i == text.length) { "trailing data at ${p.i}" }
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            ws()
            require(i < s.length) { "unexpected end" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("bad char '$c' at $i")
            }
        }

        fun lit(w: String, v: Any?): Any? {
            require(s.startsWith(w, i)) { "bad literal at $i" }
            i += w.length
            return v
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws()
                val k = str()
                ws()
                require(s[i] == ':') { "':' expected at $i" }
                i++
                m[k] = value()
                ws()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> throw IllegalArgumentException("',' or '}' expected at ${i - 1}")
                }
            }
        }

        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l += value()
                ws()
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return l
                    else -> throw IllegalArgumentException("',' or ']' expected at ${i - 1}")
                }
            }
        }

        fun str(): String {
            require(s[i] == '"') { "string expected at $i" }
            i++
            val b = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r')
                        'b' -> b.append('\b'); 'f' -> b.append('\u000C')
                        'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> b.append(e)
                    }
                    else -> b.append(c)
                }
            }
        }

        fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            return t.toLongOrNull() ?: t.toDouble()
        }
    }
}
