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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// The generator on small source trees made for each case: what each extractor reads, what it must leave out, and
/// that the same input gives the same bytes.
class TestUiCatalogueGenerator {

    private static final String JAVA = "src/main/java/";
    private static final String RESOURCES = "src/main/resources/";

    @TempDir
    Path root;

    @Test
    void menuItemsAreReadWithTheirMenuPathAndQualifiedKeys() throws IOException {
        stubs();
        write(JAVA + "stroom/core/client/MenuKeys.java", """
                package stroom.core.client;
                import stroom.widget.menu.client.presenter.MenuKey;
                import stroom.widget.menu.client.presenter.KeyedParentMenuItem;
                public class MenuKeys {
                    public static final MenuKey MAIN_MENU = new MenuKey("Main Menu");
                    public static final MenuKey TOOLS_MENU = new MenuKey("Tools Menu");
                    static void add(final Object items) {
                        items.addMenuItem(MenuKeys.MAIN_MENU, new KeyedParentMenuItem.Builder()
                                .text("Tools").menuKey(MenuKeys.TOOLS_MENU).build());
                    }
                }
                """);
        // A second MenuKeys, same simple name and constant name, other package and text.
        write(JAVA + "other/client/MenuKeys.java", """
                package other.client;
                import stroom.widget.menu.client.presenter.MenuKey;
                public class MenuKeys {
                    public static final MenuKey TOOLS_MENU = new MenuKey("Other Menu");
                }
                """);
        write(JAVA + "stroom/a/client/APlugin.java", """
                package stroom.a.client;
                import stroom.core.client.MenuKeys;
                import stroom.svg.shared.SvgImage;
                import stroom.widget.menu.client.presenter.IconMenuItem;
                import stroom.widget.menu.client.presenter.IconMenuItem.Builder;
                public class APlugin {
                    private static final String SCREEN_NAME = "Named By Constant";
                    void add(final Object event, final String name) {
                        event.getMenuItems().addMenuItem(MenuKeys.TOOLS_MENU, new IconMenuItem.Builder()
                                .icon(SvgImage.KEY)
                                .text("Say \\"hi\\" " +
                                      "twice")
                                .build());
                        event.getMenuItems().addMenuItem(MenuKeys.TOOLS_MENU, new Builder().text(SCREEN_NAME).build());
                        final IconMenuItem later;
                        later = new Builder().text("Assigned Later").build();
                        event.getMenuItems().addMenuItem(MenuKeys.TOOLS_MENU, later);
                        // Dynamic text, an unrelated builder's text and an unknown key are all left out.
                        event.getMenuItems().addMenuItem(MenuKeys.TOOLS_MENU, new Builder().text(name).build());
                        event.getMenuItems().addMenuItem(MenuKeys.TOOLS_MENU, new Other.Builder().text("Nope").build());
                        new Other.Builder().text("Nope Either").build();
                        event.getMenuItems().addMenuItem(Unknown.KEY, new Builder().text("Nope Key").build());
                    }
                }
                """);
        write(JAVA + "stroom/b/client/BPlugin.java", """
                package stroom.b.client;
                import other.client.MenuKeys;
                import stroom.widget.menu.client.presenter.IconMenuItem;
                public class BPlugin {
                    void add(final Object event) {
                        event.getMenuItems().addMenuItem(MenuKeys.TOOLS_MENU, new IconMenuItem.Builder()
                                .text("In Other").build());
                    }
                }
                """);
        final String json = generate();
        assertThat(json)
                .contains("\"id\": \"menu:stroom.a.client.APlugin:Main Menu > Tools:Say \\\"hi\\\" twice\"",
                        "\"icon\": \"SvgImage.KEY\"",
                        "\"path\": \"src/main/java/stroom/a/client/APlugin.java\",\n        \"line\": 11",
                        "\"id\": \"menu:stroom.a.client.APlugin:Main Menu > Tools:Named By Constant\"",
                        "\"id\": \"menu:stroom.a.client.APlugin:Main Menu > Tools:Assigned Later\"",
                        "\"id\": \"menu:stroom.core.client.MenuKeys:Main Menu:Tools\"",
                        "\"id\": \"menu:stroom.b.client.BPlugin:Other Menu:In Other\"")
                .doesNotContain("Nope", "\"label\": \"name\"");
    }

