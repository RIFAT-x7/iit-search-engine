# Phase 2.4B: JavaScript search integration

**Student:** Rifat Al Maruf (BSSE1705)

**Course:** SPL-1, Institute of Information Technology, University of Dhaka

**Record date:** October 28, 2026

**Project:** IIT Search Engine

## 1. Objective, source, and milestone history

Phase 2.4A gave the browser a native form that navigated to the JSON API.
Phase 2.4B made the same form display results within the existing document.
Its implementation commit is `665509f724a3b68883bc6e504f4b2bf54bb5dee5`, with
parent `44d869bc1a98701b8f7546cf33e380ae89aaca96`. The later CSS milestone is
`8b0a38906495fa850244a8e66e36bd5ea2c4e4eb`.

This manual was prepared at the CSS checkpoint. The JavaScript source is
unchanged by that CSS commit. Read [web/app.js](../web/app.js) alongside the
explanations below and the [HTML architecture manual](02-04-html-browser-client.md).

JavaScript controls browser interaction and presentation. It does not load
the corpus, construct an inverted index, compute TF-IDF, or sort documents.
Those operations remain in [SearchEngine.java](../src/SearchEngine.java).
The frontend preserves the ordered array that the Java API returns.

## 2. The DOM and the initial const bindings

Actual first five lines:

```javascript
const form = document.getElementById("search-form");
const queryInput = document.getElementById("query");
const submitButton = form.querySelector('button[type="submit"]');
const statusMessage = document.getElementById("search-status");
const resultList = document.getElementById("result-list");
```

The Document Object Model (DOM) represents the parsed HTML as objects arranged
in a tree. JavaScript can find those objects, read their properties, create
new elements, and modify what appears in the page. The DOM is the browser's
document representation; it is not the Java document index.

`document` refers to this browser document. `getElementById` returns the element
with the matching ID, or null if no such element exists. The HTML's deferred
script executes after parsing, so the current elements are available when
these lines run. Changing an ID without updating its selector would break this
connection and could cause an error before any listener is registered.

`const` creates a binding that cannot later be reassigned. It does not freeze
the referenced DOM object. For example, `submitButton` always refers to the
same button, but the program changes its `disabled` property.
`let` would permit reassignment, but is not used here. This implementation needs
neither a mutable query variable nor a global array of search results.

`form.querySelector('button[type="submit"]')` uses a CSS selector to find the
first matching descendant of the selected form. It searches inside the form,
including the CSS wrapper added later. `document.querySelector` would search
from the whole document, but that alternative is not the code used here.

Both single-quoted and double-quoted JavaScript string literals are used.
Single quotes around the selector allow its double-quoted attribute value
without escaping those quotes. Each statement ends with a semicolon.

## 3. Event registration and navigation prevention

Actual opening of the handler:

```javascript
form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (submitButton.disabled) {
        return;
    }
```

`addEventListener` registers a callback for a named event. Registration does
not search immediately; the function body runs when a submit event occurs.
Listening on the form handles ordinary button submission and normal keyboard
submission through the same path. It avoids restricting the interaction to
pointer clicks on a button.

`(event) => { ... }` is an arrow function with one parameter and a block body.
The browser supplies the event object. `event.preventDefault()` cancels the
form's normal navigation. It does not erase the form attributes, stop all event
listeners, or validate the query. Without the listener, the native form remains
capable of navigating to `/search?q=...` and showing raw JSON.

The guard checks the button's disabled state. `if` conditionally executes its
block; `return` leaves this handler invocation. Because preventDefault runs
first, an ignored overlapping submit still cannot navigate away. The disabled
state becomes a small one-request-at-a-time guard, explained in section 10.

`async` makes the arrow function asynchronous and permits `await` in its body.
It returns a Promise. A Promise represents a result that may be available later
or a failure. An async handler does not cause the browser to wait before its
normal default action; the explicit, synchronous preventDefault call is what
prevents that action.

The HTML still uses native required validation. A normal empty-field submission
is blocked before the submit event runs. Whitespace is not removed here and
reaches the backend's nonblank check.

## 4. Clear results and announce loading

Actual setup before the request:

```javascript
    resultList.replaceChildren();
    statusMessage.textContent = "Searching...";
    resultList.setAttribute("aria-busy", "true");
    submitButton.disabled = true;

    const parameters = new URLSearchParams({ q: queryInput.value });
```

`replaceChildren()` with no arguments removes the existing child nodes from
the ordered list. The next search therefore starts without stale entries from
the earlier completed search. It does not remove the list element itself or
its ID.

