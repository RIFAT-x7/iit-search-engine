# Phase 3.2B: Browser-based exact phrase search

**Student:** Rifat Al Maruf (BSSE1705)

**Course:** SPL-1, Institute of Information Technology, University of Dhaka

**Record date:** October 31, 2026

**Project:** IIT Search Engine

## 1. Objective, checkpoint, and sources

Phase 3.2B connects the existing phrase-search algorithm to the HTTP API and
browser. The implementation commit is
`9898b93768406c2402aef3a7fad996e65eaee96d` on `feat/browser-phrase-search`.
Its parent, and the main checkpoint when this manual was prepared, is
`820d95b01bb36652958408437a7fe7b19a073135`. Publication on the feature branch
does not mean integration into main.

Read the [core phrase manual](03-02-exact-phrase-search.md) for positional
matching. This milestone changes the connection to that core, not its algorithms.
The main sources are [SearchServer.java](../src/SearchServer.java),
[SearchEngine.java](../src/SearchEngine.java), [index.html](../web/index.html),
[app.js](../web/app.js), and [styles.css](../web/styles.css).

These notes were prepared with Codex assistance. Source explanations, previously
reported automated checks, and user-verified Edge interaction are different
forms of evidence. No personal learning experiences are invented here.
Earlier manuals describe their historical milestones; their statements about
phrase controls being future work refer to those earlier checkpoints.

## 2. Architecture: from browser input to displayed results

```text
SERVER STARTUP (one collection for the life of this process)
    no argument ------> data/                 [6 original fictional documents]
    --phrase-demo ----> data/phrase_samples/  [9 phrase demonstration documents]
                              |
                    SearchEngine.buildIndex()
                              |
                    in-memory frequency + positional postings

EACH SEARCH
Browser: query text + selected radio mode
    |
    | GET /search?q=...&mode=keyword|phrase
    v
SearchServer: split, decode, validate -> SearchRequest(query, mode)
    | keyword                              | phrase
    v                                      v
SearchEngine.search()                 SearchEngine.searchPhrase()
frequency postings + TF-IDF           positional matching
ranked SearchResult objects           matching Document objects
    |                                      |
    v                                      v
JSON: query, results                   JSON: query, mode, results
filename + numeric score              filename only
    |                                      |
    +------------> browser fetch/json <-----+
                         |
                  createElement + textContent
                         |
                results list + status message
```

The server also serves three fixed assets at `/`, `/app.js`, and `/styles.css`.
The page and script choose a request; Java performs all retrieval and ranking.
The JSON response transports results; it does not contain the complete index.
Without JavaScript, the native form navigates to the API's JSON response instead
of rendering results within the page.

## 3. Two search modes, one collection

| Choice | Java call | Matching and order | Result fields |
| --- | --- | --- | --- |
| Keyword Search, the default | `search(query)` | Ranked OR over distinct filtered terms; descending score, filename ties | `filename`, numeric `score` |
| Exact Phrase Search | `searchPhrase(query)` | Consecutive normalized tokens, retaining stop words and repetitions; filename order | `filename` |

Keyword search can find a document containing either java or search. A phrase
query `java search` needs java immediately followed by search in one document.
The phrase method returns each matching document once, even if the phrase
appears several times. Phrase ordering is deterministic, but it is not a
relevance ranking. Adding a made-up score of zero would suggest a ranking
calculation that does not exist, so phrase results omit the field entirely.

Both methods retain the existing lowercase ASCII letter/digit tokenization.
Punctuation separates tokens: phrase search for `JAVA,SEARCH!` uses the same
sequence as `java search`. Quotes are ordinary separators, not query grammar.
See the [positional manual](03-01-positional-index.md) for stop-word storage
and the [core phrase manual](03-02-exact-phrase-search.md) for offset matching.

### Dataset choice is not algorithm choice

The startup argument chooses which files are indexed. A radio button chooses
which algorithm searches that already built index. Selecting Exact Phrase
Search does not load the nine-document collection. Likewise, a server started
with `--phrase-demo` can still perform keyword searches over those nine files.
Changing collections requires restarting the server; changing modes does not.

Actual startup selection:

```java
if (args.length == 0) {
    collection = "data";
} else if (args.length == 1 && "--phrase-demo".equals(args[0])) {
    collection = "data/phrase_samples";
} else {
    System.err.println("Unsupported arguments. Usage: SearchServer [--phrase-demo]");
    System.exit(1);
    return;
}
```

`args` is the command-line string array; `length` checks the number of supplied
arguments. The accepted flag is an explicit fixed choice. A directory path,
unknown flag, or repeated flag is rejected with usage text and exit status 1,
before indexing or opening a listener. Browser parameters cannot select a path.

The server logs `Collection: data` and `Indexed 6 documents.`, or
`Collection: data/phrase_samples` and `Indexed 9 documents.`. It still binds
only to `127.0.0.1:8080`. The loader reads immediate regular `.txt` files and
does not recurse, so the nested [phrase collection](../data/phrase_samples/README.md)
never silently joins the default corpus. Its README is not a text document
for indexing. Separating the folders preserves the original six-document
statistics and makes the alternative demonstration deliberate. No sample files
were changed by this milestone.

## 4. Semantic HTML, native submission, and styling

Actual radio group:

```html
<fieldset class="search-modes">
    <legend>Search mode</legend>
    <label><input type="radio" name="mode" value="keyword" checked> Keyword Search</label>
    <label><input type="radio" name="mode" value="phrase"> Exact Phrase Search</label>
</fieldset>
```

`fieldset` groups related controls; `legend` names the group. Both radios share
`name="mode"`, so they form one mutually exclusive choice. `value` supplies
the submitted algorithm name, and `checked` makes keyword the initial choice.
Each wrapping label gives its control an accessible name and a clickable label.
Native radios support keyboard selection without custom keyboard code.

The existing form keeps `action="/search"` and `method="get"`. The labeled,
required search input has `name="q"` and `aria-describedby="search-help"`.
Native submission includes the selected radio value and query text even when
JavaScript is unavailable. Browser validation blocks an empty required field;
whitespace still needs the server's nonblank-query check.

CSS adds a wrapping flex layout for `.search-modes` and inline flex labels.
`gap` provides spacing and `min-width: 0` allows the group to shrink. Existing
large-input styling is scoped to `input[type="search"]`, so radios do not
inherit a full-width, three-rem-high search box. The existing narrow-screen
media query still stacks the input and button. Focus outlines still apply
to inputs, including radios, and the button. The status keeps `role="status"`
and the result list keeps `aria-busy` feedback.

These choices preserve native semantics and responsive behavior. User-reported
Edge checks are recorded in Section 11; they are not a claim of exhaustive
accessibility testing across browsers or assistive technologies.

## 5. Sending q and mode with asynchronous JavaScript

The script obtains the selected radio and builds encoded parameters:

```javascript
const mode = form.querySelector('input[name="mode"]:checked').value;
const parameters = new URLSearchParams({ q: queryInput.value, mode });
```

`querySelector()` returns the selected control; `:checked` is the CSS selector
for its state. The object literal contains `q` and the shorthand property
`mode`, equivalent to `mode: mode`. `URLSearchParams` encodes the values safely:
spaces may become `+`, while an ampersand inside query text becomes `%26`
instead of introducing another parameter. The server accepts either `+` or
`%20` for spaces. Parameter order does not select the algorithm.

The submit handler is an `async` arrow function registered with
`addEventListener()`. `event.preventDefault()` handles the request within the
page instead of performing native navigation. When busy, the disabled-button
guard prevents a second submission. Before fetching, it removes earlier results,
sets `Searching...`, marks the list busy, and disables the submit button.

Actual fetch and response check:

```javascript
const response = await fetch("/search?" + parameters.toString(), { signal: activeRequest.signal });
const data = await response.json();
if (requestId !== latestRequestId) {
    return;
}
```

`fetch()` and `response.json()` return promises. `await` pauses this handler
until each promise settles while allowing other browser events to run. A
successful network operation can still return HTTP 400, so `response.ok` is
checked after JSON decoding. An error response displays its `error` text,
or a fallback message. An empty results array displays `No results found.`.

`try` encloses the request/rendering work. `catch` handles rejected network
requests, invalid JSON, or rendering failures with the generic load-error
message. `finally` restores controls for the current request, including when
the handler returns early. An obsolete request may not restore controls that
belong to a newer request.