    @Test
    void toolbarButtonsAreReadFromPresetsAndATitleSetLaterIsLeftOut() throws IOException {
        stubs();
        write(JAVA + "stroom/svg/client/SvgPresets.java", """
                package stroom.svg.client;
                import stroom.svg.shared.SvgImage;
                public class SvgPresets {
                    public static final Preset UPLOAD = enabled(SvgImage.UPLOAD, "Upload");
                    public static final Preset DELETE = enabled(SvgImage.DELETE, "Delete");
                    public static final Preset EDIT = enabled(SvgImage.EDIT, "Edit");
                }
                """);
        write(JAVA + "stroom/a/client/APresenter.java", """
                package stroom.a.client;
                import stroom.svg.client.SvgPresets;
                public class APresenter {
                    private Object edit;
                    void bind(final Object view) {
                        view.addButton(SvgPresets.UPLOAD);
                        final Object delete = view.addButton(SvgPresets.DELETE.title("Delete Selected"));
                        edit = view.addButton(SvgPresets.EDIT);
                        edit.setTitle(dynamic());
                        view.addButton(Elsewhere.EDIT);
                    }
                }
                """);
        final String json = generate();
        assertThat(json)
                .contains("\"id\": \"toolbar:stroom.a.client.APresenter::Upload\"",
                        "\"tooltip\": \"Upload\"",
                        "\"icon\": \"SvgImage.UPLOAD\"",
                        "\"id\": \"toolbar:stroom.a.client.APresenter::Delete Selected\"")
                .doesNotContain("::Edit\"", "::Delete\"");
    }

    @Test
    void tabsDocumentTypesAndPermissionsAreReadFromLiterals() throws IOException {
        stubs();
        write(JAVA + "stroom/a/client/FeedPresenter.java", """
                package stroom.a.client;
                import stroom.widget.tab.client.presenter.TabDataImpl;
                public class FeedPresenter {
                    private static final String DOCS = "Documentation";
                    private static final TabData SETTINGS = new TabDataImpl("Settings");
                    private static final TabData DATA = new TabDataImpl("Data");
                    private static final TabData DOC = new TabDataImpl(DOCS);
                    TabData dynamic(final String text) {
                        return new TabDataImpl(text);
                    }
                }
                """);
        write(JAVA + "stroom/docstore/shared/DocumentType.java",
                "package stroom.docstore.shared;\npublic class DocumentType {\n}\n");
        write(JAVA + "stroom/docstore/shared/DocumentTypeGroup.java", """
                package stroom.docstore.shared;
                public enum DocumentTypeGroup {
                    DATA_PROCESSING(2, "Data Processing", "Feeds and pipelines.");
                }
                """);
        write(JAVA + "stroom/docstore/shared/DocumentTypeRegistry.java", """
                package stroom.docstore.shared;
                import stroom.svg.shared.SvgImage;
                public class DocumentTypeRegistry {
                    static final DocumentType FEED = new DocumentType(
                            DocumentTypeGroup.DATA_PROCESSING, "Feed", "Feed", SvgImage.DOCUMENT_FEED);
                    static final DocumentType X = new DocumentType(DocumentTypeGroup.DATA_PROCESSING, "X", name(),
                            SvgImage.DOCUMENT_FEED);
                }
                """);
        write(JAVA + "stroom/security/shared/AppPermission.java", """
                package stroom.security.shared;
                public enum AppPermission {
                    ADMINISTRATOR(
                            "Administrator",
                            "Full administrator rights."),
                    BUILT(Names.BUILT, "Not a literal.");
                }
                """);
        final String json = generate();
        assertThat(json)
                .contains("\"id\": \"tab:stroom.a.client.FeedPresenter::Settings\"",
                        "\"id\": \"tab:stroom.a.client.FeedPresenter::Data\"",
                        "\"id\": \"tab:stroom.a.client.FeedPresenter::Documentation\"",
                        "\"id\": \"document type:stroom.docstore.shared.DocumentTypeRegistry::Feed\"",
                        "\"group\": \"Data Processing\"",
                        "\"icon\": \"SvgImage.DOCUMENT_FEED\"",
                        "\"id\": \"app permission:stroom.security.shared.AppPermission::Administrator\"",
                        "\"path\": \"src/main/java/stroom/security/shared/AppPermission.java\",\n        \"line\": 4")
                .doesNotContain("\"label\": \"text\"", "Not a literal", "\"label\": \"X\"");
    }