Assigning `textContent` replaces the status paragraph's text. Since the
paragraph has `role="status"`, the update has polite live-status semantics.
The list's `aria-busy` state is set with a string value because HTML attributes
are textual. In contrast, `disabled` is assigned the JavaScript Boolean `true`.
The browser then disables the submit button, and the CSS gives it a distinct
appearance. These are feedback mechanisms, not search algorithms.

### 4.1 Query values and URLSearchParams

`queryInput.value` reads the user's text at this point in time.
`{ q: queryInput.value }` is a JavaScript object literal passed to the
URLSearchParams constructor. Its property name is `q`, matching the server
contract. This is not JSON text and does not need `JSON.parse`.

`parameters.toString()` serializes the query parameters using the URL form
encoding convention. Spaces can become plus signs, while a literal plus is
encoded so it is not mistaken for a space. The backend accepts and decodes
this convention.

Source-derived example, not an additional recorded HTTP result:

```text
Input text: java & network
Parameter text: q=java+%26+network
Request path: /search?q=java+%26+network
```

The encoded ampersand remains part of the one `q` value. Writing the raw text
directly after `?q=` could instead introduce extra parameters. URLSearchParams
also handles quotes, equals signs, backslashes, and Unicode text; it does not
perform the IR engine's normalization or stop-word filtering.

## 5. Fetch, await, JSON parsing, and HTTP status

Actual request and HTTP error handling:

```javascript
    try {
        const response = await fetch("/search?" + parameters.toString());
        const data = await response.json();
        if (!response.ok) {
            statusMessage.textContent = data.error || "Search failed. Please try again.";
            return;
        }
```

`fetch` starts an HTTP request. Its relative URL uses the page's own origin,
so this application does not require a separately configured API host or a
CORS workaround. No options override its default GET method. It sends the
query in the URL, not in a JSON request body.

The `+` operator concatenates the fixed prefix and encoded parameters.
`await fetch(...)` suspends this async function until its Promise settles.
The browser can continue other work; this is not a busy loop that freezes the
UI. A rejected Promise acts like a thrown error at the await expression.

The returned Response exposes status and body-reading methods. HTTP 400 does
not itself make fetch reject. `response.ok` is true for status codes 200 through
299; `!` negates it. Explicitly checking this property is necessary to distinguish
a server rejection from a normal search response.

`response.json()` asynchronously reads and parses the body. The second await
is needed because receiving response headers and consuming its body are
different steps. The result here is an ordinary JavaScript object whose
properties came from JSON text. Parsing is not Java object deserialization
and does not run the Java search code inside the browser.

The implementation parses before checking `ok`, allowing it to read the JSON
error object returned by the current 400 contract. `data.error || ...` uses the
server's truthy error value or a fallback message. The assigned text is safe
literal output. If the body is not valid JSON, parsing throws and reaches catch
instead; a non-JSON server failure will therefore get the general failure text.

## 6. Empty results and successful rendering

Actual response processing:

```javascript
        if (data.results.length === 0) {
            statusMessage.textContent = "No results found.";
            return;
        }

        for (const result of data.results) {
            const item = document.createElement("li");
            item.textContent = result.filename + " - Score: " + result.score.toFixed(2);
            resultList.appendChild(item);
        }
        statusMessage.textContent = data.results.length + " result(s) found.";
```

`data.results` accesses the array named by the API contract. An empty array
exists and has length zero; it is different from a missing property or null.
`===` is strict equality, so the test compares the numeric length with numeric
zero without coercing a string. The return avoids entering the rendering loop.
A no-match response is HTTP 200, because the search completed successfully.

`for (const result of data.results)` visits each array element in order.
The loop creates a binding for the current result on each iteration. There is
no sort call, score calculation, or second retrieval step. The first Java-ranked
document remains the first HTML list item.

`document.createElement("li")` creates a list item in memory. It does not yet
appear in the document. `item.textContent = ...` assigns its literal content,
and `appendChild(item)` adds it to the end of the existing `ol`.
The browser supplies numbering because the container is an ordered list.

### 6.1 Display rounding is not ranking

`result.score` is a number parsed from JSON. `toFixed(2)` returns a string
formatted with two decimal places, such as `"5.54"`. It does not modify the
number stored in the result object. Two scores can display identically after
rounding while retaining their original order. Sorting the formatted strings
would be both unnecessary and potentially incorrect; the current code does
not do that.

The status count uses the original array length. The simple literal phrase
`result(s) found.` avoids additional pluralization logic. A filename is displayed
as text, not a clickable document, snippet, preview, or download URL.

### 6.2 Why textContent is used

Server data should not become markup merely because it contains angle brackets.
A source-derived hypothetical filename `<img src=x>.txt` remains literal text
when assigned to textContent. Using innerHTML would interpret markup instead.
This client never assigns innerHTML. Query encoding and JSON escaping solve
transport problems; textContent separately controls how received data enters
the DOM.

