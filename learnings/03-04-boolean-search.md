# Phase 3.3A: Core Boolean retrieval

**Student:** Rifat Al Maruf (BSSE1705)

**Course:** SPL-1, Institute of Information Technology, University of Dhaka

**Record date:** October 31, 2026

**Project:** IIT Search Engine

## 1. Objective, checkpoint, and sources

Phase 3.3A adds explicit AND/OR retrieval through the Java core method
`List<Document> searchBoolean(String expression)`. Its implementation commit is
`3fea2090ddc30593c99fa79295b1544c7e1723a0` on `feat/boolean-search`, based on
`334905ac1c31ad6855ab4de502e9f2501a70b1a1`. Main remains at that base checkpoint
while this feature awaits independent review and integration.

Read these sources alongside this manual:

- [SearchEngine.java](../src/SearchEngine.java): Boolean parser and set helpers;
  existing keyword and phrase methods.
- [TextProcessor.java](../src/TextProcessor.java): shared normalization and stop words.
- [InvertedIndex.java](../src/InvertedIndex.java): frequency and positional postings.
- [Document.java](../src/Document.java): indexed document ID and filename.
- [BooleanSearchTests.java](../tests/BooleanSearchTests.java): all 22 Boolean tests.
- Earlier manuals: [positional indexing](03-01-positional-index.md),
  [exact phrases](03-02-exact-phrase-search.md), and
  [browser phrase search](03-03-browser-phrase-search.md).

