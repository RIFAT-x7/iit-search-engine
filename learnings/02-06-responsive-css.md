# Phase 2.4C: Responsive CSS for the browser search interface

**Student:** Rifat Al Maruf (BSSE1705)

**Course:** SPL-1, Institute of Information Technology, University of Dhaka

**Record date:** October 28, 2026

**Project:** IIT Search Engine

## 1. Objective, checkpoint, and separation of responsibilities

Phase 2.4C styled the already functional browser search client. The implementation
commit is `8b0a38906495fa850244a8e66e36bd5ea2c4e4eb` on `feat/browser-client`.
Its parent is the JavaScript integration commit
`665509f724a3b68883bc6e504f4b2bf54bb5dee5`; the native HTML milestone was
`44d869bc1a98701b8f7546cf33e380ae89aaca96`.

CSS means Cascading Style Sheets. It describes presentation: dimensions,
spacing, fonts, colors, borders, and conditional layout. The actual stylesheet
is [web/styles.css](../web/styles.css). It does not validate query text, fetch
JSON, read document files, compute TF-IDF, or sort results. Those responsibilities
remain in the existing JavaScript and Java layers.

The goal was a readable academic interface using plain CSS. No framework,
external font download, theme switch, animation system, or new search feature
was introduced. The CSS commit did not modify app.js or the IR core.

## 2. External stylesheet and Java delivery

The actual HTML reference is:

```html
    <link rel="stylesheet" href="/styles.css">
```

`rel` identifies the resource as a stylesheet. `href` identifies its root-relative
URL on the same origin as the page. The browser retrieves it separately from
the HTML, parses its rules, and applies matching declarations to elements.
Linking CSS does not mean embedding it inside JavaScript.

The Java server loads one trusted file at startup:

```java
String stylesCss = Files.readString(Path.of("web", "styles.css"), StandardCharsets.UTF_8);
```

Exact GET `/styles.css` sends that cached text using
`text/css; charset=utf-8`. POST on the same known path receives 405 and
`Allow: GET`. Unknown or suffixed paths still receive 404. No request path
becomes a filesystem path, and no generic file server was added.

Run SearchServer from the repository root so the relative file exists at the
expected location. Missing or unreadable CSS can prevent startup. Editing a
stylesheet while this server runs does not refresh its cached string; restart
the owned server to load the change, and reload the browser as appropriate.

## 3. Selectors, declarations, cascade, and inheritance

Actual first rule:

```css
* {
    box-sizing: border-box;
}
```

A CSS rule has a selector followed by a brace-enclosed declaration block.
Each declaration has a property, colon, value, and usually a terminating
semicolon. The universal selector `*` matches elements. `body`, `main`, and
`li` are element selectors. `.search-controls` matches a class; `#search-status`
matches an ID. The names must agree with the HTML. A comma-separated selector
list, as in `input, button`, applies a shared declaration block to both types.

### 3.1 Specificity and the cascade in this file

For applicable declarations at the same origin and importance, selector
specificity helps resolve conflicts. An ID selector is more specific than a
class or pseudo-class selector; an element selector is less specific than
those. Universal selectors add no specificity. `::marker` is a pseudo-element;
it targets a generated list marker rather than another HTML class.

When competing declarations have equal precedence and specificity, the later
one wins. The later media query repeats `main` and `.search-controls`, so its
matching declarations override the earlier values. The query condition does
not itself increase specificity. No `!important` rules are used in this file.

`button:hover:not(:disabled)` matches a hovered button only when it is not
disabled. Its exclusion matters: a disabled button retains its muted style
instead of looking active under the pointer. Merely relying on source order
would be less clear than expressing that condition.

### 3.2 Inheritance

Text properties such as font family, color, and line height can be inherited
from ancestors. Borders, margins, and padding do not normally pass down in
the same way. The controls use `font: inherit` to adopt surrounding typography
instead of retaining an unrelated browser-default control font.

