package com.rescuemesh.crypto

/**
 * Canonical JSON writer in the spirit of RFC 8785 (JCS), restricted to the
 * value shapes the RescueMesh envelope contract actually contains:
 * objects (Map<String, *>), arrays (List<*>), strings, integers (Int/Long),
 * booleans and null. Anything else — above all floating point — is rejected,
 * because JCS number serialisation is the one place implementations diverge,
 * and the contract simply has no floats (all timestamps are epoch millis).
 *
 * Canonical form:
 * - object keys sorted lexicographically (Kotlin String.compareTo orders by
 *   UTF-16 code units, which is the order JCS mandates), no whitespace;
 * - arrays keep their order (hopTrace order is meaningful);
 * - strings escaped per JCS: only the escapes JSON requires, using the short
 *   forms \b \t \n \f \r where they exist, \u00xx (lowercase hex) for other
 *   C0 control characters, and every other character — including non-ASCII —
 *   emitted raw (the result is encoded UTF-8 by callers).
 *
 * The same input value always produces the same string, regardless of the
 * map's iteration order — that is the entire point: two phones and the server
 * must hash/sign byte-identical text for the same envelope.
 */
object CanonicalJson {

    fun canonicalize(value: Any?): String {
        val sb = StringBuilder()
        writeValue(sb, value)
        return sb.toString()
    }

    /** Canonical UTF-8 bytes — the exact bytes that get signed / verified. */
    fun canonicalBytes(value: Any?): ByteArray = canonicalize(value).toByteArray(Charsets.UTF_8)

    private fun writeValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int -> sb.append(value.toString())
            is Long -> sb.append(value.toString())
            is String -> writeString(sb, value)
            is List<*> -> {
                sb.append('[')
                value.forEachIndexed { index, element ->
                    if (index > 0) sb.append(',')
                    writeValue(sb, element)
                }
                sb.append(']')
            }
            is Map<*, *> -> {
                val keys = value.keys.map { key ->
                    key as? String
                        ?: throw IllegalArgumentException("Canonical JSON object keys must be strings, got: $key")
                }.sorted()
                sb.append('{')
                keys.forEachIndexed { index, key ->
                    if (index > 0) sb.append(',')
                    writeString(sb, key)
                    sb.append(':')
                    writeValue(sb, value[key])
                }
                sb.append('}')
            }
            else -> throw IllegalArgumentException(
                "Value of type ${value.javaClass.name} is not part of the canonical envelope contract " +
                    "(floats are forbidden: timestamps are epoch-millis integers)"
            )
        }
    }

    private fun writeString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\t' -> sb.append("\\t")
                '\n' -> sb.append("\\n")
                '\u000C' -> sb.append("\\f")
                '\r' -> sb.append("\\r")
                else -> {
                    if (c < ' ') {
                        // Remaining C0 controls: \u00xx with lowercase hex (JCS).
                        sb.append("\\u00")
                        sb.append(HEX[(c.code shr 4) and 0xF])
                        sb.append(HEX[c.code and 0xF])
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append('"')
    }

    private const val HEX = "0123456789abcdef"
}
