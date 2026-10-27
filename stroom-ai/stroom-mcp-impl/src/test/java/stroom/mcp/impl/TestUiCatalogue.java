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
import stroom.mcp.impl.UiCatalogue.Evidence;
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

/// The Stroom UI catalogue: bundled on the classpath, descriptive only, and each entry still at the source line it
/// cites. The source tree is only read here, never at run time.
class TestUiCatalogue {

    // What an entry may hold: a description of the interface, with no steps, order or recommendation.
    private static final Set<String> ENTRY_FIELDS = Set.of("id", "label", "kind", "screen", "location", "tooltip",
            "conditions", "values", "aliases", "icon", "source_evidence", "runtime_text", "browser");
    // What a browser result can be. Not found and not exercised are not proof of absence.
    private static final Set<String> BROWSER_RESULTS = Set.of("confirmed", "differs", "not found in this run",
            "not exercised");

    @Test
    void theCatalogueIsOnTheClasspathAndParses() throws IOException {
        try (final InputStream in = UiCatalogue.class.getResourceAsStream(BundledKnowledge.ROOT + UiCatalogue.FILE)) {
            assertThat(in).isNotNull();
        }
        final UiCatalogue catalogue = UiCatalogue.bundled();
        assertThat(catalogue.entries()).hasSizeGreaterThan(100);
        assertThat(catalogue.verifiedAgainst()).matches("[0-9a-f]{10,40}");
        assertThat(catalogue.reviewMethod()).contains("Source-reviewed");
        assertThat(UiCatalogue.bundled()).isSameAs(catalogue);
    }

    @Test
    void eachEntryDescribesAControlAndNothingElse() throws IOException {
        final Map<String, Object> file = cast(JsonUtil.readValue(bundledText(), Map.class));
        assertThat(file.keySet()).containsExactlyInAnyOrder("verified_against", "source", "review_method",
                "conditions_basis", "browser_check", "entries");
        final List<Map<String, Object>> entries = cast(file.get("entries"));
        for (final Map<String, Object> entry : entries) {
            assertThat(entry.keySet()).as(String.valueOf(entry.get("id"))).isSubsetOf(ENTRY_FIELDS);
        }
        for (final Entry entry : UiCatalogue.bundled().entries()) {
            assertThat(List.of(entry.id(), entry.label(), entry.kind(), entry.screen(), entry.location()))
                    .as(entry.id())
                    .allSatisfy(value -> assertThat(value).isNotBlank());
            assertThat(entry.sourceEvidence()).as(entry.id()).isNotNull();
            if (entry.browser() != null) {
                assertThat(entry.browser().result()).as(entry.id()).isIn(BROWSER_RESULTS);
                assertThat(List.of(entry.browser().image(), entry.browser().digest(), entry.browser().platform(),
                        entry.browser().user(), entry.browser().date(), entry.browser().screen()))
                        .as(entry.id())
                        .allSatisfy(value -> assertThat(value).isNotBlank());
                if ("confirmed".equals(entry.browser().result())) {
                    assertThat(entry.browser().observedAs()).as(entry.id()).isNotBlank();
                }
            }
            if (entry.tooltip() != null) {
                assertThat(entry.kind()).as(entry.id()).contains("tooltip");
            }
            if (entry.icon() != null) {
                assertThat(entry.icon().image()).as(entry.id()).startsWith("SvgImage.");
                assertThat(entry.icon().file()).as(entry.id()).endsWith(".svg");
                assertThat(entry.icon().sourceEvidence().getLast().path()).as(entry.id()).endsWith("SvgImage.java");
            }
        }
        assertThat(UiCatalogue.bundled().entries()).extracting(Entry::id).doesNotHaveDuplicates();
        assertThat(UiCatalogue.bundled().conditionsBasis()).contains("reviewed from source", "not compiler-checked");
        assertThat(UiCatalogue.bundled().browserCheck().scope()).contains("not proof of absence");
    }

    @Test
    void everyCitedSourceLineStillHoldsWhatItCites() throws IOException {
        final Path root = repositoryRoot();
        final Map<String, List<String>> files = new HashMap<>();
        final List<String> drifted = new ArrayList<>();
        for (final Entry entry : UiCatalogue.bundled().entries()) {
            final List<Evidence> cited = new ArrayList<>(List.of(entry.sourceEvidence()));
            if (entry.icon() != null) {
                cited.addAll(entry.icon().sourceEvidence());
            }
            if (entry.runtimeText() != null) {
                cited.add(entry.runtimeText().builtBy());
            }
            for (final Evidence evidence : cited) {
                final List<String> lines = files.computeIfAbsent(evidence.path(), path -> read(root.resolve(path)));
                if (lines == null || evidence.line() < 1 || evidence.line() > lines.size()
                    || !lines.get(evidence.line() - 1).contains(evidence.match())) {
                    drifted.add(entry.id() + " " + evidence.cited());
                }
            }
            if (entry.icon() != null) {
                // The image the control names is the one whose file is given.
                final Evidence image = entry.icon().sourceEvidence().getLast();
                final String constant = entry.icon().image().substring("SvgImage.".length());
                if (!files.get(image.path()).get(image.line() - 1).contains(
                        constant + "(\"" + entry.icon().file() + "\"")) {
                    drifted.add(entry.id() + " icon " + image.cited());
                }
            }
        }
        assertThat(drifted).isEmpty();
    }

    @Test
    void theUiGuideIsTheCatalogueRendered() {
        final String guide = new BundledKnowledge().guide(BundledKnowledge.UI_GUIDE).orElseThrow();
        assertThat(guide).startsWith("# Stroom UI labels\n").isSameAs(UiCatalogue.bundled().rendered());
        for (final Entry entry : UiCatalogue.bundled().entries()) {
            assertThat(guide).contains("`" + entry.label() + "`", "## " + entry.screen(),
                    entry.sourceEvidence().cited());
        }
        assertThat(new BundledKnowledge().guideTitle(BundledKnowledge.UI_GUIDE)).contains("Stroom UI labels");
    }

    @Test
    void textBuiltAtRunTimeSaysSoAndALabelFromATemplateCitesIt() {
        final Map<String, Entry> byId = new HashMap<>();
        UiCatalogue.bundled().entries().forEach(entry -> byId.put(entry.id(), entry));
        // A document type's name in the New menu is the type's display name, as the server lists the types.
        assertThat(byId.get("doctype.feed").runtimeText().builtBy().path()).endsWith("DocumentPluginEventManager.java");
        assertThat(byId.get("create.caption").runtimeText()).isNotNull();
        assertThat(byId.get("feed.tab.settings").runtimeText()).isNull();
        // A tooltip set in a UiBinder template cites the template.
        assertThat(byId.get("query.execute").sourceEvidence().path()).endsWith("QueryButtons.ui.xml");
    }

    @Test
    void wordsCountTheSameForQuestionsAndLabels() {
        assertThat(UiCatalogue.words("What TABS does the Feed have?")).containsExactly("tab", "feed");
        assertThat(UiCatalogue.words("Processes")).containsExactly("processe");
        assertThat(UiCatalogue.words("Class")).containsExactly("class");
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
