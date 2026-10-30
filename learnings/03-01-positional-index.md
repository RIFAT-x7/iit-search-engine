# Phase 3.1A: Positional inverted index

**Student:** Rifat Al Maruf (BSSE1705)

**Course:** SPL-1, Institute of Information Technology, University of Dhaka

**Record date:** October 31, 2026

**Project:** IIT Search Engine

## 1. Objective, checkpoint, and sources

Phase 3.1A adds token positions to the existing in-memory index. Its implementation
commit is `c771ed898ee2a02f26725984c59d7d778dfad3d0` on
`feat/positional-index`, based on the Phase 1-2 main checkpoint
`86d9d2b930ac4f919cde8db93f8936ae19604291`. This manual describes that
implementation; phrase retrieval and quotation-mark query grammar remain future work.

Read these actual sources alongside the explanations:

- [TextProcessor.java](../src/TextProcessor.java): normalization, tokenization, and stop words.
- [InvertedIndex.java](../src/InvertedIndex.java): frequency and positional postings.
- [SearchEngine.java](../src/SearchEngine.java): building, retrieval, scoring, and rebuilds.
- [PositionalIndexTests.java](../tests/PositionalIndexTests.java): thirteen focused tests.

These notes were prepared with Codex assistance. They explain the code and the
recorded checks without claiming personal learning experiences or independent
student verification. Earlier manuals retain their milestone context; for example,
the [Phase 1 manual](01-phase1-foundations.md) describes the original frequency-only index.

## 2. What an inverted index answers

A document naturally contains a sequence of words. An inverted index reverses
that relationship: start with a term and find the documents containing it.
This avoids scanning every document's text for each keyword query.

A posting is information about one term in one document. The existing index
stores raw term frequency (TF), the number of occurrences in that document:

```text
java -> {1=2, 2=1}
```

This means two occurrences in document 1 and one in document 2. Document frequency
(df) counts matching documents, so df(java) is 2, not 3.

Counts cannot distinguish `java search`, `search java`, and `java useful search`:
each contains one occurrence of each keyword. Positions preserve order and gaps.
For the first text, `java` is at 0 and `search` at 1; in the second they are at
1 and 0; in the third `search` is at 2. A future phrase retriever can use these
differences. The current keyword search still uses frequency postings.

## 3. The exact data structures

These declarations come from `InvertedIndex`:

```java
private final Map<String, Map<Integer, Integer>> postings = new HashMap<>();
```

```java
private final Map<String, Map<Integer, List<Integer>>> positionalPostings = new HashMap<>();
private final Map<Integer, Integer> documentLengths = new HashMap<>();
private int totalTokens = 0;
```

| Field | Meaning | Example |
| --- | --- | --- |
| `postings` | term -> document ID -> raw searchable frequency | `java -> {1=2}` |
| `positionalPostings` | term -> document ID -> ascending token positions | `java -> {1=[0, 2]}` |
| `documentLengths` | document ID -> number of supplied tokens indexed so far | `1 -> 4` |
| `totalTokens` | total searchable token occurrences across the index | `3` for the worked example below |

Read nested generics from the inside outward. `List<Integer>` holds positions;
`Map<Integer, List<Integer>>` associates each document ID with its positions;
the outer `Map<String, ...>` associates each term with that document map.
Java collections use `Integer`, the object wrapper for primitive `int`; Java
automatically boxes and unboxes the values used here.

`Map` and `List` are interfaces; `HashMap` and `ArrayList` are their concrete
implementations. `<>` lets the compiler infer generic types. A `HashMap` does
not promise document or term iteration order. Each position list is ascending
because indexing visits tokens in sequence and appends their positions.
`final` prevents replacing the field's map reference; it does not prevent
changing the map contents while indexing.

Keeping frequency postings alongside positions preserves the existing retrieval
path and statistics. It costs extra memory, but makes this small milestone easy
to understand and keeps positional data available for later work.

## 4. tokenize() versus process()

