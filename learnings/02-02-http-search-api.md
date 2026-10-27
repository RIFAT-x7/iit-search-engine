# Phase 2.2A and 2.2B: Connecting the Java search engine to HTTP

**Student:** Rifat Al Maruf (BSSE1705)

**Course:** SPL-1, Institute of Information Technology, University of Dhaka

**Project:** IIT Search Engine (`RIFAT-x7/iit-search-engine`)

**Record date:** October 27, 2026

## 1. Objective, scope, and historical implementation status

This manual explains how the existing Java Information Retrieval (IR) engine
became accessible through HTTP. Phase 2.2A initialized the index at server
startup. Phase 2.2B added query parsing, validation, retrieval, and ranked
plain-text responses through `GET /search?q=...`.

The purpose is to understand both the architecture and the Java statements
that implement it. Read this alongside [Phase 1](01-phase1-foundations.md)
and [Phase 2.1](02-01-http-health-endpoint.md). Those earlier documents describe
their own historical milestones; their statements that search integration was
still planned are accurate for those earlier stages.

| Milestone | Evidence in Git | Implemented behavior |
| --- | --- | --- |
| Phase 2.2A | `39890b412ae940cff0515288c0f9f75ae2719c6a` | Build the search index before starting the HTTP service |
| Phase 2.2B | `19ceddf01d42bc61da0f13d1fef8d9ede51fdf02` | Validated HTTP search with plain-text responses |
| Later Task 2.3 | `e7e2d836333a3efdda27fa68b4aa81aa75c6995c` | JSON search responses and JSON validation errors |

The documentation checkpoint is branch `feat/http-search-api` at the Task 2.3
commit above. JSON is already implemented there, but **JSON was not part of
Phase 2.2**. Unless a passage explicitly says otherwise, server excerpts and
response examples below describe the historical Phase 2.2B snapshot, inspected
with:

```powershell
git show 19ceddf01d42bc61da0f13d1fef8d9ede51fdf02:src/SearchServer.java
```

`git show` reads a historical file without replacing the current working file.
The current [SearchServer.java](../src/SearchServer.java) link therefore opens
the later JSON version. The IR explanations use the inspected
[SearchEngine](../src/SearchEngine.java), [SearchResult](../src/SearchResult.java),
and [Document](../src/Document.java) classes.

## 2. Starting point: what Phase 2.1 already provided

Phase 2.1 introduced a separate `SearchServer.main()` entry point using the
JDK's `HttpServer`, with no web framework. It listened on IPv4 loopback
`127.0.0.1:8080`. Exact `GET /health` returned HTTP 200 and the body `OK`,
encoded as UTF-8 plain text with no newline.

That implementation also checked exact paths, returned 404 for unknown paths,
returned 405 with `Allow: GET` for unsupported methods on the health endpoint,
and closed streams and exchanges. A command-line HTTP client could test it;
there was no HTML, CSS, or JavaScript browser interface.

At that point, the server did not construct a `SearchEngine`. The CLI already
used the IR core, but HTTP requests could not search the collection. A working
network listener and a usable document index are separate responsibilities.

## 3. Phase 2.2A: initialize the search engine at startup

### 3.1 The actual startup statements

These statements were inserted before `HttpServer.create(...)`:

```java
SearchEngine engine = new SearchEngine();
engine.buildIndex("data");
System.out.println("Indexed " + engine.getDocumentCount() + " documents.");
```

1. `SearchEngine` is the variable's declared type; `engine` is its name.
   `new SearchEngine()` creates an object, and `=` stores its reference.
   Construction initializes the engine's processor and empty data structures;
   it does not itself load the document folder.
2. `engine.buildIndex("data")` calls a method on that object. The string
   argument identifies the relative input directory. This method returns
   `void`: its effect is to populate the engine rather than return results.
3. `getDocumentCount()` returns an `int`. The `+` operations combine the text
   and count into a log message. `System.out.println` writes to the server
   console, not to an HTTP response.

The working directory must be the repository root for `data` to identify the
sample collection. The recorded startup log was `Indexed 6 documents.`
The six files are fictional demonstration data, not official IIT documents.

### 3.2 What building the index does

