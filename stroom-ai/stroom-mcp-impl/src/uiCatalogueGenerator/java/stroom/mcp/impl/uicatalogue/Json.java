/*
 * Copyright 2026 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.mcp.impl.uicatalogue;

import java.util.Collection;
import java.util.Map;

/// Writes maps, lists, strings, numbers and null as indented JSON. The generator runs with the JDK only.
final class Json {

    private Json() {
    }

    static String write(final Object value) {
        final StringBuilder out = new StringBuilder();
        write(value, out, "");
        return out.toString();
    }

    private static void write(final Object value, final StringBuilder out, final String indent) {
        final String inner = indent + "  ";
        switch (value) {
            case null -> out.append("null");
            case final String text -> string(text, out);
            case final Number number -> out.append(number);
            case final Boolean bool -> out.append(bool);
            case final Map<?, ?> map when map.isEmpty() -> out.append("{}");
            case final Map<?, ?> map -> {
                out.append("{\n");
                boolean first = true;
                for (final Map.Entry<?, ?> entry : map.entrySet()) {
                    out.append(first ? "" : ",\n").append(inner);
                    string(entry.getKey().toString(), out);
                    out.append(": ");
                    write(entry.getValue(), out, inner);
                    first = false;
                }
                out.append('\n').append(indent).append('}');
            }
            case final Collection<?> list when list.isEmpty() -> out.append("[]");
            case final Collection<?> list -> {
                out.append("[\n");
                boolean first = true;
                for (final Object item : list) {
                    out.append(first ? "" : ",\n").append(inner);
                    write(item, out, inner);
                    first = false;
                }
                out.append('\n').append(indent).append(']');
            }
            default -> throw new IllegalArgumentException("Not JSON: " + value.getClass());
        }
    }

    private static void string(final String text, final StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
