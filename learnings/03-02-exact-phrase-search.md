# Phase 3.2A: Exact phrase retrieval

**Student:** Rifat Al Maruf (BSSE1705)

**Course:** SPL-1, Institute of Information Technology, University of Dhaka

**Record date:** October 31, 2026

**Project:** IIT Search Engine

## 1. Objective and implementation checkpoint

Phase 3.2A uses the positional index to find consecutive normalized words in
one document. The implementation commit is
`d3274d6cb221ae11aaa84937df2170777f839fb0` on `feat/phrase-search`.
Its parent is the integrated Phase 3.1A checkpoint
`736dc387a0bb675317037f94546d99e6bb6b9c41`.

Read the [positional-index manual](03-01-positional-index.md) first. This chapter
explains the new public `searchPhrase(String phrase)` method and private
`retainPhraseStarts()` helper in [SearchEngine.java](../src/SearchEngine.java),
the eighteen [phrase tests](../tests/PhraseSearchTests.java), and the separate
[fictional sample collection](../data/phrase_samples/README.md).

This milestone adds a Java core API. The CLI, browser, and existing HTTP search
endpoint still use ordinary keyword search. No quotation-mark query grammar
or phrase-specific ranking was added. These notes were prepared with Codex
assistance and should be checked alongside the source; they do not claim
personal learning experiences on the student's behalf.

## 2. Exact phrases versus ordinary keywords

Keyword search asks which documents contain any of the retained query terms.
Phrase retrieval asks which documents contain the entire normalized sequence,
in the same order and without intervening tokens.

| Behavior | `search(String query)` | `searchPhrase(String phrase)` |
| --- | --- | --- |
| Output | `List<SearchResult>`: documents with scores | `List<Document>`: matching documents |
| Normalization | `process()`, which filters stop words | `tokenize()`, which retains stop words |
| Multiple terms | Ranked OR: any retained term may match | Every token must occupy its required consecutive position |
| Repeated query terms | Deduplicated before scoring | Retained as separate phrase offsets |
| Ordering | Descending score, then filename for ties | Filename order |
| Data used | Searchable frequency postings | Positional postings |

For `java search`, keyword search can return documents containing only java,
only search, reversed words, or separated words. Phrase retrieval requires
java immediately followed by search after normalization. A higher keyword
score does not make a document a phrase match.

Here, exact means exact **normalized token sequence**. `Java,SEARCH` and
`java search` have the same two tokens. Case, punctuation, and original spacing
are not compared character by character.

## 3. Preparing input and retrieving positions

The method begins with this actual code:

```java
List<String> terms = processor.tokenize(phrase);
List<Document> matches = new ArrayList<>();
if (terms.isEmpty()) {
    return matches;
}
```

The shared [TextProcessor](../src/TextProcessor.java) lowercases with
`Locale.ROOT`, then scans ASCII letters and digits. Punctuation, whitespace,
and other separator characters finish a token. Token positions start at zero;
separators do not occupy positions. Empty, whitespace-only, and punctuation-only
input therefore produces an empty result.

Stop words must stay in `terms`: otherwise `java and search` would be shortened
to `java search`, changing the requested sequence. Repeated terms must also
stay: `java java` needs two consecutive occurrences, not a single java.

The next collection is declared as:

```java
List<Map<Integer, List<Integer>>> phrasePostings = new ArrayList<>();
```

Read it from inside outward: a list of integer positions, inside a map keyed
by document ID, inside a list whose index is the phrase-token offset. For
`java and java`, entries 0 and 2 both describe java, because they represent
different required offsets. This is not a set of distinct phrase terms.

The enhanced `for (String term : terms)` loop retrieves
`index.getPositionalPostings(term)` once for each token occurrence and appends
the result to `phrasePostings`. These are immutable snapshots supplied by
[InvertedIndex](../src/InvertedIndex.java); matching does not modify the index.
If any term has no positional postings anywhere, the method returns the empty
result immediately: a complete phrase cannot contain an absent term.

## 4. Position offsets and a common phrase start

For a possible start position `p`, token number `i` must appear at `p + i`
in the same document. The first token has offset 0, the second offset 1, and
the third offset 2. Every test of a later word must use the same original `p`.

The actual per-document matching loop is:

```java
int documentId = document.getId();
List<Integer> starts = phrasePostings.get(0).getOrDefault(documentId, List.of());
for (int offset = 1; offset < terms.size() && !starts.isEmpty(); offset++) {
    List<Integer> positions = phrasePostings.get(offset)
            .getOrDefault(documentId, List.of());
    starts = retainPhraseStarts(starts, positions, offset);
}
if (!starts.isEmpty()) {
    matches.add(document);
}
```