`SearchEngine.buildIndex` selects immediate regular `.txt` files, sorts them
by filename, reads their contents as UTF-8, and assigns document IDs starting
at 1. It preprocesses the text and adds term frequencies to the inverted index:

```text
term -> (document ID -> number of occurrences)
```

The method builds fresh lists and an index before assigning them to the
engine fields. A normal file-read failure therefore does not replace an
existing usable index with a partial one. During initial server startup,
however, there is no previously loaded index to fall back on.

The method can throw `IOException`. `main(String[] args) throws IOException`
allows that checked exception to propagate. Because indexing precedes server
creation and startup, an indexing failure prevents this application from
starting its HTTP service. A later port-binding failure is a separate problem:
indexing can succeed even though port 8080 is occupied.

### 3.3 One build, many searches

Indexing performs document loading and preparation. `server.start()` starts
serving HTTP requests. Neither operation substitutes for the other.

The engine is constructed outside the request handler, so requests reuse the
same in-memory index. No request calls `buildIndex`. This avoids rereading all
files for every query. A file edited after startup is not automatically
reindexed; the server has no HTTP rebuild endpoint.

In Phase 2.2A alone, the HTTP endpoint still only exposed health. Capturing and
using `engine` inside the handler became part of Phase 2.2B. The local variable
is effectively final: its reference is never reassigned, so Java permits the
lambda to capture it. This does not mean the object is intrinsically immutable
or that concurrent rebuilding has been implemented safely.

## 4. Phase 2.2B: request-response lifecycle and routing

The integration introduced this path through the application:

```text
HTTP client -> HttpServer -> exact path and method checks
            -> query parsing and decoding -> SearchEngine.search(query)
            -> ordered SearchResult list -> plain-text body -> HTTP client
```

A client sends, for example, `GET /search?q=java`. The method is `GET`, the
path is `/search`, and the raw query is `q=java`. `getRawQuery()` excludes the
question mark. Reading the raw query lets the application split parameters
before decoding encoded separators.

### 4.1 Exact path and method checks

Historical excerpt:

```java
String path = exchange.getRequestURI().getRawPath();
if (!"/health".equals(path) && !"/search".equals(path)) {
    exchange.sendResponseHeaders(404, -1);
} else if (!"GET".equals(exchange.getRequestMethod())) {
    exchange.getResponseHeaders().set("Allow", "GET");
    exchange.sendResponseHeaders(405, -1);
} else if ("/health".equals(path)) {
    sendText(exchange, 200, "OK");
}
```

`exchange` represents one HTTP interaction. The chained method calls obtain
its request URI and then its raw path. `equals` compares string contents;
using `==` would compare references. The literal-first form also avoids
calling a method on a possibly null variable.

`!` negates a Boolean expression. `&&` requires both conditions to be true:
the first branch is taken only when the path matches neither accepted path.
`else if` evaluates later branches only when earlier conditions were false.
Consequently, `POST /unknown` receives 404 before any method check, whereas
`POST /search` receives 405.

The root context `createContext("/", exchange -> { ... })` uses a lambda as
the request handler. Context selection uses prefixes; the explicit checks
prevent `/searching`, `/search/`, or `/health/extra` from becoming aliases.
Raw encoded spellings are not decoded into accepted endpoint paths.
The backlog argument `0` in server creation leaves that setting to the system
default; it does not mean that requests are disabled.

`sendResponseHeaders(404, -1)` and the corresponding 405 call indicate no
response body. These branches do not use `sendText` and do not explicitly set
a plain-text Content-Type. `Allow: GET` declares the supported method; it is
not an authentication permission or a CORS configuration.

### 4.2 Historical endpoint contract

| Request condition | Status | Historical response |
| --- | --- | --- |
| Valid GET search with matches | 200 | Ranked text lines |
| Valid GET search without matches | 200 | `No results found.` plus newline |
| Query validation failure on GET search | 400 | `Bad request: ` plus reason and newline |
| Exact GET health | 200 | Exactly `OK` |
| Unsupported method on either known path | 405 | No body; `Allow: GET` |
| Unknown or suffixed path | 404 | No body |

The search and validation bodies in this table were
`text/plain; charset=utf-8`. A search with no matches is a valid operation,
so it returns 200 rather than 404. Health bypasses search query parsing;
adding a query string to `/health` does not make it a search request.