Both methods use the same character scanner in `TextProcessor.tokenize()`.
The scanner lowercases with `Locale.ROOT`, accepts ASCII `a` through `z` and
`0` through `9`, and finishes a token when it encounters a separator.
`Locale.ROOT` avoids depending on the machine's default language setting.
The scanner's extra final iteration uses a space to flush the last word.

The difference is what happens to stop words. Actual `process()` code:

```java
public List<String> process(String text) {
    List<String> tokens = new ArrayList<>();
    for (String token : tokenize(text)) {
        if (!isStopWord(token)) {
            tokens.add(token);
        }
    }
    return tokens;
}
```

For `Java and Java Search`:

| Method | Returned sequence | Purpose |
| --- | --- | --- |
| `tokenize()` | `[java, and, java, search]` | Complete sequence for positional indexing |
| `process()` | `[java, java, search]` | Existing filtered keyword processing |

The twelve configured stop words are:

```text
a an the and or is are of to in for with
```

`isStopWord()` is now package-private and static. Classes in this repository's
same default package can call it without making a `TextProcessor` instance.
Sharing that check avoids maintaining a second stop-word list in the index.
Sharing the scanner avoids two tokenizers gradually disagreeing about punctuation.

### Positions count tokens, not characters

The first normalized token has position 0. `Java,,Search!` produces positions
0 and 1: punctuation separates words but does not consume token positions.
`Java and Search` puts `search` at 2, because `and` is a real token.
Hyphens and underscores also separate tokens; numbers remain tokens.
Positions are neither character offsets nor original file byte offsets.

Removing stop words before recording positions would compress these gaps.
Retaining both their positions and their identities lets future phrase work
distinguish `java and search` from `java or search`.

## 5. How the three classes cooperate

`SearchEngine.buildIndex()` reads UTF-8 text from sorted immediate `.txt` files,
creates each `Document`, and passes the complete sequence to the index:

```java
newIndex.addDocumentWithPositions(document, processor.tokenize(text));
```

`InvertedIndex.addDocumentWithPositions()` calls the shared indexing helper
with stop-word filtering enabled for frequency postings. The helper records
the position first, then decides whether to update searchable frequency:

```java
positionalPostings.computeIfAbsent(term, key -> new HashMap<>())
        .computeIfAbsent(documentId, key -> new ArrayList<>()).add(position++);
if (filterStopWords && TextProcessor.isStopWord(term)) {
    continue;
}
```

`continue` skips the rest of the current loop iteration. Consequently, a stop
word already has a recorded position, but it does not increment its searchable
frequency or `totalTokens`. Other terms update the original frequency map.
Both structures are populated during the same pass over the same input sequence.

### Reading computeIfAbsent()

The chained expression performs three steps:

1. Find the term's document map. If absent, create and store a new `HashMap`.
2. Find that document's position list. If absent, create and store an `ArrayList`.
3. Append the current position to that list.

`key -> new HashMap<>()` is a lambda: a small function supplied to the map.
Its `key` parameter is the missing key; this function only needs to create
an empty container. `computeIfAbsent()` returns either the existing value or
the newly stored value, which makes the next method call possible.
`position++` supplies the current number to `add()` and then increments it.
Thus a new document's first token is recorded at 0, not 1.

For searches, `SearchEngine.search()` still calls `processor.process(query)`
and reads `index.getPostings(term)`. The engine's new
`getPositionalPostings(term)` simply delegates to the index for future retrieval
and testing. No HTTP endpoint was added for this data.

## 6. Step-by-step trace: Java and Java Search

Suppose this text is indexed as document ID 1 in a fresh index.

1. `tokenize()` produces `[java, and, java, search]`.
2. `documentLengths.getOrDefault(1, 0)` returns 0 because the document is new.
3. The loop performs the following updates:

| Token | Position recorded | Positional list after this step | Searchable update |
| --- | --- | --- | --- |
| `java` | 0 | `java -> {1=[0]}` | java TF becomes 1; totalTokens becomes 1 |
| `and` | 1 | `and -> {1=[1]}` | skipped; totalTokens remains 1 |
| `java` | 2 | `java -> {1=[0, 2]}` | java TF becomes 2; totalTokens becomes 2 |
| `search` | 3 | `search -> {1=[3]}` | search TF becomes 1; totalTokens becomes 3 |

