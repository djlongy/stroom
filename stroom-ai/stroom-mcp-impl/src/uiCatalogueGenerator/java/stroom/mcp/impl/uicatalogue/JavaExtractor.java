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

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.lang.model.element.Modifier;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/// Reads labels from Java source with the JDK's compiler tree API: parsed only, no symbols attributed. Each
/// extractor reads one construct whose label is a literal in the source; anything built at run time is skipped.
/// Names are resolved only for the constants the extractors need (menu keys, presets, groups), through the file's
/// imports or package, so two classes of the same simple name are never confused.
final class JavaExtractor {

    static final String MENU_KEYS_TYPE = "stroom.widget.menu.client.presenter.MenuKey";
    static final String SVG_PRESETS = "stroom.svg.client.SvgPresets";
    static final String SVG_IMAGE = "stroom.svg.shared.SvgImage";
    static final String DOCUMENT_TYPE_GROUP = "stroom.docstore.shared.DocumentTypeGroup";
    static final String APP_PERMISSION = "stroom.security.shared.AppPermission";
    static final String TAB_DATA_IMPL = "stroom.widget.tab.client.presenter.TabDataImpl";
    static final String DOCUMENT_TYPE = "stroom.docstore.shared.DocumentType";
    /// The menu item builders whose `.text(...)` is the item's label.
    static final Set<String> MENU_ITEM_TYPES = Set.of(
            "stroom.widget.menu.client.presenter.IconMenuItem",
            "stroom.widget.menu.client.presenter.SimpleMenuItem",
            "stroom.widget.menu.client.presenter.IconParentMenuItem",
            "stroom.widget.menu.client.presenter.KeyedParentMenuItem");
    /// The menu item builder setters read; one set twice in a chain leaves the item out.
    static final Set<String> MENU_SETTERS = Set.of("text", "icon", "menuKey");
    /// The methods that put a preset's button on a view.
    static final Set<String> ADDS_BUTTON = Set.of("add", "addButton", "create");

    private final List<Unit> units = new ArrayList<>();
    private final Set<String> knownClasses = new HashSet<>();
    private final List<String> unparsed = new ArrayList<>();
    private final Map<String, Integer> skips = new TreeMap<>();

    // The constants the extractors resolve, by qualified name (pkg.Class.NAME).
    private final Map<String, String> menuKeys = new HashMap<>();
    private final Map<String, Preset> presets = new HashMap<>();
    private final Map<String, String> groups = new HashMap<>();

    record Preset(String title, String image) {

    }

    // A parsed file.
    record Unit(CompilationUnitTree tree, String path, String packageName, Map<String, String> imports,
                Map<String, String> staticImports) {

    }

    // A menu item added to a menu key, and the key its children go under, if it is a parent.
    private record MenuItem(String menuKey, String text, String icon, String childKey, String owner, String path,
                            int line) {

    }

    private final SourcePositions positions;
    private final Trees trees;

    /// Parses the files.
    ///
    /// @param root  The repository root, which paths in the output are relative to.
    /// @param files The Java files to read.
    JavaExtractor(final Path root, final List<Path> files) {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (final StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null,
                StandardCharsets.UTF_8)) {
            final JavacTask task = (JavacTask) compiler.getTask(null, fileManager, diagnostics,
                    List.of("-proc:none", "-XDshould-stop.at=PARSE"), null,
                    fileManager.getJavaFileObjectsFromPaths(files));
            final Iterable<? extends CompilationUnitTree> parsed = task.parse();
            trees = Trees.instance(task);
            positions = trees.getSourcePositions();
            final Set<String> failed = new TreeSet<>();
            diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR && d.getSource() != null)
                    .forEach(d -> failed.add(relative(root, Path.of(d.getSource().toUri()))));
            for (final CompilationUnitTree tree : parsed) {
                final String path = relative(root, Path.of(tree.getSourceFile().toUri()));
                if (failed.contains(path)) {
                    unparsed.add(path);
                    continue;
                }
                final String pkg = tree.getPackageName() == null
                        ? ""
                        : tree.getPackageName().toString();
                final Map<String, String> imports = new HashMap<>();
                final Map<String, String> staticImports = new HashMap<>();
                for (final ImportTree imp : tree.getImports()) {
                    final String name = imp.getQualifiedIdentifier().toString();
                    final String simple = name.substring(name.lastIndexOf('.') + 1);
                    if (!"*".equals(simple)) {
                        (imp.isStatic() ? staticImports : imports).put(simple, name);
                    }
                }
                units.add(new Unit(tree, path, pkg, imports, staticImports));
                for (final Tree type : tree.getTypeDecls()) {
                    if (type instanceof final ClassTree cls) {
                        knownClasses.add(qualify(pkg, cls.getSimpleName().toString()));
                    }
                }
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        units.sort((a, b) -> a.path().compareTo(b.path()));
        unparsed.sort(String::compareTo);
        readConstants();
    }