## 5. Query parsing: require exactly one nonblank q

### 5.1 Read and validate the raw query

Historical excerpt:

```java
private static String parseQuery(String rawQuery) {
    if (rawQuery == null) {
        throw new IllegalArgumentException("Missing q parameter.");
    }
    String query = null;
    for (String parameter : rawQuery.split("&", -1)) {
        int separator = parameter.indexOf('=');
        if (separator < 0) {
            throw new IllegalArgumentException("Parameters must use name=value.");
        }
        String name = decodeParameter(parameter.substring(0, separator));
        String value = decodeParameter(parameter.substring(separator + 1));
        if ("q".equals(name)) {
            if (query != null) {
                throw new IllegalArgumentException("Duplicate q parameter.");
            }
            query = value;
        }
    }
    if (query == null || query.isBlank()) {
        throw new IllegalArgumentException("A nonblank q parameter is required.");
    }
    return query;
}
```

The declaration says the helper is `private` to this class, `static` so it
does not require a `SearchServer` object, and returns a `String`. Its parameter
`rawQuery` receives the caller's raw query value.

1. `null` means there was no query component, as in `/search`. `throw new`
   constructs and throws an exception; normal execution of this helper stops.
   `IllegalArgumentException` is an unchecked exception suitable here for a
   value that violates the helper's input contract. HTTP 400 is chosen by the
   caller's catch block, not automatically by the exception class.
2. `query = null` records that no `q` has been seen. An empty string is
   different from null: `q=` has a value, but it is blank.
3. The enhanced `for` loop visits each parameter produced by `split`.
   The negative limit `-1` retains trailing empty pieces. Thus a trailing `&`
   produces an empty parameter that fails the required `name=value` format.
4. `indexOf('=')` returns the first equals-sign position, or `-1` if absent.
   `substring(0, separator)` extracts the name; the end position is exclusive.
   `substring(separator + 1)` extracts the remaining value. Additional equals
   signs can therefore belong to the value.
5. Both pieces are decoded before comparison. `%71=java` names the same
   parameter as `q=java`; `q=java&%71=network` is therefore a duplicate.
   Names are case-sensitive: `Q` is not `q`.
6. Any previously assigned query, even `""`, makes a second `q` invalid.
   This avoids silently choosing the first or last conflicting value.
7. `||` means OR and short-circuits. If the value is null, Java does not call
   `isBlank()` on it. `isBlank()` rejects empty or whitespace-only strings.
   It does not trim accepted text: `q=+java+` passes the spaces to the engine.
8. `return query` sends the accepted decoded string back to the handler.

Other well-formed names are decoded but ignored; there is no allowlist banning
extra parameters. An invalid encoding in an ignored parameter still fails
because decoding occurs before the name check. `/search?` supplies an empty
raw query rather than null and reaches the `name=value` error. Requiring a
nonblank value does not require useful retained terms: `the and or` passes HTTP
validation but becomes an empty processed query after stop-word removal.

### 5.2 Why split before decoding?

For `q=java%26network`, the raw string contains one parameter. Its value
decodes to `java&network`. If decoding happened first, splitting on `&` could
incorrectly treat `network` as a second parameter. Transport decoding restores
the intended query text; the IR processor later decides how that text becomes
search terms.

## 6. URL decoding and strict UTF-8

### 6.1 Assemble decoded bytes

Historical excerpt:

```java
byte[] input = encoded.getBytes(StandardCharsets.UTF_8);
ByteArrayOutputStream decoded = new ByteArrayOutputStream();
for (int i = 0; i < input.length; i++) {
    if (input[i] == '%') {
        if (i + 2 >= input.length) {
            throw new IllegalArgumentException("Incomplete URL escape.");
        }
        int high = Character.digit((char) input[i + 1], 16);
        int low = Character.digit((char) input[i + 2], 16);
        if (high < 0 || low < 0) {
            throw new IllegalArgumentException("Invalid URL escape.");
        }
        decoded.write(high * 16 + low);
        i += 2;
    } else {
        decoded.write(input[i] == '+' ? ' ' : input[i]);
    }
}
```

`byte[]` is an array of bytes. `StandardCharsets.UTF_8` supplies an explicit
encoding rather than relying on the machine's default. Percent escapes
represent bytes, so the code assembles bytes before interpreting text.

