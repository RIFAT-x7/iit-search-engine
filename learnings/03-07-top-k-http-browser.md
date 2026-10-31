# Phase 3.4B: Optimized Top-K through HTTP and the browser

**Learning Manual #14**

**Student:** Rifat Al Maruf (BSSE1705), IIT, University of Dhaka

**Course:** SPL-1 | **Record date:** November 1, 2026

## 1. Objective and verified checkpoint

Top-K means returning at most K of the highest-ranked keyword matches. This
milestone connects the existing optimized Java method to HTTP and then adds a
browser selector. It does not introduce another search algorithm or change scores.

This manual describes `feat/top-k-http` at
`f43976f52b8033cce935b7fb48cb1575499b13f0`. Local and freshly fetched remote
feature checkpoints match. Local and remote main remain at
`498de055dd6450fce06c27264ad957733d2b9f24`. The branch contains exactly these
two implementation commits ahead of main:

1. `dfbe4a44a19a53ee66de0f1091f5d71558226fa9`: HTTP Top-K support.
2. `f43976f52b8033cce935b7fb48cb1575499b13f0`: browser selector and client tests.

At this documentation checkpoint, those changes are published on the feature
branch and are not yet integrated into main. Earlier manuals retain their own
historical checkpoints; Manual #13's statement that Top-K has no HTTP/browser
support describes the earlier milestone.

### Source reading map

- [SearchServer.java](../src/SearchServer.java): parsing, dispatch and JSON.
- [SearchEngine.java](../src/SearchEngine.java): unchanged retrieval algorithms.
- [index.html](../web/index.html), [app.js](../web/app.js) and
  [styles.css](../web/styles.css): native controls, requests and presentation.