    @Test
    void templateLabelsAreReadFromLabelledWidgetsOnly() throws IOException {
        stubs();
        write(JAVA + "stroom/a/client/view/SettingsViewImpl.java", """
                package stroom.a.client.view;
                public class SettingsViewImpl {
                }
                """);
        write(RESOURCES + "stroom/a/client/view/SettingsViewImpl.ui.xml", """
                <!DOCTYPE ui:UiBinder SYSTEM "http://127.0.0.1:1/xhtml.ent">
                <ui:UiBinder xmlns:ui="urn:ui:com.google.gwt.uibinder"
                             xmlns:g="urn:import:com.google.gwt.user.client.ui"
                             xmlns:form="urn:import:stroom.widget.form.client"
                             xmlns:b="urn:import:stroom.widget.button.client">
                    <g:FlowPanel title="Not a labelled widget">
                        <form:FormGroup identity="name"
                                        label="Feed &amp; Name"
                                        helpText="Not a label attribute">
                            <g:TextBox ui:field="name" title="An input's title is not indexed"/>
                        </form:FormGroup>
                        <b:Button text="Save" ui:field="save"/>
                        <g:Label text="{messages.dynamic}"/>
                    </g:FlowPanel>
                </ui:UiBinder>
                """);
        // No class of the same name: not read, and said.
        write(RESOURCES + "stroom/a/client/view/OrphanViewImpl.ui.xml", """
                <ui:UiBinder xmlns:ui="urn:ui:com.google.gwt.uibinder"
                             xmlns:b="urn:import:stroom.widget.button.client">
                    <b:Button text="Orphan"/>
                </ui:UiBinder>
                """);
        final String json = generate();
        assertThat(json)
                .contains("\"id\": \"template:stroom.a.client.view.SettingsViewImpl:FormGroup:Feed & Name\"",
                        "\"kind\": \"field label\"",
                        "\"path\": \"src/main/resources/stroom/a/client/view/SettingsViewImpl.ui.xml\",\n"
                        + "        \"line\": 8",
                        "\"id\": \"template:stroom.a.client.view.SettingsViewImpl:Button:Save\"",
                        "OrphanViewImpl.ui.xml: no class of the same name")
                .doesNotContain("Not a labelled widget", "Not a label attribute", "An input's title", "messages",
                        "\"label\": \"Orphan\"");
    }

    @Test
    void anEntityThatWouldReadAFileIsNeverExpanded() throws IOException {
        stubs();
        final Path secret = write("secret.txt", "SECRET-CONTENT");
        write(JAVA + "stroom/a/client/view/EvilViewImpl.java", """
                package stroom.a.client.view;
                public class EvilViewImpl {
                }
                """);
        write(RESOURCES + "stroom/a/client/view/EvilViewImpl.ui.xml", """
                <?xml version="1.0"?>
                <!DOCTYPE ui:UiBinder [
                  <!ENTITY leak SYSTEM "%s">
                ]>
                <ui:UiBinder xmlns:ui="urn:ui:com.google.gwt.uibinder"
                             xmlns:b="urn:import:stroom.widget.button.client">
                    <b:Button text="Before &leak; After"/>
                </ui:UiBinder>
                """.formatted(secret.toUri()));
        final String json = generate();
        assertThat(json).doesNotContain("SECRET-CONTENT");
        assertThat(json).contains("EvilViewImpl.ui.xml: does not parse without its DTD");
    }

    @Test
    void theSameInputGivesTheSameBytesAndAChangedInputChangesTheFingerprint() throws IOException {
        tabsDocumentTypesAndPermissionsAreReadFromLiterals();
        final String first = generate();
        assertThat(generate()).isEqualTo(first);
        assertThat(first).doesNotContain(root.toString()).doesNotContainPattern("20\\d\\d-\\d\\d-\\d\\dT");
        write(JAVA + "stroom/a/client/Other.java", "package stroom.a.client;\nclass Other {\n}\n");
        final String changed = generate();
        assertThat(fingerprint(changed)).isNotEqualTo(fingerprint(first));
    }