`starts` initially contains every occurrence of the first term in this document.
After offset 1, it contains starts satisfying the first two words. After offset
2, it contains starts satisfying the first three words, and so on. The helper
returns original starts, not the later word's positions. Reassigning `starts`
progressively narrows the possibilities without changing the posting lists.

Why preserve one start? Consider `java search pause search guide` and the
phrase `java search guide`. There is a java/search pair at 0/1 and a
search/guide pair at 3/4. But the original start 0 would require guide at 2.
The document must be rejected even though both adjacent pairs exist somewhere.
Test 13 specifically checks this potential mistake.

## 5. The manual two-pointer merge

Positions and retained starts are sorted in ascending order. The helper uses
two integer list indices, `startIndex` and `positionIndex`, both initially 0.
They are called pointers here because they track the current entries; they
are not memory-address pointers.

Actual comparison loop:

```java
while (startIndex < starts.size() && positionIndex < positions.size()) {
    int expectedPosition = starts.get(startIndex) + offset;
    int actualPosition = positions.get(positionIndex);
    if (actualPosition < expectedPosition) {
        positionIndex++;
    } else if (actualPosition > expectedPosition) {
        startIndex++;
    } else {
        retained.add(starts.get(startIndex));
        startIndex++;
        positionIndex++;
    }
}
```

| Comparison | Action | Why it is safe |
| --- | --- | --- |
| actual < expected | Advance the position index | This occurrence is too early; a later occurrence might fit the same start. |
| actual > expected | Advance the start index | The current start needs a position already passed; all remaining actual positions are even later. |
| actual = expected | Retain the original start; advance both indices | This word occurs at exactly the required offset. |

`++` increments an index by one. `while` repeats until either list is exhausted.
The `&&` condition requires both indices to be valid before accessing either
list. In the outer offset loop, `&& !starts.isEmpty()` also stops unnecessary
work as soon as no complete phrase is possible in that document.

The helper creates a new `ArrayList<Integer>` named `retained` and returns it.
Because indices only move forward, one helper call takes at most a linear
number of steps in the lengths of its two input lists. This avoids restarting
a search through every occurrence for every candidate start. Whole-query cost
also includes copying postings, visiting documents, and processing each offset.

### Worked trace: Java and Java Search

For one document, the normalized tokens and postings are:

```text
position:  0     1    2     3
token:     java  and  java  search
java: [0, 2]     and: [1]     search: [3]
```

For `java search`, start with `[0, 2]` and compare search positions `[3]`
at offset 1:

| Current start | Expected | Actual | Decision |
| --- | --- | --- | --- |
| 0 | 1 | 3 | actual > expected: discard start 0 |
| 2 | 3 | 3 | equal: retain start 2 |

The document matches. For `java and java`, offset 1 retains only start 0
because and is at 1. At offset 2, compare that start against java's `[0, 2]`:

| Current start | Expected | Actual | Decision |
| --- | --- | --- | --- |
| 0 | 2 | 0 | actual < expected: advance the position index |
| 0 | 2 | 2 | equal: retain start 0 |

The three-word phrase matches. `java java` does not: starts 0 and 2 require
java at 1 and 3 respectively, but neither exists. `and java` matches from 1
because java is at 2. This trace covers all three comparison branches.

## 6. Edge cases, ordering, and document boundaries

- **One word:** The offset loop begins at 1 and never runs. Any document with
  a first-term position matches, including a one-word stop phrase such as `and`.
- **Repeated words:** For `java java java`, a start must satisfy positions
  `p`, `p + 1`, and `p + 2`. One occurrence cannot fill all three offsets.
  Overlapping occurrences may survive, but still yield one document.
- **Stop words:** `in the` and `the and the` can match even though ordinary
  keyword processing removes every term in those queries.
- **Case and punctuation:** `JaVa--SEARCH!` normalizes like `java search`.
  Phrases can span punctuation or line breaks. Quotes are ordinary separators;
  neither API parses them as a new query language.
- **Unknown terms or missing document entries:** An absent term ends the whole
  query early. `getOrDefault(documentId, List.of())` supplies an empty list
  when a known term does not occur in this document.

`List.of()` creates an unmodifiable empty list here, avoiding a null check.
`get()`, `size()`, `isEmpty()`, and `add()` perform list access, bounds control,
termination checks, and result accumulation. `List<Document>` is the declared
interface; `ArrayList` is the concrete growable result collection. Java boxes
the integer positions as `Integer` values inside generic collections.

The outer loop visits `documents`, already sorted by filename during loading.
It never relies on `HashMap` iteration order. `matches.add(document)` appears
once after all offsets have been checked, so each indexed document is returned
once regardless of its occurrence count. It returns the existing `Document`
objects, with their IDs and filenames, without a score or an extra sort.