    /// @return The files that didn't parse, which nothing was read from.
    List<String> unparsed() {
        return unparsed;
    }

    /// @return How many constructs each reason left out, after [#extract()].
    Map<String, Integer> skips() {
        return skips;
    }

    /// @return The top-level classes parsed, fully qualified.
    Set<String> knownClasses() {
        return knownClasses;
    }

    /// @return Every label the extractors read.
    List<CatalogueEntry> extract() {
        final List<CatalogueEntry> entries = new ArrayList<>();
        final List<MenuItem> menuItems = new ArrayList<>();
        for (final Unit unit : units) {
            new Scanner(unit, entries, menuItems).scan(unit.tree(), null);
        }
        // A key's path: the parent items that declare it as their children's key, up to a key no item declares.
        final Map<String, List<MenuItem>> parentsOf = new HashMap<>();
        menuItems.stream()
                .filter(item -> item.childKey() != null)
                .forEach(item -> parentsOf.computeIfAbsent(item.childKey(), key -> new ArrayList<>()).add(item));
        for (final MenuItem item : menuItems) {
            entries.add(new CatalogueEntry("menu", item.text(), "menu item", item.owner(),
                    menuPath(item.menuKey(), parentsOf, new HashSet<>()), null, item.icon(), null, item.path(),
                    item.line()));
        }
        return entries;
    }

    // The menu a key's items are in: the key's name, or, when exactly one item declares the key as its children's
    // and that item's own label is known, that item's path and label.
    private String menuPath(final String key, final Map<String, List<MenuItem>> parentsOf, final Set<String> seen) {
        final List<MenuItem> parents = parentsOf.getOrDefault(key, List.of());
        if (parents.size() != 1 || !seen.add(key)) {
            return menuKeys.get(key);
        }
        final MenuItem parent = parents.getFirst();
        return menuPath(parent.menuKey(), parentsOf, seen) + " > " + parent.text();
    }

    private void readConstants() {
        for (final Unit unit : units) {
            for (final Tree type : unit.tree().getTypeDecls()) {
                if (!(type instanceof final ClassTree cls)) {
                    continue;
                }
                final String owner = qualify(unit.packageName(), cls.getSimpleName().toString());
                for (final Tree member : cls.getMembers()) {
                    if (!(member instanceof final VariableTree field) || field.getInitializer() == null) {
                        continue;
                    }
                    final String name = owner + "." + field.getName();
                    final ExpressionTree init = field.getInitializer();
                    if (init instanceof final NewClassTree created
                        && MENU_KEYS_TYPE.equals(resolveType(unit, created.getIdentifier()))
                        && created.getArguments().size() == 1) {
                        final String text = literal(created.getArguments().getFirst());
                        if (text != null) {
                            menuKeys.put(name, text);
                        }
                    }
                    if (SVG_PRESETS.equals(owner) && init instanceof final MethodInvocationTree call
                        && call.getArguments().size() == 2) {
                        final String image = resolveConstant(unit, call.getArguments().get(0), SVG_IMAGE);
                        final String title = literal(call.getArguments().get(1));
                        if (image != null && title != null) {
                            presets.put(name, new Preset(title, image));
                        }
                    }
                }
                if (DOCUMENT_TYPE_GROUP.equals(owner)) {
                    for (final Tree member : cls.getMembers()) {
                        if (member instanceof final VariableTree constant
                            && constant.getInitializer() instanceof final NewClassTree args
                            && args.getArguments().size() >= 2) {
                            final String display = literal(args.getArguments().get(1));
                            if (display != null) {
                                groups.put(owner + "." + constant.getName(), display);
                            }
                        }
                    }
                }
            }
        }
    }