## 6. Server parsing, validation, and dispatch

The parsed values travel together in this Java record:

```java
private record SearchRequest(String query, String mode) { }
```

A `record` is a concise data carrier. Java provides its constructor and
`query()`/`mode()` accessor methods. `private` keeps this helper inside
`SearchServer`. It holds the accepted request values without changing the
public search-engine APIs.

`parseQuery()` performs these steps:

1. Reject a missing query component. Split the raw component on `&` before
   decoding, so encoded ampersands remain inside their original values.
2. Require each parameter to use `name=value`, then decode its name and value.
   The existing decoder validates percent escapes, translates `+` to a space,
   and rejects invalid UTF-8 rather than replacing it silently.
3. Retain one `q` and at most one `mode`. A second occurrence of either name
   raises `IllegalArgumentException`, even if its value is identical. Decoding
   names first also catches `mode=phrase&%6Dode=keyword` as a duplicate.
4. Reject absent or blank `q`. Default an absent mode to keyword; accept only
   the exact lowercase values keyword and phrase. Other well-formed parameter
   names remain decoded and ignored, as before.

Actual mode validation:

```java
if (mode == null) {
    mode = "keyword";
} else if (!"keyword".equals(mode) && !"phrase".equals(mode)) {
    throw new IllegalArgumentException("mode must be keyword or phrase.");
}
return new SearchRequest(query, mode);
```

`null` here means no mode was supplied. An empty string is a supplied value,
so `mode=` is invalid. `&&` requires both inequalities before rejecting the
value. String `.equals()` compares contents; using the fixed string as the
receiver also avoids calling a method on a null reference.

The request handler catches these validation exceptions and returns HTTP 400
with JSON such as `{"error":"Bad request: Duplicate mode parameter."}`.
It does not pass an invalid request to either algorithm. Exact route checking
is preserved: known routes require GET, unsupported methods return 405 with
`Allow: GET`, and unknown paths return 404. Unknown-path validation still
precedes method validation. Malformed request targets may be rejected by the
JDK HTTP stack before the application's JSON handler runs.

Actual dispatch:

```java
if ("phrase".equals(request.mode())) {
    sendJson(exchange, 200, phraseJson(request.query(),
            engine.searchPhrase(request.query())));
} else {
    sendJson(exchange, 200, searchJson(request.query(),
            engine.search(request.query())));
}
```

The algorithms are reused as implemented in earlier milestones. Keyword search
still uses filtered frequency postings and TF-IDF-style scoring; phrase search
still uses positional postings with common consecutive starts. Neither scans
the source documents at query time.

## 7. JSON compatibility and safe result rendering

The existing keyword `searchJson()` method remains unchanged. Requests with no
mode, and requests with `mode=keyword`, return only root `query` and `results`;
each result contains `filename` and numeric `score`. There is no added mode
field for keyword responses, preserving existing clients and exact legacy JSON.

`phraseJson()` returns root `query`, `mode: "phrase"`, and `results`, where
each result contains only `filename`. Both serializers use the existing
`jsonString()` escaping for strings and send JSON with UTF-8 content type.
An empty phrase result is still `results: []`, not null or a different type.

The browser uses its captured request mode when rendering:

```javascript
item.textContent = mode === "phrase"
    ? result.filename
    : result.filename + " - Score: " + result.score.toFixed(2);
```

The conditional operator `condition ? valueIfTrue : valueIfFalse` chooses only
one branch. Phrase results therefore never call `toFixed()` on a missing score.
Keyword display rounding is still presentation only; Java's full numeric scores
and result order are preserved in JSON.

`document.createElement("li")` makes a list item; `textContent` puts literal
text into it, and `appendChild()` adds it to the list. A filename resembling
`<img src=x onerror="alert(1)">.txt` is displayed as text rather than parsed
as an image or executed code. Error/status text uses the same safe property.
No result is inserted with `innerHTML`.

Actual successful status selection:

```javascript
statusMessage.textContent = data.results.length
    + (mode === "phrase" ? " phrase match(es) found." : " result(s) found.");
```

