# Task 2.3: JSON responses for the HTTP search API

**Student:** Rifat Al Maruf (BSSE1705)

**Course:** SPL-1, Institute of Information Technology, University of Dhaka

**Project:** IIT Search Engine (`RIFAT-x7/iit-search-engine`)

**Record date:** October 27, 2026

**Implementation checkpoint:** `feat/http-search-api`, commit
`e7e2d836333a3efdda27fa68b4aa81aa75c6995c`

Descriptions of missing frontend features refer to this JSON milestone. The
browser client and repeatable HTTP tests were added later; see the
[project README](../README.md) for the current application.

## 1. Objective and milestone context

### 1.1 What Phase 2.2 already implemented

[Phase 2.2A and 2.2B](02-02-http-search-api.md) connected HTTP to the existing
Information Retrieval (IR) core. At startup, the server constructed one
`SearchEngine`, built the index from `data`, and then started listening on
`127.0.0.1:8080`. Valid `GET /search?q=...` requests reused that index.

The endpoint already parsed query parameters, decoded percent escapes and
literal plus signs, rejected missing, blank, duplicate, or invalidly encoded
queries, and returned ordered search results. Its responses were plain text.
The historical implementation is commit
`19ceddf01d42bc61da0f13d1fef8d9ede51fdf02`.

For example, historical text output for `java` displayed a rank, filename,
and score rounded to two decimal places. Task 2.3 changed the representation
of that information to JSON, a structured format a future browser client can
parse without extracting values from display labels.

### 1.2 Scope of the JSON milestone

This manual explains the committed code in
[SearchServer.java](../src/SearchServer.java), including `searchJson`,
`jsonString`, and the HTTP response helpers. The comparison was inspected with:

```powershell
git diff 19ceddf01d42bc61da0f13d1fef8d9ede51fdf02 e7e2d836333a3efdda27fa68b4aa81aa75c6995c -- src/SearchServer.java
```

| Concern | Phase 2.2B | Task 2.3 |
| --- | --- | --- |
| Matched documents | Ranked text lines | JSON `results` array |
| No matches | Text message | Empty `results` array |
| Query validation error | Text beginning `Bad request: ` | JSON `error` string with that prefix |
| Search media type | `text/plain; charset=utf-8` | `application/json; charset=utf-8` |
| Score representation | Two-decimal display text | JSON number from the stored `double` |

Startup indexing, query validation rules, ranking, and order stayed the same.
`/health` remains plain text. Exact routing, 404, 405, `Allow: GET`, and exchange
cleanup also remain. The implementation commit changed only `SearchServer.java`;
it introduced no browser frontend or new retrieval algorithm.

See [Phase 1](01-phase1-foundations.md) for IR foundations and
[Phase 2.1](02-01-http-health-endpoint.md) for the HTTP foundation. Those manuals
describe their historical stages rather than the newest endpoint contract.

## 2. JSON fundamentals

### 2.1 Objects, arrays, keys, and values

JSON means JavaScript Object Notation. It is a text data format usable by many
languages. An object uses braces and named members; an array uses square
brackets and ordered elements. A colon separates a key from its value, and
commas separate members or elements. Keys are double-quoted strings.

An illustrative JSON value, not this endpoint's full response contract:

```json
{"query":"java","results":[],"ready":true,"next":null}
```

| JSON value type | Example | Meaning |
| --- | --- | --- |
| String | `"java"` | Text |
| Number | `5.54` | Numeric value, without surrounding quotes |
| Boolean | `true` or `false` | Logical value |
| Null | `null` | Explicit absence of a value |
| Object | `{"filename":"example.txt"}` | Named members |
| Array | `["first","second"]` | Ordered elements |

`ready` and `next` above are teaching examples; the current API does not emit
them. Array order matters for ranked results. Consumers should locate object
members by key instead of depending on their printed order.

### 2.2 Valid and invalid examples

These are valid JSON teaching examples:

```json
{"filename":"example.txt","score":2.5}
```

```json
{"results":[],"message":"A quote: \" and a slash: \\"}
```

The following examples are intentionally invalid and are shown as plain text:

```text
{query: "java"}                 key is not double-quoted
{'query': 'java'}               single quotes are not JSON string delimiters
{"results": [1, 2,]}            trailing comma
{"query": "say "java""}         internal quotes are not escaped
{"score": NaN}                 not a JSON number
```