`ByteArrayOutputStream` is a growable output buffer held in memory. Here,
`write(int)` appends the low eight bits as one byte. It does not send data to
the network or write a file. A resizable buffer is useful because `%20` uses
three input bytes to represent one output byte.

The ordinary `for` loop initializes `i` to zero, checks the array bound before
each iteration, and increments the index with `i++`. If the current byte is
`%`, two following hexadecimal digits are required. The bound check prevents
reading beyond the array for an incomplete escape such as `%2`.

`(char)` casts an individual byte value to the character type expected by the
chosen `Character.digit` call. With radix `16`, digits `0` to `9` and letters
`A` to `F` or `a` to `f` yield values 0 to 15. An invalid digit yields `-1`.
For `%2B`, the values are 2 and 11, and `2 * 16 + 11 = 43`, the byte for `+`.
`i += 2` skips the two consumed digits; the loop's own increment then advances
past the complete escape.

The expression `condition ? valueIfTrue : valueIfFalse` is Java's conditional
operator. In the non-percent branch it converts a literal `+` into a space,
or copies the current byte otherwise. This is the query convention this API
implements. A percent-encoded plus `%2B` stays a plus because it is written
directly in the percent branch and is not decoded a second time.

### 6.2 Interpret bytes as valid UTF-8 text

Historical excerpt:

```java
try {
    return StandardCharsets.UTF_8.newDecoder()
            .decode(ByteBuffer.wrap(decoded.toByteArray())).toString();
} catch (CharacterCodingException invalidEncoding) {
    throw new IllegalArgumentException("Query parameters must use valid UTF-8.");
}
```

`toByteArray()` returns the accumulated bytes. `ByteBuffer.wrap(...)` places
that array behind the buffer interface required by the decoder; it does not
perform decoding. `newDecoder()` creates a UTF-8 decoder whose default error
action reports malformed or unmappable input. `decode(...)` converts the
buffer to characters, and `toString()` returns those characters as a string.

For example, `%C3%A9` supplies two bytes that together represent `é`. `%C3%28`
has individually valid hexadecimal escapes but an invalid UTF-8 sequence.
The decoder throws `CharacterCodingException`, and the catch block translates
it into the validation exception handled by the HTTP layer. This prevents
silently replacing damaged input with replacement characters.

Malformed percent syntax and invalid UTF-8 are different failures. `%ZZ` is
not hexadecimal; `%FF` is a valid hex escape for a byte that cannot stand alone
in UTF-8. Also distinguish helper behavior from wire behavior: an HTTP parser
or client may reject, normalize, or encode an invalid URI before this helper
receives it. A received 400 alone does not prove which layer produced it.

Accepting UTF-8 transport does not give the IR engine general Unicode word
support. Its tokenizer retains ASCII letters and digits. A correctly decoded
Bengali word may therefore produce no searchable tokens in this prototype.

## 7. Calling the IR core and preserving ranking

The handler uses the accepted text as follows:

```java
List<SearchResult> results = engine.search(query);
```

`List<SearchResult>` is a generic type: an ordered collection whose elements
are `SearchResult` references. Angle brackets specify the element type so Java
can check uses at compile time. This declaration does not itself construct a
list; `engine.search(query)` returns the list assigned to `results`.

`SearchEngine.search` preprocesses the query, removes repeated terms through
a `LinkedHashSet`, looks up postings, and accumulates scores. The formula is:

```text
idf(term) = ln((N + 1) / (df(term) + 1)) + 1
score(document, query) = sum of raw_tf(term, document) * idf(term)
```

`N` counts loaded documents, `df` counts documents containing a term, and raw
TF counts occurrences in one document. This is a smoothed TF-IDF-style sum;
it has no length normalization or cosine similarity. Matching any retained
term can produce a result, so ordinary multi-word retrieval has OR semantics.
The words `and` and `or` are stop words, not implemented Boolean operators.

The engine selects positive-score documents and uses insertion sort for
descending score order. Because documents initially follow filename order and
the comparison moves only strictly smaller scores, ties retain filename order.
The HTTP layer iterates the returned list without reordering it.

The result classes explain the access chain:

```java
public Document getDocument() { return document; }
public double getScore() { return score; }
```

