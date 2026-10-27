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
    void aMenuItemGivesItsMenuPathOwnerIconAndSourceLine() {
        final Map<String, Object> answer = json(call("Credentials Manager"));
        assertThat(answer).containsEntry("status", "match");
        assertThat(first(answer))
                .containsEntry("label", "Credentials Manager")
                .containsEntry("kind", "menu item")
                .containsEntry("owner", "stroom.credentials.client.CredentialsPlugin")
                .containsEntry("location", "Main Menu > Security")
                .containsEntry("icon", "SvgImage.KEY_BLUE")
                .doesNotContainKeys("conditions", "screen", "browser", "aliases");
        assertThat((String) first(answer).get("source"))
                .startsWith("stroom-core-client/src/main/java/stroom/credentials/client/CredentialsPlugin.java:");
    }

    @Test
    void aToolbarButtonGivesItsTooltipAndIcon() {
        final Map<String, Object> upload = entries(json(call("the Upload button"))).stream()
                .filter(entry -> "toolbar button tooltip".equals(entry.get("kind")))
                .findFirst()
                .orElseThrow();
        assertThat(upload)
                .containsEntry("label", "Upload")
                .containsEntry("tooltip", "Upload")
                .containsEntry("icon", "SvgImage.UPLOAD")
                .containsEntry("owner", "stroom.data.client.presenter.MetaPresenter");
    }

    @Test
    void aClassesTabsAreListed() {
        final Map<String, Object> answer = json(call("what tabs does a feed have"));
        final List<Object> tabs = entries(answer).stream()
                .filter(entry -> "tab".equals(entry.get("kind"))
                                 && "stroom.feed.client.presenter.FeedPresenter".equals(entry.get("owner")))
                .map(entry -> entry.get("label"))
                .toList();
        assertThat(tabs).containsExactlyInAnyOrder("Settings", "Data", "Active Tasks", "Documentation",
                "Permissions");
    }

    @Test
    void nothingFoundSaysItIsUnknownNotAbsent() {
        final Map<String, Object> answer = json(call("Zebra Quokka Wombat"));
        assertThat(answer).containsEntry("status", "no_match").containsEntry("matched", 0);
        assertThat(entries(answer)).isEmpty();
        assertThat((String) answer.get("hint")).contains("unknown");
        assertThat((String) answer.get("coverage")).contains("unknown here, not absent");
    }

    @Test
    void oneLabelInSeveralPlacesIsAmbiguous() {
        final Map<String, Object> answer = json(call("Active Tasks"));
        assertThat(answer).containsEntry("status", "ambiguous");
        assertThat(entries(answer)).extracting(entry -> entry.get("owner"))
                .contains("stroom.feed.client.presenter.FeedPresenter",
                        "stroom.pipeline.client.presenter.PipelinePresenter");
    }

    @Test
    void atMostEightAreGivenAndHowManyMoreIsSaid() {
        final List<Map<String, Object>> entries = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            entries.add(entry("e" + i, "Settings", "a.Screen" + i, null, null));
        }
        final Map<String, Object> answer = new UiLookupTool(() -> catalogue(entries), () -> BUILD)
                .answer("Settings");
        assertThat(answer).containsEntry("status", "ambiguous")
                .containsEntry("matched", 12)
                .containsEntry("omitted", 4);
        assertThat(entries(answer)).hasSize(UiLookupTool.MAX_RESULTS);
        // Best first, then in the catalogue's order.
        assertThat(entries(answer)).extracting(entry -> entry.get("owner")).startsWith("a.Screen0", "a.Screen1");
    }

    @Test
    void provenanceGivesTheFingerprintSaysThereIsNoShaAndWhatIsRunning() {
        final Map<String, Object> provenance = cast(json(call("Upload")).get("provenance"));
        assertThat(provenance)
                .containsEntry("source_fingerprint", UiCatalogue.bundled().provenance().sourceFingerprint())
                .doesNotContainKey("source_sha")
                .containsEntry("running_build", "v7.14-SNAPSHOT-20261027");
        assertThat((String) provenance.get("source_fingerprint")).matches("sha256:[0-9a-f]{64}");
        assertThat((String) provenance.get("source_sha_note")).contains("No source commit SHA", "not a source SHA");
    }

    @Test
    void anOversizedValueIsCutAndTheAnswerKeepsToItsCap() {
        for (final String big : List.of("x".repeat(100_000), "漢".repeat(100_000), "😀".repeat(50_000))) {
            final Map<String, Object> one = entry("big", "Upload", "a.B", big, big);
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
        for (int i = 0; i < 8; i++) {
            entries.add(entry("e" + i, "Upload", "a.Screen" + i, "😀".repeat(240), "😀".repeat(240)));
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
        final String big = "漢".repeat(5_000);
        final BuildInfo bigBuild = new BuildInfo(1L, big, 2L);
        // No match, and a match whose one entry is too large to give.
        final String huge = "😀".repeat(240);
        final Map<String, List<Map<String, Object>>> cases = Map.of(
                "Designer", List.of(),
                "Upload", List.of(entry("big", "Upload", "a." + huge, huge, huge)));
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
    void theGuideCarriesWhatTheToolGives() {
        final Map<String, Object> one = entry("q", "Execute Query", "a.QueryButtons", "InlineSvgButton",
                "Run the query");
        one.put("icon", "SvgImage.PLAY");
        one.put("group", "Search");
        final UiCatalogue ui = catalogue(List.of(one));
        final Map<String, Object> shown = first(new UiLookupTool(() -> ui, () -> BUILD).answer("Execute Query"));
        assertThat(ui.rendered()).contains((String) shown.get("label"), (String) shown.get("owner"),
                (String) shown.get("location"), (String) shown.get("tooltip"), (String) shown.get("icon"),
                (String) shown.get("group"), (String) shown.get("source"));
    }

    @Test
    void ambiguityCountsEveryMatchAndTellsPlacesByOwnerAndLocation() {
        // The same location in two owners is two places, and so are two locations in one owner.
        assertThat(new UiLookupTool(() -> catalogue(List.of(
                entry("a", "Settings", "a.Feed", "tab bar", null),
                entry("b", "Settings", "a.Index", "tab bar", null))), () -> BUILD).answer("Settings"))
                .containsEntry("status", "ambiguous");
        assertThat(new UiLookupTool(() -> catalogue(List.of(
                entry("a", "Settings", "a.Feed", "FormGroup", null),
                entry("b", "Settings", "a.Feed", "Button", null))), () -> BUILD).answer("Settings"))
                .containsEntry("status", "ambiguous");
        // Eight given, all one place; the ninth, left out by the count, is another place.
        final List<Map<String, Object>> entries = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            entries.add(entry("same" + i, "Settings", "a.Feed", "tab bar", null));
        }
        entries.add(entry("other", "Settings", "a.Index", "tab bar", null));
        final Map<String, Object> answer = new UiLookupTool(() -> catalogue(entries), () -> BUILD).answer("Settings");
        assertThat(answer).containsEntry("omitted", 1).containsEntry("status", "ambiguous");
        assertThat(entries(answer)).extracting(entry -> entry.get("owner")).containsOnly("a.Feed");
        // One place only, with no location.
        assertThat(new UiLookupTool(() -> catalogue(List.of(
                entry("a", "Settings", "a.Feed", null, null),
                entry("b", "Settings", "a.Feed", null, null))), () -> BUILD).answer("Settings"))
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

    private static Map<String, Object> entry(final String id, final String label, final String owner,
                                             final String location, final String tooltip) {
        final Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("family", "tab");
        entry.put("label", label);
        entry.put("kind", "tab");
        entry.put("owner", owner);
        if (location != null) {
            entry.put("location", location);
        }
        if (tooltip != null) {
            entry.put("tooltip", tooltip);
        }
        entry.put("source", Map.of("path", "a/B.java", "line", 1));
        return entry;
    }

    private static UiCatalogue catalogue(final List<Map<String, Object>> entries) {
        return catalogue(entries, "test");
    }

    // A catalogue whose provenance values are all the given text.
    private static UiCatalogue catalogue(final List<Map<String, Object>> entries, final String provenance) {
        return UiCatalogue.parse(JsonUtil.writeValueAsString(Map.of(
                "provenance", Map.of(
                        "generated_by", provenance,
                        "source_fingerprint", provenance,
                        "source_files", 1,
                        "source_sha", provenance,
                        "source_sha_note", provenance,
                        "coverage", provenance),
                "entries", entries), false));
    }
}