A real unescaped newline inside a JSON string is also invalid. The two
characters backslash and `n` can represent that newline safely as `\n`.
These syntax rules follow [RFC 8259](https://www.rfc-editor.org/rfc/rfc8259),
particularly its sections on values, numbers, and strings.

### 2.3 JSON compared with Java classes and objects

`SearchResult` is a Java class with fields, a constructor, and methods. A
`SearchResult` object exists in JVM memory and refers to a `Document` object.
JSON is text describing selected data; it does not carry executable methods,
Java references, or the original class definition.

The server explicitly chooses `filename` and `score`. It does not automatically
export every Java field. The document ID exists in `Document` but is absent
from the JSON result contract. `null` as a JSON value is different from the
string `"null"`; the current helpers do not provide a general Java-null-to-JSON
mapping and expect nonnull strings and result objects on their normal path.

## 3. Serialization and deserialization

### 3.1 Turning results into a transport representation

Serialization converts application data into a representation that can be
stored or transmitted. Here, the server turns query text and result objects
into JSON text, then encodes that text as UTF-8 bytes for an HTTP body:

```text
Java query and result objects -> JSON String -> UTF-8 byte[] -> HTTP response
```

This is manual JSON serialization, not Java's `ObjectOutputStream` binary
object serialization. The methods choose each field and each structural
delimiter explicitly.

Deserialization is the reverse kind of operation: a consumer parses the JSON
representation into values it can use. A future JavaScript client could parse
the body and read its `results` array. That frontend is not implemented yet.

### 3.2 Why the API does not parse JSON request bodies

The search input contract is a GET URL parameter. `parseQuery` receives
`getRequestURI().getRawQuery()`, not the request body. It returns the decoded
query string. Percent decoding is not JSON parsing, and parsing JSON responses
in a verification client does not mean the Java server accepts JSON requests.

Sending a POST JSON body does not replace `q`; the known endpoint rejects POST
with 405 before query processing. This milestone implements JSON output only.

### 3.3 Three different forms of escaping

| Layer | Example | Purpose |
| --- | --- | --- |
| URL encoding | `%22` in a query value | Carry a quote through the request URL |
| Java source escaping | `"\""` | Describe a quote in a Java string literal |
| JSON escaping | `\"` inside JSON text | Keep that quote inside a JSON string |

For example, a URL value `java%22` becomes the Java text `java"`, with an actual
quote at its end. The response must encode that quote inside the JSON string.
Copying input directly between JSON delimiters would allow its quotes,
backslashes, or controls to damage the structure. `jsonString` handles this
for query text, filenames, and validation error text.

JSON escaping is also distinct from HTML escaping. A later frontend must use
appropriate DOM operations when displaying received strings; producing JSON
does not implement an HTML renderer.

## 4. Response contract

### 4.1 Successful and empty searches

The request form remains `GET /search?q=...`. The decoded, accepted query is
echoed in `query`, including accepted surrounding spaces and punctuation.
It is not replaced with the engine's normalized tokens.

Actual response recorded during the earlier Task 2.3 verification:

```json
{"query":"java","results":[{"filename":"java_programming.txt","score":5.541893581161611},{"filename":"academic_notice.txt","score":1.8472978603872037}]}
```

`results` is always an array on this success path. Each element has `filename`
as a string and `score` as a number. There is no explicit `rank` field; the
array position carries the existing ranking order.

The source-derived body for a no-match query, also covered by a prior HTTP
empty-result check, is:

```json
{"query":"zzzznomatchingterm","results":[]}
```

The request succeeded even though no document matched, so the status is 200.
This is different from a missing endpoint or invalid input.

### 4.2 Validation errors

Actual earlier verification recorded this response for `GET /search`:

```json
{"error":"Bad request: Missing q parameter."}
```

The status remains 400. The prefix is exactly `Bad request: `, including the
space after the colon. The handler constructs it from source as follows:

```java
sendJson(exchange, 400, "{\"error\":"
        + jsonString("Bad request: " + invalidQuery.getMessage()) + "}");
```

`invalidQuery.getMessage()` supplies the particular validation explanation.
The concatenated string passes through `jsonString` before becoming a member
value. The error object does not include the success-path `query` and `results`
members. Unlike the historical error text, the new body has no added newline.

Existing rules still require exactly one nonblank `q`. Examples include
`A nonblank q parameter is required.`, `Duplicate q parameter.`,
`Parameters must use name=value.`, and
`Query parameters must use valid UTF-8.` These are explanations from validation,
not alternative HTTP status codes.

### 4.3 Media type, encoding, and preserved routes

Search success and handled validation errors use the exact application header
`Content-Type: application/json; charset=utf-8`. The helper also actually
encodes the body with UTF-8. A header declaration alone would not encode text.
This documents the project's emitted header; the JSON media-type registration
itself defines no charset parameter. Interoperable JSON uses UTF-8.
See [RFC 8259, sections 8.1 and 11](https://www.rfc-editor.org/rfc/rfc8259).

| Request | Status and body behavior |
| --- | --- |
| Valid GET search | 200; JSON object with `query` and `results` |
| Invalid query on GET search | 400; JSON object with `error` |
| Exact GET health | 200; exact body `OK`; `text/plain; charset=utf-8` |
| Unsupported method on `/search` or `/health` | 405; empty body; `Allow: GET` |
| Unknown or suffixed path | 404; empty body |

The 404 and 405 branches directly call `sendResponseHeaders(..., -1)`. They
do not use `sendJson` or become JSON error objects. Path validation still runs
before method validation, so `POST /unknown` gives 404. The handler's `finally`
continues to close every exchange.

## 5. Detailed code walkthrough: searchJson()

### 5.1 Complete method from the source

```java
private static String searchJson(String query, List<SearchResult> results) {
    StringBuilder response = new StringBuilder("{\"query\":");
    response.append(jsonString(query)).append(",\"results\":[");
    for (int i = 0; i < results.size(); i++) {
        if (i > 0) {
            response.append(',');
        }
        SearchResult result = results.get(i);
        response.append("{\"filename\":")
                .append(jsonString(result.getDocument().getFilename()))
                .append(",\"score\":").append(result.getScore()).append('}');
    }
    return response.append("]}").toString();
}
```

### 5.2 Signature and initialization

`private` limits access to this class. `static` allows calling the helper
without constructing a `SearchServer` instance. The return type `String`
means the result is JSON text, not a Java list or network response.

The first parameter is the accepted decoded query. The second,
`List<SearchResult>`, is an ordered generic list. The type parameter tells Java
which kind of element the list provides. The helper receives search results;
it does not perform a second search or rebuild the index.

`new StringBuilder("{\"query\":")` starts a mutable character buffer containing
the JSON characters `{"query":`. In the Java source, `\"` puts a quote into
the string; those source backslashes are not themselves present in that
structural text. Beginning with this prefix opens the outer object and names
its first member, but has not supplied its value yet.

### 5.3 Appending the query and opening the array

`jsonString(query)` returns a complete JSON string token, including opening
and closing quotes. `response.append(...)` adds it to the buffer.
`append` returns the same builder, permitting the next `.append(...)` call.
This chaining changes one buffer; it is not a chain of new result objects.

The second append contributes `,"results":[`: a comma between object members,
the next quoted key, a colon, and the array opener. At this point, for `java`,
the builder contains:

```text
{"query":"java","results":[
```

This is intentionally an unfinished JSON prefix, not a complete JSON example.

### 5.4 Loop and conditional commas

`int i = 0` starts at the first list position. `i < results.size()` is tested
before each iteration. `i++` advances one position after the loop body. This
is an ordinary indexed `for` loop, not Java's enhanced `for (item : items)`
syntax. Its index is useful for deciding when to insert an element separator.

| Iteration | `i > 0` | Text added before the object |
| --- | --- | --- |
| First result, i = 0 | false | No comma |
| Second result, i = 1 | true | One comma |
| Each later result | true | One comma |

Placing commas before every element except the first avoids both a leading
comma and a trailing comma. With an empty list, the loop runs zero times, so
there are no result objects or commas.

### 5.5 Result access and numeric scores

`results.get(i)` returns the next `SearchResult`. Its methods are:

```java
public Document getDocument() { return document; }
public double getScore() { return score; }
```

These real excerpts come from [SearchResult.java](../src/SearchResult.java).
`getDocument()` returns the referenced [Document](../src/Document.java), whose
`getFilename()` returns its filename string. The nested call therefore obtains
text to pass to `jsonString`, covering filenames as well as queries with the
same escaping rules.

`append("{\"filename\":")` opens one result object. The next call appends the
escaped filename token. `append(",\"score\":")` opens the score member, and
`append(result.getScore())` appends a numeric representation of the `double`.
The closing character `'}'` completes that object.

There are no surrounding quotes around the score. `StringBuilder`'s numeric
append uses Java's double-to-string representation, independent of the display
locale. It no longer calls `String.format` with `%.2f`. Values are still binary
floating-point approximations, not arbitrary-precision mathematical scores.
The finiteness limitation is explained in section 11.

The method reads the list sequentially. It neither sorts nor recomputes scores,
so the descending score order and filename tie order from `SearchEngine` remain
the response order. Formatting a result does not change the core object.

### 5.6 Complete and return the structure

`response.append("]}")` closes the results array and outer object.
`toString()` returns the accumulated characters as an immutable `String`.
`return` gives that value to the handler, which passes it to `sendJson`.

For zero results, joining the query prefix, array opener, and final `]}`
automatically yields `"results":[]`. A separate no-results message branch is
unnecessary. For two results, the same method inserts exactly one comma
between their objects.

## 6. Detailed code walkthrough: jsonString()

### 6.1 Complete method from the source

```java
private static String jsonString(String value) {
    StringBuilder escaped = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) {
        char character = value.charAt(i);
        switch (character) {
            case '"' -> escaped.append("\\\"");
            case '\\' -> escaped.append("\\\\");
            case '\b' -> escaped.append("\\b");
            case '\f' -> escaped.append("\\f");
            case '\n' -> escaped.append("\\n");
            case '\r' -> escaped.append("\\r");
            case '\t' -> escaped.append("\\t");
            default -> {
                if (character < 0x20) {
                    escaped.append("\\u00")
                            .append(Character.forDigit(character >> 4, 16))
                            .append(Character.forDigit(character & 0xf, 16));
                } else {
                    escaped.append(character);
                }
            }
        }
    }
    return escaped.append('"').toString();
}
```

### 6.2 Traversal, char, and Unicode

The helper returns one quoted JSON string token. Its initial builder contains
one double quote; the final return appends the matching closing quote.

`value.length()` supplies the loop bound and `value.charAt(i)` obtains the
current UTF-16 code unit. `char character` declares a primitive `char` local
variable; single quotes in the switch cases denote Java character literals.

A Java `char` is a 16-bit UTF-16 code unit. A Unicode code point such as an
ordinary Latin letter can occupy one unit; a supplementary code point such
as many emoji needs a surrogate pair. The loop consequently visits the two
units of such a pair separately. It does not iterate user-perceived characters
or count visual glyphs. For a well-formed pair, both units are appended in
order and later UTF-8 encoding preserves the represented character.
See the [Java 25 Character documentation](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Character.html).

### 6.3 Arrow switch and named escapes

`switch (character)` chooses a branch by the current code unit. In the arrow
form, a matched case executes its expression or block and does not fall through
to the next case. There is no need for a `break` after each arrow branch.
This syntax is supported by the documented JDK 25 toolchain.

| Input code unit | Meaning | Characters emitted inside the JSON token |
| --- | --- | --- |
| `"` | Double quote | `\"` |
| `\` | Backslash | `\\` |
| U+0008 | Backspace | `\b` |
| U+000C | Form feed | `\f` |
| U+000A | Newline | `\n` |
| U+000D | Carriage return | `\r` |
| U+0009 | Tab | `\t` |

For the quote branch, the Java literal `"\\\""` produces two runtime
characters: a backslash and a double quote. For the backslash branch,
`"\\\\"` produces two runtime backslashes. These are the characters the JSON
consumer needs to reconstruct the original data without ending the token early.

A tab is stored as one control character in the input. Its JSON escape is
two printable characters, backslash and `t`. A literal backslash followed by
`t` in the input is different: its backslash is escaped, preserving those two
original characters rather than converting them into a tab.

### 6.4 Control threshold and hexadecimal digits

`default` handles values without a named case. `0x20` is a hexadecimal Java
integer literal equal to decimal 32, the code point for space. Values below
it are the 32 code units U+0000 through U+001F. Space itself is not below the
threshold. Ordinary text and space are copied by the final `else` branch.

For an otherwise unhandled control, the helper appends the literal JSON escape
prefix `\u00`, then two hexadecimal digits. A JSON Unicode escape has four
hexadecimal digits after `u`. Two leading zeroes suffice here because the
remaining control value is at most 31.

The calculations use the current character's numeric value promoted to `int`:

- `character >> 4` shifts its bits right four positions. For these nonnegative
  small values, that gives the high hexadecimal digit, equivalent to integer
  division by 16. Java's `>>` is a signed right shift; there is no negative
  sign issue for this input range.
- `character & 0xf` applies bitwise AND with hexadecimal 15, binary `1111`.
  The mask keeps only the lowest four bits, giving the low digit. This `&` is
  a bitwise integer operation, distinct from the Boolean short-circuit `&&`
  used in routing conditions.
- `Character.forDigit(number, 16)` converts a digit value into its hexadecimal
  character. For example, 15 becomes `f`. The radix is 16 because each hex
  digit represents four bits; two digits cover one byte's possible values.

This is the reverse direction of the earlier decoder's `Character.digit`:
`digit` reads a hex character into a number, while `forDigit` produces a
character from a number. The arguments here are within the required range.

### 6.5 Worked control-character escapes

These are source-derived calculations, not additional executed test cases:

| Input | Decimal | High: value >> 4 | Low: value & 15 | Output inside token |
| --- | --- | --- | --- | --- |
| U+0000 | 0 | 0 | 0 | `\u0000` |
| U+0001 | 1 | 0 | 1 | `\u0001` |
| U+001B | 27 | 1 | 11, digit `b` | `\u001b` |
| U+001F | 31 | 1 | 15, digit `f` | `\u001f` |

For U+001B, binary `0001 1011` becomes high digit `1` and low digit `b`.
Appending `\u00`, `1`, and `b` produces six printable characters. A JSON parser
can reconstruct the original control value from them.

U+000A takes the earlier named newline case and emits `\n`; it never reaches
this default calculation. Both `\n` and `\u000a` could represent that value,
but the current implementation chooses the shorter named escape.

### 6.6 What this helper does not validate

The helper escapes syntax-sensitive code units and required low controls. It
does not check arbitrary Java strings for well-formed surrogate pairing. An
isolated surrogate passes its default append branch. The later
`getBytes(StandardCharsets.UTF_8)` conversion replaces malformed input using
the charset's replacement bytes instead of providing a strict validation error.
That behavior is specified in the
[Java 25 String API](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/String.html#getBytes(java.nio.charset.Charset)).

The request decoder separately reports invalid UTF-8 input, and the earlier
Unicode round-trip check covered valid text and a valid emoji. Neither fact
proves that `jsonString` validates malformed Java UTF-16 strings from every
possible internal caller. It also does not accept Java null as a string value.

## 7. Detailed walkthrough: HTTP helpers

### 7.1 sendJson() and sendText()

Current source:

```java
private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
    sendResponse(exchange, status, json, "application/json; charset=utf-8");
}

private static void sendText(HttpExchange exchange, int status, String text) throws IOException {
    sendResponse(exchange, status, text, "text/plain; charset=utf-8");
}
```

Both methods return `void`: they send a response rather than return a body
value. `HttpExchange` represents this particular request and its response.
`status` supplies the HTTP status, while the string supplies already prepared
body text. `throws IOException` declares that checked network I/O errors may
propagate to the caller.

`sendJson` selects the JSON Content-Type. It does not construct or validate
JSON itself; `searchJson` or the error-building expression prepares the text.
`sendText` selects plain text for health. Each delegates the common byte-writing
work to `sendResponse`.

### 7.2 sendResponse() line by line

```java
private static void sendResponse(HttpExchange exchange, int status, String text,
                                 String contentType) throws IOException {
    byte[] response = text.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.sendResponseHeaders(status, response.length);
    try (OutputStream output = exchange.getResponseBody()) {
        output.write(response);
    }
}
```

1. The signature receives all four inputs needed for the shared operation:
   exchange, status, prepared text, and media type. The parameter name `text`
   does not mean that its media type must be plain text; JSON is also textual.
2. `getBytes(StandardCharsets.UTF_8)` encodes the body. `byte[] response` stores
   the bytes to transmit. Explicit UTF-8 avoids a platform-default choice.
3. `getResponseHeaders()` obtains the headers for this response; `set` assigns
   Content-Type before sending headers. This describes the prepared body.
4. `sendResponseHeaders(status, response.length)` sends the selected status
   and body length. The length is the encoded byte count, not Java
   `String.length()`. For example, a Bengali character can occupy one UTF-16
   code unit and three UTF-8 bytes.
5. `getResponseBody()` returns an `OutputStream`. This stream writes bytes to
   the HTTP body, unlike the in-memory `ByteArrayOutputStream` in query decoding.
6. `output.write(response)` transmits the prepared array. The surrounding
   try-with-resources closes the stream even if writing throws.

The handler additionally has `finally { exchange.close(); }` around routing
and processing. That cleanup still executes when validation returns early.
It completes the exchange without stopping the listening server.

### 7.3 Code reuse and refactoring

The historical `sendText` performed encoding, header setting, and output
directly. Task 2.3 moved the shared work into `sendResponse` and made the two
media-type helpers delegate to it. This refactoring keeps byte length,
encoding, and stream cleanup consistent while allowing search JSON and health
text to have different headers.

The serialization helper returns a value; the sending helper performs I/O.
Keeping those responsibilities visible makes a trace easier: first inspect
the JSON text, then inspect how that text becomes a network body.

## 8. Worked execution traces

### 8.1 java: two actual ranked documents

The earlier successful Task 2.3 HTTP check requested `/search?q=java` and
recorded the exact response shown in section 4.1. Processing proceeds as follows:

1. The handler accepts `/search` and GET, parses one nonblank query, and
   obtains the decoded string `java`.
2. `engine.search(query)` uses the existing index. With six documents and two
   documents containing `java`, IDF is `ln(7/3)+1`.
3. Raw term frequencies three and one give scores `5.541893581161611` and
   `1.8472978603872037`. The engine returns `java_programming.txt` followed by
   `academic_notice.txt`.
4. `searchJson` writes the query, opens the array, appends the first object,
   inserts a comma before the second, and closes the array and outer object.
5. `sendJson` delegates to `sendResponse`, which sends 200 and UTF-8 JSON bytes.

This one request illustrates both a keyword search and multiple ranked
documents. No new ranking algorithm is involved in constructing their JSON.

### 8.2 No matching documents

The earlier verification checked both `zzzznomatchingterm` and the stop-word-only
query `the and or`. They passed HTTP validation and produced empty arrays.
An unknown term has no postings; the stop words leave no retained query terms.
Both lead to an empty result list, zero loop iterations, and HTTP 200.
The query string is still echoed, allowing the client to associate the response
with the submitted text.

### 8.3 Missing and duplicate q parameters

Previously recorded missing-query response:

```json
{"error":"Bad request: Missing q parameter."}
```

Previously recorded response for `/search?q=java&q=search`:

```json
{"error":"Bad request: Duplicate q parameter."}
```

The first fails because there is no raw query component. The second fails when
parsing reaches the second decoded `q` name. `IllegalArgumentException` leaves
the parser; the catch block sends JSON with status 400 and returns before
calling the engine. The outer `finally` then closes the exchange.

### 8.4 Query containing a quote

Hypothetical standalone trace derived from source, not a separately recorded
HTTP test: `/search?q=java%22`. Decoding produces `java` followed by a quote.
The engine treats the punctuation as a separator, leaving the `java` term.
The response contains the following JSON member fragment:

```text
"query":"java\""
```

The fragment is not a whole JSON response. The quote in the input is escaped
by the quote case; the final unescaped quote closes the JSON string token.
The results would match the `java` result list on the same unchanged corpus.
Quotation marks do not enable phrase search in this engine.

### 8.5 Query containing a backslash

Hypothetical standalone trace: `/search?q=java%5C`. Percent decoding produces
`java` followed by one backslash. The backslash switch case appends two
backslashes inside the JSON string, giving this member fragment:

```text
"query":"java\\"
```

An eventual JSON parser reconstructs one backslash in the query value. The
original query backslash is not a JSON escape command after parsing; it is
data. IR tokenization again retains the `java` term.

The actual earlier escaping test combined a quote, a backslash, controls,
and Unicode in one request. These standalone examples explain the branches
without claiming separate historical tests for them.

### 8.6 Encoded Unicode query

Hypothetical standalone trace: the query value
`%E0%A6%AC%E0%A6%BE%E0%A6%82%E0%A6%B2%E0%A6%BE` decodes as `বাংলা`.
The parser assembles the escaped bytes and uses strict UTF-8 decoding. The
accepted string is preserved in the JSON query value and UTF-8 response body.

The current ASCII tokenizer produces no searchable tokens from this word,
so the source-derived full response is:

```json
{"query":"বাংলা","results":[]}
```

This demonstrates the distinction between Unicode transport and Unicode
retrieval support. The earlier actual round-trip check included Bengali and
an emoji together with `java`; it preserved that mixed query and still found
the two Java documents. It did not establish general Bengali word indexing.

## 9. Testing and verification

### 9.1 Documented JDK 25 compilation and IR regression tests

The Task 2.3 implementation session recorded successful compilation with
Temurin JDK 25.0.3 and the seven existing baseline tests reporting
`Total passed: 7`, `Total failed: 0`. Their subjects were preprocessing,
inverted-index correctness, keyword retrieval, ranking, empty/unknown queries,
rebuild correctness, and failed-rebuild preservation.

These tests exercise the IR core, not HTTP serialization. Their success is
evidence that the covered core behavior remained intact; it is not a JSON test
count. The harness is [BaselineTests.java](../tests/BaselineTests.java).

Documented reproduction commands, not executed again for this manual:

```powershell
$projectJdk = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'
& "$projectJdk\bin\javac.exe" -version
& "$projectJdk\bin\java.exe" -version
& "$projectJdk\bin\javac.exe" -encoding UTF-8 -d out\app src\*.java tests\BaselineTests.java
if ($LASTEXITCODE -eq 0) {
    & "$projectJdk\bin\java.exe" -cp out\app BaselineTests
}
```

Use the repository root as the working directory. Explicit executable paths
avoid the previously observed older Java launcher on PATH.

### 9.2 Reported prior HTTP integration results

The actual earlier tool output in this task sequence reported:

| Check | Prior observed result |
| --- | --- |
| GET search for `java` | 200, JSON media type, expected query and two filenames in order |
| Numeric scores | Parsed as numbers; compared with `3 * (ln(7/3)+1)` and `ln(7/3)+1` within `1e-12` |
| Unknown and stop-word-only queries | 200, JSON response with empty arrays |
| Missing, empty, or whitespace-only q | 400, JSON `error` beginning `Bad request: ` |
| Duplicate q, absent q name, missing equals sign | 400, JSON validation body |
| Invalid UTF-8 `%FF` and `%C3%28` | 400, JSON validation body |
| Combined escaping query | Round-trip query equality after parsing JSON; two results |
| Health | 200, exact `OK`, plain-text UTF-8 header |
| Unknown and suffixed paths | 404 with empty body |
| POST, PUT, and HEAD on both known endpoints | 405, `Allow: GET`, empty body |
| POST on unknown path | 404 |
| Cleanup | Only owned verification server processes stopped |

The eight validation URLs in the successful run were `/search`, `/search?q=`,
`/search?q=+++`, `/search?q=java&q=search`, `/search?x=java`, `/search?q`,
`/search?q=%FF`, and `/search?q=%C3%28`. No overall HTTP test total is invented
from this table.

The first HTTP verification attempt stopped at a test-script type assumption:
PowerShell's JSON parser produced a `Decimal`, while the assertion demanded
`Double`. The corrected run accepted a parsed numeric value and compared its
converted value against the expected scores. It completed successfully. That
first assertion failure was not a recorded server serialization defect.

### 9.3 Escaping, Unicode, and all 32 low controls

The prior combined request contained `java`, a quote, a backslash, every code
unit from 0 through 31, Bengali text, an emoji, ampersand, plus, and equals.
The script URL-encoded the value, sent it, parsed the JSON, and checked that
the recovered `query` exactly equaled the input. It also checked that the raw
body contained no literal U+0000 through U+001F characters.

This exercises named escapes and the default hexadecimal control branch.
The original `java` term explains why the query still produced two matches.
Unicode and separator preservation are transport observations, not evidence
of new tokenization features. Query and filename serialization call the same
helper, but no special-character filename fixture was reported in this HTTP run.

### 9.4 Fresh review and the limits of evidence

For this documentation task, code, historical differences, and previous
verification output were reviewed. No fresh compiler, baseline runner, or HTTP
server was executed. Document checks concern excerpt accuracy, links,
whitespace, file scope, branch, and HEAD.

The prior results do not prove arbitrary JSON input handling, exhaustive
Unicode correctness, malformed UTF-16 behavior, non-finite score safety, load
capacity, cross-computer networking, browser compatibility, or ranking quality.
No persistent HTTP test suite was added by the JSON implementation commit;
the recorded integration checks were performed by an external verification
script. A repeat check should launch and stop only its own identified server;
an occupied port does not justify killing an unknown process.

## 10. Important design decisions

| Decision | Reason and application consequence |
| --- | --- |
| Standard Java only | The task required no external library; a small explicit serializer meets this fixed response shape |
| Reuse the original IR core | The milestone changes transport representation while retaining manually implemented indexing, retrieval, and ranking |
| Preserve numeric scores | A consumer can compare or format numbers without parsing human display labels |
| Escape every string value | Query, filename, and error text must stay within their JSON string boundaries |
| Represent no matches as `[]` | The success schema remains predictable even when its collection is empty |
| Keep existing status codes | Clients can distinguish successful searches, bad query input, missing routes, and unsupported methods |
| Share response writing | Encoding, byte-length calculation, and stream cleanup have one implementation |

A manual serializer is understandable for this small educational contract.
It must still be maintained carefully as fields and accepted data expand.
This choice does not establish that handwritten serialization is preferable
for every application. No library such as Jackson or Gson was introduced,
and no Lucene, Elasticsearch, or Solr replaced the academic IR engine.

## 11. Current limitations and correctness boundaries

### 11.1 Product and architecture scope

The repository still has no HTML/CSS/JavaScript browser frontend, JSON
request-body parser, PDF extraction or indexing, OCR, fuzzy search, phrase
retrieval, or Boolean parser. The search index is in memory, reads immediate
`.txt` files, and is built once at server startup. The collection is six
fictional demonstration documents, not official IIT material.

The server binds only to `127.0.0.1:8080`, has no verified cross-computer
deployment, and implements no TLS or authentication. It returns all matches
with no pagination, Top-K selection, or snippets. JSON changes none of those
capabilities.

### 11.2 Non-finite numeric values

JSON numbers cannot represent Java `NaN`, positive infinity, or negative
infinity. `searchJson` directly appends `getScore()` and does not explicitly
check finiteness. If given an arbitrary `SearchResult` containing such a value,
its output would not be valid JSON numeric syntax.

The current engine computes positive TF-IDF-style contributions for its loaded
documents, and the actual observed sample scores were finite. That evidence
does not turn the helper into a general serializer for all possible Java
double values. Adding new scoring logic or external result construction would
require reviewing this boundary. The implementation does not currently define
a fallback such as null or a custom error for non-finite scores.

### 11.3 Unicode boundaries

Strict UTF-8 request decoding, JSON escaping, UTF-16 string representation, and
ASCII tokenization are four separate concerns. Valid Unicode can round-trip
without being searchable as a word. `jsonString` preserves valid surrogate
pairs by copying their units in order, but does not validate isolated surrogates.
The subsequent encoding can replace malformed units, so exact preservation
of every arbitrary Java string is not guaranteed.

The 32-control test covers U+0000 through U+001F, not every Unicode code point.
There is no implemented Unicode normalization, transliteration, or full
multilingual IR pipeline. Proper JSON escaping also does not by itself provide
all security properties of a future web application.

## 12. Supervisor viva preparation

1. **Why introduce JSON after plain text?** Named fields and arrays let a client
   consume results without parsing rank labels and formatted score text.
2. **Is a JSON object a Java object?** No. JSON is text representing selected
   data. A Java object has JVM identity, fields, and methods.
3. **What is serialization here?** Converting query and result values into JSON
   text, followed by UTF-8 encoding for transport.
4. **Does the server deserialize JSON requests?** No. It parses GET URL
   parameters; POST is unsupported on the search endpoint.
5. **What does `private static String searchJson(...)` declare?** A class-only
   helper callable without a server instance that returns a String.
6. **Why use `List<SearchResult>`?** It supplies ordered typed result elements
   returned by the existing engine.
7. **Does `searchJson` sort results?** No. Its indexed loop preserves the order
   received from `SearchEngine`.
8. **Why test `i > 0` before a comma?** The first array element needs no
   separator; every later element needs exactly one preceding comma.
9. **How does an empty list become an array?** The loop runs zero times, and
   the fixed opening and closing brackets form `[]`.
10. **What enables chained append calls?** Each append returns the same mutable
    StringBuilder for the next operation.
11. **What does `result.getDocument().getFilename()` do?** It obtains the
    referenced Document and then its filename string.
12. **Why is score unquoted?** The contract represents it as a JSON number
    rather than numeric-looking string data.
13. **Is score now mathematically exact?** No. It is still a Java double;
    Task 2.3 removed two-decimal display formatting, not floating-point rounding.
14. **Why must quotes and backslashes be escaped?** Otherwise they can change
    JSON string boundaries or escape interpretation instead of remaining data.
15. **Does the arrow switch need breaks?** No. Arrow cases do not fall through
    to following cases.
16. **Does charAt return a complete Unicode character every time?** It returns
    one UTF-16 code unit; supplementary code points require two units.
17. **What is 0x20?** Decimal 32, the space value. Lower values are the low
    controls handled by escapes.
18. **What does `character >> 4` compute here?** The high hex digit for the
    nonnegative control value.
19. **What does `character & 0xf` compute?** The low four bits, which select
    the low hexadecimal digit.
20. **How do digit and forDigit differ?** `digit` reads a character into a
    numeric digit value; `forDigit` converts a numeric digit value to a character.
21. **Why measure response.length rather than text.length()?** The encoded
    byte count determines the body length; UTF-8 bytes and UTF-16 units differ.
22. **Does sendJson validate JSON?** No. It sets the media type through the
    shared response writer and sends the prepared text.
23. **Why is no match HTTP 200?** The request is valid and search completed;
    the result collection happens to be empty.
24. **What happens to GET health?** It still returns exactly `OK` as plain
    text, not a JSON string token or object.
25. **What does Allow: GET mean?** GET is the supported method for that known
    endpoint. It is not an authentication decision.
26. **How would you debug malformed JSON?** Inspect status, media type, and
    raw body; check delimiter placement, escaped string values, and numeric
    values before assuming the IR engine is wrong.
27. **Do seven baseline tests validate JSON escaping?** No. Separate HTTP
    round-trip checks supplied that evidence for the exercised inputs.
28. **Can every Java double be appended as valid JSON?** No. Non-finite values
    are invalid JSON numbers and this helper has no explicit finiteness guard.
29. **Does the Unicode test prove malformed-surrogate validation?** No. It
    tested valid text; the serializer does not validate arbitrary UTF-16 pairing.
30. **What makes the next browser milestone possible?** The client can read a
    stable query, ordered result array, filenames, scores, and validation error.

## 13. Personal practice and reflection

These are exercises to complete personally, not statements of experiences
already achieved.

### 13.1 Tracing exercises

- Draw the builder contents after opening the object, adding the query,
  appending each of two result objects, and closing the structure.
- Trace the same loop with an empty list and explain every remaining delimiter.
- Convert U+0002, U+0010, and U+001E into their two calculated hex digits.
- Compare a real newline with an input backslash followed by `n`. Predict
  the JSON text and what a parser would reconstruct.
- Trace a string containing one ordinary letter, one double quote, a tab,
  and one backslash, including the opening and closing quotes.
- Explain how an emoji can use two `charAt` iterations and still be preserved
  by the complete response pipeline when its surrogate pair is valid.
- Predict the status and media type for missing q, an unknown term,
  POST search, GET health, and GET an unknown path.

### 13.2 Reflection prompts

- Which distinction can I explain clearly: Java object versus JSON text,
  URL escaping versus JSON escaping, or UTF-16 versus UTF-8?
- Can I explain the two bitwise operations without memorizing their output?
- What does each prior check actually establish, and what would need a new
  fixture or a new integration check?
- If score formatting looks different in a future UI, how would I determine
  whether ranking or only display formatting changed?
- What did I personally find difficult, and which specific example helped me
  understand it? Record an actual experience only after doing the exercise.

## 14. Summary and next milestone

Task 2.3 gives the existing HTTP-to-IR flow a structured response contract.
`searchJson` selects fields and preserves the ranked list, `jsonString`
escapes string values, and the shared HTTP writer encodes the result as UTF-8.
Handled validation errors retain their HTTP meaning while becoming JSON.

A future HTML/CSS/JavaScript frontend can URL-encode the submitted query, make
a request, inspect its status and media type, parse the JSON search body, and
render filenames and scores or an empty-state message. It must also handle
network failures and the body's actual format, including the preserved
bodyless routing errors. Those are next-step design considerations, not an
implemented browser interface.

The structured API prepares that client integration while keeping the
manually implemented search core explainable for SPL-1. The next milestone
still requires implementation and verification of the browser experience.

Return to the [learning index](README.md), the
[Phase 2.2 manual](02-02-http-search-api.md), or the
[project overview](../README.md).