4. The loop finishes with `position == 4`; `documentLengths` stores `{1=4}`.
5. Frequency postings contain `java -> {1=2}` and `search -> {1=1}`. There is
   no searchable `and` entry. Unique searchable terms = 2; processed tokens = 3.
6. A keyword query for the same text becomes `[java, java, search]`, then
   `[java, search]` after query deduplication. It uses the old ranking formula.
   With this single document, each IDF is 1, so the score is `2 * 1 + 1 * 1 = 3`.

## 7. documentLengths, repeated additions, and the legacy API

The helper begins and ends with these actual statements:

```java
int position = documentLengths.getOrDefault(documentId, 0);
```

```java
documentLengths.put(documentId, position);
```

`getOrDefault()` supplies 0 when the ID has no stored length. After indexing,
the final value is the next free token position. This length includes stop
words on the complete-sequence path; it is not the searchable `totalTokens`
count and is not used for score normalization.

Calling `addDocumentWithPositions()` again for ID 1 with `[java]` appends at
position 4 in the worked example. Java's list becomes `[0, 2, 4]`, its TF
becomes 3, and the stored length becomes 5. Reusing an ID directly appends;
it does not replace an existing document. Passing a whole document twice
directly would therefore count and store it twice.

The original public `addDocument(Document, List<String>)` remains compatible:
it calls the helper with frequency filtering disabled and counts every supplied
token verbatim. Even a supplied `and` is counted, and supplied uppercase text
is not normalized by the index. Existing callers normally pass processed tokens.
Positions on this legacy path describe only that supplied sequence; discarded
stop words and original gaps cannot be recovered. Full positions require
`addDocumentWithPositions(document, processor.tokenize(text))`. Neither entry
point modifies the supplied list. Avoid mixing filtered and complete sequences
for one document when original positions matter.

## 8. Rebuilds and immutable snapshots

### A full rebuild starts fresh

`SearchEngine.buildIndex()` constructs a new document list and a new
`InvertedIndex`, so both posting maps, position offsets, and statistics start
empty. Only after all files are read successfully does it assign:

```java
documents = newDocuments;
index = newIndex;
```

This replaces the previous engine state rather than appending into it. Rebuilding
the same collection produces the same positions without duplicates. Changed
documents get their new positions; removed occurrences do not survive the rebuild.
If reading fails, such as on invalid UTF-8 in a later file, these assignments
are not reached and the previous usable index remains. This is failure
preservation for the existing usage, not a promise of thread-safe concurrent rebuilds.

### Returning data without exposing mutable lists

The positional accessor copies a term's postings:

```java
Map<Integer, List<Integer>> snapshot = new HashMap<>();
for (Map.Entry<Integer, List<Integer>> entry : positions.entrySet()) {
    snapshot.put(entry.getKey(), List.copyOf(entry.getValue()));
}
return Collections.unmodifiableMap(snapshot);
```

`Map.Entry` represents one document/list pair. `List.copyOf()` creates an
unmodifiable list with the current positions. Later internal appends cannot
change this copied list. `Integer` values are immutable, so the lists do not
contain mutable position objects that could bypass this protection.

`Collections.unmodifiableMap()` wraps the copied outer document map and
rejects updates, including replacing a value through an entry. Wrapping only
the map would still leave mutable lists exposed; copying the lists closes
that route. Attempts to mutate these collections throw
`UnsupportedOperationException`. A previously returned positional snapshot
remains unchanged after later additions or rebuilds.

Unknown terms return `Collections.emptyMap()`. A known term has no entry for
a document where it is absent; callers can use `getOrDefault(id, List.of())`
when they want an empty list. Accessors expect an already normalized term,
such as `java`; they do not tokenize an arbitrary query for the caller.