These notes were prepared with Codex assistance. Excerpts describe the actual
implementation; test results are attributed historical reports, not independent
student verification. See [verification evidence](#12-verification-evidence)
for the distinction between earlier executions and this documentation task.

## 2. What Boolean retrieval means, and why add it

Boolean retrieval answers a yes/no question for each document: does it satisfy
the requested combination of terms? `java AND programming` requires both words
in the same document. `java OR networking` accepts either word, including both.
This lets a caller express required combinations that ordinary keyword search
does not enforce. Matching documents receive no Boolean relevance score.

The implementation uses an existing index to obtain document IDs for each
operand, combines those IDs, then returns the corresponding `Document` objects.
It is a separate API so existing keyword and phrase behavior stays intact.

| Behavior | Ranked keyword search | Exact phrase search | Boolean search |
| --- | --- | --- | --- |
| Method | `search()` | `searchPhrase()` | `searchBoolean()` |
| Meaning | Match any retained query term | Match consecutive normalized tokens in order | Satisfy explicit AND/OR conditions |
| Index data | Frequency postings | Positional postings | Frequency-posting document IDs |
| Stop words | Removed by `process()` | Kept by `tokenize()` | Rejected as operands; AND/OR are operators |
| Output | `List<SearchResult>` with scores | `List<Document>` | `List<Document>` |
| Ordering | Descending score; filename breaks ties | Filename | Filename |
| Adjacency required? | No | Yes | No |

For example, `search("java AND programming")` still treats AND as a removable
stop word and performs ranked keyword retrieval. Only `searchBoolean()` gives
AND its operator meaning. `searchPhrase("java and programming")` instead
requires the literal normalized sequence, including the word and.

## 3. From frequency postings to document-ID sets

An inverted index maps a term to the documents containing it. Its existing
frequency map in `InvertedIndex` is declared as:

```java
private final Map<String, Map<Integer, Integer>> postings = new HashMap<>();
```

Read this as **term -> (document ID -> occurrence count)**. For example,
`java -> {1=2, 4=1}` records two occurrences in document 1 and one in document 4.
Boolean matching only needs membership: java occurs in the set `{1, 4}`.
It does not use the counts as weights or inspect token positions.

Actual extraction in `booleanOperandDocuments()`:

```java
Set<Integer> ids = new HashSet<>();
for (Integer documentId : index.getPostings(term).keySet()) {
    ids.add(documentId);
}
return ids;
```

`getPostings(term)` exposes a read-only map, or an empty map for an unknown
term. `keySet()` gives its document IDs. Copying those keys into a new set
lets the Boolean helpers work on local data without changing index postings.

For two document sets A and B:

- **AND is intersection:** retain an ID only when it belongs to both A and B.
- **OR is union:** include an ID when it belongs to either A or B.

For A = `{1, 4}` and B = `{2, 4}`, AND gives `{4}` and OR gives `{1, 2, 4}`.
The braces express mathematical membership; a Java `HashSet` does not promise
to iterate in the order shown.

## 4. The expression rules and parsing steps

The accepted structure is one operand followed by zero or more operator/operand
pairs. Each operator is a standalone AND or OR, ignoring case, separated from
its operands by ordinary whitespace such as spaces, tabs, or newlines.

Examples: `java`, `java AND programming`, `JAVA or networking AND laboratory`.
Adjacent words such as `java programming` are invalid because no operator
connects them. Parentheses are not part of this grammar.

The beginning of the actual method is:

```java
if (expression == null || expression.isBlank()) {
    throw new IllegalArgumentException("Boolean expression must not be null or blank.");
}
String[] parts = expression.strip().split("\\s+");
Set<Integer> matches = new HashSet<>();
Set<Integer> group = booleanOperandDocuments(parts[0]);
```

`strip()` removes leading/trailing whitespace. In the Java string `"\\s+"`,
the doubled backslash supplies one backslash to the regular expression;
`\s+` means a run of whitespace. Splitting preserves the expression's order
as an array of parts, before any operand is normalized.

The parser then:

1. Validates the first operand and obtains its document set.
2. Visits indices 1, 3, 5, and so on as operator positions.
3. Requires AND or OR at each such index and checks that a following operand exists.
4. Validates the operand at `i + 1` and retrieves its set.
5. Intersects into the current group for AND, or completes a group for OR.
6. Includes the final group and converts matching IDs into ordered documents.

The actual loop starts with `for (int i = 1; i < parts.length; i += 2)`.
Advancing by two skips the operand just handled. The `i + 1 == parts.length`
check catches an expression ending with an operator, such as `java AND`.
These are direct loops and conditionals, with no external parser library.

## 5. Operand normalization and strict validation

`booleanOperandDocuments()` first rejects AND/OR where an operand is expected,
and rejects standalone NOT because it is unsupported. It then checks characters
for unsupported grouping, quote, wildcard, or symbolic-operator syntax:

```java
for (int i = 0; i < operand.length(); i++) {
    if ("()[]{}\"'*?&|".indexOf(operand.charAt(i)) >= 0) {
        throw new IllegalArgumentException("Unsupported Boolean syntax in operand: " + operand);
    }
}
```

Here `indexOf()` returns a nonnegative index if the character is in that string.
`\"` represents a double-quote character inside a Java string. Rejecting these
characters prevents unsupported syntax from silently becoming ordinary terms.

The helper next calls `processor.tokenize(operand)`, requires exactly one token,
and checks `TextProcessor.isStopWord(term)`. This shares the same lowercase
`Locale.ROOT` normalization, ASCII letters/digits, and stop-word rule used by
keyword processing; it does not copy the tokenizer's character-scanning logic.

The difference from calling `process()` on an entire expression matters:
`process()` removes stop words, including and/or. Removing them before parsing
would erase the operators, and dropping a stop-word operand could silently
change a requested condition. Boolean retrieval therefore validates rather
than discarding such operands.

The configured stop words are `a`, `an`, `the`, `and`, `or`, `is`, `are`, `of`,
`to`, `in`, `for`, and `with`. AND/OR are recognized as operators before operand
normalization; the remaining stop words cannot be Boolean operands either.
The same words remain searchable through positional phrase retrieval.

| Input | Outcome and reason |
| --- | --- |
| `JaVa, AND PROGRAMMING.` | Valid; operands normalize to java and programming. |
| `25 OR java` | Valid; digits are searchable tokens. |
| `java-programming` | Invalid; one raw operand becomes two tokens. |
| `java AND the-programming` | Invalid; two tokens are not collapsed into programming by removing the. |
| `java AND the` | Invalid; stop-word operand. |
| `java AND OR programming` | Invalid; an operator occupies an operand position. |
| `java AND` | Invalid; missing final operand. |
| `java XOR programming` | Invalid; only AND/OR may connect terms. |
| `(java)`, `"java"`, `java*` | Invalid; grouping, quoted operands, and wildcards are unsupported. |
| Null, blank, `!!!`, or Bengali-only input | Invalid; there is no valid searchable operand under these rules. |

All invalid cases raise descriptive `IllegalArgumentException`s. This is
different from a **valid unknown term**, which simply has an empty posting set:
`java AND absent` matches nothing; `java OR absent` matches the java documents.
The parser still validates the whole input. For example, `absent AND the` throws
instead of returning early when absent has no postings.

## 6. Why AND groups are completed before OR

AND has higher precedence than OR, like multiplication before addition.
For explanation, `java OR networking AND laboratory` means
`java OR (networking AND laboratory)`. Those parentheses describe the meaning;
typing them into this API is invalid.

Two local sets carry the calculation:

- `group` holds the intersection of operands in the current AND group.
- `matches` holds the union of already completed groups.

The actual decision after retrieving the next operand is:

```java
if (operator.equalsIgnoreCase("AND")) {
    group = intersectDocuments(group, next);
} else {
    includeDocuments(matches, group);
    group = next;
}
```

At OR, the previous group is complete: no later AND belongs to it. Its IDs
can be included in `matches`, and `next` starts a new group. After the loop,
`includeDocuments(matches, group)` includes the last group, which has no later
OR to trigger inclusion. This also makes a single-term expression work.

Intersection is implemented manually:

```java
Set<Integer> intersection = new HashSet<>();
for (Integer documentId : left) {
    if (right.contains(documentId)) {
        intersection.add(documentId);
    }
}
return intersection;
```

The helper returns a fresh set containing only IDs in both inputs. Union is
the loop inside `includeDocuments()`:

```java
for (Integer documentId : source) {
    target.add(documentId);
}
```

`add()` leaves an existing member present once. Thus repeated terms and documents
that match several OR groups cannot create duplicate results. `java AND java`
requires membership in java's set twice; it does not require two occurrences.

## 7. Worked trace: java OR networking AND laboratory

Use the artificial collection from `testPrecedence()`. Indexing assigns IDs in
filename order, regardless of the fixture's reverse file-creation order:

| ID | Filename | Text |
| --- | --- | --- |
| 1 | a.txt | java |
| 2 | b.txt | networking laboratory |
| 3 | c.txt | networking |
| 4 | d.txt | java programming laboratory |
| 5 | e.txt | programming |

The relevant frequency postings are java `{1=1, 4=1}`, networking `{2=1, 3=1}`,
and laboratory `{2=1, 4=1}`. Their keys give these document sets:

```text
java        -> {1, 4}
networking  -> {2, 3}
laboratory  -> {2, 4}

parts = [java, OR, networking, AND, laboratory]
```

| Step | group | matches | Explanation |
| --- | --- | --- | --- |
| First operand java | {1, 4} | {} | Start the first group. |
| OR networking | {2, 3} | {1, 4} | Include the completed java group; start networking. |
| AND laboratory | {2} | {1, 4} | Intersect networking with laboratory. |
| End of expression | {2} | {1, 2, 4} | Include the final group. |

The returned filenames are `a.txt`, `b.txt`, `d.txt`. A purely left-to-right
calculation would incorrectly intersect the earlier java matches with laboratory
and lose a.txt. Keeping the current group separate prevents that error.

## 8. Important Java syntax and concepts

| Syntax or method | What it means here, and why it is useful |
| --- | --- |
| `Set<Integer>` | Interface for a collection of unique integer IDs; expresses membership rather than occurrence counts. |
| `new HashSet<>()` | Constructs the concrete hash-based set. `<>` infers Integer from the declaration; iteration order is unspecified. |
| `Integer` | Wrapper type used by collections for primitive int values; Java boxes/unboxes IDs as needed. |
| `Set<Integer> group = ...` | A variable referring to the current set. Reassigning group changes which set it refers to. |
| `equalsIgnoreCase("AND")` | Compares operator spelling without requiring one capitalization. |
| `for (Integer documentId : left)` | Enhanced for loop: visit each member, regardless of its iteration order. |
| `contains(documentId)` | Membership check used for intersection and final result selection. |
| `add(documentId)` | Inserts an ID only if absent; this provides duplicate elimination. |
| `private` helper methods | Keep operand lookup, intersection, and union understandable as separate steps while exposing only the public Boolean API. |
| `void includeDocuments(...)` | Mutates the supplied local target set and returns no value; intersection instead returns a new set. |
| `throw new IllegalArgumentException(...)` | Signals invalid caller input with a reason, rather than disguising it as an empty result. |

Hash sets normally offer fast membership checks, but this is a small in-memory
implementation, not a measured large-corpus optimization. Each operand's posting
IDs are copied; each AND loops over its left set; each union loops over a group.
The final result pass also visits all document metadata.

## 9. Unique ordered results, no rescanning, and compatibility

After computing IDs, the actual result-building code is:

```java
List<Document> results = new ArrayList<>();
for (Document document : documents) {
    if (matches.contains(document.getId())) {
        results.add(document);
    }
}
return results;
```

`buildIndex()` sorted files by filename and stored each `Document` once in that
order. Visiting this list makes output deterministic even though the HashSet's
iteration order is unspecified. Matching several terms never adds a document
more than once. The method returns a new mutable result list containing the
existing immutable ID/filename objects, without exposing the index's sets.

All AND conditions compare the **same document ID**. One document containing java
and another containing programming cannot jointly satisfy java AND programming.
Words may still appear in any order with any gap inside a matching document.

Only index construction reads source text. Boolean retrieval looks up postings
and iterates `Document` metadata; it does not open files or scan original text.
`testNoFileScan()` deletes its owned fixture files after indexing, then checks
that Boolean retrieval continues to work from the built index.

Rebuild behavior is inherited unchanged: `buildIndex()` constructs fresh documents
and postings, then replaces the old structures after successful loading. A
repeated build cannot accumulate Boolean matches; a changed or deleted document
is reflected after rebuilding. A failed read leaves the previous index usable.
There is no new persistent Boolean index or cache to keep synchronized.

Keyword scoring, query deduplication, and phrase offsets are untouched. The
keyword formula remains raw TF multiplied by smoothed IDF,
`ln((N + 1) / (df + 1)) + 1`, summed across distinct searchable query terms.
Boolean matching does not change frequencies, positional entries, or statistics.
The compatibility tests compare keyword filenames, order, and exact double bits,
as well as phrase matches before and after Boolean calls.

## 10. Results on the two existing collections

The tests load one collection explicitly through `buildIndex("data")` or
`buildIndex("data/phrase_samples")`. Loading reads immediate `.txt` files only:
it is not recursive, so the nine nested fictional files do not enter the default
six-document index. The [sample collection README](../data/phrase_samples/README.md)
identifies those documents as fictional demonstration data, not real IIT notices
or policies. Neither collection was changed for Boolean retrieval.

These are expected results asserted by the committed Boolean tests, whose
executions were previously reported by Codex; they are not new queries run
during this documentation task.

### Original six-document collection

| Expression | Filenames in returned order |
| --- | --- |
| `java AND programming` | java_programming.txt |
| `java OR networking` | academic_notice.txt, java_programming.txt, networking.txt |
| `java OR networking AND laboratory` | academic_notice.txt, java_programming.txt, networking.txt |
| `software AND engineering` | algorithms.txt, software_engineering.txt |

Default statistics remain **6 documents, 75 searchable terms, and 120 processed
tokens**. The tested java keyword ranking remains java_programming.txt followed
by academic_notice.txt, with raw TF values 3 and 1 respectively and IDF
`ln(7 / 3) + 1`. Boolean results are alphabetical, not ordered by those scores.

### Separate nine-document fictional collection

| Expression | Filenames in returned order |
| --- | --- |
| `java AND programming` | java_programming_lab.txt, seminar_discussion.txt |
| `java AND search` | information_retrieval_workshop.txt, java_programming_lab.txt, networking_lab.txt, reverse_order_exercise.txt |
| `networking AND laboratory` | networking_lab.txt |
| `project AND presentation` | academic_announcement.txt, project_presentation.txt |
| `error AND error` | java_programming_lab.txt, software_engineering_coursework.txt |
| `binary OR networking AND laboratory` | algorithms_coursework.txt, networking_lab.txt |
| `database OR login` | No matches |

The broader java AND search result illustrates the difference from exact
`java search`: the networking text separates those words, and the reverse-order
exercise has search java. Both satisfy Boolean membership. Exact phrase retrieval
still returns only information_retrieval_workshop.txt and java_programming_lab.txt.
Likewise, error AND error accepts the coursework's separated occurrences; it does
not impose the repeated-token adjacency required by the phrase error error.

## 11. What each of the 22 Boolean tests verifies

The [test suite](../tests/BooleanSearchTests.java) uses explicit `AssertionError`
checks rather than Java assert or JUnit. `checkBoolean()` compares full ordered
filename lists; `checkInvalid()` requires an IllegalArgumentException with a
descriptive message fragment. The following rows follow `main()` registration
order; each row is a test group with several assertions where appropriate.

| # | Test method | Evidence provided |
| --- | --- | --- |
| 1 | `testAnd` | Intersects sets correctly, accepts either operand order, and excludes documents containing only one required term. |
| 2 | `testOr` | Unions either operand order, includes documents with either or both terms, and excludes an unrelated document. |
| 3 | `testMultipleAnd` | Requires all three operands; reordering them preserves results and adding an absent term empties the intersection. |
| 4 | `testMultipleOr` | Combines several OR operands, including an unknown first term. |
| 5 | `testPrecedence` | Checks AND before OR in both positions, multiple AND groups, longer mixed expressions, and empty groups. |
| 6 | `testNormalization` | Mixed-case operators and operands, surrounding punctuation, numeric terms, tabs, line breaks, and outer whitespace work. |
| 7 | `testDuplicates` | Repeated operands, repeated text occurrences, and overlapping OR groups still return each document once. |
| 8 | `testOrder` | Repeated queries keep filename order and indexed IDs, regardless of frequency; clearing a returned list cannot affect later queries. |
| 9 | `testUnknown` | An unknown single term has no matches; unknown terms empty AND groups but do not erase other OR matches. |
| 10 | `testSingleTerm` | Single known, normalized, and unknown terms work without an operator or frequency ranking. |
| 11 | `testStopWords` | All twelve configured stop words are handled strictly: ten are invalid operands and AND/OR are reserved operators; case, punctuation, and an absent first term cannot hide an invalid stop operand. |
| 12 | `testMissingOperands` | Rejects null/blank input, leading or trailing operators, incomplete longer expressions, punctuation-only operands, and Bengali-only input. |
| 13 | `testConsecutiveOperators` | Rejects all AND/OR consecutive-operator pairs, even after an unknown term. |
| 14 | `testMissingOperators` | Rejects adjacent terms, XOR as a connector, punctuation-joined multiple tokens, and a stop word joined to a searchable term. |
| 15 | `testUnsupportedSyntax` | Rejects grouping brackets, ASCII quotes, wildcards, symbolic Boolean syntax, and unsupported NOT usage. |
| 16 | `testEmptyIndex` | Valid expressions return no matches on an unbuilt engine or an indexed empty folder; invalid input still throws. |
| 17 | `testNoFileScan` | AND, OR, and a nonmatch still work after deleting indexed fixture files, proving retrieval does not depend on reading them again. |
| 18 | `testRebuild` | Rebuilding unchanged data is stable; changed/deleted files and replacement collections replace old matches; invalid UTF-8 preserves the last successful index. |
| 19 | `testCompatibility` | Checks exact keyword scores/order, query deduplication, keyword AND/quotes semantics, phrase stop words/repetition/adjacency, positions, and statistics around successful and rejected Boolean calls. |
| 20 | `testDefaultCollection` | Checks known Boolean results in the original six files, baseline statistics, and the original java keyword ranking and exact scores. |
| 21 | `testSampleCollection` | Checks the separately loaded nine-file corpus, Boolean membership versus phrase order/gaps, repeated-term membership, mixed precedence, no matches, and unchanged phrase results. |
| 22 | `testDocumentBoundaries` | Separate documents cannot satisfy one AND; a document containing both terms in reverse order with a gap can match. |

Artificial fixtures use owned temporary folders and filenames a.txt onward.
They are created in reverse order, then loaded by the real index builder.
Try-with-resources closes the fixture and deletes only its named files and
directory. Tests for both real repository collections only read those datasets.
The suite uses no new dependencies and requires no JVM assertion-enable flag.

## 12. Verification evidence

### Codex-reported implementation-publication rerun

During publication of commit `3fea2090ddc30593c99fa79295b1544c7e1723a0`, Codex
reported fresh compilation with configured Temurin **JDK 25.0.3** and reran
all four Java suites:

| Java suite | Passed | Failed |
| --- | --- | --- |
| [BaselineTests](../tests/BaselineTests.java) | 7 | 0 |
| [PositionalIndexTests](../tests/PositionalIndexTests.java) | 13 | 0 |
| [PhraseSearchTests](../tests/PhraseSearchTests.java) | 18 | 0 |
| [BooleanSearchTests](../tests/BooleanSearchTests.java) | 22 | 0 |
| **Fresh publication rerun total, as reported by Codex** | **60** | **0** |

These are prior Codex-reported executions, not independently verified results
or tests rerun for this manual.

### Earlier Codex-reported HTTP and browser regressions

During Phase 3.3A implementation, before the publication rerun, Codex also
reported these existing suites passing:

| Regression suite | Passed | Failed |
| --- | --- | --- |
| [Original HTTP smoke tests](../tests/HttpSmokeTests.ps1), default collection | 16 | 0 |
| [Phrase HTTP tests](../tests/PhraseHttpTests.ps1), default collection | 13 | 0 |
| Phrase HTTP tests, fictional demo collection | 22 | 0 |
| [Browser-client model tests](../tests/BrowserClientTests.js) | 12 | 0 |
| **Earlier HTTP/client total** | **63** | **0** |

Together with the 60 Java tests, the implementation report counted **123 passing
test groups**. This combines the named suites; it is not a count of every inner
assertion. The HTTP/client suites were not rerun during implementation publication.
JavaScript syntax checking was also previously reported successful.

The [HTTP runner](../tests/RunHttpIntegrationTests.ps1) was reported to verify
ownership before stopping its two temporary localhost servers and to leave port
8080 free. Its additional startup/log checks were outside the 123 suite total.
The Node client suite models DOM/fetch behavior; it is not a real browser test
or evidence of Boolean browser support. Earlier user-verified Edge checks belong
to the [Phase 3.2B record](03-03-browser-phrase-search.md#11-verification-evidence-automated-manual-and-documentation-only).

### Checks performed for this documentation task

This task checks the Git checkpoint, source excerpts, all 22 test descriptions,
worked trace, collection expectations, Markdown links/anchors, code fences,
whitespace, and exact two-file documentation scope. No compilation, Java suite,
HTTP suite, JavaScript suite, or real browser verification is rerun here.
Static documentation checks do not establish new runtime results.

## 13. Current limitations and deferred work

- Only explicit standalone AND/OR operators and single normalized term operands
  are supported. There is no implicit operator between adjacent terms.
- No parentheses or other grouping syntax, NOT operator, quoted/phrase operands,
  wildcard expansion, or Boolean ranking is implemented.
- Stop words cannot be Boolean operands. This follows searchable frequency
  postings, while phrase retrieval separately retains their positions.
- Normalization follows the existing ASCII-token rules; general Bengali or
  Unicode word retrieval, stemming, synonyms, and fuzzy matching are absent.
- Boolean retrieval is a Java core API only. The CLI, HTTP endpoint, and browser
  have no Boolean integration; the browser still selects keyword or phrase.
  Passing AND in an ordinary keyword request does not activate this parser.
- The index stays in memory. Changes to files require a rebuild; there are no
  new persistence or concurrency guarantees, and large-corpus performance is
  unmeasured. The final ordering pass visits all document metadata.

## 14. Eight supervisor viva questions

1. **What does Boolean retrieval return?** Documents that satisfy explicit term
   conditions, once each in filename order, without relevance scores.
2. **Why use frequency postings if frequencies are ignored?** Their keys already
   identify the documents containing each searchable term; positions are unnecessary.
3. **How do AND and OR combine documents?** AND retains IDs in both sets; OR
   inserts IDs from either set, with set membership preventing duplicates.
4. **How is AND precedence enforced without parentheses?** Intersect the current
   AND group; at OR, include the completed group and begin the next one.
5. **Why reject stop-word operands?** They are excluded from searchable postings;
   silently removing a requested operand would change the condition.
6. **How does an unknown term differ from invalid syntax?** An unknown valid term
   yields an empty set; invalid syntax raises IllegalArgumentException even when
   another term has no matches.
7. **Why are results deterministic despite using HashSet?** The final pass follows
   documents already stored in filename order, testing membership in the set.
8. **How is compatibility demonstrated?** Existing suites and the new compatibility
   test check keyword scores/order, phrase results, positions, statistics, and
   rebuilds; only a separate Boolean API was added.

## 15. Quick revision summary

- **WHAT:** explicit document membership conditions through `searchBoolean()`.
- **WHY:** require all terms with AND or alternatives with OR while preserving
  existing ranked keyword and exact phrase behavior.
- **HOW:** validate alternating parts, get frequency-posting IDs, intersect AND
  groups, union those groups, and return documents in filename order.
- Remember `java OR networking AND laboratory`: finish networking AND laboratory
  before combining that group with java.
- Normalize each operand to one searchable token; reject stop words and malformed
  syntax. Unknown valid terms produce empty sets, not syntax errors.
- Sets ensure uniqueness; sorted document metadata ensures deterministic output.
  Retrieval reads postings, not source files.
- Evidence: **60 Java checks freshly rerun at publication, Codex-reported**;
  **63 earlier HTTP/client checks, Codex-reported**; **documentation checks only
  in this task**. No Boolean browser mode exists yet.

For self-study, explain why `java AND absent OR networking` can still match
networking documents, while `java AND the OR networking` must throw an error.
Then compare Boolean java AND java with the exact phrase java java.

Return to the [learning index](README.md).
