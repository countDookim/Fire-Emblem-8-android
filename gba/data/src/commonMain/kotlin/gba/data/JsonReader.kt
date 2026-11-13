package gba.data

/**
 * Minimal JSON reader for the data files the extractors write (no reflection, no external
 * dependencies): objects become `Map<String, Any?>` (preserving insertion order), arrays
 * `List<Any?>`, numbers `Long` when the token parses as one and `Double` otherwise (so an integer
 * too large for `Long` becomes a `Double`), strings `String`, booleans `Boolean`, and `null`.
 *
 * Lenient: the number scanner accepts any run of digits and `.eE+-` and leaves validation to
 * `toLongOrNull`/`toDouble`; duplicate object keys keep the last value.
 */
public object JsonReader {
    /**
     * Parses one JSON document and returns its value (see the class KDoc for the types).
     *
     * @throws IllegalArgumentException on most malformed input and on trailing text.
     *
     * NOTE: input that ends early inside an object or array (for example `{` or `[1`) reaches an
     * unchecked `source[cursor]` and throws `StringIndexOutOfBoundsException` instead; an invalid
     * unicode escape throws `NumberFormatException` (an `IllegalArgumentException` subclass).
     */
    public fun parse(text: String): Any? {
        val parser = Parser(text)
        val result = parser.parseValue()
        parser.skipWhitespace()
        require(parser.cursor == text.length) { "trailing data at ${parser.cursor}" }
        return result
    }

    /** Recursive-descent parser over [source]; one instance per [parse] call. */
    private class Parser(val source: String) {
        /** Index of the next unread character in [source]. */
        var cursor: Int = 0

        /** Advances [cursor] past any whitespace characters. */
        fun skipWhitespace() {
            while (cursor < source.length && source[cursor].isWhitespace()) cursor++
        }

        /** Parses and returns the next JSON value (object, array, string, number, boolean, or null). */
        fun parseValue(): Any? {
            skipWhitespace()
            require(cursor < source.length) { "unexpected end of JSON input" }
            return when (val ch = source[cursor]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> if (ch == '-' || ch.isDigit()) parseNumber() else {
                    throw IllegalArgumentException("unexpected character '$ch' at $cursor")
                }
            }
        }

        /** Matches exact literal string [expected] and returns [value]. */
        fun parseLiteral(expected: String, value: Any?): Any? {
            require(source.startsWith(expected, cursor)) { "bad literal at $cursor" }
            cursor += expected.length
            return value
        }

        /** Parses a JSON object `{ ... }` as an insertion-ordered `LinkedHashMap`. */
        fun parseObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            cursor++ // skip '{'
            skipWhitespace()
            if (source[cursor] == '}') {
                cursor++
                return map
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                require(source[cursor] == ':') { "':' expected at $cursor" }
                cursor++
                map[key] = parseValue()
                skipWhitespace()
                when (source[cursor++]) {
                    ',' -> continue
                    '}' -> return map
                    else -> throw IllegalArgumentException("',' or '}' expected at ${cursor - 1}")
                }
            }
        }

        /** Parses a JSON array `[ ... ]` as an `ArrayList`. */
        fun parseArray(): List<Any?> {
            val list = ArrayList<Any?>()
            cursor++ // skip '['
            skipWhitespace()
            if (source[cursor] == ']') {
                cursor++
                return list
            }
            while (true) {
                list += parseValue()
                skipWhitespace()
                when (source[cursor++]) {
                    ',' -> continue
                    ']' -> return list
                    else -> throw IllegalArgumentException("',' or ']' expected at ${cursor - 1}")
                }
            }
        }

        /** Parses a quoted JSON string with standard escape sequences and unicode escapes `\uXXXX`. */
        fun parseString(): String {
            require(source[cursor] == '"') { "string expected at $cursor" }
            cursor++ // skip opening quote
            val sb = StringBuilder()
            while (true) {
                require(cursor < source.length) { "unterminated string starting before $cursor" }
                val ch = source[cursor++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(cursor < source.length) { "unterminated escape at $cursor" }
                        when (val esc = source[cursor++]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                require(cursor + 4 <= source.length) { "incomplete unicode escape at $cursor" }
                                sb.append(source.substring(cursor, cursor + 4).toInt(16).toChar())
                                cursor += 4
                            }
                            else -> sb.append(esc)
                        }
                    }
                    else -> sb.append(ch)
                }
            }
        }

        /** Parses a JSON number token into a [Long] if integral, or a [Double] if fractional/exponential. */
        fun parseNumber(): Any {
            val start = cursor
            if (source[cursor] == '-') cursor++
            while (cursor < source.length && (source[cursor].isDigit() || source[cursor] in ".eE+-")) {
                cursor++
            }
            val text = source.substring(start, cursor)
            return text.toLongOrNull() ?: text.toDouble()
        }
    }
}