The original frequency accessor is different: it returns an unmodifiable
view of the internal frequency map. Callers cannot edit it, but subsequent
direct additions to that same index can appear through the view. The positional
API deliberately returns a snapshot. Copying has a time and memory cost
proportional to the requested term's document entries and position values.

## 9. The thirteen positional tests

The runner uses explicit `AssertionError` checks, so it needs neither JUnit
nor Java's optional `-ea` flag. It prints individual results and totals and
exits with status 1 on failure. Temporary fixtures are separate from the six
demonstration documents; the harness cleans up its own named files.

| Test | What it verifies |
| --- | --- |
| 1. Adjacent tokens | `java search` has positions 0 and 1. |
| 2. Reverse-order tokens | `java search` and `search java` retain different positions under separate document IDs. |
| 3. Tokens separated by other words | `java useful fast search` keeps search at 3, preserving the gap. |
| 4. Repeated and nonconsecutive words | java positions `[0, 1, 3, 5]` retain every occurrence and searchable TF remains 4. |
| 5. Case, punctuation, and ASCII tokens | Mixed case, repeated punctuation, hyphens, underscores, and numbers follow the shared scanner; accented/Bengali text does not become general Unicode word support. |
| 6. Original positions include all stop words | The worked example retains `and`; all twelve stop words occupy their original positions, and following java appears at 12. `process()` still filters them. |
| 7. Stop-word postings preserve searchable frequencies | Complete-sequence indexing stores stop-word positions without searchable entries, while TF, df, unique terms, and token totals remain filtered; stop-only and punctuation-only documents do not inflate these counts. |
| 8. Unknown terms and missing documents | Empty-index and unknown-term lookups return empty maps; absent document IDs have no entry, and lookups do not alter statistics. |
| 9. Deeply read-only positional snapshots | Map, entry, and nested list mutations are rejected; an earlier snapshot stays unchanged after a later append. |
| 10. Legacy addDocument compatibility | Direct additions accumulate counts, count supplied stop words/uppercase tokens verbatim, append positions, and leave the input list unchanged. |
| 11. Rebuild replaces positional entries | Unchanged rebuilds do not duplicate positions; changing a file replaces its old occurrences and updates statistics. |
| 12. Failed rebuild preserves both indexes | Invalid UTF-8 in a later file leaves old keyword results, exact scores, positions, stop-word entries, and statistics available despite an earlier changed file. |
| 13. Exact keyword scores, ordering, and statistics | Small fixtures verify TF-IDF scores by double bits, descending order, alphabetical ties, duplicate-query removal, case/punctuation, ordinary quoted keywords, empty/unknown/stop-only queries, and counts. |

The original seven tests in [BaselineTests.java](../tests/BaselineTests.java)
remain unchanged. The new tests extend their coverage rather than replacing them.

## 10. Preserving ranking and statistics

Keyword search still uses distinct filtered query terms, with ranked OR
semantics: a document may match any retained term. For each term:

```text
idf = ln((N + 1) / (df + 1)) + 1
score contribution = raw TF * idf
```

`N` is the loaded document count, including documents with no searchable tokens.
`df` comes from the existing searchable frequency map's size. Contributions
are summed, positive-score results are sorted in descending order by stable
insertion sort, and filename order breaks ties. Query deduplication preserves
the first occurrence order through `LinkedHashSet`.

Positions and `documentLengths` do not enter this calculation. Unique-term
and processed-token statistics still come from frequency postings and filtered
counts. The bundled corpus was reported unchanged at 6 documents, 75 searchable
terms, and 120 processed tokens. There is still no document-length normalization,
phrase score, or Top-K cutoff. Quotes are currently punctuation, not phrase syntax.

## 11. Verification evidence and commands for a later check

### Previously reported by Codex during implementation

| Check | Reported result |
| --- | --- |
| Temurin JDK 25.0.3 compilation | Passed |
| Original Java regression suite | 7 passed, 0 failed |
| Positional suite | 13 passed, 0 failed |
| Existing HTTP smoke suite | 16 passed, 0 failed against a temporary localhost server |
| Before/after keyword comparison | 5,708 queries had identical filenames, ordering, exact double scores, and statistics |