These are actual methods from `SearchResult`. Each result holds a `Document`
reference and a numeric `double` score. `Document` holds a final integer ID and
filename and provides `getId()` and `getFilename()`. Thus
`result.getDocument().getFilename()` first returns the document, then obtains
its filename. `result.getScore()` returns a number, not a preformatted string.
The objects do not contain snippets, full document text, or download URLs.

## 8. Historical plain-text response construction

### 8.1 Results, loops, and formatting

Historical excerpt:

```java
if (results.isEmpty()) {
    sendText(exchange, 200, "No results found.\n");
} else {
    StringBuilder response = new StringBuilder();
    for (int i = 0; i < results.size(); i++) {
        SearchResult result = results.get(i);
        response.append(String.format(Locale.ROOT, "%d. %s  Score: %.2f%n",
                i + 1, result.getDocument().getFilename(), result.getScore()));
    }
    sendText(exchange, 200, response.toString());
}
```

`isEmpty()` returns a Boolean. The empty branch sends a successful explanatory
message. It does not create an empty JSON array in this historical version.

`StringBuilder` accumulates characters in a mutable buffer, making it suitable
for adding an unknown number of result lines. The indexed loop starts at list
position zero and stops before `size()`. `get(i)` returns that result;
`i + 1` creates a human-readable rank starting at 1.

`String.format` produces one string. `%d` displays the integer rank, `%s` the
filename, `%.2f` the floating-point score rounded to two decimal places, and
`%n` a platform line separator. The two literal spaces before `Score` are part
of the format. `Locale.ROOT` keeps numeric formatting independent of the
machine's display locale, including use of the decimal point here. Finally,
`append` adds the line and `toString()` obtains the completed body.

Recorded historical response for `java`:

```text
1. java_programming.txt  Score: 5.54
2. academic_notice.txt  Score: 1.85
```

The underlying scores remain `double` values; displaying two decimal places
does not alter engine ranking. This representation was convenient for manual
inspection, but a browser program would need to parse labels and punctuation
to recover fields.

### 8.2 UTF-8 body bytes and resource cleanup

Historical helper:

```java
private static void sendText(HttpExchange exchange, int status, String text) throws IOException {
    byte[] response = text.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
    exchange.sendResponseHeaders(status, response.length);
    try (OutputStream output = exchange.getResponseBody()) {
        output.write(response);
    }
}
```

The arguments supply the current exchange, chosen HTTP status, and body text.
The method returns `void`. First it encodes the text, then sets the media type
and charset, then sends headers with the number of encoded bytes. Byte count
matters because one character may require several UTF-8 bytes.

`try (OutputStream output = ...)` is try-with-resources: Java closes the
response stream after the block, including when writing throws an exception.
`throws IOException` declares that network I/O failure may propagate.

The historical handler separately catches input errors:

```java
try {
    query = parseQuery(exchange.getRequestURI().getRawQuery());
} catch (IllegalArgumentException invalidQuery) {
    sendText(exchange, 400, "Bad request: " + invalidQuery.getMessage() + "\n");
    return;
}
```

`try` contains the potentially failing parse operation. A matching `catch`
receives the thrown exception in `invalidQuery`; `getMessage()` retrieves its
explanation. `return` exits this handler invocation, so invalid input never
reaches `engine.search`. It does not exit the whole server process.

The surrounding handler uses `finally { exchange.close(); }`. A `finally`
block runs after ordinary completion, a return, or an exception leaving the
try block. It completes cleanup for health, search, and routing failures.
Closing an exchange ends that interaction; it does not stop the listener.
These cleanup constructs do not turn every possible runtime failure into a
custom HTTP error response: the explicit 400 handling covers query validation.

## 9. Worked request examples

These traces explain the historical implementation. Section 10 identifies
which cases have recorded runtime evidence; a trace alone is not a test result.

### 9.1 GET /search?q=java

1. The raw path is accepted and the method is GET.
2. Parsing separates `q` from `java`; decoding leaves both unchanged.
3. The nonblank value returns to the handler.
4. The existing index supplies two matches. With six documents and `df=2`,
   IDF is `ln(7/3)+1`, approximately 1.8473. Java appears three times in
   `java_programming.txt` and once in `academic_notice.txt`.