    // Reads the labels in one file.
    private final class Scanner extends TreePathScanner<Void, Void> {

        private final Unit unit;
        private final List<CatalogueEntry> entries;
        private final List<MenuItem> menuItems;
        private final List<String> owners = new ArrayList<>();

        private Scanner(final Unit unit, final List<CatalogueEntry> entries, final List<MenuItem> menuItems) {
            this.unit = unit;
            this.entries = entries;
            this.menuItems = menuItems;
        }

        private String owner() {
            return owners.getLast();
        }

        @Override
        public Void visitClass(final ClassTree cls, final Void unused) {
            final String name = cls.getSimpleName().toString();
            if (name.isEmpty()) {
                // An anonymous class belongs to the class it is in.
                return super.visitClass(cls, unused);
            }
            owners.add(owners.isEmpty()
                    ? qualify(unit.packageName(), name)
                    : owner() + "." + name);
            if (APP_PERMISSION.equals(owner())) {
                readEnumDisplayNames(cls);
            }
            super.visitClass(cls, unused);
            owners.removeLast();
            return null;
        }

        @Override
        public Void visitNewClass(final NewClassTree created, final Void unused) {
            final String type = resolveType(unit, created.getIdentifier());
            if (TAB_DATA_IMPL.equals(type) && !created.getArguments().isEmpty()) {
                final ExpressionTree arg = created.getArguments().getFirst();
                final String text = literalOrOwnConstant(arg);
                if (text != null) {
                    add("tab", text, "tab", null, null, null, null, arg);
                } else {
                    skip("tab: name is not a literal or a constant of the same class");
                }
            }
            if (DOCUMENT_TYPE.equals(type) && created.getArguments().size() == 4) {
                final List<? extends ExpressionTree> args = created.getArguments();
                final String display = literal(args.get(2));
                if (display != null) {
                    final String group = groups.get(resolveConstant(unit, args.get(0), DOCUMENT_TYPE_GROUP));
                    add("document type", display, "document type display name", null, null,
                            imageName(resolveConstant(unit, args.get(3), SVG_IMAGE)), group, args.get(2));
                } else {
                    skip("document type: display name is not a literal");
                }
            }
            return super.visitNewClass(created, unused);
        }

        @Override
        public Void visitMethodInvocation(final MethodInvocationTree call, final Void unused) {
            final String method = methodName(call);
            if ("addMenuItem".equals(method) && call.getArguments().size() == 2) {
                readMenuItem(call);
            }
            if (ADDS_BUTTON.contains(method)) {
                for (final ExpressionTree arg : call.getArguments()) {
                    readPresetButton(arg);
                }
            }
            return super.visitMethodInvocation(call, unused);
        }