The ordered list retains the returned order. Numbers beside phrase results
identify their list positions; they do not represent a computed phrase score.

## 8. Why cancellation and request IDs are both needed

`let` declares state that changes across events. `latestRequestId` starts at 0;
each submission saves a new ID using `const requestId = ++latestRequestId`.
Prefix `++` increments first, then supplies the new number. `const` keeps that
handler's saved ID unchanged even while later events change the shared counter.

`new AbortController()` creates a cancellation controller. Passing its `signal`
to fetch connects that request to `abort()`. When the selected mode changes,
the listener increments `latestRequestId`, aborts any active request, clears
the old results/status, marks the list not busy, and enables submission again.
It does not automatically search; the user submits the new mode explicitly.

Cancellation alone is insufficient: a response may already be arriving, or
its JSON work may already be underway. The saved ID is checked after decoding
before any result or API-error rendering. `!==` compares without type coercion.
The `catch` and `finally` blocks also update the page only when the saved ID
still equals the latest ID.

Example sequence:

| Event | Latest ID | Effect |
| --- | --- | --- |
| Submit keyword request A | 1 | A captures 1 and starts loading. |
| Select phrase mode | 2 | Abort A and clear old content. |
| Submit phrase request B | 3 | B captures 3 and starts loading. |
| A succeeds or fails late | 3 | A's saved 1 is obsolete; it cannot render or unlock B's controls. |
| B completes | 3 | Its saved 3 is current; render and restore controls. |

This protects successes, failures, and cleanup. Switching back to the original
mode does not make an old request current again, because IDs keep increasing.
Editing query text alone still does not cancel a running request; this
milestone's cancellation behavior is attached to mode changes.

## 9. Complete worked examples

### Keyword Search on the default collection

1. Start `SearchServer` with no arguments from the repository root. It loads
   the six immediate `.txt` files in `data/` and logs that collection.
2. Open the page, keep Keyword Search selected, enter `java`, and submit.
   JavaScript sends `/search?q=java&mode=keyword`.
3. The server decodes the values, accepts the mode, and calls `search("java")`.
   The term occurs in two of six documents. Smoothed IDF is
   `ln(7 / 3) + 1 = 1.8472978603872037`; raw TF values are 3 and 1.
4. The keyword serializer returns the following response (formatted here for
   reading; the server emits compact JSON). Omitting mode gives the same body:

```json
{"query":"java","results":[{"filename":"java_programming.txt","score":5.541893581161611},{"filename":"academic_notice.txt","score":1.8472978603872037}]}
```

5. The browser displays `java_programming.txt - Score: 5.54`, then
   `academic_notice.txt - Score: 1.85`, and `2 result(s) found.`. It clears
   `aria-busy` and enables the submit button. It has not calculated ranking.

### Exact Phrase Search on the nine-document demo collection

1. Stop the owned default server, then start `SearchServer --phrase-demo`.
   This loads only `data/phrase_samples/`, logging nine indexed documents.
2. Open/reload the page, select Exact Phrase Search, enter `java search`, and
   submit. JavaScript sends `/search?q=java+search&mode=phrase`; `%20` is an
   equivalent space encoding.
3. The server decodes the phrase, validates mode, and calls
   `searchPhrase("java search")`. Both tokens must occur at positions `p`
   and `p + 1` in the same document.
4. In `information_retrieval_workshop.txt`, java/search occupy positions 24/25.
   In `java_programming_lab.txt`, they occupy 19/20 and 44/45. These positions
   count the fictional-data header as part of each complete token sequence.
   The lab still appears only once. The reversed-word exercise and the
   networking text's intervening words do not satisfy this phrase.
5. The server returns filenames in deterministic filename order:

```json
{"query":"java search","mode":"phrase","results":[{"filename":"information_retrieval_workshop.txt"},{"filename":"java_programming_lab.txt"}]}
```

6. The browser shows those two filenames without score labels and displays
   `2 phrase match(es) found.`. A query `java java` instead returns an empty
   array and displays `No results found.`. Switching back to Keyword Search
   clears these results and selects keyword matching over the same nine files.

The first example demonstrates original default behavior; the second uses a
deliberately selected alternative corpus. Their different collections are not
an effect of the radio control.

## 10. What the new test files verify