CSS inheritance differs from Java class inheritance. It is about computed
property values flowing through the document tree, not subclass methods.

## 4. Body typography, colors, and units

Actual body rule:

```css
body {
    margin: 0;
    padding: 1.5rem;
    background: #f1f5f9;
    color: #1e293b;
    font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    line-height: 1.6;
}
```

The margin reset removes the browser's default body margin. Padding gives the
page content space from the viewport edges. The pale background and dark text
establish a neutral base rather than depending on a background image.

Hex colors use pairs of base-16 digits for red, green, and blue. For example,
`#ffffff` is white. A color's name or appearance alone does not establish
readability; foreground/background contrast must be considered together.

The font stack asks for installed system UI fonts and ends with the generic
sans-serif family. A browser uses an available choice; rendering can differ
between operating systems. No `@font-face`, external stylesheet import, or CDN
is present. CSS does not download Segoe UI merely because its name appears.

Unitless `line-height: 1.6` gives lines space proportional to their font size.
It remains useful when text sizes change. The h1 separately uses 1.25 to keep
the large heading compact.

### 4.1 rem, em, percentages, and pixels

`rem` lengths in ordinary declarations relate to the root font size. At a
16px root size, 1.5rem is 24px and 3rem is 48px. `em` relates to the element's
font size; the status paragraph's `min-height: 1.6em` reserves text-sized space.
Percentages such as width 100% refer to the relevant containing width here.
Pixels are used for thin borders and the focus outline.

These are source-based calculations, not browser measurements. The application
does not force the root font size to 16px. Relative units allow user font
settings to influence the layout. Minimum heights allow content to grow;
they are not fixed heights that deliberately clip additional lines.

## 5. Box model, centered container, and headings

Actual main rule:

```css
main {
    max-width: 52rem;
    margin: 3rem auto;
    padding: 2.5rem;
    background: #ffffff;
    border: 1px solid #cbd5e1;
    border-top: 5px solid #1d4ed8;
    border-radius: 0.75rem;
}
```

The box model distinguishes content, padding, border, and margin. Padding is
inside the border; margin is outside it. `border-box` makes specified widths
include padding and border, which prevents a width-100% input from gaining
extra width simply because it also has padding.

`max-width: 52rem` caps the container but still permits it to shrink with its
available width. `margin: 3rem auto` supplies top/bottom spacing and automatic
horizontal margins. When the block reaches its width cap, those automatic
margins share the remaining horizontal space, centering it.

`border` is a shorthand for width, style, and color. `border-top` follows it
and replaces the top edge with a thicker blue line. `border-radius` rounds
corners; it does not change heading semantics or make an element interactive.

The h1 rule removes top margin, leaves 1.75rem below it, sets dark blue, and
uses a 2rem font. The h2 uses a smaller 1.25rem font and 0.75rem bottom margin.
Those visual differences support the existing h1/h2 document hierarchy.
The label is a block with 0.5rem below it and font weight 600, keeping its text
above the search controls. The label remains a real HTML label.

Spacing shorthands depend on the number of values. `padding: 0.75rem 1rem`
means vertical then horizontal padding. `margin: 0 0 1rem` means top,
horizontal, then bottom. Confusing these positions can create unexplained
gaps; expanding the values mentally is a useful debugging habit.

## 6. Search controls and flexbox

The HTML adds only one wrapper, `<div class="search-controls">`, around the
existing input and submit button. It remains inside the native form and does
not change the IDs or names used by the script or backend.

Actual layout and shared control rules:

```css
.search-controls {
    display: flex;
    gap: 0.75rem;
}

input,
button {
    min-height: 3rem;
    padding: 0.75rem 1rem;
    border-radius: 0.375rem;
    font: inherit;
}

input {
    flex: 1;
    min-width: 0;
    width: 100%;
    border: 1px solid #64748b;
    background: #ffffff;
    color: #1e293b;
}
```