        // addMenuItem(<menu key constant>, new <menu item type>.Builder()...text("<literal>")...build())
        private void readMenuItem(final MethodInvocationTree call) {
            final String key = resolveAnyConstant(unit, call.getArguments().get(0));
            if (key == null || !menuKeys.containsKey(key)) {
                skip("menu: first argument is not a menu key constant");
                return;
            }
            final Map<String, List<ExpressionTree>> setters = new HashMap<>();
            ExpressionTree link = localFinal(call.getArguments().get(1));
            while (link instanceof final MethodInvocationTree step
                   && step.getMethodSelect() instanceof final MemberSelectTree select) {
                final String name = select.getIdentifier().toString();
                if (MENU_SETTERS.contains(name) && step.getArguments().size() == 1) {
                    setters.computeIfAbsent(name, k -> new ArrayList<>()).add(step.getArguments().getFirst());
                }
                link = select.getExpression();
            }
            if (!(link instanceof final NewClassTree created)) {
                skip("menu: item is not a builder chain in the call or in a final local");
                return;
            }
            if (!isMenuItemBuilder(resolveType(unit, created.getIdentifier()))) {
                skip("menu: item is not a menu item builder (a separator, say)");
                return;
            }
            // A builder keeps the last value set; rather than order the calls, an item that sets one twice is
            // left out.
            if (setters.values().stream().anyMatch(values -> values.size() > 1)) {
                skip("menu: text, icon or menuKey set more than once in one chain");
                return;
            }
            final ExpressionTree textArg = only(setters, "text");
            if (textArg == null) {
                skip("menu: no text set");
                return;
            }
            final String text = literalOrOwnConstant(textArg);
            if (text == null) {
                skip("menu: text is not a literal or a constant of the same class");
                return;
            }
            final ExpressionTree iconArg = only(setters, "icon");
            final String icon = iconArg == null
                    ? null
                    : imageName(resolveConstant(unit, iconArg, SVG_IMAGE));
            final ExpressionTree keyArg = only(setters, "menuKey");
            final String childKey = keyArg == null
                    ? null
                    : resolveAnyConstant(unit, keyArg);
            menuItems.add(new MenuItem(key, text, icon, menuKeys.containsKey(childKey)
                    ? childKey
                    : null, owner(), unit.path(), line(textArg)));
        }

        // The one argument a setter was given in a chain, or null if it wasn't set.
        private static ExpressionTree only(final Map<String, List<ExpressionTree>> setters, final String name) {
            final List<ExpressionTree> args = setters.get(name);
            return args == null
                    ? null
                    : args.getFirst();
        }

        // The initialiser of a final local variable of the enclosing method, else the expression itself.
        private ExpressionTree localFinal(final ExpressionTree tree) {
            if (!(tree instanceof final IdentifierTree id)) {
                return tree;
            }
            TreePath path = getCurrentPath();
            while (path != null && !(path.getLeaf() instanceof MethodTree)) {
                path = path.getParentPath();
            }
            if (path == null) {
                return tree;
            }
            final List<VariableTree> found = new ArrayList<>();
            final List<ExpressionTree> assigned = new ArrayList<>();
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitVariable(final VariableTree variable, final Void unused) {
                    if (variable.getName().contentEquals(id.getName())) {
                        found.add(variable);
                        if (variable.getInitializer() != null) {
                            assigned.add(variable.getInitializer());
                        }
                    }
                    return super.visitVariable(variable, unused);
                }

                @Override
                public Void visitAssignment(final AssignmentTree assignment, final Void unused) {
                    if (id.getName().toString().equals(name(assignment.getVariable()))) {
                        assigned.add(assignment.getExpression());
                    }
                    return super.visitAssignment(assignment, unused);
                }
            }.scan(path, null);
            // A final local assigned once, where it is declared or later.
            return found.size() == 1
                   && found.getFirst().getModifiers().getFlags().contains(Modifier.FINAL)
                   && assigned.size() == 1
                    ? assigned.getFirst()
                    : tree;
        }