### HTTP integration and safe startup

[PhraseHttpTests.ps1](../tests/PhraseHttpTests.ps1) targets an already-running
localhost server. Its `Collection` parameter selects expected test outcomes,
not the running server's collection. The script defaults to `phrase-demo`;
explicitly pass `-Collection default` for normal startup.

Eleven common test groups check default/explicit keyword equality, JSON fields
and numeric scores, invalid/empty/case-sensitive mode values, duplicate modes
(including encoded names), duplicate q, missing/empty/blank q, invalid UTF-8,
unknown/punctuation-only empty phrase results, and missing `name=value` syntax.
The default-corpus run adds two groups checking its known networking phrase
and exclusion of the demo phrase: **13 groups total**.

The demo run adds eleven groups for adjacent phrases, reversed order,
intervening-word rejection, internal stop words, stop-word-only phrases,
repeated terms, normalization, longer phrases, exclusion of default files,
encoded mode/plus-space decoding, and ignored extra parameters: **22 total**.
Phrase assertions require exactly the root fields query/mode/results and only
filename in each result. Keyword assertions require the legacy fields; the
default java body is compared against its exact original JSON string. Numeric
checks accept decimal as well as double/integer representations produced by
PowerShell's JSON parser.

[RunHttpIntegrationTests.ps1](../tests/RunHttpIntegrationTests.ps1) manages the
process lifecycle. It requires an available port 8080, rejects three unsupported
argument examples, launches hidden servers for each fixed collection, waits
for health, and checks the listener belongs to its own Java process at
127.0.0.1. It runs the original sixteen smoke tests only on default startup,
then the new checks for each corresponding collection. It checks collection
logs/counts, verifies PID/executable/command line before stopping only its owned
process, and checks port availability after cleanup. It does not free a port
by killing an unidentified listener. Compilation is a prerequisite; the runner
does not compile the Java classes itself.

### JavaScript client model

[BrowserClientTests.js](../tests/BrowserClientTests.js) uses Node's built-in
`assert`, `fs`, and `vm` modules, with a small DOM/fetch model. It executes the
actual app.js source in an isolated context and supplies controlled responses.
No npm dependency or real browser is required.

| Client check | What it verifies |
| --- | --- |
| Keyword display | Encoded query/mode, result order, rounded scores, busy and restored controls. |
| Phrase display | Filename-only entries and literal rendering of an HTML-looking filename. |
| Empty results | Earlier content is replaced and the no-results status appears. |
| JSON errors | API error text is rendered safely and controls are restored. |
| Network failure | Generic load error and restored controls. |
| Invalid JSON | Parse failure handling and cleared busy state. |
| Mode change after completion | Old results and status are cleared. |
| Late success | An old response cannot render or unlock a newer pending request. |
| Late failure | An obsolete failure cannot overwrite a newer completed result. |
| Duplicate submission | A busy form sends no additional request. |
| Switching back | Keyword scores return; a late phrase result stays ignored. |
| Native form structure | GET action, labeled radio choices, keyword default, required q, and no HTML insertion API in app.js. |

The model checks script behavior and selected HTML structure. It does not
provide a layout engine, native form submission, real network cancellation,
or assistive-technology interaction. Those require separate browser evidence.

## 11. Verification evidence: automated, manual, and documentation-only

### Previously reported Codex automated results

| Suite | Passed | Failed |
| --- | --- | --- |
| BaselineTests | 7 | 0 |
| PositionalIndexTests | 13 | 0 |
| PhraseSearchTests | 18 | 0 |
| Original HTTP smoke tests, default collection | 16 | 0 |
| New HTTP tests, default collection | 13 | 0 |
| New HTTP tests, demo collection | 22 | 0 |
| JavaScript client checks | 12 | 0 |
| **Total previously reported** | **101** | **0** |

Compilation with configured Temurin JDK 25.0.3 and JavaScript syntax checking
were also reported successful. The runner additionally checked three invalid
startup examples and both collection logs/counts; these supporting checks are
not included in the 101 suite total. During implementation, the first new HTTP
run rejected a valid score parsed as a PowerShell decimal. The test assertion
was corrected to accept that numeric type, then the complete HTTP run passed.
This was a test-type issue, not a ranking-algorithm change.

