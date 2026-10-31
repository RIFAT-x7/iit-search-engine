# Phase 3.3B: Browser-based Boolean retrieval

**Learning Manual:** 12

**Student:** Rifat Al Maruf (BSSE1705)

**Course:** SPL-1, Institute of Information Technology, University of Dhaka

**Record date:** November 1, 2026

**Project:** IIT Search Engine

## 1. Objective and checkpoint

Phase 3.3B connects the existing Boolean search API to the HTTP server and browser.
The implementation commit is `eb01d2a77e7edd34dac73d62df2ba95f5a09cc18` on
`feat/browser-boolean-search`. At this documentation checkpoint, local and remote
main remain at `d57b4f43cb5bebe5157ec02fa0d14dd6a717f030`; publication of the
feature branch does not mean it has been integrated into main.

Read the [core Boolean manual](03-04-boolean-search.md) for parsing and set
operations, and the [browser phrase manual](03-03-browser-phrase-search.md) for
the earlier two-mode integration. This chapter explains the third mode and the
complete current request flow without changing any retrieval algorithm.

The actual implementation sources are [SearchServer.java](../src/SearchServer.java),
[SearchEngine.java](../src/SearchEngine.java), [index.html](../web/index.html),
and [app.js](../web/app.js). Existing [styles.css](../web/styles.css) was reused.
These notes were prepared with Codex assistance. Previously reported automated
results, user-reported Edge verification, and this task's static documentation
checks are separated in [Section 12](#12-verification-evidence).

## 2. Architecture: what each layer does

The browser collects the query and selected mode. The HTTP server validates
and routes the request. Java searches the already built index. The server
serializes results as JSON, and the browser renders them safely.

```text
STARTUP: choose one fixed collection
  no arguments -> data/                [6 documents]
  --phrase-demo -> data/phrase_samples/ [9 fictional samples]
                         |
                SearchEngine.buildIndex()
                         |
           frequency and positional postings in memory

REQUEST: HTML query input + selected native radio
                         |
           app.js -> URLSearchParams -> fetch GET /search
                         |
           SearchServer: split, decode, validate q/mode
                         |
         +---------------+----------------+
         | keyword       | phrase         | boolean
         v               v                v
      search()       searchPhrase()    searchBoolean()
   TF-IDF ranking     token positions  AND/OR document sets
         |               |                |
      searchJson()       +-- documentJson()-+
         |                         |
   filenames + scores         filenames only
         +---------------+---------+
                         |
              JSON -> browser response.json()
                         |
          request-ID check -> textContent -> results/status

Invalid query/mode/expression -> escaped JSON HTTP 400 -> error text
Without JavaScript: native GET navigates directly to the JSON response.
```

The same server still serves `/`, `/app.js`, `/styles.css`, and `/health`.
No new endpoint, retrieval algorithm, or runtime document-path selector was added.
The browser does not calculate term matches or relevance scores.

## 3. Three retrieval modes

| Mode value and label | Java method | Matching | Response result fields and order |
| --- | --- | --- | --- |
| `keyword`: Keyword Search, default | `search(query)` | Any distinct searchable query term; stop words filtered | filename and numeric score; descending score, filename ties |
| `phrase`: Exact Phrase Search | `searchPhrase(query)` | Consecutive normalized tokens, including stop words and repetitions | filename only; filename order |
| `boolean`: Boolean Search | `searchBoolean(query)` | Explicit AND/OR membership conditions; AND before OR | filename only; filename order |

Keyword search already computes TF-IDF-style scores. Phrase and Boolean methods
return `List<Document>` and do not calculate scores, so their JSON omits score
entirely. Adding zero would imply a ranking calculation that does not exist.
The list numbers shown beside matches are display positions, not relevance scores.

Boolean operators are standalone, case-insensitive AND/OR. Each operand must
normalize to one searchable token; stop-word operands and malformed expressions
raise `IllegalArgumentException`. Unknown valid terms have empty posting sets.
There are no parentheses, NOT, phrase operands, or wildcards in this grammar.
The existing lowercase ASCII letter/digit rules still apply to all three methods.

Mode selection is essential: `search("java AND programming")` still performs
ordinary keyword retrieval after filtering and. Only Boolean mode assigns AND
its operator meaning. Phrase mode treats and as a word occupying a token position.

## 4. HTML controls, accessibility, and native GET

Actual radio group from `index.html`:

```html
<fieldset class="search-modes">
    <legend>Search mode</legend>
    <label><input type="radio" name="mode" value="keyword" checked> Keyword Search</label>
    <label><input type="radio" name="mode" value="phrase"> Exact Phrase Search</label>
    <label><input type="radio" name="mode" value="boolean"> Boolean Search</label>
</fieldset>
```

`fieldset` groups related controls and `legend` names the group. Shared
`name="mode"` makes the radios mutually exclusive; `value` supplies the request
value. `checked` makes keyword the default. Wrapping each control in a label
provides an accessible name and a clickable label. Native radios provide keyboard
selection without custom keyboard code.

The query input keeps its visible label, `name="q"`, and `required` attribute.
Its `aria-describedby="search-help"` connects it to help explaining phrases,
AND-before-OR precedence, single searchable words, and unsupported stop-word
operands, parentheses, and NOT. The status retains `role="status"`; the result
list retains `aria-busy` to indicate loading.

The form keeps `action="/search"`, `method="get"`, and UTF-8 submission.
When JavaScript is disabled, native submission sends the checked mode and q,
then navigates to the API's JSON page. It does not render an HTML results page.
Browser required-field validation prevents an empty field; the server separately
rejects whitespace-only q. This fallback follows the source structure; no new
user verification of JavaScript-disabled submission is claimed in this manual.

CSS was not modified. The existing `.search-modes` flex container wraps labels,
and input focus outlines apply to radios. The existing narrow-screen rule stacks
the query input and button. These preserve the design's responsive and keyboard
features; they are not claims of additional user-tested layouts or screen readers.

## 5. JavaScript sends the selected mode

Actual selection and parameter construction:

```javascript
const mode = form.querySelector('input[name="mode"]:checked').value;
const parameters = new URLSearchParams({ q: queryInput.value, mode });
```

`querySelector()` locates the selected radio using `:checked`. `const` declares
a binding that is not reassigned. The object property shorthand `mode` means
`mode: mode`. `URLSearchParams` encodes the query and mode rather than concatenating
raw input into a URL: spaces may become `+`, while a query's ampersand becomes
`%26` and cannot accidentally create another parameter.

The existing async submit listener calls `event.preventDefault()` to handle
the response within the page. It ignores submissions while the button is busy,
clears earlier results, sets `Searching...`, marks `aria-busy` true, and disables
the button until the current request finishes or the selected mode changes.

Actual fetch, JSON decoding, and current-request guard:

```javascript
const response = await fetch("/search?" + parameters.toString(), { signal: activeRequest.signal });
const data = await response.json();
if (requestId !== latestRequestId) {
    return;
}
```

`fetch()` and `json()` return promises; `await` waits for each result without
blocking other browser events. A completed fetch can still carry HTTP 400,
so the code checks `response.ok` after JSON decoding. The saved mode controls
rendering for this request; Java still determines matching and ordering.

## 6. Server validation and dispatch to the existing core

`parseQuery()` splits the raw query on ampersands before decoding names and
values. This preserves an encoded ampersand inside a value. The existing decoder
handles percent escapes and plus spaces, and rejects invalid UTF-8. Each
parameter must use name=value. Duplicate q or mode is rejected, including when
the repeated name is percent-encoded. Other well-formed parameters remain ignored.

After requiring a nonblank q, actual mode validation is:

```java
if (mode == null) {
    mode = "keyword";
} else if (!"keyword".equals(mode) && !"phrase".equals(mode) && !"boolean".equals(mode)) {
    throw new IllegalArgumentException("mode must be keyword or phrase or boolean.");
}
return new SearchRequest(query, mode);
```

Mode **values are case-sensitive**: boolean is accepted, Boolean is rejected.
This differs from AND/OR operators inside a Boolean expression, whose case is
ignored by the core. `&&` means all three comparisons must be true to reject
an unsupported mode. Calling `equals()` on a string literal is null-safe.
`SearchRequest` is a private Java record carrying query and mode; `query()`
and `mode()` are its generated accessor methods.

The accepted request is dispatched by this actual code:

```java
if ("phrase".equals(request.mode())) {
    sendJson(exchange, 200, documentJson(request.query(), request.mode(),
            engine.searchPhrase(request.query())));
} else if ("boolean".equals(request.mode())) {
    sendJson(exchange, 200, documentJson(request.query(), request.mode(),
            engine.searchBoolean(request.query())));
} else {
    sendJson(exchange, 200, searchJson(request.query(),
            engine.search(request.query())));
}
```

`else` is keyword because validation already excluded every other mode.
`searchBoolean()` reads frequency-posting IDs, intersects each AND group, unions
those groups, and returns unique documents in filename order. It does not read
source text at query time. This milestone calls the unchanged method; it does
not reimplement Boolean parsing in the server or browser.

## 7. JSON serialization, errors, and continued service

Keyword responses retain `searchJson()`: root fields query/results, with filename
and score in each result. Missing mode and explicit keyword mode produce the same
body. Phrase responses retain query/mode/results with filename-only entries.

The former phrase-only serializer is now shared with Boolean retrieval. Its
actual beginning is:

```java
private static String documentJson(String query, String mode, List<Document> documents) {
    StringBuilder response = new StringBuilder("{\"query\":");
    response.append(jsonString(query)).append(",\"mode\":")
            .append(jsonString(mode)).append(",\"results\":[");
```

`List<Document>` is a list of indexed document objects; `<Document>` specifies
its element type. `StringBuilder` accumulates a string. Chained `append()` calls
work because each append returns the builder. The remainder loops over documents,
inserts commas between objects, escapes each filename with `jsonString()`, and
closes the array/object. It never adds a score. Passing mode phrase therefore
preserves the earlier phrase JSON body while mode boolean identifies the new mode.

Parsing and dispatch are inside the same try block, so the core's validation
exceptions reach the existing error serializer:

```java
} catch (IllegalArgumentException invalidQuery) {
    sendJson(exchange, 400, "{\"error\":"
            + jsonString("Bad request: " + invalidQuery.getMessage()) + "}");
    return;
}
```

`catch` handles invalid caller input for this request. `getMessage()` supplies
the reason. `jsonString()` surrounds text with quotes and escapes quotes,
backslashes, common control characters, and other characters below U+0020.
Consequently a diagnostic containing raw user input still forms valid JSON.
JSON escaping and safe browser DOM insertion solve different problems; both
are needed. `sendJson()` sets `application/json; charset=utf-8`.

Malformed input is an expected request error, not a reason to shut down the
application. The catch sends HTTP 400 and returns from this handler invocation;
it does not exit the server. The outer finally closes the current exchange.
Subsequent searches and health requests can still succeed. This handling covers
IllegalArgumentException; it is not a claim that every possible server failure
is recovered automatically.

## 8. Browser errors, safe rendering, and stale responses

For an HTTP error, actual browser handling is:

```javascript
if (!response.ok) {
    statusMessage.textContent = data.error || "Search failed. Please try again.";
    return;
}
```

`||` selects the fallback if error is absent or otherwise falsy. A valid
expression with no matches follows the success path and displays `No results
found.`. Network failures or invalid JSON go to catch and show the existing
generic connection message. Finally restores controls only if this request's
ID is still current, including after an early return for HTTP 400.

Actual result rendering inside the loop:

```javascript
const item = document.createElement("li");
item.textContent = mode === "keyword"
    ? result.filename + " - Score: " + result.score.toFixed(2)
    : result.filename;
resultList.appendChild(item);
```

`===` compares without type conversion. The conditional operator `condition ?
valueIfTrue : valueIfFalse` displays rounded numeric scores only for keyword mode.
Phrase and Boolean responses therefore need no numeric score. `toFixed(2)` is
presentation rounding; the full score and ranking remain Java's responsibility.

`createElement()` makes a list item and `appendChild()` inserts it. `textContent`
treats filenames and server diagnostics as literal text. An HTML-looking filename
such as `<img src=x onerror="alert(1)">.txt` is displayed rather than interpreted
as markup. `innerHTML` is not used. Successful status messages distinguish
`result(s) found.`, `phrase match(es) found.`, and `Boolean match(es) found.`.

### Why cancellation and request IDs work together

Each submission saves `const requestId = ++latestRequestId` and creates a new
`AbortController`, whose signal is passed to fetch. Prefix ++ increments the
shared counter before saving the request's number. On a mode change, the actual
callback includes:

```javascript
latestRequestId++;
if (activeRequest) {
    activeRequest.abort();
    activeRequest = null;
}
resultList.replaceChildren();
statusMessage.textContent = "";
resultList.setAttribute("aria-busy", "false");
submitButton.disabled = false;
```

The change invalidates older IDs, asks fetch to abort, clears results/status,
and permits a new submission. It does not automatically search the new mode.
Cancellation alone is insufficient because a response or its JSON decoding may
already be completing. The saved ID is checked before rendering success or API
errors; catch and finally also guard their updates. A stale completion cannot
overwrite results or unlock controls belonging to a newer request.

| Event | Latest ID | Effect |
| --- | --- | --- |
| Submit Boolean request A | 1 | A saves 1 and begins loading. |
| Select phrase mode | 2 | Abort A, clear results, invalidate ID 1. |
| Submit phrase request B | 3 | B saves 3 and begins loading. |
| A later succeeds or returns an error | 3 | Its saved ID 1 fails the guard; no page update. |
| B completes | 3 | Its ID matches; render B and restore controls. |

Switching back to Boolean does not revive A because IDs keep increasing.
Editing query text alone does not cancel a request; cancellation is attached
to mode changes. Aborting a browser fetch does not prove the Java server stopped
processing that request.

## 9. Fixed collections and startup options

Collection selection happens once at server startup. Mode selection chooses
an algorithm over that already indexed collection. All three modes work with
either collection; selecting Boolean never loads another folder.

Actual startup choice:

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

`args` is the command-line argument array. Only no arguments or exactly one
fixed flag is accepted; unsupported/repeated flags and arbitrary directory paths
are rejected before serving requests. The server still binds to 127.0.0.1:8080.
It logs the selected collection and indexed document count.

| Startup | Collection | Purpose |
| --- | --- | --- |
| `SearchServer` | Six immediate `.txt` files in data/ | Original demonstration corpus; baseline statistics remain 6 documents, 75 searchable terms, 120 processed tokens. |
| `SearchServer --phrase-demo` | Nine `.txt` files in data/phrase_samples/ | Separate fictional IIT-themed examples that also demonstrate Boolean retrieval. |

The existing loader does not recurse. Keeping samples nested preserves the
default corpus and its statistics; the sample README is excluded by the text-file
filter. The [sample collection](../data/phrase_samples/README.md) is clearly
fictional, not institutional policy or confidential material. Neither collection
was modified. Restart the server to change collections; no browser parameter
can select an arbitrary filesystem path.

## 10. Worked requests and precedence

These examples are source/test-derived walkthroughs, not new HTTP requests
executed during this documentation task.

### Successful Boolean request on the default collection

1. Start the server without arguments and select Boolean Search.
2. Enter `java AND programming`. URLSearchParams sends q and mode, commonly
   as `q=java+AND+programming&mode=boolean`; percent-encoded spaces are equivalent.
3. The server validates the request and calls `searchBoolean()`.
4. Java's java set contains academic_notice.txt and java_programming.txt.
   Intersecting with programming retains only java_programming.txt.
5. The serializer sends this response; the browser displays the filename without
   a score and reports `1 Boolean match(es) found.`.

```http
GET /search?q=java%20AND%20programming&mode=boolean HTTP/1.1
Host: 127.0.0.1:8080
```

```http
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{"query":"java AND programming","mode":"boolean","results":[{"filename":"java_programming.txt"}]}
```

On the nine-document collection, the same expression instead returns
java_programming_lab.txt and seminar_discussion.txt, in that order. The algorithm
is unchanged; its indexed documents differ.

### Malformed query and recovery

For q `java AND`, the final operand is missing. The core throws
IllegalArgumentException, and the server maps its message to HTTP 400:

```http
GET /search?q=java%20AND&mode=boolean HTTP/1.1
Host: 127.0.0.1:8080
```

```http
HTTP/1.1 400 Bad Request
Content-Type: application/json; charset=utf-8

{"error":"Bad request: Missing operand after AND."}
```

The browser renders that message as text, restores submission controls, and
allows a corrected query. An unknown valid term such as zzzznomatchingterm
instead produces HTTP 200 with an empty results array; it is not malformed syntax.
The HTTP tests verify a valid Boolean request, keyword request, phrase request,
and health request after an invalid Boolean request.

### AND precedence over OR

`java OR networking AND laboratory` means java OR the networking/laboratory
intersection. Parentheses may describe that meaning on paper; the parser does
not accept them as input. In the default corpus:

```text
java -> {academic_notice.txt, java_programming.txt}
networking AND laboratory -> {networking.txt}
OR union -> {academic_notice.txt, java_programming.txt, networking.txt}
```

academic_notice.txt remains because java forms its own group. Evaluating strictly
left-to-right and then requiring laboratory would wrongly remove it. This is
why the unchanged core completes AND groups before combining them with OR.

## 11. Regression coverage and compatibility

### Boolean HTTP suite: 22 groups per collection

[BooleanHttpTests.ps1](../tests/BooleanHttpTests.ps1) targets an already-running
server and uses PowerShell/.NET HTTP and JSON facilities. Its `-Collection default`
or `-Collection phrase-demo` argument selects expected filenames; it does not
change the running server's indexed folder. Each request has a five-second timeout,
and the client disables proxy and redirect handling.

The 22 test groups check:

| Groups in registration order | What they verify |
| --- | --- |
| 1-4 | AND intersection, OR union, precedence in either position, and multiple AND groups. |
| 5-8 | Operator case and operand punctuation, unknown terms/empty results, single terms, duplicates and deterministic filename order. |
| 9-13 | Missing operators/operands, consecutive operators, unsupported grouping/NOT/quoted operands/wildcards, stop words and punctuation-only operands give JSON 400. |
| 14 | Error text containing quotes, backslashes and U+0001 round-trips through JSON escaping. |
| 15-18 | Duplicate/encoded q and mode names, invalid or incorrectly capitalized mode values, nonblank q, plus spaces, encoded values, whitespace, ignored parameters and invalid UTF-8. |
| 19-20 | Legacy keyword JSON and exact phrase JSON remain compatible. |
| 21-22 | Valid requests and health succeed after an invalid expression; results belong only to the selected collection. |

The shared assertion requires exactly query/mode/results at the root, a results
array, and only filename in each Boolean result. It compares full ordered filename
lists, so missing/extra documents and duplicates fail. Default keyword JSON is
checked against its exact original string, including numeric scores. Phrase JSON
is compared against the existing exact bodies for each collection.

[RunHttpIntegrationTests.ps1](../tests/RunHttpIntegrationTests.ps1) manages hidden
temporary Java servers for the two fixed collections. It refuses an occupied
port, checks executable/PID/command line and loopback listener ownership, runs
the original smoke suite on default startup, then phrase and Boolean suites on
each collection. Before stopping a server, it verifies ownership again. It also
checks unsupported startup arguments, collection logs/counts, and free port 8080
after cleanup. It requires compiled classes; it does not compile the project.

### Browser-client suite: 12 retained checks plus 8 additions

[BrowserClientTests.js](../tests/BrowserClientTests.js) uses Node's built-in assert,
fs and vm modules to execute the actual app.js in a small DOM/fetch model.
The original twelve checks retain keyword/phrase rendering, errors, empty results,
mode changes, stale successes/failures, busy-submission protection and native GET
structure checks. The added checks cover:

| New check | What it verifies |
| --- | --- |
| Third radio and help | Three labeled modes, only keyword initially checked, help and query association. |
| Boolean success | q/mode encoding, score-free filenames, literal HTML-looking filename, status and restored controls. |
| Malformed expression | Literal server diagnostic and a subsequent corrected submission. |
| Empty Boolean result | Earlier ranked content disappears and no-results status is shown. |
| Completed mode changes | Boolean to phrase to keyword clears content and restores score display only for keyword. |
| Late Boolean success | Cannot render or unlock a newer keyword request; old signal is aborted. |
| Late Boolean error | Cannot overwrite newer phrase results. |
| Earlier modes completing late | A stale keyword failure and phrase success cannot overwrite current Boolean results. |

The model checks behavior and selected HTML structure, not a real rendering
engine, native form navigation, screen-reader interaction, or actual cancellation
timing. Separate user-reported Edge observations are recorded below.

All three SearchEngine retrieval methods and existing datasets remain unchanged
by Phase 3.3B. Existing Java and HTTP regression suites were preserved, and the
client suite was extended. Query decoding, routing/status behavior, keyword
ranking and phrase matching remain the responsibility of their existing code.

## 12. Verification evidence

### A. Previously Codex-reported automated results

During Phase 3.3B implementation, Codex reported successful compilation with
configured Temurin JDK 25.0.3 and the following executions:

| Suite | Passed | Failed |
| --- | --- | --- |
| BaselineTests | 7 | 0 |
| PositionalIndexTests | 13 | 0 |
| PhraseSearchTests | 18 | 0 |
| BooleanSearchTests | 22 | 0 |
| Original HTTP smoke tests, default collection | 16 | 0 |
| Existing phrase HTTP tests, default collection | 13 | 0 |
| Existing phrase HTTP tests, demo collection | 22 | 0 |
| New Boolean HTTP tests, default collection | 22 | 0 |
| New Boolean HTTP tests, demo collection | 22 | 0 |
| Browser-client JavaScript checks | 20 | 0 |
| **Previously Codex-reported total** | **175** | **0** |

This is 60 Java, 95 HTTP and 20 client test groups, not a count of every inner
assertion. JavaScript and PowerShell syntax checks were also reported passing.
Supporting startup/log checks are outside the 175 suite total. Owned temporary
servers were reported stopped after identity checks, with port 8080 free.

The nightly Git checkpoint relied on those earlier results and did not rerun
the application suites. They remain Codex-reported prior executions, not new
runs or independent verification performed for this manual.

### B. Real Microsoft Edge testing reported by the user

For this documentation task, the student supplied these confirmations:

| Collection | User-verified behavior | Reported result |
| --- | --- | --- |
| Default six-document collection | Keyword, phrase, Boolean and malformed-expression behavior | Passed |
| Nine-document fictional collection | Boolean, phrase and malformed-expression checks | Passed |

These are user-verified real Edge results, not Codex browser automation. No
additional browser checks are attributed to the student. In particular, these
confirmations do not establish extra tests of responsive layouts, keyboard-only
use, JavaScript-disabled submission, or delayed-response races for Phase 3.3B.
The earlier Codex automation attempt found an unavailable native helper; that
limitation does not negate the separate user-supplied observations above.

### C. This documentation task

Only Git checkpoint verification, source/test reading, excerpt comparison,
HTTP-example consistency, test counts, links/anchors, Markdown formatting,
whitespace and exact two-file scope are checked here. No compilation, Java tests,
HTTP requests, client regression suites or real Edge interactions are rerun.
Running a script that validates Markdown is a documentation check, not a new
application-test result.

## 13. Limitations and future work

The existing core grammar still excludes parentheses, NOT, nested expressions,
Boolean phrase operands and wildcards. Boolean stop-word operands are rejected;
phrase mode still supports stop words. There is no new ranking algorithm,
Boolean score, Top-K, fuzzy search, database/accounts, PDF extraction or OCR.
The shared ASCII token rules do not provide general Bengali word retrieval.

The server is a localhost-only, in-memory demonstration with two fixed startup
collections. File and asset changes require restarting the server to reload
them. There is no runtime arbitrary-path selection or document download feature.

The client has no application-level timeout. Editing query text alone does not
invalidate an in-flight request. Cancellation and IDs protect the UI from stale
responses, but do not guarantee server-side cancellation. Broader accessibility,
cross-browser, large-corpus and concurrency verification remain future work;
the recorded checks establish only their stated scope.

## 14. Eight supervisor viva questions

1. **What did Phase 3.3B add?** HTTP and browser access to the existing Boolean
   core, with a third mode and regression coverage; retrieval algorithms stayed unchanged.
2. **Why does selecting Boolean not change the corpus?** Startup selects the
   indexed folder; the browser mode selects an algorithm over that index.
3. **Why can AND ignore case while Boolean mode cannot?** The core compares
   operators case-insensitively; HTTP validation accepts exact lowercase mode values.
4. **Why share documentJson()?** Phrase and Boolean methods both return ordered
   documents without scores, so one serializer represents their common result shape.
5. **How does malformed Boolean input reach the browser?** IllegalArgumentException
   is caught per request, escaped into JSON HTTP 400, then displayed with textContent.
6. **Why use request IDs as well as AbortController?** A response may finish despite
   cancellation; IDs prevent obsolete rendering, errors and cleanup from affecting newer work.
7. **What happens without JavaScript?** The native GET form sends q and the checked
   mode, and the browser navigates to the JSON response.
8. **How do the three evidence categories differ?** Codex previously reported 175
   automated checks; the user reported the stated Edge checks; this task only verifies documentation.

## 15. Quick revision sheet

- **WHAT:** three browser choices send q and mode through the existing `/search` API.
- **WHY:** expose explicit AND/OR conditions while preserving keyword and phrase behavior.
- **HOW:** native radios -> URLSearchParams -> validated SearchRequest -> existing
  Java method -> correct JSON serializer -> guarded textContent rendering.
- Missing mode means keyword. Invalid/duplicate mode and malformed Boolean input
  give descriptive JSON 400; unknown valid terms can give successful empty results.
- Keyword has scores. Phrase/Boolean have filename-only results in filename order.
- AND binds before OR; parentheses and NOT remain unsupported.
- Changing modes increments the request ID, aborts, and clears results/status.
  Current-ID checks also guard success, error and finally updates.
- No arguments loads six default documents; --phrase-demo loads nine separate
  fictional samples. Either collection supports all three modes.
- Remember the evidence labels: **175 prior Codex-reported checks**, **only the
  stated user-verified Edge checks**, and **documentation-only checks now**.

For practice, trace a Boolean HTTP 400 that arrives after a newer phrase request
has completed. Explain which ID check prevents its diagnostic from replacing
the current phrase results.

Return to the [learning index](README.md).