        // A preset passed to a method that puts its button on a view: its title is the button's tooltip, unless
        // the class sets the title of what the call returned, which is then not known here. Only `.title("...")`,
        // once, may follow the preset.
        private void readPresetButton(final ExpressionTree arg) {
            final List<ExpressionTree> titles = new ArrayList<>();
            boolean other = false;
            ExpressionTree preset = arg;
            while (preset instanceof final MethodInvocationTree step
                   && step.getMethodSelect() instanceof final MemberSelectTree select) {
                if ("title".contentEquals(select.getIdentifier()) && step.getArguments().size() == 1) {
                    titles.add(step.getArguments().getFirst());
                } else {
                    other = true;
                }
                preset = select.getExpression();
            }
            final Preset found = presets.get(resolveConstant(unit, preset, SVG_PRESETS));
            if (found == null) {
                return;
            }
            if (other) {
                skip("toolbar: preset changed by a call other than title");
                return;
            }
            if (titles.size() > 1) {
                skip("toolbar: title set more than once in one chain");
                return;
            }
            final ExpressionTree titleArg = titles.isEmpty()
                    ? null
                    : titles.getFirst();
            String title = literal(titleArg);
            if (titleArg != null && title == null) {
                skip("toolbar: title is not a literal");
                return;
            }
            if (title == null) {
                // Kept when the button is not used further, or kept in a variable whose title is never set.
                final Tree parent = getCurrentPath().getParentPath().getLeaf();
                final String variable = assignedTo(getCurrentPath());
                if (!(parent instanceof ExpressionStatementTree) && (variable == null || setsTitle(variable))) {
                    skip("toolbar: button's title may be set elsewhere");
                    return;
                }
                title = found.title();
            }
            add("toolbar", title, "toolbar button tooltip", null, title, imageName(found.image()), null,
                    titleArg != null
                            ? titleArg
                            : preset);
        }

        // The field or variable the current call's result is assigned to, if it is.
        private String assignedTo(final TreePath path) {
            final Tree parent = path.getParentPath().getLeaf();
            if (parent instanceof final AssignmentTree assignment) {
                return name(assignment.getVariable());
            }
            if (parent instanceof final VariableTree variable) {
                return variable.getName().toString();
            }
            return null;
        }