The original six-file statistics were preserved at 6 documents, 75 searchable
terms, and 120 processed tokens. Earlier Java suites and dataset files were
unchanged. The managed servers were reported stopped safely with port 8080
free afterward.

### Microsoft Edge results reported by the user

| User-verified group | Reported outcome |
| --- | --- |
| Keyword search and ranked scores | Passed |
| Exact phrase search and score-free results | Passed |
| Nine-document fictional demo collection | Passed |
| Mode switching and result clearing | Passed |
| Responsive browser layout | Passed |
| Keyboard navigation and repeated submissions | Passed |
| Native GET submission without JavaScript | Passed |

These seven confirmations were supplied by the student before publication.
They are manual user verification, not Codex browser automation. During
implementation, Codex could not use the native browser automation helper;
its DOM model does not substitute for these Edge observations. The confirmations
do not establish every browser, viewport, screen reader, or network condition.

### What this documentation task checks

Only source/excerpt accuracy, test descriptions, example consistency, local
links/anchors, formatting, whitespace, and file scope are checked here. The
sample token offsets in Section 9 are a static text trace. No compilation,
Java tests, HTTP tests, JavaScript client tests, or Edge interactions are rerun
for this documentation task. Historical counts above are not new executions.

## 12. Limitations and deferred work

The server remains a localhost-only in-memory demonstration. The flag accepts
one of two fixed collections, not arbitrary paths or per-user file access.
Document loading and web-asset reading happen at startup, so changes require
a restart. Search results contain text filenames, not download links.

Phrase matching retains the existing ASCII normalized-token definition and
may cross punctuation or line boundaries. There is no phrase-specific ranking,
quotation-mark query grammar, Boolean operators, Top-K, fuzzy search, PDF/OCR,
database, account system, or document downloading in this milestone.

The browser uses fetch and AbortController and assumes the server's response
schema. It has no application-level request timeout, and editing text alone
does not cancel pending work. Aborting a fetch does not prove Java stopped
processing that request. Request-ID guards protect the page from obsolete
responses; they are not a server-side cancellation protocol. Exhaustive
accessibility, cross-browser, and large-corpus performance testing remains open.

## 13. Eight supervisor viva questions

1. **Where does search happen?** In the Java search engine; the browser sends
   input and renders JSON results.
2. **How do collection selection and mode selection differ?** The startup flag
   picks the indexed files; the radio control picks an algorithm over that index.
3. **Why keep keyword JSON unchanged?** Existing clients expect query/results
   and filename/score fields; a new mode need not break that contract.
4. **Why omit phrase scores?** Phrase retrieval returns matching documents in
   filename order, with no implemented phrase-ranking calculation.
5. **How is mode validated?** Decode first, reject duplicates, default missing
   mode to keyword, and accept only keyword or phrase.
6. **Why use both abort() and request IDs?** Cancellation reduces obsolete work,
   while IDs stop late success, failure, or cleanup from changing newer UI state.
7. **Why is textContent safer than innerHTML for results?** It inserts literal
   filename/error text without interpreting it as markup or executable content.
8. **Does the DOM model prove real browser behavior?** No. It tests script
   logic; the user's separate Edge checks cover the reported browser interactions.

## 14. Quick revision summary

- Startup chooses **which documents**; mode chooses **how to search them**.
- `q` is decoded query text; absent mode means keyword; invalid/duplicate mode
  gives JSON 400.
- Keyword results keep numeric scores and their legacy JSON shape. Phrase
  results add root mode and contain filenames only, in filename order.
- Shared radio names enforce one native choice; GET submission works without JS.
- URLSearchParams encodes input; async/await handles fetch and JSON promises.
- `abort()` plus increasing request IDs prevents obsolete UI updates.
- `createElement`, `textContent`, and `appendChild` render literal result text.
- Remember the evidence split: **101 prior automated checks**, **seven
  user-verified Edge groups**, and **documentation checks only in this task**.

For practice, explain what happens if a keyword response arrives after switching
to phrase mode and submitting again. Trace which request may update the result
list, status, busy indicator, and submit button. Complete personal reflection
after studying the source.

Return to the [learning index](README.md).
