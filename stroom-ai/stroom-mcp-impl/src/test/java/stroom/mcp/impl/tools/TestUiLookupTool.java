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

package stroom.mcp.impl.tools;

import stroom.ai.impl.tools.ToolResult;
import stroom.ai.shared.AiToolCategory;
import stroom.mcp.impl.UiCatalogue;
import stroom.util.json.JsonUtil;
import stroom.util.shared.BuildInfo;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestUiLookupTool {

    private static final BuildInfo BUILD = new BuildInfo(1L, "v7.14-SNAPSHOT-20261027", 2L);

    private final UiLookupTool tool = new UiLookupTool(UiCatalogue::bundled, () -> BUILD);

    @Test
    void theToolIsReadOnlyCoreBoundedAndReadsNothingUntrusted() {
        assertThat(tool.definition().name()).isEqualTo("ui_lookup");
        assertThat(tool.category()).isEqualTo(AiToolCategory.CORE);
        assertThat(tool.isReadOnly()).isTrue();
        assertThat(tool.boundsItsOutput()).isTrue();
        assertThat(tool.readsUntrustedContent()).isFalse();
        assertThat(tool.confirmsItself()).isFalse();
        assertThat(tool.definition().description()).contains("interface only", "unknown here");
    }

    @Test
    void aLabelGivesWhereItIsItsConditionsAndItsSourceLine() {
        final Map<String, Object> answer = json(call("Add Processor"));
        assertThat(answer).containsEntry("status", "match");
        final Map<String, Object> entry = first(answer);
        assertThat(entry)
                .containsEntry("label", "Add Processor")
                .containsEntry("kind", "toolbar button tooltip")
                .containsEntry("screen", "Pipeline editor: Processors tab")
                .containsEntry("location", "Pipeline editor > Processors tab > processor list toolbar")
                .containsEntry("tooltip", "Add Processor");
        assertThat(TestUiLookupTool.<List<String>>cast(entry.get("conditions")))
                .contains("Needs the Manage Processors permission.");
        assertThat((String) entry.get("source_evidence"))
                .startsWith("stroom-core-client/src/main/java/stroom/processor/client/presenter/"
                            + "ProcessorPresenter.java:");
        assertThat(TestUiLookupTool.<Map<String, Object>>cast(entry.get("icon_provenance")))
                .containsEntry("image", "SvgImage.ADD")
                .containsEntry("file", "add.svg");
        assertThat(TestUiLookupTool.<Map<String, Object>>cast(entry.get("browser")))
                .containsEntry("result", "confirmed")
                .containsEntry("image", "gradata/stroom:v7.14-SNAPSHOT-20261027")
                .containsEntry("observed_as", "tooltip (title attribute)");
    }

    @Test
    void textBuiltAtRunTimeAndAnUncheckedEntryAreSaid() {
        final Map<String, Object> feed = entries(json(call("XSL Translation"))).stream()
                .filter(entry -> "XSL Translation".equals(entry.get("label")))
                .findFirst()
                .orElseThrow();
        assertThat(TestUiLookupTool.<Map<String, Object>>cast(feed.get("text_built_at_run_time")))
                .containsKeys("how", "built_by");
        final Map<String, Object> unchecked = first(new UiLookupTool(
                () -> catalogue(List.of(entry("x", "Upload", "Feed editor", "Feed editor", null))), () -> BUILD)
                .answer("Upload"));
        assertThat(TestUiLookupTool.<Map<String, Object>>cast(unchecked.get("browser")))
                .containsEntry("result", "unchecked");
    }

    @Test
    void aTooltipIsFoundByItsWords() {
        final Map<String, Object> answer = json(call("what does the button with tooltip Refresh Current Step do"));
        assertThat(first(answer))
                .containsEntry("label", "Refresh Current Step")
                .containsEntry("tooltip", "Refresh Current Step")
                .containsEntry("screen", "Stepping");
    }

    @Test
    void aScreensTabsAreListed() {
        final Map<String, Object> answer = json(call("what tabs does a feed have"));
        final List<Object> tabs = entries(answer).stream()
                .filter(entry -> "tab".equals(entry.get("kind")) && "Feed editor".equals(entry.get("screen")))
                .map(entry -> entry.get("label"))
                .toList();
        assertThat(tabs).containsExactlyInAnyOrder("Settings", "Data", "Active Tasks", "Documentation",
                "Permissions");
    }

    @Test
    void nothingFoundSaysItIsUnknownNotAbsent() {
        final Map<String, Object> answer = json(call("Pipeline Designer toolbar"));
        assertThat(answer).containsEntry("status", "no_match").containsEntry("matched", 0);
        assertThat(entries(answer)).isEmpty();
        assertThat((String) answer.get("hint")).contains("unknown");
        assertThat((String) answer.get("coverage")).contains("unknown here, not absent");
    }

    @Test
    void oneLabelInSeveralPlacesIsAmbiguous() {
        final Map<String, Object> answer = json(call("Active Tasks"));
        assertThat(answer).containsEntry("status", "ambiguous");
        assertThat(entries(answer)).extracting(entry -> entry.get("screen"))
                .containsExactlyInAnyOrder("Feed editor", "Pipeline editor");
    }

    @Test
    void atMostEightAreGivenAndHowManyMoreIsSaid() {
        final List<Map<String, Object>> entries = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            entries.add(entry("e" + i, "Settings", "Screen " + i, "Screen " + i + " > tab bar", null));
        }
        final Map<String, Object> answer = new UiLookupTool(() -> catalogue(entries), () -> BUILD)
                .answer("Settings");
        assertThat(answer).containsEntry("status", "ambiguous")
                .containsEntry("matched", 12)
                .containsEntry("omitted", 4);
        assertThat(entries(answer)).hasSize(UiLookupTool.MAX_RESULTS);
        // Best first, then in the catalogue's order.
        assertThat(entries(answer)).extracting(entry -> entry.get("screen")).startsWith("Screen 0", "Screen 1");
    }

    @Test
    void provenanceSaysWhatTheCatalogueWasReadFromAndWhatIsRunning() {
        final Map<String, Object> provenance = cast(json(call("Upload")).get("provenance"));
        assertThat(provenance)
                .containsEntry("verified_against", UiCatalogue.bundled().verifiedAgainst())
                .containsEntry("running_build", "v7.14-SNAPSHOT-20261027");
        assertThat((String) provenance.get("review_method")).contains("Source-reviewed");
        assertThat((String) provenance.get("conditions_basis")).contains("reviewed from source");
        assertThat(TestUiLookupTool.<Map<String, Object>>cast(provenance.get("browser_check")))
                .containsEntry("platform", "linux/amd64")
                .containsKey("digest");
    }

    @Test
    void anOversizedValueIsCutAndTheAnswerKeepsToItsCap() {
        for (final String big : List.of("x".repeat(100_000), "漢".repeat(100_000), "😀".repeat(50_000))) {
            final Map<String, Object> one = entry("big", "Upload", "Feed editor", big, List.of(big, big));
            final UiLookupTool bounded = new UiLookupTool(() -> catalogue(List.of(one)), () -> BUILD);
            final ToolResult result = bounded.execute(PortedToolCalls.call(UiLookupTool.NAME,
                    "{\"question\":\"Upload\"}"), PortedToolCalls.context());
            final Map<String, Object> answer = json(result);
            assertThat(UiLookupTool.payloadSize(answer)).isLessThanOrEqualTo(UiLookupTool.MAX_PAYLOAD);
            assertThat(result.text().length()).isLessThanOrEqualTo(UiLookupTool.MAX_PAYLOAD);
            assertThat(first(answer).get("location").toString()).endsWith(UiLookupTool.CUT);
            assertThat(answer).containsKeys("status", "provenance", "coverage");
        }
    }

    @Test
    void manyLargeEntriesAreLeftOutWholeAndSaid() {
        final List<Map<String, Object>> entries = new ArrayList<>();
        final List<String> conditions = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            conditions.add("😀".repeat(500));
        }
        for (int i = 0; i < 8; i++) {
            entries.add(entry("e" + i, "Upload", "Screen " + i, "Screen " + i, conditions));
        }
        final Map<String, Object> answer = new UiLookupTool(() -> catalogue(entries), () -> BUILD).answer("Upload");
        assertThat(UiLookupTool.payloadSize(answer)).isLessThanOrEqualTo(UiLookupTool.MAX_PAYLOAD);
        assertThat((Integer) answer.get("omitted_for_size")).isPositive();
        assertThat(answer).containsEntry("matched", 8).containsKeys("provenance", "coverage", "note");
        // The same each time.
        assertThat(new UiLookupTool(() -> catalogue(entries), () -> BUILD).answer("Upload")).isEqualTo(answer);
    }

    @Test
    void oversizedProvenanceIsCutThenReplacedAndTheAnswerKeepsToItsCap() {
        final String big = "\u6f22".repeat(5_000);
        final BuildInfo bigBuild = new BuildInfo(1L, big, 2L);
        // No match, and a match whose one entry is too large to give.
        final List<String> conditions = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            conditions.add("\uD83D\uDE00".repeat(240));
        }
        final Map<String, List<Map<String, Object>>> cases = Map.of(
                "Designer", List.of(),
                "Upload", List.of(entry("big", "Upload", "Feed editor", "Feed editor", conditions)));
        cases.forEach((question, entries) -> {
            final UiLookupTool bounded = new UiLookupTool(() -> catalogue(entries, big), () -> bigBuild);
            final ToolResult result = bounded.execute(PortedToolCalls.call(UiLookupTool.NAME,
                    JsonUtil.writeValueAsString(Map.of("question", question), false)), PortedToolCalls.context());
            final Map<String, Object> answer = json(result);
            assertThat(UiLookupTool.payloadSize(answer)).as(question).isLessThanOrEqualTo(UiLookupTool.MAX_PAYLOAD);
            assertThat(answer).as(question).containsKeys("status", "matched", "coverage", "note", "provenance");
            assertThat(answer).as(question).containsEntry("provenance_truncated", true);
            assertThat(entries(answer)).as(question).isEmpty();
            assertThat(JsonUtil.writeValueAsString(answer.get("provenance"), false)).as(question)
                    .containsAnyOf(UiLookupTool.CUT, UiLookupTool.PROVENANCE_TOO_LARGE);
        });
    }

    @Test
    void theGuideCarriesTheSameQualifiersAsTheTool() {
        final Map<String, Object> one = entry("q", "Execute Query", "Query editor", "Query editor > Query tab toolbar",
                null);
        one.put("kind", "toolbar button tooltip");
        one.put("tooltip", "Stop Query while it runs");
        one.put("runtime_text", Map.of("how", "Set by the query buttons as the query starts and stops.",
                "built_by", Map.of("path", "a/QueryButtons.java", "line", 9, "match", "setTitle")));
        one.put("browser", Map.of("result", "confirmed", "image", "img", "digest", "d", "platform", "linux/amd64",
                "user", "admin", "date", "2026-10-27", "screen", "Query editor",
                "observed_as", "tooltip (title attribute)", "note", "Only Execute Query was seen, not Stop Query."));
        final UiCatalogue ui = catalogue(List.of(one));
        final Map<String, Object> shown = first(new UiLookupTool(() -> ui, () -> BUILD).answer("Execute Query"));
        final Map<String, Object> runtime = cast(shown.get("text_built_at_run_time"));
        final Map<String, Object> browser = cast(shown.get("browser"));
        assertThat(ui.rendered()).contains((String) shown.get("tooltip"), (String) runtime.get("how"),
                (String) runtime.get("built_by"), (String) browser.get("result"), (String) browser.get("observed_as"),
                (String) browser.get("note"), (String) shown.get("source_evidence"));
    }

    @Test
    void ambiguityCountsEveryMatchAndTellsPlacesByScreenAndLocation() {
        // The same location on two screens is two places.
        assertThat(new UiLookupTool(() -> catalogue(List.of(
                entry("a", "Settings", "Feed editor", "tab bar", null),
                entry("b", "Settings", "Index editor", "tab bar", null))), () -> BUILD).answer("Settings"))
                .containsEntry("status", "ambiguous");
        // Eight given, all one place; the ninth, left out by the count, is another place.
        final List<Map<String, Object>> entries = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            entries.add(entry("same" + i, "Settings", "Feed editor", "tab bar", null));
        }
        entries.add(entry("other", "Settings", "Index editor", "tab bar", null));
        final Map<String, Object> answer = new UiLookupTool(() -> catalogue(entries), () -> BUILD).answer("Settings");
        assertThat(answer).containsEntry("omitted", 1).containsEntry("status", "ambiguous");
        assertThat(entries(answer)).extracting(entry -> entry.get("screen")).containsOnly("Feed editor");
        // One place only.
        assertThat(new UiLookupTool(() -> catalogue(entries.subList(0, 2)), () -> BUILD).answer("Settings"))
                .containsEntry("status", "match");
    }

    @Test
    void anOversizedQuestionIsRefused() {
        final ToolResult result = call("x".repeat(UiLookupTool.MAX_QUESTION + 1));
        assertThat(result.isError()).isTrue();
        assertThat(result.text()).contains("longer than 200 characters");
        assertThat(call("").isError()).isTrue();
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(final Object value) {
        return (T) value;
    }

    private ToolResult call(final String question) {
        return tool.execute(PortedToolCalls.call(UiLookupTool.NAME,
                JsonUtil.writeValueAsString(Map.of("question", question), false)), PortedToolCalls.context());
    }

    private static Map<String, Object> json(final ToolResult result) {
        assertThat(result.isError()).as(result.text()).isFalse();
        return PortedToolCalls.json(result.text());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(final Map<String, Object> answer) {
        return (List<Map<String, Object>>) answer.get("entries");
    }

    private static Map<String, Object> first(final Map<String, Object> answer) {
        assertThat(entries(answer)).isNotEmpty();
        return entries(answer).getFirst();
    }

    private static Map<String, Object> entry(final String id, final String label, final String screen,
                                             final String location, final List<String> conditions) {
        final Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("label", label);
        entry.put("kind", "tab");
        entry.put("screen", screen);
        entry.put("location", location);
        if (conditions != null) {
            entry.put("conditions", conditions);
        }
        entry.put("source_evidence", Map.of("path", "a/B.java", "line", 1, "match", label));
        return entry;
    }

    private static UiCatalogue catalogue(final List<Map<String, Object>> entries) {
        return UiCatalogue.parse(JsonUtil.writeValueAsString(Map.of(
                "verified_against", "test",
                "source", "a test",
                "review_method", "Made for a test.",
                "conditions_basis", "Reviewed from source.",
                "entries", entries), false));
    }

    // A catalogue whose provenance values are all the given text.
    private static UiCatalogue catalogue(final List<Map<String, Object>> entries, final String provenance) {
        return UiCatalogue.parse(JsonUtil.writeValueAsString(Map.of(
                "verified_against", provenance,
                "source", provenance,
                "review_method", provenance,
                "conditions_basis", provenance,
                "browser_check", Map.of("image", provenance, "digest", provenance, "platform", provenance,
                        "date", provenance, "user", provenance, "method", provenance, "scope", provenance),
                "entries", entries), false));
    }
}