All posting lookups inside this loop use the same `documentId`. Positions
restart in each document and cannot connect a word at the end of one file to
a word in another. The query uses no file reads: it examines stored positions
and document metadata, even if the original files have since disappeared.

## 7. The separate nine-document fictional corpus

The [collection README](../data/phrase_samples/README.md) labels these original
English paragraphs as fictional demonstrations, not real IIT notices or policies.
Each document also includes a fictional-data label.

| Document | What it helps distinguish |
| --- | --- |
| [academic_announcement.txt](../data/phrase_samples/academic_announcement.txt) | A project presentation `in the seminar room`, including stop words. |
| [algorithms_coursework.txt](../data/phrase_samples/algorithms_coursework.txt) | `binary search`; a different topic without java. |
| [information_retrieval_workshop.txt](../data/phrase_samples/information_retrieval_workshop.txt) | Adjacent `java search` and information retrieval vocabulary. |
| [java_programming_lab.txt](../data/phrase_samples/java_programming_lab.txt) | `java and java search`, two `java search` occurrences, and adjacent `error error`. |
| [networking_lab.txt](../data/phrase_samples/networking_lab.txt) | Java and search separated by `for a simple`; topical similarity is insufficient. |
| [project_presentation.txt](../data/phrase_samples/project_presentation.txt) | Another presentation `in the seminar room`, testing multiple results. |
| [reverse_order_exercise.txt](../data/phrase_samples/reverse_order_exercise.txt) | `search java`, reversing the requested `java search` order. |
| [seminar_discussion.txt](../data/phrase_samples/seminar_discussion.txt) | `programming in java`, with different wording from `java programming`. |
| [software_engineering_coursework.txt](../data/phrase_samples/software_engineering_coursework.txt) | Error followed later by another error; repeated words are not automatically adjacent. |

The sample test explicitly loads the folder with this actual statement:

```java
engine.buildIndex("data/phrase_samples");
```

`buildIndex()` loads only immediate regular `.txt` files, sorted by filename.
It ignores the README and does not recurse into subdirectories. Therefore the
normal server/CLI call `buildIndex("data")` still loads only the original six
documents. The sample folder is a separate collection, not an addition to
the default index. A full build replaces the engine's previous collection;
use a separate engine when both collections are needed independently.

For example, `java search` matches `information_retrieval_workshop.txt` then
`java_programming_lab.txt`. `search java` matches only
`reverse_order_exercise.txt`; `error error` matches only the Java lab.
`project presentation` matches the academic announcement then the presentation
document. These expected filenames are checked in test 16.

## 8. What all eighteen tests verify

`PhraseSearchTests` uses explicit `AssertionError` checks, reports each test,
and exits with status 1 if any fails. It needs no JUnit or `-ea` flag.
`checkPhrase()` compares the complete ordered filename list against its expected
list, checking omissions, unwanted matches, duplicates, and ordering together.
Most tests own three temporary files, deliberately created in reverse filename
order; cleanup removes only those named files and their owned directory.

| Test | Correctness checked |
| --- | --- |
| 1. Two-word phrase | The supplied `Java and Java Search` example matches `java search` and `and java`, but rejects `java java`. |
| 2. Reverse-order rejection | `java search` and `search java` match different documents. |
| 3. Nonadjacent-word rejection | Ordinary words and stop words both prevent a false adjacent match; the full longer phrase still matches. |
| 4. Three-word phrases | Checks all three consecutive offsets, including `java and java`, and rejects a wrong order. |
| 5. Repeated terms and overlapping occurrences | Handles two or three consecutive java tokens, rejects four when only three exist, and returns one document for several occurrences. |
| 6. Stop words inside phrases | Distinguishes `java and java` from `java or java`, while finding later adjacent `java search`. |
| 7. Stop-word-only phrases | Matches `in the`, `the in`, one-word `the`, and `the and the`; keyword search still filters them. |
| 8. Case and punctuation normalization | Covers mixed case, hyphens, underscores, line breaks, numeric tokens, and quotes as ordinary separators. |
| 9. One-word phrases | Returns documents containing `java` or the stop word `and`, without duplicates. |
| 10. Empty, unknown, and nonmatching input | Checks an unbuilt engine, empty/blank/punctuation input, unknown terms at different offsets, nonmatching combinations, and Bengali-only input under the existing ASCII scanner. |
| 11. Unique documents in filename order | Checks repeated queries, multiple occurrences, filename ordering, and the indexed document IDs. |
| 12. No cross-document matches | Separate java and search documents cannot combine; an intervening word also prevents a match. |
| 13. All offsets share one phrase start | Rejects unrelated java/search and search/guide pairs that do not form one full three-word phrase. |
| 14. Queries use the built index without reading files | Deletes all fixture files after indexing and confirms retrieval still works. |
| 15. Successful and failed rebuilds | Rebuilds without duplicate matches, replaces changed phrases, and retains the old usable index after invalid UTF-8 causes a later read failure. |
| 16. Fictional IIT phrase collection | Loads exactly nine documents and checks adjacent, reversed, repeated, stop-word, longer, one-word, and nonmatching sample queries. |
| 17. Keyword scores and semantics unchanged | Checks exact double score bits, OR results, alphabetical ties, case/punctuation, query deduplication, ordinary quoted keywords, and statistics before/after phrase calls. |
| 18. Default corpus statistics and ranking unchanged | Confirms nested samples are excluded: six documents, 75 terms, 120 tokens, and the original java filenames/order/scores. |