`display: flex` makes the wrapper a flex container. Its direct children become
flex items, arranged in a row by default. The label is outside this wrapper
and is therefore not another item in that row.

`gap` supplies spacing between items without assigning separate margins to
each child. On desktop, the button uses its content-based size, while the
input's `flex: 1` lets it grow and shrink to use the remaining space.
The common shorthand enables growth and shrinking with a zero basis in
browser implementation. The important application effect is that the input
shares available row space rather than pushing the button offscreen.

`min-width: 0` permits shrinking below the flex item's automatic minimum.
Together with border-box sizing, it helps prevent a wide intrinsic input from
forcing horizontal overflow. `width: 100%` also gives a useful full-width
control in the stacked layout. Flex sizing still adjusts its row width when
the button and gap need space.

Both controls have a minimum height of 3rem, internal padding, rounded corners,
and inherited typography. The input's visible border distinguishes its boundary
from the white container. There is no removed outline without a replacement.

## 7. Button, hover, keyboard focus, and disabled state

The base button has a blue border/background, white text, font weight 600,
and a pointer cursor. It remains a native submit button; CSS does not attach
its submit listener.

Actual interaction rules:

```css
button:hover:not(:disabled) {
    border-color: #1e40af;
    background: #1e40af;
}

input:focus-visible,
button:focus-visible {
    outline: 3px solid #1d4ed8;
    outline-offset: 3px;
}

button:disabled {
    border-color: #94a3b8;
    background: #e2e8f0;
    color: #475569;
    cursor: not-allowed;
}
```

`:hover` provides a darker pointer state. It is not a substitute for keyboard
feedback, since keyboard users do not need to hover over controls.
`:focus-visible` matches focus when the browser determines a visible indicator
is appropriate, commonly during keyboard use. The outline is 3px wide and
offset another 3px from the control. Unlike a border-width change, an outline
does not consume layout space and move adjacent content.

`:disabled` responds to the actual disabled property set by app.js during a
request. Muted colors and a not-allowed cursor communicate its temporary state.
The disabled property, not the CSS cursor, prevents ordinary activation.
CSS alone would not disable a button just because it looked grey.

The implementation does not globally suppress browser focus behavior. It also
does not implement a custom keyboard interaction model: the form retains
native Tab and submit behavior.

## 8. Results, status, and long text

The results section uses top margin, top padding, and a border to distinguish
it from the form. The status paragraph has a minimum height, bottom margin,
and neutral dark text. The reserved height reduces some movement between a
short status and an empty paragraph, but is not a promise of no layout shift.

Actual list rules:

```css
ol {
    margin: 0;
    padding-left: 1.75rem;
}

li {
    padding: 0.75rem 0.5rem;
    border-bottom: 1px solid #cbd5e1;
    overflow-wrap: anywhere;
}

li::marker {
    color: #1d4ed8;
    font-weight: 600;
}
```

The ordered list retains normal rank numbering. Left padding leaves room for
markers; each item has breathing room and a dividing line. `::marker` changes
the number's appearance without inserting fake numbers into the result text.
It does not change result order.

`overflow-wrap: anywhere` permits otherwise unbreakable text to wrap when
necessary. It is also applied to the status paragraph. A long filename can
therefore fit within the container instead of widening it. This affects visual
line breaks, not the underlying filename string or search behavior.

The selectors `section`, `ol`, and `li` are deliberately broad for this small
page. If future pages add unrelated sections or lists, narrower selectors may
be useful. No such additional components are implemented now.

## 9. Responsive media query: desktop to narrow screens

Actual complete query:

```css
@media (max-width: 40rem) {
    body {
        padding: 1rem;
    }

    main {
        margin: 1rem auto;
        padding: 1.25rem;
    }

    .search-controls {
        flex-direction: column;
    }

    button {
        width: 100%;
    }
}
```