The comparison covered all 75 searchable terms, every ordered pair of them,
and eight additional queries. It was a separate verification helper under
ignored `out/`, not a committed regression test. The HTTP run reported identity
and port ownership checks before stopping only its temporary server, followed
by a free-port check. These are historical Codex-reported results associated
with the implementation work, not independent verification by the student.

The implementation commit was supplied as the verified checkpoint for this
documentation task. That establishes which code is being documented; it does
not establish an independently reproduced 7/13/16 test run. No independent
test results were supplied for this manual, and none are invented here.

This documentation pass checks source consistency, excerpts, local links,
Markdown fences, whitespace, and changed-file scope. It does not rerun Java,
HTTP, or browser tests. No browser interaction or performance result is claimed.

To reproduce the committed Java checks later, run from the repository root:

```powershell
$projectJdk = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'
& "$projectJdk\bin\javac.exe" -encoding UTF-8 -d out\app src\*.java tests\BaselineTests.java tests\PositionalIndexTests.java
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
& "$projectJdk\bin\java.exe" -cp out\app BaselineTests
if ($LASTEXITCODE -ne 0) { throw 'Baseline tests failed' }
& "$projectJdk\bin\java.exe" -cp out\app PositionalIndexTests
if ($LASTEXITCODE -ne 0) { throw 'Positional tests failed' }
```

Expected totals are 7/0 and 13/0. For the sixteen HTTP checks, follow the existing
[server and smoke-test instructions](../README.md#http-integration-smoke-tests-phase-25a).
Use an available port 8080 and stop only a server whose ownership is verified.
Writing a command here is not evidence that it ran during documentation.

## 12. Limitations and future phrase retrieval

The index is in memory, stores boxed positions, and duplicates occurrence
information across two structures. It has no persistence or concurrency
protection. Document IDs depend on sorted filenames and can change after a
collection change. Tokenization retains ASCII letters/digits after lowercasing;
UTF-8 input alone does not provide Bengali or general Unicode word retrieval.

For future exact normalized phrase retrieval, a two-token phrase would need
the second term at `p + 1` in the same document. A longer phrase would need
all successive positions. Repeated words need separate occurrences, and stop
words must be kept when preparing a phrase query. For example, the worked
document contains adjacent `java search` at positions 2 and 3; the java at 0
is separated from search by other tokens. These are explanations of how the
stored data could be used, not an implemented phrase algorithm.

Positional adjacency describes normalized tokens, so it would not distinguish
punctuation differences such as `java,search` and `java search`. Original text
formatting or character-exact matching would need a different requirement.
Phrase grammar, Boolean operators, fuzzy/substring matching, PDF/OCR, new
endpoints, and frontend changes were outside Phase 3.1A.

## 13. Six supervisor viva questions

1. **Why are frequencies insufficient for phrase search?** They count occurrences
   but lose order and distance; positions retain both.
2. **Why keep stop words in positional postings?** They occupy real token positions
   and distinguish phrases such as `java and search` and `java or search`.
3. **Why keep tokenize() and process()?** They share one scanner but serve different
   needs: complete document positions and the existing filtered keyword behavior.
4. **What does documentLengths do?** It records the next free position for each ID,
   allowing repeated direct additions to append; it is not a ranking length weight.
5. **Why copy lists before returning an unmodifiable map?** An outer wrapper alone
   would expose mutable lists. List copies also keep earlier snapshots stable.
6. **Why does rebuilding not duplicate positions or change ranking?** It builds a
   fresh index and publishes it after successful reads; keyword scoring still uses
   the original frequency map, formula, deduplication, and sorting.

For practice, trace `Search and Java, java` and predict both posting structures,
the next free position, and what happens after appending another `search`.
Then explain why a full rebuild behaves differently. Complete any personal
reflection after doing the exercise.

Return to the [learning index](README.md).
