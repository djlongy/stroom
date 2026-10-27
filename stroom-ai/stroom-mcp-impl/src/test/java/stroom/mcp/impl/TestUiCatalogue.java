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

package stroom.mcp.impl;

import stroom.mcp.impl.UiCatalogue.Entry;
import stroom.mcp.impl.UiCatalogue.Source;
import stroom.util.json.JsonUtil;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// The Stroom UI catalogue: generated at build time onto the classpath, descriptive only, and each entry at the
/// source line it cites. The anchors are labels each supported extractor must find in this tree; one failing means
/// that extractor broke. The source tree is only read here, never at run time.
class TestUiCatalogue {

    // What an entry may hold: a description of the interface, with no steps, order, conditions or recommendation.
    private static final Set<String> ENTRY_FIELDS = Set.of("id", "family", "label", "kind", "owner", "location",
            "tooltip", "icon", "group", "source");
    private static final String PRESETS = "stroom-core-client-widget/src/main/java/stroom/svg/client/SvgPresets.java";
    private static final Set<String> FAMILIES = Set.of("menu", "toolbar", "tab", "template", "document type",
            "app permission");

    @Test
    void theCatalogueIsOnTheClasspathAndParses() throws IOException {
        try (final InputStream in = UiCatalogue.class.getResourceAsStream(BundledKnowledge.ROOT + UiCatalogue.FILE)) {
            assertThat(in).isNotNull();
        }
        final UiCatalogue catalogue = UiCatalogue.bundled();
        assertThat(catalogue.entries()).hasSizeGreaterThan(1000);
        assertThat(catalogue.provenance().sourceFingerprint()).matches("sha256:[0-9a-f]{64}");
        assertThat(catalogue.provenance().sourceSha()).isNull();
        assertThat(catalogue.provenance().sourceShaNote()).contains("No source commit SHA");
        assertThat(catalogue.provenance().entryCounts().keySet()).containsExactlyInAnyOrderElementsOf(FAMILIES);
        assertThat(catalogue.provenance().unparsedJava()).isEmpty();
        // What was left out is counted by reason, so coverage is not overstated.
        assertThat(catalogue.provenance().skippedCounts().keySet())
                .contains("menu: item is not a builder chain in the call or in a final local",
                        "template: no class of the same name");
        assertThat(catalogue.rendered()).contains("Left out, by reason:");
        // The guide says up front that it is the whole catalogue, and how large.
        assertThat(catalogue.rendered().lines().skip(2).findFirst().orElseThrow())
                .startsWith("This is the full catalogue, unpaged: " + catalogue.entries().size() + " entries, about "
                            + Math.round(catalogue.renderedBytes() / 1024.0) + " KB.")
                .contains("ui_lookup");
        assertThat(UiCatalogue.bundled()).isSameAs(catalogue);
    }

    @Test
    void eachEntryDescribesALabelAndNothingElse() throws IOException {
        final Map<String, Object> file = cast(JsonUtil.readValue(bundledText(), Map.class));
        assertThat(file.keySet()).containsExactly("provenance", "entries");
        final String text = bundledText();
        assertThat(text).doesNotContain(repositoryRoot().toString());
        final List<Map<String, Object>> entries = cast(file.get("entries"));
        for (final Map<String, Object> entry : entries) {
            assertThat(entry.keySet()).as(String.valueOf(entry.get("id"))).isSubsetOf(ENTRY_FIELDS);
        }
        for (final Entry entry : UiCatalogue.bundled().entries()) {
            assertThat(List.of(entry.id(), entry.label(), entry.kind(), entry.owner(), entry.family()))
                    .as(entry.id())
                    .allSatisfy(value -> assertThat(value).isNotBlank());
            assertThat(entry.family()).as(entry.id()).isIn(FAMILIES);
            assertThat(entry.owner()).as(entry.id()).matches("[a-z][a-z0-9_]*(\\.[A-Za-z0-9_]+)+");
            if (entry.icon() != null) {
                assertThat(entry.icon()).as(entry.id()).startsWith("SvgImage.");
            }
        }
        // Sorted by identity, one entry each.
        final List<String> ids = UiCatalogue.bundled().entries().stream().map(Entry::id).toList();
        assertThat(ids).doesNotHaveDuplicates().isSorted();
    }

    @Test
    void everyCitedSourceLineIsInItsFileAndTheFileHoldsTheLabel() {
        final Path root = repositoryRoot();
        final Map<String, List<String>> files = new HashMap<>();
        final List<String> drifted = new ArrayList<>();
        for (final Entry entry : UiCatalogue.bundled().entries()) {
            final Source source = entry.source();
            final List<String> lines = files.computeIfAbsent(source.path(), path -> read(root.resolve(path)));
            // A toolbar button's label can be its preset's title, which is in SvgPresets.
            final List<String> presets = files.computeIfAbsent(PRESETS, path -> read(root.resolve(path)));
            if (lines == null || source.line() < 1 || source.line() > lines.size()
                || !holds(lines, entry.label())
                   && !("toolbar".equals(entry.family()) && holds(presets, "\"" + entry.label() + "\""))) {
                drifted.add(entry.id() + " " + source.cited());
            }
        }
        assertThat(drifted).isEmpty();
    }