5. The engine returns the higher score first; the server formats scores as
   5.54 and 1.85 and sends HTTP 200 plain text.

### 9.2 GET /search?q=java%20programming

The `%20` becomes a space byte and then part of the string `java programming`.
The alternative `q=java+programming` produces the same value. The IR processor
retains two distinct terms, and their contributions are added. The recorded
first score was 7.79 and the second 1.85. This is ranked OR retrieval, not a
requirement that every returned document contain both words.

### 9.3 GET /search?q=java%26network%3Dguide

Splitting sees one parameter. `%26` becomes `&` and `%3D` becomes `=`, yielding
`java&network=guide`. The IR tokenizer treats this punctuation as separators.
There is no extra parameter named `network` and no Boolean operator introduced
by the decoded ampersand. This exact trace is source-derived; the historical
log records acceptance of encoded ampersand and equals without preserving this
specific combined example.

### 9.4 GET /search?q=unknownword

The query is syntactically valid and nonblank. No postings match, so the engine
returns an empty list. The server returns 200 with `No results found.\n`.
A stop-word-only query also reaches an empty list, but that particular HTTP
case is a source-derived explanation here, not an additional historical test.

### 9.5 Missing, blank, and duplicate values

`/search` produces null raw query and the missing-parameter exception.
`/search?q=` assigns an empty value and fails `isBlank()`.
`/search?q=+%20%09` decodes to spaces and a tab and is also blank.
`/search?q=java&q=network` fails when the second `q` is encountered.
The catch block sends a 400 text body and returns in each case.

### 9.6 Invalid encodings and routing failures

For `/search?q=%C3%28`, percent decoding assembles bytes but strict UTF-8
decoding fails, leading to 400. For `%ZZ`, the intended decoder check detects
invalid hexadecimal digits, though HTTP-layer URI validation may reject it
earlier. An incomplete `%2` is covered by the helper's bound check; no separate
historical HTTP result for `%2` is claimed.

`POST /search?q=java` reaches 405 with `Allow: GET` before parsing. `GET
/search/` reaches 404 because its path is not exact. `GET /health` sends `OK`
without calling the search method.

## 10. Testing, recorded evidence, and reproducibility

### 10.1 Seven IR regression tests

[BaselineTests.java](../tests/BaselineTests.java) contains seven named tests:

| Test | Main purpose |
| --- | --- |
| Text preprocessing | Normalization, separators, stop words, numbers |
| Inverted-index correctness | Postings, frequencies, term and token counts |
| Keyword retrieval | Expected matching documents |
| Ranking | Ordering and expected score arithmetic |
| Empty and unknown queries | Empty, blank, unknown, stop-word-only engine input |
| Rebuild correctness | Consistent results after repeated builds |
| Failed rebuild preservation | Keep the earlier index after a failed rebuild |

These use temporary fixtures and explicit assertion failures. They do not
start an HTTP server or verify query decoding, HTTP headers, or status codes.
The historical development record reports successful JDK 25.0.3 compilation
and all seven passing with zero failures. The later Task 2.3 session also
recorded 7 passed and 0 failed; that later run concerns the JSON checkpoint,
not a fresh execution of the historical server.

### 10.2 Historical HTTP integration evidence

The original Phase 2.2B verification tool output was inspected while preparing
this manual. It reported the following outcomes for the plain-text server:

| Recorded check | Recorded outcome |
| --- | --- |
| GET health | 200, exact `OK`, UTF-8 Content-Type |
| `q=java` | 200, exact ranked text with scores 5.54 and 1.85 |
| `q=java%20programming` compared with plus encoding | Same result text; scores 7.79 and 1.85 |
| `q=unknownword` | 200, `No results found.` |
| Missing q, empty q, whitespace-only q, parameter without equals | 400 |
| Duplicate q, including encoded second name `%71` | 400 |
| `%ZZ`, `%C3%28`, `%FF` inputs | 400 observed; the log alone does not attribute every rejection to the custom decoder |
| POST search and POST health | 405 with `Allow: GET` |
| `/unknown`, `/search/`, `/searching`, `/health/` | 404 |
| Valid UTF-8 and encoded ampersand/equals | Accepted, according to the recorded check |
| Valid request after malformed requests | Server remained usable |
| Startup and cleanup | Six documents indexed; owned verification server stopped |