- [TopKHttpTests.ps1](../tests/TopKHttpTests.ps1): 32 test groups per collection.
- [RunHttpIntegrationTests.ps1](../tests/RunHttpIntegrationTests.ps1): owned server lifecycle.
- [BrowserClientTests.js](../tests/BrowserClientTests.js): 42 simulated client checks.
- [Manual #13: optimized Top-K](03-06-optimized-top-k.md): full heap explanation,
  complexity, Java tests and historical synthetic benchmark.
- [Manual #12: browser Boolean search](03-05-browser-boolean-search.md): the
  three-mode foundation extended here.

Study [HTTP validation](#4-strict-k-validation),
[worked requests](#6-complete-http-examples),
[race traces](#10-race-condition-traces), then
[verification evidence](#15-verification-evidence).
Excerpts below are actual source fragments; surrounding explanations are teaching
notes prepared with Codex assistance.

## 2. The complete architecture

```text
Startup: choose one fixed collection -> build in-memory index
                                           |
Browser form: query + mode + optional limit |
    | URLSearchParams, fetch(GET)            |
    v                                       v
SearchServer /search -> decode and validate SearchRequest
    |
    +-- keyword, no k --> search(query) ------> all ranked matches
    +-- keyword, k ----> searchTopK(query,k) --> bounded-heap selection
    |                         both use searchJson()
    +-- phrase --------> searchPhrase(query) --+
    +-- boolean -------> searchBoolean(query) -+--> documentJson()
    |
    v
HTTP status + UTF-8 JSON
    |
Browser: check request ID -> handle error or render ordered text
```

**What:** the browser chooses a retrieval mode and, optionally, a keyword limit.
**Why:** users can request a short ranked list without making Java rank every
match using the older full-search insertion sort.
**How:** the server selects the appropriate Java API. The browser displays the
returned sequence; it does not sort, slice or otherwise implement Top-K.

| Choice | Core method | Result meaning | Scores? |
| --- | --- | --- | --- |
| Keyword, All results | `search(query)` | All positive-score keyword matches, ranked | Yes |
| Keyword, Top K | `searchTopK(query, k)` | Exact first K of that ranking, or fewer | Yes, identical values |
| Exact Phrase | `searchPhrase(query)` | Consecutive normalized tokens, filename order | No |
| Boolean | `searchBoolean(query)` | Explicit AND/OR sets, AND before OR, filename order | No |

Keyword processing removes configured stop words and deduplicates query terms.
Phrase retrieval preserves stop words and positions. Boolean retrieval has strict
single-word operands and rejects stop-word operands. A keyword limit cannot be
silently applied to either of the two different, unranked retrieval APIs.

### Collection selection is separate from algorithm selection

The server chooses its corpus once at startup:

| Startup arguments | Folder | Documents |
| --- | --- | --- |
| None | `data/` | Original six fictional demonstration documents |
| `--phrase-demo` | `data/phrase_samples/` | Separate nine fictional IIT-themed samples |

The loader reads immediate `.txt` files, not subfolders, so normal startup does
not combine the collections. The [sample collection README](../data/phrase_samples/README.md)
identifies its fictional purpose. Both collections support all three modes and
keyword Top-K. Unsupported startup arguments are rejected; a browser cannot
choose an arbitrary filesystem path. Binding remains `127.0.0.1:8080`.

For an already compiled `out/app`, these are alternative commands to run from
the repository root; start only one server at a time:

```powershell
& 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\java.exe' -cp out\app SearchServer
```

```powershell
& 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\java.exe' -cp out\app SearchServer --phrase-demo
```

Startup logs identify the selected folder and indexed document count. Restart
with the other option to change collections; changing a radio button only
changes the algorithm used on the current collection. These commands are study
instructions, not commands executed during this documentation task.

## 3. Representing and decoding an HTTP request

From `SearchServer.java`:

```java
private record SearchRequest(String query, String mode, Integer k) { }
```

A **record** is a compact Java data carrier. These components provide accessors
`query()`, `mode()` and `k()`. `private` keeps this request representation inside
the server. `Integer` is the object wrapper for `int`: it can hold `null`, meaning
the parameter was absent. A primitive `int` cannot represent absence with null.
Zero is not used as an absence marker because zero is an invalid supplied limit.

Parsing starts with the raw query string. It splits before decoding:

```java
for (String parameter : rawQuery.split("&", -1)) {
    int separator = parameter.indexOf('=');
    if (separator < 0) {
        throw new IllegalArgumentException("Parameters must use name=value.");
    }
    String name = decodeParameter(parameter.substring(0, separator));
    String value = decodeParameter(parameter.substring(separator + 1));
```

This is the opening fragment of the parser's loop. `-1` retains empty split
pieces, allowing malformed empty parameters to be rejected. `indexOf()` finds
the first equals sign; `substring()` separates the name and value. Splitting
first ensures `%26` becomes a literal ampersand inside a value, not a separator.

The decoder converts `%HH` to bytes, converts `+` to a space, and then strictly
decodes UTF-8. For example, `q=java+search` and `q=java%20search` mean the same
query. `%6B=003` is a decoded `k=003`. A literal plus must arrive as `%2B`.

```java
return StandardCharsets.UTF_8.newDecoder()
        .decode(ByteBuffer.wrap(decoded.toByteArray())).toString();
```

`ByteBuffer.wrap()` exposes the decoded bytes to the charset decoder. Invalid
UTF-8 throws `CharacterCodingException`, translated into a descriptive
`IllegalArgumentException`. Incomplete or nonhex percent escapes also fail.
Some malformed raw request targets can be rejected by the JDK HTTP layer before
this handler runs; a handler-generated JSON error is not promised for every
possible malformed HTTP byte sequence.

Parameter names are case-sensitive. Recognized names are `q`, `mode` and `k`.
Other well-formed parameters are decoded but ignored, preserving earlier rules.
Thus uppercase `K` does not set a limit. Encoded names are checked after decoding,
so `k=1&%6B=1` is a duplicate, not a way around validation.

The parser stores the first value and rejects a second recognized parameter,
including a blank first value:

```java
} else if ("k".equals(name)) {
    if (kValue != null) {
        throw new IllegalArgumentException("Duplicate k parameter.");
    }
    kValue = value;
```

These are lines inside the parser's conditional chain. Empty string and null are
different: `k=` sets an empty string and is still a supplied parameter.

The existing rules still require one nonblank `q`. Missing mode defaults to
`keyword`; supplied mode must be exactly `keyword`, `phrase` or `boolean`.
An empty mode, uppercase `Keyword`, unknown mode or duplicate mode is invalid.
Boolean operators are case-insensitive inside Boolean expressions; HTTP mode
values are not. Query text is returned as received after URL decoding; the core
performs its own normalization for searching.

## 4. Strict K validation

First the parser restricts the parameter to keyword requests:

```java
Integer k = null;
if (kValue != null) {
    if (!"keyword".equals(mode)) {
        throw new IllegalArgumentException("k is supported only in keyword mode.");
    }
    k = parseK(kValue);
}
return new SearchRequest(query, mode, k);
```

The `!` means logical NOT; `equals()` compares string contents. Phrase and
Boolean requests with any `k`, even blank, fail this mode check. No limit is
invented when `k` is absent.

`parseK()` begins with a fixed diagnostic and empty-value check:

```java
String message = "k must be a decimal integer from 1 to 2147483647.";
if (value.isEmpty()) {
    throw new IllegalArgumentException(message);
}
```

It then checks each character before numeric conversion:

```java
for (int i = 0; i < value.length(); i++) {
    if (value.charAt(i) < '0' || value.charAt(i) > '9') {
        throw new IllegalArgumentException(message);
    }
}
```

`charAt(i)` reads a character, and `||` means OR. Anything outside ASCII `0` to
`9` is rejected. This intentionally accepts a narrower syntax than
`Integer.parseInt()` alone, which can accept a plus/minus sign and some Unicode
digits. There is no trimming, exponent interpretation or decimal rounding.

```java
try {
    int k = Integer.parseInt(value);
    if (k < 1) {
        throw new IllegalArgumentException(message);
    }
    return k;
} catch (NumberFormatException outOfRange) {
    throw new IllegalArgumentException(message);
}
```

`parseInt()` handles decimal conversion and detects overflow beyond Java's signed
32-bit range. The positive check rejects zero. The catch converts numeric
conversion failures into the same public diagnostic. It does not allocate an
array of K items. The existing core heap grows only when candidates are retained.

| Input after URL decoding | Outcome and reason |
| --- | --- |
| Absent | Full keyword search |
| `1`, `3`, `10`, `003` | Valid; leading zeros are accepted, `003` means 3 |
| `2147483647` | Valid `Integer.MAX_VALUE`; return all matches if fewer exist |
| `0`, `00` | Invalid: positive limit required |
| `-1`, `+1` | Invalid: signs are outside ASCII digits |
| Empty, spaces, tabs, newlines | Invalid: empty or nondigit characters |
| `1.5`, `3.0`, `1,000` | Invalid: fractions and separators are unsupported |
| `1e3`, `0x10`, `0b11`, `1_000` | Invalid: unsupported numeric notation |
| Arabic or fullwidth digits | Invalid: ASCII decimal digits only |
| `2147483648` or larger | Invalid: Java integer overflow |
| Two `k` parameters | Invalid even when equal |

A raw `+1` in the URL becomes space followed by 1; `%2B1` becomes plus followed
by 1. Both fail. Valid Unicode transport for `q` does not imply multilingual
tokenization: the existing core still uses lowercase ASCII-alphanumeric tokens.

## 5. Dispatch, errors and unchanged JSON

The keyword branch passes either full results or optimized results to the same
serializer:

```java
sendJson(exchange, 200, searchJson(request.query(),
        request.k() == null ? engine.search(request.query())
                : engine.searchTopK(request.query(), request.k())));
```

The conditional expression `condition ? first : second` evaluates the selected
branch only. A nonnull `Integer` is automatically unboxed to the `int` accepted
by `searchTopK()`. There is no full-search call followed by truncation in the
Top-K branch. Search algorithms remain in `SearchEngine`, not in the server.

`searchJson()` still emits `query` followed by `results`, and each result emits
`filename` followed by numeric `score`. It appends the original Java double
without rounding. It adds neither `mode` nor `k` nor a total-match count. Reusing
this serializer preserves the exact legacy keyword schema and field ordering.

Phrase and Boolean dispatch remain separate and use `documentJson()`, whose root
contains `query`, `mode`, `results` and whose entries contain only `filename`.
No artificial relevance score is introduced for unranked retrieval.

Invalid requests are handled per exchange:

```java
} catch (IllegalArgumentException invalidQuery) {
    sendJson(exchange, 400, "{\"error\":"
            + jsonString("Bad request: " + invalidQuery.getMessage()) + "}");
    return;
}
```

`jsonString()` escapes quotes, backslashes and control characters before putting
the message into JSON. Numeric validation uses a fixed diagnostic rather than
reflecting the raw invalid input. Boolean diagnostics can include operand text,
so shared escaping remains important. HTTP status 400 means invalid input, not
an empty valid result. The return ends this handler invocation; it does not stop
the HTTP server. The outer `finally` closes the exchange. `sendJson()` supplies
`application/json; charset=utf-8`; response length is measured in UTF-8 bytes.

Existing routing, GET-only handling, duplicate `q`/`mode` rules and decoding are
preserved. Correct requests can still succeed after invalid ones.

## 6. Complete HTTP examples

These examples use the original six-document collection. Bodies follow source
contracts and the committed test assertions; no live HTTP requests were made for
this manual. All JSON examples use `Content-Type: application/json; charset=utf-8`.

### Unrestricted keyword search

`GET /search?q=java` returns HTTP 200:

```json
{"query":"java","results":[{"filename":"java_programming.txt","score":5.541893581161611},{"filename":"academic_notice.txt","score":1.8472978603872037}]}
```

`GET /search?q=java&mode=keyword` has exactly the same body. The page displays
`java_programming.txt - Score: 5.54`, then `academic_notice.txt - Score: 1.85`,
with `2 result(s) found.` The two-decimal display does not change server scores.

### Optimized Top 1

`GET /search?q=java&mode=keyword&k=1` returns HTTP 200:

```json
{"query":"java","results":[{"filename":"java_programming.txt","score":5.541893581161611}]}
```

Omitting `mode` gives the same result. The page says
`Showing 1 ranked result(s) (Top 1 limit).` It cannot infer from this response
alone whether there was one match or many matches.

### Fewer than K matches

`GET /search?q=java&k=10` returns HTTP 200 with the same two-result body shown in
the unrestricted example. It displays both results and
`Showing 2 ranked result(s) (Top 10 limit).` K is an upper bound, not a promise
to create ten results. No padding or fabricated documents are added.

`GET /search?q=zzzznomatchingterm&k=3` returns HTTP 200:

```json
{"query":"zzzznomatchingterm","results":[]}
```

The page retains the established empty status: `No results found.`

### Invalid limit and incompatible mode

`GET /search?q=java&k=0` returns HTTP 400:

```json
{"error":"Bad request: k must be a decimal integer from 1 to 2147483647."}
```

`GET /search?q=java&mode=phrase&k=1` returns HTTP 400:

```json
{"error":"Bad request: k is supported only in keyword mode."}
```

### Other modes without a limit

`GET /search?q=computer%20networking&mode=phrase` returns HTTP 200:

```json
{"query":"computer networking","mode":"phrase","results":[{"filename":"networking.txt"}]}
```

`GET /search?q=java%20AND%20programming&mode=boolean` returns HTTP 200:

```json
{"query":"java AND programming","mode":"boolean","results":[{"filename":"java_programming.txt"}]}
```

The same API operates on the nine-document demo when started with
`--phrase-demo`, but document matches and scores depend on that different corpus.
For example, its `java search` phrase matches
`information_retrieval_workshop.txt` and `java_programming_lab.txt`, in that order.
Its `java AND programming` Boolean matches `java_programming_lab.txt` and
`seminar_discussion.txt`. These filenames are asserted in the HTTP tests.

## 7. Native HTML and progressive enhancement

The actual new control is a select, not a fourth retrieval mode:

```html
<label for="keyword-limit">Keyword result limit</label>
<select id="keyword-limit" aria-describedby="keyword-limit-help" disabled>
    <option value="" selected>All results</option>
    <option value="1">Top 1</option>
    <option value="3">Top 3</option>
    <option value="5">Top 5</option>
    <option value="10">Top 10</option>
</select>
```

`for` links the label to the matching `id`. `aria-describedby` links explanatory
help text. `selected` makes All results the initial choice; its value is the empty
string, not zero. The other values are strings that JavaScript sends as numbers
written in decimal text. A native select supports normal keyboard operation.

Why does it begin `disabled`, with **no `name` attribute**? Without JavaScript,
the browser performs the existing native GET submission using named `q` and
`mode` controls. The select must not accidentally submit an empty or inappropriate
`k`. Disabled controls are not submitted, and an unnamed control also contributes
no name/value parameter. With JavaScript, code explicitly constructs `k` only
when appropriate.

This is **progressive enhancement**: JavaScript adds Top-K selection, while the
fallback retains unrestricted keyword search and the existing phrase/Boolean
GET modes. The fallback navigates to the JSON response, rather than rendering
results inside the page. Visible help explicitly states that keyword limits
require JavaScript and do not apply to phrase or Boolean search.

The three radios remain inside a fieldset with a `Search mode` legend and
associated labels. The query remains required and labeled. Status uses
`role="status"`; the result list uses `aria-busy`. These provide semantic context
without inventing custom controls.

### Styling and keyboard feedback

The select shares existing font, padding, minimum height, border and full-width
styling. Help text shares the existing help style. Disabled appearance is shared
with the button. The actual focus rule is:

```css
input:focus-visible,
select:focus-visible,
button:focus-visible {
    outline: 3px solid #1d4ed8;
    outline-offset: 3px;
}
```

Comma-separated selectors share one rule; `:focus-visible` provides a visible
focus indicator when appropriate, including keyboard navigation. `#keyword-limit:disabled`
styles its unavailable state. Existing full-width sizing and the `40rem` media
query retain the responsive layout; no separate page redesign was introduced.
Source and simulated checks support these claims; they are not new visual Edge
observations from this documentation task.

## 8. Building and displaying a browser request

`getElementById()` retrieves one specific control. `querySelector()` finds the
checked radio; `querySelectorAll()` retrieves the mode controls for attaching
listeners. `const` prevents reassignment of a binding, but the referenced DOM
element can still change. `let` is used for changing request state.

Within the submit handler:

```javascript
const mode = form.querySelector('input[name="mode"]:checked').value;
const limit = mode === "keyword" ? keywordLimit.value : "";
const parameters = new URLSearchParams({ q: queryInput.value, mode });
if (limit !== "") {
    parameters.set("k", limit);
}
```

Line by line:

1. Read the selected radio's value; `:checked` selects its current state.
2. Use the select value only for keyword mode. `===` compares without coercion.
3. Construct URL parameters from an object. `mode` is shorthand for `mode: mode`.
4. Add `k` only for a nonempty limit. `set()` supplies a single value for that name.

`URLSearchParams` encodes spaces, ampersands, plus signs and other query text
correctly. It avoids concatenating unescaped user input into parameter syntax.
For example, an ampersand in the query must remain query text, not start a new
parameter. Transport encoding does not change the server's search normalization.

The handler calls `event.preventDefault()` to take over native submission.
An arrow callback such as `async (event) => { ... }` is the function registered
with `addEventListener()`. The disabled-button check blocks duplicate submits
while a current request is pending. It clears prior entries, announces
`Searching...`, marks the list busy and disables submit.

Rendering uses the received array in order:

```javascript
for (const result of data.results) {
    const item = document.createElement("li");
    item.textContent = mode === "keyword"
        ? result.filename + " - Score: " + result.score.toFixed(2)
        : result.filename;
    resultList.appendChild(item);
}
```

`for...of` visits each result; `createElement()` creates a list item;
`appendChild()` preserves server order. `toFixed(2)` produces a display string
with two decimal places. Only keyword results access a numeric score. Phrase and
Boolean results display filenames alone.

`textContent` treats a filename such as `<img ...>` as text. `innerHTML` would
interpret markup and is not used. Error messages also use `textContent`.
Safe HTML rendering and valid JSON escaping address different layers of the
pipeline; both are necessary.

For limited nonempty results, the status is:

```javascript
statusMessage.textContent = limit !== ""
    ? "Showing " + data.results.length + " ranked result(s) (Top " + limit + " limit)."
    : data.results.length + (mode === "keyword" ? " result(s) found."
        : mode === "phrase" ? " phrase match(es) found." : " Boolean match(es) found.");
```

The captured `limit` and `mode` describe that submission. The limited status
reports returned count and requested limit, not total matching count. Empty
results take an earlier branch and say `No results found.` The browser never
sorts or truncates the payload; enforcing K is the Java backend's responsibility.

### Keeping a limit while switching modes

Assume Top 3 was selected and the default corpus is running:

| Action | Select state | Request on submission | Display meaning |
| --- | --- | --- | --- |
| Keyword, `java` | Enabled, value `3` | `q=java&mode=keyword&k=3` | Two ranked matches, Top 3 status |
| Switch to Phrase, `computer networking` | Disabled, still `3` | No `k` | `networking.txt`, no score |
| Switch to Boolean, `java AND programming` | Disabled, still `3` | No `k` | `java_programming.txt`, no score |
| Return to Keyword, `java` | Enabled, still `3` | `k=3` again | Same ranked prefix |

Each mode change clears obsolete results and status. It does not automatically
submit or reset the select's value. The enable/disable rule runs both during
initialization and whenever mode or limit changes.

## 9. Asynchronous correctness: the two protections

A request does not complete immediately. `fetch()` returns a **Promise**, a value
representing future success or failure. An `async` function also returns a
Promise. `await` suspends that function until the promise settles; it does not
freeze the page while waiting for network I/O. Reading and parsing the response
body is a separate asynchronous operation.

```javascript
const requestId = ++latestRequestId;
activeRequest = new AbortController();
try {
    const response = await fetch("/search?" + parameters.toString(), { signal: activeRequest.signal });
    const data = await response.json();
    if (requestId !== latestRequestId) {
        return;
    }
```

This is the opening fragment of the handler's `try` block. Prefix `++` increments
the shared counter before capturing its new value. Each invocation keeps its
own `requestId` in its local scope. `AbortController.signal` connects cancellation
to fetch. `response.json()` parses JSON; an HTTP 400 response does not itself
reject fetch, so the subsequent `response.ok` check handles server errors.

**Cancellation** asks fetch to stop obsolete work. **Request identity** prevents
obsolete work from changing the page even if it finishes despite cancellation.
Aborting browser fetch does not guarantee cancellation of Java server computation.

The same reset function is attached to mode changes and limit changes:

```javascript
function clearSearch() {
    // Ignore even a late response whose cancellation has not completed yet.
    latestRequestId++;
    if (activeRequest) {
        activeRequest.abort();
        activeRequest = null;
    }
    resultList.replaceChildren();
    statusMessage.textContent = "";
    resultList.setAttribute("aria-busy", "false");
    submitButton.disabled = false;
    keywordLimit.disabled = form.querySelector('input[name="mode"]:checked').value !== "keyword";
}
```

The counter changes even if no new request is submitted. Therefore changing K
and then waiting cannot let the old response restore obsolete content. The
select remains usable during a keyword request, so the user can change K while
submit is disabled. Clearing neither sends a request nor changes the chosen
limit value.

### Error handling and completion

After the identity check, an unsuccessful HTTP response displays `data.error`
or a fallback message. A network failure, invalid JSON or exception while
rendering reaches `catch`. Completion reaches `finally`, including after a
return or exception:

```javascript
} catch (error) {
    if (requestId === latestRequestId) {
        resultList.replaceChildren();
        statusMessage.textContent = "Unable to load search results. Check your connection and try again.";
    }
} finally {
    if (requestId === latestRequestId) {
        activeRequest = null;
        resultList.setAttribute("aria-busy", "false");
        submitButton.disabled = false;
    }
}
```

The guards apply to success, failure **and cleanup**. An old `finally` must not
enable submit or clear the active controller belonging to a newer request.

Suppose result 1 renders successfully but result 2 lacks a numeric score.
Calling `toFixed()` then throws. Clearing children in `catch` removes the partial
list before displaying the generic failure; current-request `finally` restores
controls. This is exception cleanup, not a complete JSON-schema validator.
For example, the client does not independently enforce the server's K bound;
the tests deliberately simulate an oversized payload to prove there is no
client-side truncation. The production server enforces that bound.

## 10. Race-condition traces

These are illustrative event sequences derived from code and the deterministic
simulated tests, not measured network or browser timings. A race condition means
the order of asynchronous completion could otherwise produce the wrong screen.

### Trace A: changing K, then receiving an old success

Start with counter 0 and All results selected.

| Event | Counter / captured ID | Correct visible state |
| --- | --- | --- |
| Submit `java` as request A | Latest 1; A captures 1 | Searching, submit disabled |
| Change limit to Top 1 | Latest 2; abort A | Empty list/status, submit enabled, not busy |
| Submit again as request B | Latest 3; B captures 3 | Searching for Top 1, busy |
| A's success still arrives | A's 1 differs from latest 3 | Ignore data; A's finally cannot unlock B |
| B succeeds | B's 3 equals latest 3 | Render Top 1; restore controls |

If the user never submits B, A is still stale because 1 differs from 2. Merely
aborting without incrementing the counter would not provide this guarantee.

### Trace B: changing mode, then receiving an old error

Assume request A is a pending keyword Top 3 request. The user selects Phrase.
`clearSearch()` invalidates A, aborts it, clears content, disables the selector
and keeps its value `3`. Request B submits `computer networking` without K and
finishes with `networking.txt`. A then rejects with a network error, or returns
an HTTP error body. Its ID is no longer current: a rejected promise cannot enter
the current-error branch, and an HTTP response cannot pass the post-JSON identity
check. A's `finally` also does nothing. B's filename and phrase status remain.
Returning to Keyword reenables Top 3 for a later submission.

### Trace C: headers arrived, JSON still pending

1. A's fetch resolves, but `await response.json()` is still waiting.
2. The user changes K to 10; the counter advances and A is aborted.
3. B is submitted and completes; its results and Top 10 status appear.
4. A's delayed JSON resolves. The identity check occurs **after** JSON parsing,
   so A cannot render over B.
5. If A's JSON instead rejects, the guarded catch cannot overwrite B either.

The corresponding test explicitly controls a Promise for JSON parsing. It does
not rely on arbitrary timing sleeps. Together these traces explain why a guard
only before fetch, or cancellation alone, would be insufficient.

## 11. The 32 Top-K HTTP test groups

The same registrations run once per collection: **32 + 32 = 64** new HTTP
checks. A named check can contain many requests and assertions; 32 is not the
number of individual HTTP requests. The following table follows actual
registration order in `TopKHttpTests.ps1`.

| # | Case | What it verifies |
| --- | --- | --- |
| 1 | Absent K | Missing/explicit keyword mode have identical legacy JSON; no default limit; real collection filenames and default score literals. |
| 2 | Top 1 | Exact full-response prefix for `fictional` and `java`. |
| 3 | Top 2 | Same prefix comparison for K=2. |
| 4 | Top 3 | Same prefix comparison for K=3. |
| 5 | Top 5 | Same prefix comparison for K=5. |
| 6 | K larger than matches | K=100 returns all matches. |
| 7 | Maximum integer | `2147483647` succeeds and returns all matches; exercises the nonproportional-allocation design. |
| 8 | Missing/explicit mode | Multi-term Top-K is identical with explicit keyword mode and reordered parameters. |
| 9 | Query variants | Multi-term, reordered and repeated/case-normalized terms preserve exact scores for K=1,3,5. |
| 10 | Ties at cutoff | `fictional` gives equal scores; the first three filenames follow alphabetical tie-breaking. |
| 11 | Unknown term | Valid unknown queries return successful empty arrays for small and maximum K. |
| 12 | Punctuation and stop words | `!!!` and `and or the` remain empty for valid K. |
| 13 | Zero | `0`, `00` and `000` return numeric errors. |
| 14 | Negative | Negative limits are rejected. |
| 15 | Blank | Empty, spaces, tabs and newlines are rejected after decoding. |
| 16 | Nonnumeric | Words, `NaN` and `Infinity` are rejected. |
| 17 | Fractions/separators | Decimal points and commas are rejected. |
| 18 | Overflow | Values above Java int range return controlled errors. |
| 19 | Unsupported notation | Signs, exponent/radix syntax, underscores and other numeric-like forms are rejected. |
| 20 | Whitespace | Surrounding/internal spaces and tabs are rejected without trimming. |
| 21 | Unicode digits | Arabic and fullwidth digit examples are rejected. |
| 22 | Duplicate K | Identical, different, encoded and blank duplicate parameters are rejected. |
| 23 | Phrase with K | Rejected regardless of parameter order. |
| 24 | Boolean with K | Rejected regardless of parameter order. |
| 25 | Phrase without K | Exact existing filename-only JSON for the current collection. |
| 26 | Boolean without K | Exact existing filename-only JSON for `java AND programming`. |
| 27 | Q validation | Missing, blank and encoded duplicate query parameters remain invalid with K. |
| 28 | Mode validation | Empty, uppercase, unknown and encoded duplicate mode remain invalid. |
| 29 | Valid decoding | Encoded names/values, `003`, plus-space query and ignored extra parameters preserve output; uppercase `K` stays ignored. |
| 30 | Encoding/structure | Invalid UTF-8, missing equals sign and a decoded invalid numeric string fail safely. |
| 31 | Unsafe numeric text | Quote, backslash and control-character input yields valid fixed error JSON without reflecting raw input. |
| 32 | Recovery | After an invalid limit, Top-K, unrestricted search, phrase, Boolean and health still work; no previous limit leaks. |

### How the HTTP helpers establish compatibility

`Assert-Check` throws when a condition fails. `Test-Case` runs one registered
script block, catches failures and increments the named pass/fail counters.
This lets the suite report all groups before returning a failing exit code.

`Send-Request` uses a localhost HttpClient with no proxy or redirects and a
five-second timeout; it records status, content type and raw body, then disposes
the response. `Read-Json` checks status and exact JSON content type before
parsing. `Read-Keyword` checks root property order `query,results`, array shape
and each result's `filename,score` order and types. `Assert-Error` requires an
error-only object and the exact descriptive `Bad request: ...` message.

`Assert-Prefix` requests an unrestricted response as the oracle, then a Top-K
response for the same query. It checks count `min(K, matches)`, filenames and
order, then compares parsed score bits:

```powershell
Assert-Check ([BitConverter]::DoubleToInt64Bits([double] $top.results[$i].score) -eq [BitConverter]::DoubleToInt64Bits([double] $all.results[$i].score)) "Score bits differ at $i"
```

Casting both parsed values to double and comparing their bit patterns is
stricter than checking rounded display values. However, a client parser can
conceal distinctions between raw decimal representations. The helper therefore
also extracts the original complete result objects from the full raw JSON and
compares the limited raw body against that exact prefix. This checks numeric
literal spelling, precision, schema and field ordering without reserializing.
Using full search as a **test oracle** is appropriate; production Top-K does not
delegate to full search.

The maximum-K HTTP case exercises successful behavior, while the core source
and existing Java heap tests establish the allocation and size invariant more
directly. An HTTP success alone is not a proof of every internal heap operation.

## 12. The runner owns its servers

`TopKHttpTests.ps1` targets an already-running localhost server and does not own
its lifecycle. `RunHttpIntegrationTests.ps1` starts and cleans up the servers:

1. Require port 8080 to be free; an existing listener is not terminated.
2. Use the explicit Temurin JDK 25.0.3 executable and compiled `out/app` classes.
3. Check unsupported startup arguments exit promptly with the usage diagnostic.
4. Start the default collection, then the demo collection separately, with
   redirected output and no visible console window.
5. Verify PID, executable path and command line through `Win32_Process`; verify
   the actual listener belongs to that process and binds `127.0.0.1`.
6. Run original HTTP smoke tests only on the default corpus. Run phrase,
   Boolean and Top-K suites on each appropriate collection.
7. In `finally`, recheck identity before stopping an owned running process,
   wait for exit, collect output and dispose process resources.
8. Check collection/count startup logs and require port 8080 to be free again.

Standard output/error are read asynchronously to avoid filling redirected pipes
and blocking the server. Each failed child test causes runner failure; cleanup
still applies. Startup and ownership checks support the regression run but are
not extra named checks added to the reported 159 HTTP total. No servers were
started or stopped for this documentation task.

## 13. The 42 browser-client checks in registration order

The harness executes actual `web/app.js` in Node's `vm` with a small DOM model
and controllable fetch Promises. It records URLs and abort signals; tests choose
when requests succeed, reject or finish parsing JSON. DOM elements model text,
children, attributes, disabled state and event listeners. An `innerHTML` setter
throws if unsafe rendering is attempted.

### Original 20 checks preserved

| # | Case | What it verifies |
| --- | --- | --- |
| 1 | Keyword requests | Query encoding, received order, two-decimal scores and busy-state restoration. |
| 2 | Phrase rendering | Filename-only entries and safe literal text. |
| 3 | Empty results | Earlier content is cleared and the empty status appears. |
| 4 | JSON errors | Server error text is displayed safely; controls recover. |
| 5 | Network failure | Generic failure and restored submit control. |
| 6 | Invalid JSON | Failure status and cleared busy state. |
| 7 | Mode change after completion | Old results/status clear. |
| 8 | Late success | Old response cannot render or unlock a newer pending mode. |
| 9 | Late failure | Old network failure cannot replace newer completed content. |
| 10 | Duplicate submit | Busy submission sends no additional fetch. |
| 11 | Return to keyword | Scores return; stale phrase data cannot replace them. |
| 12 | Native form | GET route, labels, required query and absence of unsafe HTML rendering APIs. |
| 13 | Third radio | Three native modes, keyword default and Boolean precedence/unsupported-syntax help. |
| 14 | Boolean request | Encoded expression, filename-only safe text and restored controls. |
| 15 | Malformed Boolean | Error text stays literal and a corrected submission succeeds. |
| 16 | Empty Boolean | Old ranked results disappear. |
| 17 | Boolean to phrase to keyword | Clears prior content and restores each mode's status/score behavior. |
| 18 | Late Boolean success | Cannot render or unlock a newer keyword request. |
| 19 | Late Boolean error | Cannot overwrite completed phrase results. |
| 20 | Multiple stale modes | Old keyword failure and phrase success cannot replace current Boolean results. |

### The 22 additional checks

| # | Case | What it verifies |
| --- | --- | --- |
| 21 | All results | Initially enabled in keyword mode; no K initially or after returning from a limited choice. |
| 22 | Four limits | Top 1,3,5,10 send exactly one K; query with special characters and Unicode survives URL encoding. |
| 23 | Limited rendering | Server order and two-decimal scores stay intact; exact limited-count status and controls. |
| 24 | Fewer than K | Two returned results with Top 10 produce actual-count wording without claiming total matches. |
| 25 | Empty Top-K | Prior entries clear; existing empty message and selected limit remain. |
| 26 | Phrase mode | Selector disabled, value preserved, no K sent, filenames only. |
| 27 | Boolean mode | Same keyword-only limit restriction for Boolean requests. |
| 28 | Mode round trip | Phrase to Boolean to keyword restores the previously selected limit. |
| 29 | Change after completion | Clears results/status without submitting a new request. |
| 30 | Change while pending | Aborts old signal, immediately restores controls; old success stays ignored even without resubmitting. |
| 31 | Late success, new pending | Cannot render or unlock the current request after K change. |
| 32 | Late success, new completed | Cannot replace current results/status. |
| 33 | Late network failure | Cannot unlock or alter a newer pending request. |
| 34 | Late server error | Cannot replace newer completed content. |
| 35 | JSON parsing race | K change invalidates a response whose body is still parsing. |
| 36 | Duplicate limited submit | No additional fetch while a Top-K request is busy. |
| 37 | Server error and retry | Safe error text, restored controls and same selected K on retry. |
| 38 | Transport/body failures | Network error, invalid JSON and `results: null` restore controls and preserve K safely. |
| 39 | Unsafe-looking filename | Rendered only as literal text, with score. |
| 40 | No client truncation | Deliberately oversized mock payload is rendered in received order; source contains no slice/splice/sort calls. |
| 41 | Partial rendering | A later missing score clears the earlier rendered entry and restores controls. |
| 42 | Native selector structure | Explicit label/help, disabled unnamed select, exact five choices/default, unrestricted GET fallback and focus/disabled CSS rules. |

The mocked Unicode query in check 22 tests parameter transport, not multilingual
indexing. Check 40 is intentionally an unrealistic oversized server payload,
used to detect hidden JavaScript truncation. It is not a claim that the actual
HTTP server returns more than K results.

These simulations do not run Edge's layout, rendering, native keyboard widgets
or real network stack. They let asynchronous completion order be tested reliably,
but do not replace manual browser verification. HTML/CSS assertions inspect
structure and selectors rather than proving every accessibility or layout property.

## 14. Optimization and its limits

The Java core optimization already existed before this HTTP/browser work. It
accumulates scores only for posting-list candidates in a sparse map, retains at
most K candidates in a worst-first binary heap, extracts retained entries and
reverses them into ranking order. It does not sort every match, call full search
or reread document files. Scores use the same TF-IDF calculation and query-term
addition order, with higher score first and earlier filename breaking ties.

For posting entries P, matching candidates M and K' = min(K,M), the expected
core time bound is `O(P + M log(K'+1) + K' log(K'+1))`, with `O(M + K')`
additional candidate/heap storage, excluding the existing index and query terms.
This still computes candidate scores; it is not WAND or score-bound skipping.
See [Manual #13's complexity explanation](03-06-optimized-top-k.md#12-time-and-memory-complexity)
and [historical benchmark](03-06-optimized-top-k.md#14-previously-reported-exploratory-benchmark).

HTTP dispatch does not change that algorithm. Sending fewer results can reduce
JSON size and rendering work, but browser-perceived time also includes request
handling, serialization, transfer, JSON parsing and DOM updates. A passing
287-check regression establishes tested behavior, not a browser speedup.

The earlier synthetic benchmark measured Java methods, with warmup, repeated
timings and medians, excluding fixture/index construction. Its larger measured
cases favored Top-K against this project's full insertion-sort ranking; a small
multi-term case showed overhead. HashMap/boxing, allocation, heap operations,
JIT compilation, garbage collection and system load all influence timing.
Six- and nine-document demonstrations provide no meaningful scalability proof.
There is no new benchmark or end-to-end HTTP latency measurement in this milestone
documentation, and no claim of universal speedup.

Current boundaries:

- Browser choices are fixed to All, 1, 3, 5 and 10; the API accepts any valid
  positive int, but there is no custom browser K input.
- Top-K applies only to ranked keyword retrieval, not phrase or Boolean results.
- No pagination, total-match count, default server limit or new ranking formula.
- No parentheses, NOT, phrase operands or other Boolean grammar extension.
- No fuzzy retrieval, authentication, PDF support or runtime corpus selection.
- Cancellation protects the interface; server-side computation may still finish.
- There is no request timeout in the browser code. Editing query text alone does
  not clear a pending request; mode and limit changes do.
- Error cleanup is defensive but not a comprehensive successful-response schema
  validator. The browser trusts the server to return the supported contract.

## 15. Verification evidence

Counts are named checks/groups, not every request or assertion. Keep the stages
and sources of evidence distinct:

| Stage | Evidence and attribution |
| --- | --- |
| A. Phase 3.4B implementation | Codex previously reported **287/287 automated checks**, Temurin JDK 25.0.3 compilation, JavaScript/PowerShell syntax success and safe temporary-server cleanup. These are historical results, not fresh execution for this manual. |
| B. Browser implementation publication | Existing regression evidence was reviewed; fresh syntax and whitespace checks were reported. The complete application suites were not rerun. This does not create another independent 287-check run. |
| C. Manual browser verification | The user reported that Microsoft Edge browser functionality worked correctly after the prescribed browser checks. No additional precise observations, independently executed Edge cases or browser timings are invented here. |
| D. This documentation task | Source/excerpt review, HTTP example comparison, test registration/count review, race reasoning, link/anchor validation, Markdown/whitespace and exact two-file scope checks only. No application tests, servers, benchmark or Edge session were run. |

The historical total is:

| Suite | Checks |
| --- | ---: |
| Baseline Java | 7 |
| Positional Java | 13 |
| Phrase Java | 18 |
| Boolean Java | 22 |
| Top-K Java | 26 |
| **Java subtotal** | **86** |
| Original HTTP, default only | 16 |
| Phrase HTTP, default / demo | 13 + 22 |
| Boolean HTTP, default / demo | 22 + 22 |
| Top-K HTTP, default / demo | 32 + 32 |
| **HTTP subtotal** | **159** |
| Browser-client JavaScript | 20 original + 22 new = 42 |
| **Total** | **287** |

Existing default statistics remain 6 documents, 75 searchable terms and 120
processed tokens in the previously reported regressions. This task verifies the
documentation's attribution and consistency, not a new execution of those checks.
The [core Top-K tests](../tests/TopKSearchTests.java) and existing
[baseline tests](../tests/BaselineTests.java) provide deeper algorithm/statistics
coverage than the browser simulations alone.

## 16. Supervisor viva: twelve concise answers

1. **What changed in Phase 3.4B?** HTTP gained optional keyword `k`; the browser
   gained a result-limit select that sends it. Core ranking stayed unchanged.
2. **Why is this genuine optimized Top-K?** The server directly invokes the
   bounded-heap core method, rather than sorting full results and truncating.
3. **Why does missing K use null?** Absence selects legacy full retrieval;
   zero is invalid, so nullable `Integer` expresses the distinction clearly.
4. **Why validate ASCII digits before parseInt?** To reject signs, Unicode
   digits and unsupported syntax consistently; conversion then checks range.
5. **Why reject duplicate K?** To avoid ambiguity over which limit controls the
   request, including when names are encoded differently.
6. **Why reuse searchJson()?** Both keyword paths produce SearchResult lists
   with the same score contract; one serializer preserves schema and precision.
7. **Why not add a score to phrase/Boolean results?** Those APIs return matching
   documents without ranking. Inventing scores would misrepresent their meaning.
8. **Why does the select lack a name?** Native GET must omit K without JavaScript;
   JavaScript alone adds a valid keyword limit through URLSearchParams.
9. **Why both abort and request IDs?** Abort requests cancellation; IDs prevent
   late success, failure or cleanup from changing newer UI state.
10. **Why guard finally?** An obsolete request must not clear a newer request's
    controller or unlock its submit button.
11. **Does Showing 3 Top 3 mean exactly three documents matched?** No. It reports
    returned count and limit; the API supplies no total-match count.
12. **Do passing tests prove a faster browser?** No. They verify behavior. A
    controlled end-to-end performance measurement would be needed for that claim.

## 17. Quick revision sheet and architectural fit

- **Core:** same TF-IDF scores, sparse candidate scoring and bounded heap.
- **HTTP:** one endpoint; no K means full keyword; valid K calls Top-K directly.
- **Validation:** one ASCII-decimal K, range 1..2147483647, keyword mode only.
- **JSON:** keyword retains numeric scores and legacy field order; other modes
  retain filename-only results and mode metadata.
- **HTML:** three modes plus one keyword-only native select; All is default.
- **Fallback:** disabled unnamed select; JavaScript-free GET remains unrestricted.
- **JavaScript:** URLSearchParams sends K only when needed; no sorting/truncation.
- **Rendering:** textContent, server order, two-decimal score display only.
- **Async:** abort obsolete work, advance identity, guard success/catch/finally.
- **Status:** displayed count and selected limit do not imply total match count.
- **Collections:** startup selects six or nine documents; browser selects algorithm.
- **Evidence:** historical Codex 287 checks, user-reported Edge success,
  documentation-only checks here; no new performance measurement.

The architecture remains layered: processing and indexing build searchable
structures; Java retrieval decides matches and rank; HTTP validates and transports
results; the browser collects choices and safely presents them. This milestone
makes an existing algorithm available through those outer layers without moving
ranking into the UI.

Return to the [learning index](README.md).
