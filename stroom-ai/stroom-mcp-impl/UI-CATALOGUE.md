# UI catalogue and the `ui_lookup` tool

## What it is for

An agent that guides a user through Stroom has to name things on screen: "open the
**Data** tab", "click **Upload**", "in **Main Menu > Security** choose **Credentials
Manager**". Without a source for those names a model guesses, and the guesses often name
menus, tabs and buttons that do not exist.

`ui_lookup` gives the agent the names the UI actually uses. It is the agent's eyes on the
interface. It does not decide what to build or in what order, because the other built-in
tools and guides own that. It only says what a control is called, what kind of control it
is, which class shows it, its tooltip and icon, and where in the source the label comes
from.

## The requirement: no manual maintenance

Stroom has many maintainers. A catalogue that someone has to update by hand when the UI
changes becomes nobody's job, and it slowly goes stale. So the rule is:

> The catalogue is 100% generated from source at build time. Anything that cannot be
> generated mechanically and correctly is left out.

The design follows from that rule:
- **No hand-written content ships.** There are no curated entries, no alias lists, no
  hand-written screen names or conditions, and no manually recorded browser checks.
- **Omission over guessing.** When the generator cannot read a label with certainty, it
  skips the label and counts the skip by reason. It never invents or infers the text.
- **The build does the work.** Every build regenerates the catalogue from the source it is
  building. A UI change shows up in the next build without anyone touching the catalogue.

## How it works

### Generation (build time)

- **Source set:** `uiCatalogueGenerator`, in this module. It uses only the JDK and does not
  ship in the application.
  - Java is parsed with the JDK compiler tree API (`JavacTask.parse`, `com.sun.source.tree`,
    `SourcePositions`). It needs no symbol resolution and no extra dependency.
  - `*.ui.xml` templates are read with the JDK SAX parser. DTD loading, external entities
    and network access are disabled.
- **Gradle task:** `generateUiCatalogue`.
  - Inputs: the client sources (`stroom-core-client`, `stroom-core-client-widget`,
    `stroom-core-shared`, `stroom-statistics-client`). Paths are compared relatively, and
    the generator itself is also an input.
  - Output: `build/generated/ui-catalogue/.../ui-catalogue.json`, which is never checked
    in.
  - `processResources` depends on the task through the main resource source set, so the
    JSON is packaged in the jar, and from there in the distribution and image.
- **Determinism:** the same source gives a byte-identical file.
  - Entries are sorted and deduplicated by `family:owner:location:label`.
  - Paths are relative.
  - There are no timestamps or absolute paths.
  - Gradle's up-to-date check reruns the task only when its inputs change.

### What is extracted

Only constructs whose meaning is mechanical:

| Family | Read from | Location given as |
|---|---|---|
| menu | `addMenuItem(<MenuKey>, <*MenuItem.Builder chain>)` with a literal `.text` | menu path from the `MenuKey` parents, e.g. `Main Menu > Security` |
| toolbar | `SvgPresets` constants (title and `SvgImage`) passed to `add`/`addButton`/`create` | the class that adds the button |
| tab | `new TabDataImpl("...")`, or a same-class `static final String` constant | the presenter class |
| template | label/text/title attributes in `*.ui.xml`, on an allowlist of widgets keyed by **full class** (package and name) | the widget element in the template's view |
| document type | `new DocumentType(group, type, "Display", icon)`, with the group's display name from its enum literal | its group |
| app permission | `AppPermission` enum display names | the enum |

Every entry carries `label`, `kind`, `owner` (fully qualified class), `location`,
`tooltip` and `icon` where the construct states them, and `source` (relative file:line).

### What is deliberately left out

Each of these would need a person or a heuristic, which the rule forbids:

- **Screen names.** The owner is the class that adds the control. That is a code fact, not
  a verified on-screen place, so the tool says "owner", not "screen". A human-readable
  screen or menu path is given only where the code states it as a literal, such as a menu
  key path or a tab name.
- **Visibility conditions and permissions.** These live in surrounding code, such as
  `if (hasAppPermission(...))`, negations and compound guards. Inferring them from that
  code could be wrong, so none are emitted.