    @Test
    void aSetterRepeatedInOneChainLeavesTheItemOutAndIsCounted() throws IOException {
        stubs();
        menuKeys();
        write(JAVA + "stroom/a/client/RepeatPlugin.java", """
                package stroom.a.client;
                import stroom.core.client.MenuKeys;
                import stroom.svg.shared.SvgImage;
                import stroom.widget.menu.client.presenter.IconMenuItem;
                public class RepeatPlugin {
                    void add(final Object event, final String name) {
                        event.getMenuItems().addMenuItem(MenuKeys.MAIN_MENU, new IconMenuItem.Builder()
                                .text("Old Literal").text("New Literal").build());
                        event.getMenuItems().addMenuItem(MenuKeys.MAIN_MENU, new IconMenuItem.Builder()
                                .text("Literal Then Dynamic").text(name).build());
                        event.getMenuItems().addMenuItem(MenuKeys.MAIN_MENU, new IconMenuItem.Builder()
                                .text(name).text("Dynamic Then Literal").build());
                        event.getMenuItems().addMenuItem(MenuKeys.MAIN_MENU, new IconMenuItem.Builder()
                                .icon(SvgImage.A).text("Two Icons").icon(SvgImage.B).build());
                        event.getMenuItems().addMenuItem(MenuKeys.MAIN_MENU, new IconMenuItem.Builder()
                                .text("Kept Once").build());
                    }
                }
                """);
        presets();
        write(JAVA + "stroom/a/client/RepeatPresenter.java", """
                package stroom.a.client;
                import stroom.svg.client.SvgPresets;
                public class RepeatPresenter {
                    void bind(final Object view, final String name) {
                        view.addButton(SvgPresets.UPLOAD.title("Old Title").title("New Title"));
                        view.addButton(SvgPresets.UPLOAD.title("Literal Title").title(name));
                        view.addButton(SvgPresets.UPLOAD.title(name).title("Dynamic Then Title"));
                        view.addButton(SvgPresets.UPLOAD.title(name));
                        view.addButton(SvgPresets.DELETE.enabled(false));
                        view.addButton(SvgPresets.EDIT.title("Kept Title"));
                    }
                }
                """);
        final String json = generate();
        assertThat(json)
                .contains("\"label\": \"Kept Once\"", "\"label\": \"Kept Title\"",
                        "\"menu: text, icon or menuKey set more than once in one chain\": 4",
                        "\"toolbar: title set more than once in one chain\": 3",
                        "\"toolbar: title is not a literal\": 1",
                        "\"toolbar: preset changed by a call other than title\": 1")
                .doesNotContain("Old Literal", "New Literal", "Literal Then Dynamic", "Dynamic Then Literal",
                        "Two Icons", "Old Title", "New Title", "Literal Title", "Dynamic Then Title",
                        "\"label\": \"Delete\"");
    }

    @Test
    void aWidgetIsKnownByItsPackageAndASameNamedOneIsLeftOut() throws IOException {
        stubs();
        write(JAVA + "stroom/a/client/view/MixedViewImpl.java", """
                package stroom.a.client.view;
                public class MixedViewImpl {
                }
                """);
        write(RESOURCES + "stroom/a/client/view/MixedViewImpl.ui.xml", """
                <ui:UiBinder xmlns:ui="urn:ui:com.google.gwt.uibinder"
                             xmlns:form="urn:import:stroom.widget.form.client"
                             xmlns:fake="urn:import:stroom.somewhere.else"
                             xmlns:b="urn:import:stroom.widget.button.client">
                    <form:FormGroup label="Real Group"/>
                    <fake:FormGroup label="Fake Group"/>
                    <fake:Button text="Fake Button"/>
                    <b:InlineSvgButton text="Icon Text" title="Icon Tooltip"/>
                </ui:UiBinder>
                """);
        final String json = generate();
        assertThat(json)
                .contains("\"label\": \"Real Group\"", "\"label\": \"Icon Tooltip\"",
                        "\"template: widget of the same name in another package\": 2",
                        "\"template: text on InlineSvgButton not read\": 1")
                .doesNotContain("Fake Group", "Fake Button", "\"label\": \"Icon Text\"");
    }

    @Test
    void duplicatesKeepTheFirstSourcePosition() {
        final CatalogueEntry late = new CatalogueEntry("tab", "Data", "tab", "a.B", null, null, null, null, "a", 9);
        final CatalogueEntry early = new CatalogueEntry("tab", "Data", "tab", "a.B", null, null, null, null, "a", 3);
        assertThat(UiCatalogueGenerator.dedupe(List.of(late, early))).containsExactly(early);
    }

    private void menuKeys() throws IOException {
        write(JAVA + "stroom/core/client/MenuKeys.java", """
                package stroom.core.client;
                import stroom.widget.menu.client.presenter.MenuKey;
                public class MenuKeys {
                    public static final MenuKey MAIN_MENU = new MenuKey("Main Menu");
                }
                """);
    }

    private void presets() throws IOException {
        write(JAVA + "stroom/svg/client/SvgPresets.java", """
                package stroom.svg.client;
                import stroom.svg.shared.SvgImage;
                public class SvgPresets {
                    public static final Preset UPLOAD = enabled(SvgImage.UPLOAD, "Upload");
                    public static final Preset DELETE = enabled(SvgImage.DELETE, "Delete");
                    public static final Preset EDIT = enabled(SvgImage.EDIT, "Edit");
                }
                """);
    }

    // Classes the fixtures name, so their package resolves; parsed only, never compiled.
    private void stubs() throws IOException {
        write(JAVA + "stroom/widget/menu/client/presenter/MenuKey.java",
                "package stroom.widget.menu.client.presenter;\npublic class MenuKey {\n}\n");
    }

    private String generate() throws IOException {
        return UiCatalogueGenerator.generate(root, List.of(root.resolve(JAVA), root.resolve(RESOURCES)));
    }

    private static String fingerprint(final String json) {
        final int start = json.indexOf("sha256:");
        return json.substring(start, start + 71);
    }

    private Path write(final String path, final String text) throws IOException {
        final Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }
}