Tests 14 and 15 check different promises: queries use the last built index,
while successful rebuilding refreshes it. As in Phase 3.1A, fresh structures
are assigned only after all files load, preserving the old index on failure.

## 9. Ranking compatibility and verification evidence

The existing keyword method is unchanged. It still deduplicates filtered terms
with `LinkedHashSet`, sums raw TF multiplied by smoothed IDF, and sorts by
descending score with filename order for ties:

```text
idf = ln((N + 1) / (df + 1)) + 1
score(document) = sum of raw TF * idf over distinct processed query terms
```

Phrase matching does not update frequencies, statistics, or positions. Its
filename ordering is separate from score ordering; it has no phrase score.
The existing HTTP `/search` route continues calling keyword search, even when
its query contains quotes. No phrase route or browser control was introduced.

During implementation, Codex reported successful JDK 25.0.3 compilation and
these results: **7 baseline tests, 13 positional tests, 18 phrase tests, and
16 HTTP smoke tests passed, with no failures**. A separate before/after helper
reported identical filenames, order, exact scores, and statistics for 5,708
keyword queries. That helper lived under ignored `out/`, not in the committed
test suite. The temporary HTTP server was reported verified and stopped safely.

These are historical Codex-reported checks, not independently reproduced
student results. The supplied verified commit identifies the code documented
here; no independent rerun of the test suites was supplied. This documentation
task checks source/excerpt consistency, links, Markdown, whitespace, and scope.
It does not rerun compilation, Java suites, HTTP tests, or browser interaction.

For a later check, run these commands from the repository root:

```powershell
$projectJdk = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'
& "$projectJdk\bin\javac.exe" -encoding UTF-8 -d out\app src\*.java tests\BaselineTests.java tests\PositionalIndexTests.java tests\PhraseSearchTests.java
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
& "$projectJdk\bin\java.exe" -cp out\app PhraseSearchTests
if ($LASTEXITCODE -ne 0) { throw 'Phrase tests failed' }
```

Expect 18 passed and 0 failed for the phrase runner. The other suite commands
and safe server workflow are described in the
[positional manual](03-01-positional-index.md#11-verification-evidence-and-commands-for-a-later-check)
and [project README](../README.md#http-integration-smoke-tests-phase-25a).
Commands offered for a later check are not new test results.

## 10. Limitations

Exact matching follows normalized ASCII tokens, so it ignores punctuation
differences and can cross sentence or line boundaries within a document.
It does not compare original characters, stem words, match substrings, tolerate
misspellings, or accept gaps. Like existing keyword processing, phrase input
is expected to be non-null.

The implementation copies positional snapshots for every phrase-token occurrence,
including repeated terms, visits the loaded document list, and allocates
intermediate retained-start lists. It is understandable for this small in-memory
project; no large-corpus performance claim or concurrency guarantee is made.
There is no persistence, phrase-frequency ranking, Top-K limit, Boolean grammar,
frontend phrase integration, PDF extraction, or OCR in this milestone.

## 11. Six supervisor viva questions

1. **What makes a phrase match exact here?** All normalized phrase tokens occur
   at consecutive positions in one document, in the requested order.
2. **Why keep a common start instead of finding adjacent pairs independently?**
   Separate pairs may occur in different places; one start aligns every offset
   into the same complete phrase.
3. **How do the two pointers move?** Skip an actual position when it is too early,
   discard a start when its expected position is too early, and advance both
   after retaining an equal match.
4. **Why retain repeated terms and stop words?** Each token occupies its own
   required position; removing it changes the requested sequence.
5. **Why are results deterministic and confined to one document?** The outer loop
   follows sorted filenames and every positional lookup uses that document's ID.
6. **Does phrase retrieval change keyword ranking?** No. It returns documents
   without scores, and keyword TF-IDF-style scoring still uses the frequency map.

For practice, trace `java search guide` in `java search pause search guide`.
Write down the retained starts after each offset and explain why the final
result is empty. Complete personal reflection after working through the code.

Return to the [learning index](README.md).