No aggregate HTTP test count is asserted. The recovered output supports these
named observations; it does not establish arbitrary-input correctness or
performance. In particular, it does not preserve an exact response body for
every accepted encoded-value case.

The earlier health manual records an occupied-port retry during its own
documentation workflow. That failed launch did not reverify endpoints. The
later successful plain-text search verification is separate evidence. Neither
event should be rewritten as a different outcome.

No Java compilation, baseline run, or HTTP launch was performed for this
documentation-only task. This manual records inspected source and prior
verification, rather than claiming fresh historical runtime results.

### 10.3 Commands for a later controlled check

The documented toolchain uses explicit executable paths to avoid accidentally
launching an older Java runtime on PATH:

```powershell
$projectJdk = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'
& "$projectJdk\bin\javac.exe" -version
& "$projectJdk\bin\java.exe" -version
& "$projectJdk\bin\javac.exe" -encoding UTF-8 -d out\app src\*.java tests\BaselineTests.java
# Continue only if compilation succeeds.
& "$projectJdk\bin\java.exe" -cp out\app BaselineTests
```

From the repository root, a separate controlled server run would use:

```powershell
& "$projectJdk\bin\java.exe" -cp out\app SearchServer
```

An HTTP client can then inspect responses:

```powershell
curl.exe --noproxy '*' -i 'http://127.0.0.1:8080/search?q=java'
curl.exe --noproxy '*' -i 'http://127.0.0.1:8080/search?q=java%20programming'
curl.exe --noproxy '*' -i 'http://127.0.0.1:8080/search'
curl.exe --noproxy '*' -i -X POST 'http://127.0.0.1:8080/search?q=java'
```

These are checking instructions, not newly executed results. On the current
checkout they exercise Task 2.3 JSON behavior. Reproducing historical plain text
requires separately compiling the historical snapshot in an isolated location;
`git show` alone does not change executable classes. Do not overwrite the
current source merely to compare formats. Start only when port 8080 is free,
and stop only the identified instance launched for the check. An occupied port
does not authorize terminating an unknown process.

## 11. Architectural significance and limitations

The HTTP layer adapts a request to a call on the existing manually implemented
IR engine. `SearchEngine` owns preprocessing, indexing, retrieval, and ranking;
`SearchServer` owns routing, input decoding, statuses, and response formatting.
The CLI and HTTP server are separate entry points that reuse the same core
classes. They do not automatically share one live index across separate Java
processes.

This separation allowed the external interface to develop without replacing
the academic algorithms. The built-in HTTP server supplies networking, not
search functionality. No Lucene, Elasticsearch, or Solr is used, and the
historical endpoint introduced no external Java dependency.

Limitations at Phase 2.2 included:

- Loopback-only HTTP on fixed port 8080; no verified cross-computer service.
- No browser frontend or static page hosting.
- Immediate text files only; no PDF extraction or OCR.
- No fuzzy search, substring search, phrase retrieval, or Boolean parser.
- ASCII token processing despite UTF-8 input transport.
- An in-memory index built once, with no persistence or automatic refresh.
- All matches returned, with no pagination, Top-K limit, or snippets.
- No implemented TLS, authentication, configurable request limits, or verified
  load-handling guarantees.

The successful health response is a simple liveness observation. Startup now
builds an index before serving, but `/health` does not perform a new index
diagnostic on every request. Successful indexing also does not prove the
collection is nonempty or academically representative.

## 12. Supervisor viva preparation

1. **What changed in Phase 2.2A?** The server created `SearchEngine` and built
   its index before starting HTTP service. It still had only health routing.
2. **Why build once?** Loading and preprocessing are preparation work. Requests
   reuse postings instead of rereading documents for every query.
3. **What does `List<SearchResult>` mean?** It is an ordered list restricted
   by Java generics to `SearchResult` elements. `search` returns that list.
4. **Does `new SearchEngine()` read the files?** No. `buildIndex("data")` is
   the explicit method call that loads and indexes them.
5. **Why inspect the raw query?** The application must split raw parameter
   separators before decoding `%26` into a possible ampersand inside a value.
6. **Why reject duplicate q values?** Exactly one search value is required;
   choosing an arbitrary first or last value would hide an ambiguous request.