`@media` conditionally applies the declarations inside its outer braces. Here,
`max-width` tests the viewport width, not the width of the main element. At a
usual 16px initial font size, 40rem corresponds to 640px. Font-relative units
in media queries use the initial font-size value rather than a root font-size
declaration in the page. This distinction follows the
[Media Queries unit rules](https://www.w3.org/TR/mediaqueries-4/#units).

When the query matches, body and main spacing shrink, the controls' main axis
becomes vertical, and the button becomes full width. These changes do not
create a separate mobile page or detect a particular phone model. Responsive
design adapts the same document to available viewport space.

### 9.1 Worked width calculation

At a 320px viewport and a 16px root size, narrow body padding uses 16px per
side. The main box has 288px available. Subtracting 20px padding and a 1px
border on each side leaves about 246px of main content width before list
indentation. A 375px viewport similarly leaves about 301px.

This arithmetic explains the chosen rules; it is not a layout-engine result.
The user separately tested both viewport widths in Edge without horizontal
overflow. The max-width container, flex shrinking, wrapping, and stacking work
together. Setting a breakpoint alone would not fix every oversized child.

## 10. Accessibility, contrast, and verification evidence

The CSS preserves the semantic form, explicit label, required input, result
heading, ordered list, status role, and busy attribute described in the
[HTML manual](02-04-html-browser-client.md). It does not implement screen-reader
announcements by color alone; the script supplies text status updates.

Prior static calculations for the selected foreground/background pairs were:

| Pair | Calculated contrast |
| --- | --- |
| Main text / white | 14.63:1 |
| Main heading / white | 10.36:1 |
| Status text / white | 7.58:1 |
| White button text / normal blue | 6.70:1 |
| White button text / hover blue | 8.72:1 |
| Disabled text / disabled background | 6.15:1 |
| Input border / white | 4.76:1 |
| Focus outline / white | 6.70:1 |

The calculated text pairs exceed 4.5:1, and the checked input border and focus
outline exceed 3:1 against white. These pair calculations are useful evidence,
not a complete accessibility conformance claim for the application.

### 10.1 Prior Codex-run checks

Temurin JDK 25.0.3 compilation and all seven baseline IR tests passed during
Phase 2.4C implementation. HTTP checks passed for HTML linking the stylesheet,
exact CSS and JavaScript content/media types, health, ranked JSON, empty results,
validation, unknown routes, and unsupported methods with `Allow: GET`.

Static CSS checks covered balanced blocks, parentheses, quotes, and basic
declaration structure, with no external imports or resource URLs. They were
structural checks, not a complete standards validator. Static narrow-layout
review used 320, 375, and 640px calculations. HTML hooks and the app.js hash
were checked unchanged. No visual browser interaction was performed by Codex.

### 10.2 Five user-confirmed Microsoft Edge checks

| User-confirmed check | Reported outcome |
| --- | --- |
| Desktop appearance | Centered blue/neutral page, readable layout, separated results |
| Normal search | Results appeared without navigation |
| Validation and no matches | Unknown and whitespace-only queries gave correct feedback |
| Responsive layout | 375px and 320px controls stacked without horizontal overflow |
| Keyboard use | Tab navigation and visible focus indicators worked |

These are explicit user confirmations, not automated screenshot tests. They
do not establish every viewport, browser, zoom level, operating-system contrast
mode, or assistive-technology combination. The task-owned verification servers
were stopped after testing; no unrelated Java process was intentionally stopped.

### 10.3 What this documentation task reruns

Only document/source consistency, links, excerpts, and Git whitespace/scope
checks are rerun here. Compilation, baseline tests, HTTP checks, contrast
calculations, and Edge interactions are recorded prior results. No new CSS
test files, browser automation suite, or dependencies are committed by these
frontend implementation milestones.

## 11. Common mistakes, limitations, and possible future work

- Confusing padding with margin moves spacing to the wrong side of a border.
- A width of 100% with the default content-box model can grow beyond the
  intended outer width once padding and borders are added.
- A flex child can resist shrinking unless its minimum-width behavior is
  understood. `min-width: 0` addresses that issue for this input.
- Removing focus outlines without replacing them hides keyboard position.
- Styling hover alone provides no equivalent keyboard indication.
- A fixed device-size assumption ignores user font settings and window resizing.
- Declaring a font name does not prove that every operating system has that font.

The current CSS has one breakpoint, one light palette, no animation, and no
theme switching. Status/error variants share the same neutral paragraph style.
Future improvements could be considered after broader browser, zoom/reflow,
and assistive-technology testing. Such ideas are not completed features or
promised completion dates.

The server remains loopback-only. Search still uses six fictional text files,
an in-memory startup index, and Java ranking. Styling adds no PDF import, OCR,
fuzzy retrieval, document links, previews, or dynamic indexing.

## 12. Supervisor viva preparation

1. **What does CSS do here?** Presents the existing form and results; it does
   not perform the search.
2. **How does the browser obtain the stylesheet?** The HTML link requests the
   fixed `/styles.css` route.
3. **What is a declaration?** A property/value pair such as a padding setting.
4. **How do element, class, and ID selectors differ?** They match a tag name,
   a class token, or a unique document ID respectively.
5. **What does the universal selector contribute?** Border-box sizing for elements
   without adding selector specificity.
6. **What is the cascade?** The rule system that chooses applicable declarations
   when several can affect the same property.
7. **Does a media query increase selector specificity?** No; it controls whether
   the enclosed rules apply.
8. **Why use font: inherit on controls?** To adopt the surrounding page typography.
9. **How do margin and padding differ?** Margin is outside the border; padding
   is inside it.
10. **What does border-box change?** Specified widths include padding and border.
11. **How is main centered?** Its maximum width and automatic horizontal margins
    distribute remaining space.
12. **Why use max-width rather than a fixed width?** It caps reading width while
    allowing the box to shrink.
13. **What does flex: 1 do for the input?** Allows it to use and share available
    flex space through growth and shrinking.
14. **Why min-width: 0?** To let the input shrink below its automatic minimum.
15. **What does gap do?** Adds spacing between flex children.
16. **What changes at the breakpoint?** Padding shrinks and the controls stack
    vertically with a full-width button.
17. **Is 40rem always exactly 640px?** No; that assumes a 16px initial font size.
18. **Why is hover not enough?** Keyboard users need a visible focus indicator.
19. **How is focus made visible?** A 3px blue outline with 3px offset.
20. **Does a disabled-looking color disable a button?** No; the actual disabled
    property does that, while CSS communicates the state.
21. **Why exclude disabled buttons from hover?** To keep the inactive appearance
    from changing to the active hover style.
22. **How are long filenames handled?** overflow-wrap permits breaks within
    otherwise unbreakable text.
23. **What does ::marker style?** The native ordered-list number.
24. **Do contrast calculations prove full accessibility?** No; they cover selected
    pairs, while interaction and assistive-tool behavior need separate evidence.
25. **Was responsive behavior actually tested?** The user confirmed Edge checks
    at 375px and 320px; Codex separately reported static calculations.

## 13. Practice and reflection

Calculate main content width at 375px using the narrow rules. Identify which
declarations change at the breakpoint and which remain inherited or unchanged.
Explain the disabled hover exclusion and trace which selectors match a focused
enabled button versus a disabled button.

Choose a long hypothetical filename and predict where wrapping is permitted.
Explain why CSS can alter its visual lines without changing the textContent
set by JavaScript. Compare what a static arithmetic check and a real Edge test
can establish. Complete personal reflection after doing the exercises; these
notes contain no invented experiences.

Return to the [HTML manual](02-04-html-browser-client.md),
[JavaScript manual](02-05-javascript-search-client.md), or [learning index](README.md).
