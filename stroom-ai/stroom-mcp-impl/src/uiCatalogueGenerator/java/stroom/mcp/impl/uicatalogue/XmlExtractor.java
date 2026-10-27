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

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/// Reads the labels in UiBinder templates (`*.ui.xml`) with the JDK's XML parser. DTDs, external entities and
/// network access are off, so a template that needs its DTD's entities (e.g. `&nbsp;`) does not parse and is
/// reported, not guessed at. Only the label attributes of label-carrying widgets are read.
final class XmlExtractor {

    /// The widgets read, by imported class (the `urn:import:` package and the element name), and the attributes on
    /// each that its class shows as text (`label`, `text`) or sets as the element's tooltip (`title`). Text on an
    /// [stroom.widget.button.client.InlineSvgButton] is not read: whether it shows beside the icon isn't known here.
    static final Map<String, Set<String>> WIDGETS = Map.ofEntries(
            Map.entry("stroom.widget.form.client.FormGroup", Set.of("label")),
            Map.entry("stroom.widget.form.client.FormLabel", Set.of("label")),
            Map.entry("stroom.planb.client.view.SettingsGroup", Set.of("label")),
            Map.entry("stroom.annotation.client.SettingBlock", Set.of("title")),
            Map.entry("stroom.widget.tickbox.client.view.CustomCheckBox", Set.of("label", "title")),
            Map.entry("stroom.widget.button.client.Button", Set.of("text", "title")),
            Map.entry("stroom.widget.button.client.InlineSvgButton", Set.of("title")),
            Map.entry("stroom.widget.button.client.InlineSvgToggleButton", Set.of("title")),
            Map.entry("stroom.widget.button.client.FabButton", Set.of("title")),
            Map.entry("com.google.gwt.user.client.ui.Button", Set.of("text", "title")),
            Map.entry("com.google.gwt.user.client.ui.CheckBox", Set.of("text", "title")),
            Map.entry("com.google.gwt.user.client.ui.Label", Set.of("text", "title")),
            Map.entry("com.google.gwt.user.client.ui.Hyperlink", Set.of("text")));
    private static final String IMPORT = "urn:import:";
    private static final Set<String> LABEL_ATTRIBUTES = Set.of("label", "text", "title");
    // The element names of the widgets, to count a same-named widget of another package as left out.
    private static final Set<String> WIDGET_NAMES = WIDGETS.keySet().stream()
            .map(name -> name.substring(name.lastIndexOf('.') + 1))
            .collect(Collectors.toSet());
    private static final String SUFFIX = ".ui.xml";
    private static final Pattern SOURCE_ROOT = Pattern.compile("^(.*?/)?src/main/(java|resources)/");

    private final List<String> skipped = new ArrayList<>();
    private final Map<String, Integer> skips = new TreeMap<>();

    /// @return How many labels each reason left out.
    Map<String, Integer> skips() {
        return skips;
    }

    /// @return Each template that was not read, and why.
    List<String> skipped() {
        return skipped;
    }

