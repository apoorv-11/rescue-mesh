package com.rescuemesh.crypto

/**
 * Minimal strict JSON parser, just big enough for RescueMesh's own stored
 * JSON (envelope payload and hopTrace in Room are JSON text).
 *
 * Parsed values use plain Kotlin types:
 * object -> Map<String, Any?>, array -> List<Any?>, string -> String,
 * integer -> Long, boolean -> Boolean, null -> null.
 *
 * Non-integer numbers are REJECTED: the envelope contract has no floats
 * anywhere (timestamps are epoch-millis integers), and accepting a float here
 * would make canonicalisation (RFC 8785) ambiguous. String escapes follow
 * JSON, including \uXXXX with surrogate pairs.
 */
object JsonParser {

    fun parse(text: String): Any? {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) throw IllegalArgumentException("Trailing content after JSON value at index ${parser.pos}")
        return value
    }

    private class Parser(private val text: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= text.length

        fun skipWhitespace() {
            while (pos < text.length && (text[pos] == ' ' || text[pos] == '\t' || text[pos] == '\n' || text[pos] == '\r')) pos++
        }

        fun parseValue(): Any? {
            if (atEnd()) throw IllegalArgumentException("Unexpected end of JSON")
            return when (val c = text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                '-', in '0'..'9' -> parseInteger()
                else -> throw IllegalArgumentException("Unexpected character '$c' at index $pos")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            pos++ // consume '{'
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (!atEnd() && text[pos] == '}') { pos++; return result }
            while (true) {
                skipWhitespace()
                if (atEnd() || text[pos] != '"') throw IllegalArgumentException("Expected object key at index $pos")
                val key = parseString()
                skipWhitespace()
                if (atEnd() || text[pos] != ':') throw IllegalArgumentException("Expected ':' at index $pos")
                pos++
                skipWhitespace()
                result[key] = parseValue()
                skipWhitespace()
                if (atEnd()) throw IllegalArgumentException("Unterminated object")
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return result }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at index $pos")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            pos++ // consume '['
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (!atEnd() && text[pos] == ']') { pos++; return result }
            while (true) {
                skipWhitespace()
                result.add(parseValue())
                skipWhitespace()
                if (atEnd()) throw IllegalArgumentException("Unterminated array")
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return result }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at index $pos")
                }
            }
        }

        private fun parseString(): String {
            pos++ // consume opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw IllegalArgumentException("Unterminated string")
                val c = text[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw IllegalArgumentException("Unterminated escape")
                        when (val e = text[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> sb.append(parseUnicodeEscape())
                            else -> throw IllegalArgumentException("Bad escape '\\$e' at index ${pos - 1}")
                        }
                    }
                    else -> {
                        if (c < ' ') throw IllegalArgumentException("Unescaped control character in string at index ${pos - 1}")
                        sb.append(c)
                    }
                }
            }
        }

        private fun parseUnicodeEscape(): String {
            val first = parseHex4()
            if (first.isHighSurrogate()) {
                if (pos + 1 < text.length && text[pos] == '\\' && text[pos + 1] == 'u') {
                    pos += 2
                    val second = parseHex4()
                    if (!second.isLowSurrogate()) throw IllegalArgumentException("High surrogate not followed by low surrogate")
                    return String(charArrayOf(first, second))
                }
                throw IllegalArgumentException("Lone high surrogate in string escape")
            }
            if (first.isLowSurrogate()) throw IllegalArgumentException("Lone low surrogate in string escape")
            return first.toString()
        }

        private fun parseHex4(): Char {
            if (pos + 4 > text.length) throw IllegalArgumentException("Truncated \\u escape at index $pos")
            var value = 0
            repeat(4) {
                val digit = text[pos++].digitToIntOrNull(16)
                    ?: throw IllegalArgumentException("Bad hex digit in \\u escape at index ${pos - 1}")
                value = value * 16 + digit
            }
            return value.toChar()
        }

        private fun parseInteger(): Long {
            val start = pos
            if (text[pos] == '-') pos++
            while (pos < text.length && text[pos].isDigit()) pos++
            if (pos < text.length && (text[pos] == '.' || text[pos] == 'e' || text[pos] == 'E')) {
                throw IllegalArgumentException("Non-integer numbers are not part of the envelope contract (index $start)")
            }
            val literal = text.substring(start, pos)
            return literal.toLongOrNull()
                ?: throw IllegalArgumentException("Invalid integer '$literal' at index $start")
        }

        private fun expect(word: String) {
            if (!text.regionMatches(pos, word, 0, word.length)) {
                throw IllegalArgumentException("Expected '$word' at index $pos")
            }
            pos += word.length
        }
    }
}
