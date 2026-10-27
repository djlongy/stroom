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

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/// One label the generator read from source.
///
/// @param family   Which extractor read it, e.g. `tab`.
/// @param label    The text, as the source holds it.
/// @param kind     What kind of construct carries it, e.g. `tab`, `menu item`, `toolbar button tooltip`.
/// @param owner    The fully qualified class that owns the construct (where it is declared or added), not a
///                 claimed screen.
/// @param location Where it is, only when the source states it (a menu's path, the template element); may be null.
/// @param tooltip  The tooltip the construct itself carries; may be null.
/// @param icon     The `SvgImage` constant the construct itself names; may be null.
/// @param group    For a document type, its group's display name; may be null.
/// @param path     The source file, relative to the repository root, with `/` separators.
/// @param line     The line of the construct that carries the label (the literal, or the constant's use), from 1.
public record CatalogueEntry(String family,
                             String label,
                             String kind,
                             String owner,
                             String location,
                             String tooltip,
                             String icon,
                             String group,
                             String path,
                             int line) {

    /// Orders entries by their identity, then by where they were read, so the output is the same each time.
    static final Comparator<CatalogueEntry> ORDER = Comparator.comparing(CatalogueEntry::id)
            .thenComparing(CatalogueEntry::path)
            .thenComparingInt(CatalogueEntry::line);

    /// @return The entry's stable identity: family, owner, location and label. Two reads of the same label at the
    /// same place are one entry.
    public String id() {
        return family + ":" + owner + ":" + (location == null ? "" : location) + ":" + label;
    }

    /// @return The entry as JSON fields, in a fixed order, without empty ones.
    Map<String, Object> toJson() {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id());
        out.put("family", family);
        out.put("label", label);
        out.put("kind", kind);
        out.put("owner", owner);
        putIfSet(out, "location", location);
        putIfSet(out, "tooltip", tooltip);
        putIfSet(out, "icon", icon);
        putIfSet(out, "group", group);
        final Map<String, Object> source = new LinkedHashMap<>();
        source.put("path", path);
        source.put("line", line);
        out.put("source", source);
        return out;
    }

    private static void putIfSet(final Map<String, Object> out, final String key, final String value) {
        if (value != null) {
            out.put(key, value);
        }
    }
}