    /// @param root         The repository root, which paths in the output are relative to.
    /// @param files        The templates to read.
    /// @param knownClasses The classes the Java extractor parsed; a template is read only if its owner is one.
    /// @return The labels read.
    List<CatalogueEntry> extract(final Path root, final List<Path> files, final Set<String> knownClasses) {
        final List<CatalogueEntry> entries = new ArrayList<>();
        for (final Path file : files) {
            final String path = JavaExtractor.relative(root, file);
            final String owner = owner(path);
            if (owner == null || !knownClasses.contains(owner)) {
                skipped.add(path + ": no class of the same name");
                skips.merge("template: no class of the same name", 1, Integer::sum);
                continue;
            }
            try {
                final String text = Files.readString(file, StandardCharsets.UTF_8);
                parse(text, path, owner, entries, skips);
            } catch (final SAXException e) {
                skipped.add(path + ": does not parse without its DTD (" + firstLine(e.getMessage()) + ")");
                skips.merge("template: does not parse without its DTD", 1, Integer::sum);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return entries;
    }

    /// Reads one template into entries, all or nothing.
    static void parse(final String text, final String path, final String owner, final List<CatalogueEntry> entries,
                      final Map<String, Integer> skips) throws SAXException, IOException {
        final String[] lines = text.split("\n", -1);
        final List<CatalogueEntry> found = new ArrayList<>();
        final Map<String, Integer> left = new TreeMap<>();
        final SAXParser parser = parser();
        parser.parse(new InputSource(new StringReader(text)), new DefaultHandler() {
            private Locator locator;

            @Override
            public void setDocumentLocator(final Locator locator) {
                this.locator = locator;
            }

            @Override
            public InputSource resolveEntity(final String publicId, final String systemId) {
                // Never fetch a DTD or entity: an empty one stands in for it.
                return new InputSource(new StringReader(""));
            }

            @Override
            public void startElement(final String uri, final String localName, final String qName,
                                     final Attributes attributes) {
                final Set<String> labels = uri.startsWith(IMPORT)
                        ? WIDGETS.get(uri.substring(IMPORT.length()) + "." + localName)
                        : null;
                if (labels == null) {
                    if (WIDGET_NAMES.contains(localName)) {
                        left.merge("template: widget of the same name in another package", 1, Integer::sum);
                    }
                    return;
                }
                for (int i = 0; i < attributes.getLength(); i++) {
                    final String name = attributes.getLocalName(i);
                    final String value = attributes.getValue(i).strip();
                    if (value.isEmpty() || !LABEL_ATTRIBUTES.contains(name)) {
                        continue;
                    }
                    if (!labels.contains(name)) {
                        left.merge("template: " + name + " on " + localName + " not read", 1, Integer::sum);
                        continue;
                    }
                    // A value with `{` is a UiBinder expression, worked out at run time.
                    if (value.contains("{")) {
                        left.merge("template: label is a UiBinder expression", 1, Integer::sum);
                    } else {
                        found.add(new CatalogueEntry("template", value, kind(name), owner, localName,
                                null, null, null, path, line(lines, locator.getLineNumber(), name)));
                    }
                }
            }
        });
        entries.addAll(found);
        left.forEach((reason, count) -> skips.merge(reason, count, Integer::sum));
    }

    // The parser reports the line a start tag ends on; the attribute is on that line or one above it.
    private static int line(final String[] lines, final int end, final String attribute) {
        final Pattern at = Pattern.compile("(^|\\s)" + Pattern.quote(attribute) + "\\s*=");
        for (int i = end - 1; i >= 0; i--) {
            if (at.matcher(lines[i]).find()) {
                return i + 1;
            }
            if (lines[i].contains("<")) {
                break;
            }
        }
        return end;
    }

    private static String kind(final String attribute) {
        return switch (attribute) {
            case "label" -> "field label";
            case "title" -> "tooltip";
            default -> "widget text";
        };
    }

    // The template's owner by the UiBinder convention: the class of the same name in the same package.
    private static String owner(final String path) {
        if (!path.endsWith(SUFFIX)) {
            return null;
        }
        final String stripped = SOURCE_ROOT.matcher(path).replaceFirst("");
        if (stripped.equals(path)) {
            return null;
        }
        return stripped.substring(0, stripped.length() - SUFFIX.length()).replace('/', '.');
    }

    static SAXParser parser() throws SAXException {
        try {
            // The JDK's own parser, whatever else is on the classpath.
            final SAXParserFactory factory = SAXParserFactory.newDefaultInstance();
            factory.setNamespaceAware(true);
            factory.setValidating(false);
            factory.setXIncludeAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            final SAXParser parser = factory.newSAXParser();
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return parser;
        } catch (final ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String firstLine(final String message) {
        if (message == null) {
            return "no message";
        }
        final int end = message.indexOf('\n');
        return end < 0
                ? message
                : message.substring(0, end);
    }
}