This protects these text insertion points from HTML interpretation. It is not
a claim of a complete web-security audit, authorization system, or safe handling
of arbitrary future URLs and templates.

## 7. Catch, finally, and the three types of failure

Actual final part of the handler:

```javascript
    } catch (error) {
        statusMessage.textContent = "Unable to load search results. Check your connection and try again.";
    } finally {
        resultList.setAttribute("aria-busy", "false");
        submitButton.disabled = false;
    }
});
```

`try` contains the asynchronous operations and result processing. `catch`
receives a thrown or rejected error in `error`. This implementation displays
a general message rather than exposing low-level details. The variable is
available but is not otherwise used.

`finally` executes after the try/catch path, including early returns for a
400 response or an empty array. It clears the busy state and re-enables the
button. Without it, an early return could leave the page unable to search again.
The closing `});` ends the arrow function and the addEventListener call.

| Situation | Actual control flow |
| --- | --- |
| HTTP 200 with results | Parse JSON, render list, update count, finally |
| HTTP 200 with empty array | Empty message, early return, finally |
| HTTP 400 with JSON error | Error text through the !ok branch, return, finally |
| Network failure | Fetch rejects, catch displays general text, finally |
| Invalid JSON | response.json rejects, catch, finally |
| Unexpected result shape or score type | Can throw during processing, catch, finally |

The network-oriented wording can also appear for parsing or rendering errors;
it does not diagnose their cause precisely. The code trusts the current server's
success schema. It does not validate every field before inserting all results,
so malformed future data could leave a partial list beside the error message.

## 8. Fully traced java example

The previously verified API response was:

```json
{"query":"java","results":[{"filename":"java_programming.txt","score":5.541893581161611},{"filename":"academic_notice.txt","score":1.8472978603872037}]}
```

1. The deferred script has already attached the listener to `search-form`.
2. The user submits nonempty `java`. preventDefault keeps the document open.
3. The list is emptied, `Searching...` is assigned, the list becomes busy,
   and the button becomes disabled.
4. URLSearchParams produces `q=java`, and fetch requests `/search?q=java`.
5. The Java server reuses its index and returns the JSON above. Its TF-IDF-style
   calculation, not this script, produced the scores.
6. Awaiting response.json gives an object with an array of two result objects.
   `response.ok` is true and length is not zero.
7. The first loop iteration creates an li with
   `java_programming.txt - Score: 5.54`.
8. The second creates `academic_notice.txt - Score: 1.85`.
9. The paragraph becomes `2 result(s) found.` and finally restores controls.

The backend values and display text are supported by prior checks. This trace
explains execution; it is not a newly performed browser test.

## 9. No-result and error walkthroughs

For an unknown retained term, Java returns an empty array with HTTP 200. The
script has already removed previous entries. It displays `No results found.`
and finally restores the button.

For whitespace-only text, no trim operation changes the input before encoding.
The Java nonblank validation returns HTTP 400 and this error object:

```json
{"error":"Bad request: A nonblank q parameter is required."}
```

JSON parsing succeeds, `ok` is false, and the paragraph displays that exact
error text. The script does not attempt to read results from this error object.

A network failure is different: there may be no Response to inspect. The await
throws into catch, which displays the general connection message. All of these
paths leave the original HTML form and result container in place.

## 10. Repeated submissions and concurrency limits

The button is disabled synchronously before the first await. A second handler
invocation sees that state and returns before sending another request. This
prevents overlapping requests through this listener from racing to replace
each other's results. After completion, a new submission clears the old list
and performs the next search.

This is not cancellation or a latest-query-wins system. While a request is
pending, the input can be edited, but another submit is ignored. The results
still belong to the text captured when the first request started. The code
has no application timeout or AbortController; a request that never settles
can leave the button disabled. Those are known boundaries of the intentionally
small client, not implemented advanced UI features.

## 11. Verification: prior automated checks and manual Edge checks

| Evidence category | Prior Phase 2.4B results |
| --- | --- |
| Codex-run Java checks | Temurin JDK 25.0.3 compilation passed; seven IR tests passed |
| Codex-run HTTP integration | Root HTML, exact script and media type, health, ranked JSON, validation, 404, and 405 checks passed |
| JavaScript syntax check | Node syntax check passed |
| Mocked DOM/fetch checks | Encoding, navigation prevention, list clearing, busy state, overlap guard, order, display rounding, literal filenames, empty results, 400, network failure, and JSON failure paths passed |
| User-confirmed Edge test 1 | java results appeared beneath the form without navigation |
| User-confirmed Edge test 2 | Unknown term displayed No results found. |
| User-confirmed Edge test 3 | Whitespace displayed the backend nonblank-query error |
| User-confirmed Edge test 4 | Repeated searches replaced earlier results |