7. **How do null and blank differ?** Null records absence. An empty or
   whitespace-only string exists but fails the nonblank requirement.
8. **What does `Character.digit(c, 16)` return?** A hexadecimal digit value,
   or `-1` when the character is invalid for that radix.
9. **Why multiply the first hex digit by 16?** In a two-digit hexadecimal
   byte, the first digit represents sixteens and the second represents units.
10. **Why use `ByteArrayOutputStream` and `ByteBuffer` together?** The first
    accumulates decoded bytes; the second exposes those bytes to the charset
    decoder. Neither is an inverted index or a network connection.
11. **Why strict UTF-8?** Hexadecimal validity alone does not establish valid
    text. Reporting malformed sequences avoids silently altering the query.
12. **How do try, catch, and finally contribute?** Try runs an operation,
    catch handles a matching exception, and finally completes cleanup when
    control leaves the block, including through the validation return.
13. **Does IllegalArgumentException itself send HTTP 400?** No. The handler
    catches it and explicitly calls the response helper with status 400.
14. **What does `input[i] == '+' ? ' ' : input[i]` mean?** Select a space
    for a literal plus and otherwise select the current byte.
15. **How are results ranked?** The core sums raw TF times smoothed IDF over
    distinct query terms and sorts descending, retaining filename order on ties.
16. **Why use StringBuilder?** The server accumulates one formatted line per
    result, then obtains a single response string with `toString()`.
17. **Why does the response length use bytes?** HTTP body length concerns
    encoded bytes, and UTF-8 may use several bytes for one character.
18. **How do 400, 404, and 405 differ here?** Invalid query input gives 400;
    an unknown path gives 404; an unsupported method on a known path gives
    405 with `Allow: GET`.
19. **Do seven passing baseline tests prove HTTP correctness?** No. They
    exercise the IR core; HTTP integration checks provide separate evidence.
20. **Was JSON implemented in Phase 2.2?** No. Plain-text search was its
    response contract. Task 2.3 subsequently introduced JSON serialization.

## 13. Student reflection and self-practice

Complete these personally. They are prompts, not claims about experiences or
understanding already achieved.

- Draw the path from one HTTP request to postings and back to response bytes.
- Explain each method call in `result.getDocument().getFilename()` aloud.
- Trace `q=java%2Bnetwork` and `q=java+network`; identify the decoded difference
  even if later tokenization gives similar search terms.
- Predict the parser outcomes for `q=java&`, `%71=java`, `Q=java`, and
  `q=java&extra=value`. Label predictions before comparing with evidence.
- Calculate `%20`, `%26`, and `%2B` by converting their two hexadecimal digits.
- Explain why a valid UTF-8 word can still yield no tokens in this IR engine.
- Calculate the `java` scores using six documents and document frequency two.
- Identify where an indexing IOException, a validation exception, and a write
  IOException would leave the normal execution path.
- Compare the historical `sendText` helper with the current JSON helpers.
- Write a reflection on whichever part you personally found difficult, using
  an actual example rather than treating these notes as a completed reflection.

## 14. How Phase 2.2 prepared the architecture for Task 2.3 JSON

Phase 2.2 established a reusable server-side flow: initialize the core once,
validate the request, decode one query, call `search`, and receive ordered
objects containing filenames and numeric scores. The final presentation step
was therefore a defined place to introduce another representation.

Task 2.3 replaced historical search text with a JSON object containing `query`
and `results`, each result containing `filename` and a numeric `score`. Empty
search results became an empty array. Validation bodies became JSON objects
with an `error` field while retaining HTTP 400. JSON string escaping and
`application/json; charset=utf-8` were added at that later stage.

The current source confirms this later change through `searchJson`,
`jsonString`, and `sendJson`. It serializes scores as numbers rather than
formatting them into two-decimal text lines. The existing engine and its result
order remain the source of ranking. Health, exact path checks, unsupported
method behavior, and `Allow: GET` were preserved.

Thus Phase 2.2 supplied the HTTP-to-IR integration needed by a future browser
client, while Task 2.3 supplied the structured response format. Neither phase
by itself implemented the browser frontend or established cross-computer
operation.

Return to the [learning index](README.md) or [project overview](../README.md).