- **Ambiguous or overridden setters.** The generator skips a menu item that sets its text,
  icon or menu more than once, a toolbar button whose title is changed, made dynamic or
  overridden by another call, and text built at runtime.
- **Widgets not on the allowlist.** A widget is skipped when its package is not allowed,
  even if its short name matches an allowed one. `InlineSvgButton` text is not read,
  because nothing proves it shows next to the icon.
- **Templates with no class of the same name**, because they have no reliable owner.

Every skip is counted by reason in the catalogue's provenance (`skipped_counts`), so
coverage is reported honestly and not inflated. When the tree was last measured, the
generator found 1278 entries and skipped 145. The largest single reason was 101 toolbar
buttons whose title may be set elsewhere.

### Lookup (run time)

`ui_lookup` is read-only. It searches the bundled JSON and calls nothing in Stroom.
- It returns at most 8 matches. When more match, it says how many were left out.
- It marks an answer `ambiguous` when the matches are in more than one place, judged over
  all matches before the cap.
- Its answer is hard-capped in size, measured on the actual serialised payload. Provenance
  is cut, and then replaced, before the limit could be exceeded.
- Every answer says that a control the catalogue does not list is **unknown, not
  absent**.

### The full guide

The same catalogue is rendered as the guide `stroom://guide/ui`, about 250 KB. It is
a bulk resource that a caller reads on purpose. The resource entry and the guide header
both say so and give the size. `tools/list` does not embed it. `get_instructions` with
`guide=ui` also returns it whole, as an explicit choice by the caller:
- In the Stroom AI chat, large tool results are saved to the workspace and replaced with a
  stub.
- An external MCP client gets the whole guide in that one reply.

Normal callers should use `ui_lookup`. If a real client fails on the size, the fix belongs
at the shared guide output boundary, with evidence, not as a UI-specific special case.

## Provenance

The catalogue records:
- `source_fingerprint`: sha256 over the sorted relative paths and contents of every input
  file
- the number of files read
- unparsed files and skipped templates
- entry counts per family, and skip counts per reason

It does **not** claim a git commit. A build does not always have one, and the running
Stroom version is not a source commit. The tool also reports the running build identity,
labelled as such.

## How it stays correct without manual upkeep

- **Generated on every build** from the source being built, so it cannot drift from that
  build.
- **Extractor tests** use small fixtures to check each supported pattern, positive and
  negative:
  - unrelated `.text` calls
  - dynamic text
  - duplicate simple names
  - multi-line and escaped literals
  - repeated setters
  - foreign widgets with the same short name
  - an XML external entity attempt
- **Determinism test:** the same input gives the same bytes, and a changed input gives a
  changed fingerprint.
- **Real-tree anchors.** These fail if a refactor breaks a supported extractor:
  - the Feed editor's tabs
  - the Upload toolbar button
  - the Credentials Manager menu item

  When an anchor fails, the fix is to repair the extractor, not to patch a label by hand.
- **Packaging and regeneration were checked by hand on this branch, not by a CI test:**
  - a clean build put the JSON in the jar, with the same hash as the generated file
  - adding a source file regenerated the catalogue with the new label
  - removing it restored identical bytes

Nobody edits the catalogue. When the UI changes, the next build reflects it. When a new
kind of control should be covered, someone extends an extractor with a test, which is a
normal code change.

## Known limits and open items

- Coverage is limited to the supported constructs. Labels set dynamically, or through
  patterns no extractor recognises, are absent and counted as skipped.
- `owner` is a class, not a guaranteed screen. Conditions and permissions are not included.
- The full guide is about 250 KB. It is fine as an opt-in read, and its effect on external
  clients is not measured.
- Not run in this work:
  - the database-backed upload integration tests (they need MySQL)
  - a full distribution and image build in CI
  - a live smoke test of tool discovery, a call and a guide read on a running Stroom (to
    be reported separately from pod health)
- The earlier hand-curated catalogue and its manual browser evidence were removed from the
  shipped output. They do not meet the no-manual-process rule.
