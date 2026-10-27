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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/// Builds the `ui_lookup` catalogue from the client source at build time. Everything in it is read mechanically
/// from the source; a label the extractors can't read as a literal is left out, never written by hand.
///
/// Usage: `UiCatalogueGenerator <repository root> <output file> <source dir>...`.
public final class UiCatalogueGenerator {

    static final String NO_SHA = "No source commit SHA is available to the build. The fingerprint identifies the "
                                 + "exact source files read; the running build's version is not a source SHA.";
    static final String COVERAGE = "Generated from client source: menu items, toolbar buttons, tabs, template "
                                   + "labels, document type and application permission display names, where the "
                                   + "label is a literal. Labels built at run time are not listed; not listed "
                                   + "means unknown, not absent.";

    private UiCatalogueGenerator() {
    }

    public static void main(final String[] args) throws IOException {
        if (args.length < 3) {
            throw new IllegalArgumentException("Usage: UiCatalogueGenerator <root> <output> <source dir>...");
        }
        final Path root = Path.of(args[0]).toAbsolutePath().normalize();
        final Path output = Path.of(args[1]);
        final List<Path> dirs = Stream.of(args).skip(2).map(root::resolve).toList();
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, generate(root, dirs), StandardCharsets.UTF_8);
    }

    /// @param root The repository root, which paths in the output are relative to.
    /// @param dirs The source directories to read.
    /// @return The catalogue as JSON, the same bytes for the same input.
    static String generate(final Path root, final List<Path> dirs) throws IOException {
        final List<Path> java = files(dirs, ".java");
        final List<Path> templates = files(dirs, ".ui.xml");
        final JavaExtractor javaExtractor = new JavaExtractor(root, java);
        final XmlExtractor xmlExtractor = new XmlExtractor();
        final List<CatalogueEntry> read = new ArrayList<>(javaExtractor.extract());
        read.addAll(xmlExtractor.extract(root, templates, javaExtractor.knownClasses()));

        final List<CatalogueEntry> unique = dedupe(read);
        final List<Map<String, Object>> entries = unique.stream().map(CatalogueEntry::toJson).toList();
        final Map<String, Integer> counts = new TreeMap<>();
        unique.forEach(entry -> counts.merge(entry.family(), 1, Integer::sum));

        final List<Path> inputs = new ArrayList<>(java);
        inputs.addAll(templates);
        final Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("generated_by", UiCatalogueGenerator.class.getName());
        provenance.put("source_fingerprint", "sha256:" + fingerprint(root, inputs));
        provenance.put("source_files", inputs.size());
        provenance.put("source_sha", null);
        provenance.put("source_sha_note", NO_SHA);
        provenance.put("coverage", COVERAGE);
        provenance.put("entry_counts", counts);
        provenance.put("unparsed_java", javaExtractor.unparsed());
        provenance.put("skipped_templates", xmlExtractor.skipped());
        // What each reason left out, so the counts above are not taken as all there is.
        final Map<String, Integer> skips = new TreeMap<>(javaExtractor.skips());
        xmlExtractor.skips().forEach((reason, count) -> skips.merge(reason, count, Integer::sum));
        provenance.put("skipped_counts", skips);

        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("provenance", provenance);
        out.put("entries", entries);
        return Json.write(out) + "\n";
    }

    /// @return One entry per identity, the first by source position, in identity order.
    static List<CatalogueEntry> dedupe(final Collection<CatalogueEntry> read) {
        final Map<String, CatalogueEntry> byId = new TreeMap<>();
        read.stream().sorted(CatalogueEntry.ORDER).forEach(entry -> byId.putIfAbsent(entry.id(), entry));
        return List.copyOf(byId.values());
    }

    // sha256 over each input's relative path and contents, in path order.
    static String fingerprint(final Path root, final List<Path> inputs) throws IOException {
        final Map<String, Path> sorted = new TreeMap<>();
        inputs.forEach(file -> sorted.put(JavaExtractor.relative(root, file), file));
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (final Map.Entry<String, Path> input : sorted.entrySet()) {
                digest.update(input.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(input.getValue()));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Path> files(final List<Path> dirs, final String suffix) throws IOException {
        final List<Path> found = new ArrayList<>();
        for (final Path dir : dirs) {
            if (Files.isDirectory(dir)) {
                try (final Stream<Path> walk = Files.walk(dir)) {
                    walk.filter(file -> file.getFileName().toString().endsWith(suffix)
                                        && !(".java".equals(suffix) && file.toString().endsWith(".ui.xml")))
                            .forEach(found::add);
                }
            }
        }
        found.sort(Path::compareTo);
        return found;
    }
}