        // Whether the file calls <variable>.setTitle(...) anywhere.
        private boolean setsTitle(final String variable) {
            final boolean[] found = {false};
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitMethodInvocation(final MethodInvocationTree call, final Void unused) {
                    if (call.getMethodSelect() instanceof final MemberSelectTree select
                        && "setTitle".contentEquals(select.getIdentifier())
                        && variable.equals(name(select.getExpression()))) {
                        found[0] = true;
                    }
                    return super.visitMethodInvocation(call, unused);
                }
            }.scan(unit.tree(), null);
            return found[0];
        }

        // An enum's constants whose first argument is a literal: AppPermission's display names.
        private void readEnumDisplayNames(final ClassTree cls) {
            for (final Tree member : cls.getMembers()) {
                if (member instanceof final VariableTree constant
                    && constant.getInitializer() instanceof final NewClassTree args
                    && !args.getArguments().isEmpty()) {
                    final ExpressionTree first = args.getArguments().getFirst();
                    final String display = literal(first);
                    if (display != null) {
                        add("app permission", display, "application permission display name", null, null, null,
                                null, first);
                    } else {
                        skip("app permission: display name is not a literal");
                    }
                }
            }
        }

        // A literal, or a static final String constant of this file's class whose initialiser is a literal.
        private String literalOrOwnConstant(final ExpressionTree tree) {
            final String text = literal(tree);
            if (text != null || !(tree instanceof final IdentifierTree id)) {
                return text;
            }
            for (final Tree type : unit.tree().getTypeDecls()) {
                if (type instanceof final ClassTree cls) {
                    for (final Tree member : cls.getMembers()) {
                        if (member instanceof final VariableTree field
                            && field.getName().contentEquals(id.getName())
                            && field.getModifiers().getFlags().containsAll(Set.of(Modifier.STATIC, Modifier.FINAL))) {
                            return literal(field.getInitializer());
                        }
                    }
                }
            }
            return null;
        }

        private void skip(final String reason) {
            skips.merge(reason, 1, Integer::sum);
        }

        private void add(final String family, final String label, final String kind, final String location,
                         final String tooltip, final String icon, final String group, final Tree at) {
            entries.add(new CatalogueEntry(family, label, kind, owner(), location, tooltip, icon, group,
                    unit.path(), line(at)));
        }

        private int line(final Tree tree) {
            final LineMap lines = unit.tree().getLineMap();
            return (int) lines.getLineNumber(positions.getStartPosition(unit.tree(), tree));
        }
    }

    // The type a name refers to: a single-type import, a class of the same package that was parsed, or the name
    // itself if it is qualified.
    String resolveType(final Unit unit, final Tree name) {
        if (name instanceof final IdentifierTree id) {
            final String simple = id.getName().toString();
            final String imported = unit.imports().get(simple);
            if (imported != null) {
                return imported;
            }
            final String local = qualify(unit.packageName(), simple);
            return knownClasses.contains(local)
                    ? local
                    : null;
        }
        if (name instanceof final MemberSelectTree select) {
            final String outer = resolveType(unit, select.getExpression());
            return outer != null
                    ? outer + "." + select.getIdentifier()
                    : select.toString();
        }
        return null;
    }

    // A constant of the given class, as Class.NAME, a static import, or (in that class) NAME; as pkg.Class.NAME.
    String resolveConstant(final Unit unit, final ExpressionTree tree, final String type) {
        final String resolved = resolveAnyConstant(unit, tree);
        return resolved != null && resolved.startsWith(type + ".")
                ? resolved
                : null;
    }

    private String resolveAnyConstant(final Unit unit, final ExpressionTree tree) {
        if (tree instanceof final MemberSelectTree select) {
            final String type = resolveType(unit, select.getExpression());
            return type == null
                    ? null
                    : type + "." + select.getIdentifier();
        }
        if (tree instanceof final IdentifierTree id) {
            return unit.staticImports().get(id.getName().toString());
        }
        return null;
    }

    /// @param tree An expression.
    /// @return Its value if it is a string literal, or string literals joined with `+`; else null.
    static String literal(final Tree tree) {
        if (tree instanceof final LiteralTree literal && literal.getValue() instanceof final String text) {
            return text;
        }
        if (tree instanceof final BinaryTree binary && binary.getKind() == Tree.Kind.PLUS) {
            final String left = literal(binary.getLeftOperand());
            final String right = literal(binary.getRightOperand());
            return left != null && right != null
                    ? left + right
                    : null;
        }
        if (tree != null && tree.getKind() == Tree.Kind.PARENTHESIZED) {
            return literal(((com.sun.source.tree.ParenthesizedTree) tree).getExpression());
        }
        return null;
    }

    private static boolean isMenuItemBuilder(final String type) {
        return type != null
               && type.endsWith(".Builder")
               && MENU_ITEM_TYPES.contains(type.substring(0, type.length() - ".Builder".length()));
    }

    private static String methodName(final MethodInvocationTree call) {
        final ExpressionTree select = call.getMethodSelect();
        if (select instanceof final MemberSelectTree member) {
            return member.getIdentifier().toString();
        }
        return select instanceof final IdentifierTree id
                ? id.getName().toString()
                : null;
    }

    private static String name(final ExpressionTree tree) {
        if (tree instanceof final IdentifierTree id) {
            return id.getName().toString();
        }
        if (tree instanceof final MemberSelectTree select
            && select.getExpression() instanceof final IdentifierTree self
            && "this".contentEquals(self.getName())) {
            return select.getIdentifier().toString();
        }
        return null;
    }

    private static String imageName(final String constant) {
        return constant == null
                ? null
                : "SvgImage." + constant.substring(constant.lastIndexOf('.') + 1);
    }

    private static String qualify(final String pkg, final String simple) {
        return pkg.isEmpty()
                ? simple
                : pkg + "." + simple;
    }

    static String relative(final Path root, final Path file) {
        return root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString()
                .replace('\\', '/');
    }
}