The Node check used a minimal DOM stub and mocked fetch responses. That tests
the script's logic but does not supply a real layout engine, native form
validation, browser network stack, or screen reader. The four Edge outcomes
were separately confirmed by the user. Codex did not perform an automated
browser interaction and should not claim that it did.

No JavaScript test file, Node dependency, or browser test suite was committed.
The seven committed regression tests remain the Java IR harness. Later CSS
verification confirmed app.js unchanged. This documentation task reruns only
document validation, not Node behavior, compilation, HTTP, or Edge tests.

## 12. Common mistakes and debugging questions

- Forgetting preventDefault makes a working request navigate away.
- Concatenating raw query text can corrupt separators; inspect the encoded URL.
- Assuming fetch rejects HTTP 400 skips the API error contract; inspect ok.
- Forgetting the second await leaves a Promise where result data is expected.
- Using innerHTML for filenames would interpret server text as HTML.
- Appending without clearing would mix different queries' result sets.
- Treating toFixed output as the ranking score confuses presentation with IR.
- Moving cleanup out of finally can leave the button disabled on early returns.

When debugging, distinguish a missing script route from a selector mismatch,
a network rejection, a JSON parsing failure, and a valid empty result. The
same visible symptom can have different causes. Source inspection and the
browser's request information help identify the relevant layer.

## 13. Supervisor viva preparation

1. **What does this JavaScript implement?** Browser submission and presentation,
   not indexing or ranking.
2. **What is the DOM?** The browser's object tree representing the document.
3. **Why use const for DOM elements?** Their bindings are not reassigned even
   though element properties can change.
4. **Is let used here?** No; it is an alternative for reassigned bindings.
5. **What if an ID does not match?** getElementById can return null and later
   operations can fail.
6. **Why select the button through form.querySelector?** It limits the search
   to a submit button inside this form.
7. **Why listen to submit instead of only click?** It covers normal keyboard
   and button form submission through one handler.
8. **What does preventDefault prevent?** The form's ordinary navigation.
9. **Does preventDefault validate input?** No; browser constraints and Java
   validation handle their respective checks.
10. **What does async change?** The function returns a Promise and can use await.
11. **Does await freeze the entire browser?** No; it suspends this async function.
12. **What does URLSearchParams protect?** Correct parameter serialization,
    including separators and special characters.
13. **Does fetch use POST here?** No; its default GET sends the query in the URL.
14. **Does HTTP 400 automatically reject fetch?** No; the script checks response.ok.
15. **What statuses make ok true?** The 200 through 299 range.
16. **Why await response.json?** Body consumption and JSON parsing are asynchronous.
17. **What is data.results?** The ordered array parsed from the API response.
18. **Why compare length with === 0?** It checks numeric zero without type coercion.
19. **What does for...of preserve?** The iteration order of the returned array.
20. **When does a created li become visible?** After appendChild inserts it
    into the document's list.
21. **Why use textContent?** Received text remains literal instead of becoming HTML.
22. **What does toFixed return?** A formatted string; it does not change the score.
23. **Why clear previous children?** Each completed search should show its own results.
24. **Why use finally?** Restore controls on success, errors, and early returns.
25. **How is loading communicated?** Status text, aria-busy, and a disabled button.
26. **Can two requests race through this handler?** Its disabled-state guard
    prevents a second fetch while the first is pending.
27. **Does that guard cancel an old query?** No. It ignores another submission
    until the current one finishes.
28. **What if the response is invalid JSON?** Parsing rejects and the general
    failure message is displayed.
29. **Do DOM mocks establish browser rendering correctness?** No; manual Edge
    checks supplied separate evidence for the reported scenarios.
30. **What search behavior stays in Java?** Normalization, posting lookup,
    TF-IDF-style scoring, and result ordering.

## 14. Practice and reflection

Trace an unknown query after a successful query, writing the list's child
count at each step. Trace a whitespace query and identify exactly where
control returns and where finally still runs. Explain how a literal plus
differs from a space in URL parameter serialization.

Predict what happens if the user edits the input while fetch is pending. Then
explain why no cancellation was claimed. Compare a DOM mock with one of the
user's Edge tests and state what each can establish. Record personal reflection
only after performing the exercise; no experiences or answers are fabricated here.

Continue with [responsive CSS](02-06-responsive-css.md) or return to the
[HTML manual](02-04-html-browser-client.md) and [learning index](README.md).