    @Test
    void eachExtractorFindsItsAnchorsInThisTree() {
        final List<Entry> entries = UiCatalogue.bundled().entries();
        // Tabs: the feed editor's.
        assertThat(entries.stream()
                .filter(entry -> "tab".equals(entry.family())
                                 && "stroom.feed.client.presenter.FeedPresenter".equals(entry.owner()))
                .map(Entry::label))
                .containsExactlyInAnyOrder("Settings", "Data", "Active Tasks", "Documentation", "Permissions");
        // Menu items, with the menu path read from the menu keys.
        assertThat(only(entries, "menu", "stroom.credentials.client.CredentialsPlugin", "Credentials Manager"))
                .extracting(Entry::location, Entry::kind, Entry::icon)
                .containsExactly("Main Menu > Security", "menu item", "SvgImage.KEY_BLUE");
        // Toolbar buttons, from a preset.
        assertThat(only(entries, "toolbar", "stroom.data.client.presenter.MetaPresenter", "Upload"))
                .extracting(Entry::tooltip, Entry::icon)
                .containsExactly("Upload", "SvgImage.UPLOAD");
        // Template labels, with the template's owner.
        assertThat(only(entries, "template", "stroom.query.client.view.QueryButtons", "Execute Query"))
                .extracting(Entry::kind, entry -> entry.source().path())
                .containsExactly("tooltip",
                        "stroom-core-client/src/main/resources/stroom/query/client/view/QueryButtons.ui.xml");
        // Document types, with their group, and application permissions.
        assertThat(only(entries, "document type", "stroom.docstore.shared.DocumentTypeRegistry", "Feed"))
                .extracting(Entry::group, Entry::icon)
                .containsExactly("Data Processing", "SvgImage.DOCUMENT_FEED");
        assertThat(only(entries, "app permission", "stroom.security.shared.AppPermission", "Manage Users"))
                .isNotNull();
    }

    @Test
    void theUiGuideIsTheCatalogueRendered() {
        final String guide = new BundledKnowledge().guide(BundledKnowledge.UI_GUIDE).orElseThrow();
        assertThat(guide).startsWith("# Stroom UI labels\n").isSameAs(UiCatalogue.bundled().rendered());
        assertThat(guide).contains(UiCatalogue.bundled().provenance().sourceFingerprint(), "No source commit SHA",
                "not listed means unknown");
        for (final Entry entry : UiCatalogue.bundled().entries()) {
            assertThat(guide).contains("## " + entry.owner(), entry.source().cited());
        }
        assertThat(new BundledKnowledge().guideTitle(BundledKnowledge.UI_GUIDE)).contains("Stroom UI labels");
    }

    @Test
    void wordsCountTheSameForQuestionsAndLabels() {
        assertThat(UiCatalogue.words("What TABS does the Feed have?")).containsExactly("tab", "feed");
        assertThat(UiCatalogue.words("Processes")).containsExactly("processe");
        assertThat(UiCatalogue.words("Class")).containsExactly("class");
    }

    // Whether a file holds a label, as Java or XML would write it.
    private static boolean holds(final List<String> lines, final String label) {
        final String text = String.join("\n", lines);
        return text.contains(label)
               || text.contains(label.replace("\"", "\\\""))
               || text.contains(label.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;"));
    }

    private static Entry only(final List<Entry> entries, final String family, final String owner,
                              final String label) {
        final List<Entry> found = entries.stream()
                .filter(entry -> family.equals(entry.family()) && owner.equals(entry.owner())
                                 && label.equals(entry.label()))
                .toList();
        assertThat(found).as(family + " " + owner + " " + label).hasSize(1);
        return found.getFirst();
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(final Object value) {
        return (T) value;
    }

    private static String bundledText() throws IOException {
        try (final InputStream in = UiCatalogue.class.getResourceAsStream(BundledKnowledge.ROOT + UiCatalogue.FILE)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> read(final Path path) {
        try {
            return Files.isRegularFile(path)
                    ? Files.readAllLines(path)
                    : null;
        } catch (final IOException e) {
            return null;
        }
    }

    // The repository's root: two above the module's folder when Gradle runs the tests, or the working directory
    // when an IDE runs them from there.
    private static Path repositoryRoot() {
        final Path workingDir = Paths.get("").toAbsolutePath();
        for (final Path candidate : List.of(workingDir.getParent().getParent(), workingDir)) {
            if (Files.isDirectory(candidate.resolve("stroom-core-client"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to find the repository root from " + workingDir);
    }
}
