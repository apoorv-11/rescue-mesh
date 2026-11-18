package com.rescuemesh.server.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Canonical JSON writer, byte-identical to Android's {@code CanonicalJson}
 * (RFC 8785 style, restricted to the envelope contract's value shapes):
 * object keys sorted by UTF-16 code units ({@link String#compareTo}), no
 * whitespace, integers only (non-integral JSON numbers are rejected, exactly
 * like Android's JsonParser rejects them), strings escaped only as JSON
 * requires — short forms \b \t \n \f \r, {@code \\u00xx} lowercase for other
 * C0 controls, non-ASCII emitted raw (UTF-8 when encoded).
 *
 * <p>Input is a Jackson {@link JsonNode} tree; the same logical value always
 * produces the same string regardless of the source field order — that is
 * what lets the server verify signatures produced on phones.
 */
public final class CanonicalJson {

    private static final String HEX = "0123456789abcdef";

    private CanonicalJson() {
    }

    public static String canonicalize(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, node);
        return sb.toString();
    }

    /** Canonical UTF-8 bytes — the exact bytes that are signed / verified. */
    public static byte[] canonicalBytes(JsonNode node) {
        return canonicalize(node).getBytes(StandardCharsets.UTF_8);
    }

    private static void writeValue(StringBuilder sb, JsonNode node) {
        if (node == null || node.isNull()) {
            sb.append("null");
            return;
        }
        switch (node.getNodeType()) {
            case BOOLEAN -> sb.append(node.booleanValue() ? "true" : "false");
            case NUMBER -> {
                if (!node.isIntegralNumber()) {
                    throw new IllegalArgumentException(
                            "Non-integral numbers are not part of the canonical envelope contract: "
                                    + node);
                }
                if (!node.canConvertToLong()) {
                    throw new IllegalArgumentException("Integer out of long range: " + node);
                }
                sb.append(node.longValue());
            }
            case STRING -> writeString(sb, node.textValue());
            case ARRAY -> {
                sb.append('[');
                boolean first = true;
                for (JsonNode element : node) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    writeValue(sb, element);
                }
                sb.append(']');
            }
            case OBJECT -> {
                List<String> keys = new ArrayList<>();
                Iterator<String> names = node.fieldNames();
                while (names.hasNext()) {
                    keys.add(names.next());
                }
                // String natural ordering compares UTF-16 code units — the JCS order,
                // identical to Kotlin's String.compareTo used on Android.
                keys.sort(null);
                sb.append('{');
                boolean first = true;
                for (String key : keys) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    writeString(sb, key);
                    sb.append(':');
                    writeValue(sb, node.get(key));
                }
                sb.append('}');
            }
            default -> throw new IllegalArgumentException(
                    "Value of type " + node.getNodeType() + " is not part of the canonical envelope contract");
        }
    }

    private static void writeString(StringBuilder sb, String value) {
        sb.append('"');
        for (int idx = 0; idx < value.length(); idx++) {
            char c = value.charAt(idx);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\f' -> sb.append("\\f");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (c < ' ') {
                        sb.append("\\u00");
                        sb.append(HEX.charAt((c >> 4) & 0xF));
                        sb.append(HEX.charAt(c & 0xF));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** Node-type probe kept for callers that branch on shape before canonicalising. */
    static boolean isObject(JsonNode node) {
        return node != null && node.getNodeType() == JsonNodeType.OBJECT;
    }
}
