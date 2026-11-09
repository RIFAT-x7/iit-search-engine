# Phase 3.5: Midterm features and the complete search pipeline

**Learning Manual #15**

**Student:** Rifat Al Maruf (BSSE1705), IIT, University of Dhaka

**Course:** SPL-1, third semester

**Record:** November 9, 2026; draft for independent review

**Application baseline:** `ab6eacc43b9b8424f332304f18729255ef45d3a0`

Phases 3.5A-3.5C are integrated. Features are frozen for the November 12 midterm;
Phase 3.5D is active stabilization, not completed presentation or viva practice.
This AI-assisted manual explains the code; the student should trace it personally.
The previous fourteen manuals remain historical records and are not rewritten.

## A. Complete request pipeline

Read [SearchEngine](../src/SearchEngine.java), [TextProcessor](../src/TextProcessor.java),
[InvertedIndex](../src/InvertedIndex.java), [SearchServer](../src/SearchServer.java),
[app.js](../web/app.js), [highlight.js](../web/highlight.js) and
[reader.js](../web/reader.js) alongside this explanation.

```text
One selected collection: data/ OR data/phrase_samples/
  -> sorted immediate .txt files -> read bytes -> strict UTF-8 decoding
  -> lowercase ASCII tokens -> frequency + positional inverted index
                           -> private filename/original-byte snapshots
Browser query -> GET /search?q=...&mode=... [keyword k if selected]
  -> validation -> existing Java retrieval -> unchanged JSON -> ordered links
  -> GET /document/view?name=... -> source offsets -> snippet + safe highlights
  -> reader.html?name=...&q=...&mode=... -> same matcher, whole document
  -> /document/download?name=... -> exact original snapshot bytes
```

The default collection has six files; `--phrase-demo` selects nine different files.
The loader is not recursive, so these collections do not combine. Document IDs
start at 1 in sorted filename order. A `Document` contains ID/filename, not text.

An inverted index maps a term to documents containing it. Frequency postings
store occurrence counts. Positional postings also store zero-based token positions.
The server builds before listening, retains that engine and reads fixed web
assets once at startup. Restart to deliberately refresh HTTP content. The separate
CLI creates its own engine and can rebuild its default index manually.

Java retrieves documents. JavaScript composes queries and presents results; its
highlights do not decide whether a document belongs in the result list.

## B. Advanced Search and Boolean meaning

Basic Keyword Search accepts ordinary multiword text: matching any searchable
term can make a document eligible, with relevance ranking. Explicit Boolean
retrieval instead applies conditions and returns filenames in filename order.
It does not add relevance ranking or semantic understanding.

The browser represents groups as arrays of word strings:

```js
[["computer", "java"], ["computer", "python"]]
```

Each inner array joins with ` AND `; complete groups join with ` OR `. This produces:

```text
computer AND java OR computer AND python
```

Its intended meaning is `computer AND (java OR python)`. The repeated computer
condition is necessary because AND is evaluated before OR. The shortened
`computer AND java OR python` means `(computer AND java) OR python`, which
wrongly permits a python-only document. Parentheses here explain logic; the
parser itself does not support them. This is an illustrative condition, not a
promise that the bundled collections contain matching computer/java examples.

The UI supplies Add AND Word, Add OR Group and remove controls, retaining at
least one group and one word. Each field accepts one ASCII letter/digit operand,
trims surrounding whitespace and rejects empty fields, internal spaces,
punctuation, stop words and reserved AND/OR/NOT. Unknown valid words are allowed.
The raw API permits some surrounding punctuation that normalizes to one token;
the builder is intentionally stricter so it never silently alters an operand.

The expression preview is the submitted `q`; the mode remains `boolean`, and no
`k` is sent. All conditions in an AND group must occur somewhere in the same
document, without adjacency. The backend intersects each AND group's document
sets, then unions complete groups. No new retrieval algorithm or general
expression tree was needed. The interface is inspired by IEEE Xplore's grouped
entry layout, without its metadata fields or nested-query capabilities.

Actual default-corpus demonstration: groups java AND programming, OR computer
AND networking generate `java AND programming OR computer AND networking` and
return `java_programming.txt`, then `networking.txt`.

## C. Keyword ranking: the exact current formula

[TextProcessor.process](../src/TextProcessor.java) lowercases and keeps ASCII
letters/digits, with other characters separating tokens. It removes these twelve
stop words: a, an, the, and, or, is, are, of, to, in, for, with.
A `LinkedHashSet` keeps distinct query terms in their first-occurrence order.
Repeating a query word does not multiply its contribution.

For each distinct retained query term t:

```text
TF(t,d)   = raw occurrence count of t in document d
DF(t)     = number of documents containing searchable term t
N         = number of indexed documents
IDF(t)    = ln((N + 1) / (DF(t) + 1)) + 1
score(d,q)= sum over distinct retained query terms t of TF(t,d) * IDF(t)
```

`ln` is the natural logarithm, implemented with `Math.log`. Missing terms have
no posting contributions. The smoothing avoids a division by zero in the formula;
a term in every document has IDF 1. This is a raw-TF, smoothed TF-IDF-style sum,
not cosine similarity, BM25 or document-length normalization. Repetition in a
long document can increase its score; relevance quality is not guaranteed by
counting more terms alone.

On the default corpus, N=6 and java occurs in two documents. Its IDF is
`ln(7/3)+1`, approximately 1.8472978603872037. Three java occurrences in
`java_programming.txt` contribute 5.541893581161611; one in `academic_notice.txt`
contributes 1.8472978603872037. Higher scores come first.

Unrestricted `search()` uses a score array, scans documents in filename order,
and performs stable insertion sort by descending score. It moves only strictly
smaller scores, so equal scores retain Java filename order (`String.compareTo`).
This is not a case-insensitive or locale-aware natural filename sort.

Keyword JSON still contains numerical scores and the original schema. The
browser hides those values while preserving server order. Hiding text cannot
change Java's score calculation or reorder results. The CLI still displays scores.

## D. Optimized Top-K

K is the maximum number of ranked keyword matches requested, not a similarity
threshold. The browser offers All results or Top 1/3/5/10. The HTTP API accepts
one positive ASCII-decimal int through 2147483647, only in keyword mode.
Missing K calls unrestricted search. If fewer than K documents match, return all.

`searchTopK()` visits query posting lists and accumulates scores in a sparse
`HashMap<Integer, Double>` containing candidate document IDs. It preserves the
same distinct-term accumulation order and formula as unrestricted search.
Contiguous document IDs allow metadata lookup with `documents.get(id - 1)`.

A manually implemented **bounded binary heap** retains at most K results in an
ArrayList. The root is the worst retained result: smaller score, or later filename
when scores tie. A heap is partially ordered, not a globally sorted array.

1. If space remains, append and sift up.
2. If full, compare the candidate with the root. Replace/sift down only if better.
3. Extract worst results until empty, then reverse the retained list.

For zero-based indices, parent is `(child-1)/2`, left child `2*parent+1`, right
child `2*parent+2`. Each repair travels one tree path. The heap grows on demand;
passing a huge K does not immediately allocate K slots.

Let P be posting entries visited, M matching candidates and H=min(K,M). Excluding
query processing, expected time with ordinary hash-map assumptions is:

```text
O(P + M log(H+1) + H log(H+1))
```

Scoring visits P entries; selection visits M candidates; final extraction orders
H entries. Query scanning and distinct-term lookups add their own costs, including
unknown terms. Extra candidate/heap/output space is O(M+H), plus query storage and
the existing index. Filename comparisons also depend on filename length.
Unrestricted search uses O(N) score storage and can take O(M²) insertion-sort
work, in addition to scoring/document scans. These are algorithmic bounds,
not measured browser performance.

Top-K returns the exact prefix of unrestricted ranking because it retains the
best H entries under the same score/tie order, regardless of candidate arrival
order. It neither calls full search then truncates nor skips scoring candidates.
There is no WAND, score-bound pruning or new relevance formula. See
[Manual #13](03-06-optimized-top-k.md) for deeper heap traces and separately
attributed historical benchmark evidence; no benchmark was run for this manual.

## E. Exact phrase retrieval

`tokenize()` retains all normalized tokens, including stop words. Frequency
postings omit the twelve stop words, but positional postings keep them so phrase
gaps are not lost. `java and programming` must not collapse into an adjacent
`java programming` phrase.

For query tokens t0,t1,..., a phrase start p is valid when every token ti has a
position `p+i` in the same document. `retainPhraseStarts()` merges sorted start
and position lists with two pointers, preserving one common start for the whole
phrase. Separate adjacent pairs are not sufficient. Matches are unique documents
in filename order, without a numerical phrase score.

Punctuation separates tokens. Thus `Java, programming` can match normalized
`java programming`; this is not literal punctuation/case matching. Quotes do not
activate a query language: select phrase mode and supply the phrase text.

In the nine-file collection, `java search` matches only
`information_retrieval_workshop.txt` and `java_programming_lab.txt`.
`networking_lab.txt` contains Java and search separated by other words; the
reverse-order exercise contains search Java. Those are not the requested phrase.
`in the seminar room` demonstrates that stop words remain part of the sequence.

## F. Indexed original-byte access

During `buildIndex`, the engine builds new documents, postings and a private
filename-to-byte-array map. It reads each accepted file once, decodes strictly as
UTF-8 for indexing and retains the original bytes for access. It assigns the new
structures only after the complete build succeeds. Read/decoding failure leaves
the previous structures usable. This is not a concurrent atomic-rebuild guarantee.

`getIndexedDocumentBytes(filename)` performs an exact map lookup and returns a
clone, preventing callers from modifying stored bytes. Unknown/unindexed names
return no document. No browser request resolves `root.resolve(name)` or reads
`Path.of(name)`. Exact filenames identify current indexed records, not arbitrary
files or permanent globally unique IDs.

| Action | Response |
| --- | --- |
| `/document/view?name=java_programming.txt` | Original bytes; text/plain UTF-8; nosniff |
| `/document/download?name=java_programming.txt` | Same original bytes/type/nosniff; attachment Content-Disposition |
| `/reader.html?name=java_programming.txt&q=java&mode=keyword` | Fixed HTML page; JavaScript fetches raw text and adds safe highlights |

The download header has a sanitized ASCII fallback plus percent-encoded UTF-8
`filename*`. Client input is not copied unchecked into headers. Exactly one
nonempty name parameter is required; malformed UTF-8/escapes, unexpected or
duplicate parameters, controls, separators and path syntax receive 400. Valid
unindexed filenames receive 404. Unsupported methods receive 405 with Allow: GET.
Errors avoid filesystem paths. Decoding happens once, so double-encoded text is
not repeatedly interpreted as traversal syntax.

Changing or deleting a file after indexing leaves the original snapshot available
until successful rebuild/restart. Search results and served content therefore
refer to the same loaded document state. Default and demo collections remain
separate. Symlink checks, collection-parent validation and NOFOLLOW_LINKS opens
are present, but the Windows symlink fixture was skipped. This is an indexed-file
prototype boundary, not a login, authorization system or complete security audit.

## G. Context snippets and shared highlights

The browser fetches `/document/view` once per displayed result and passes its
text to `HighlightPresentation`. The module scans normalized ASCII-alphanumeric
tokens while retaining original UTF-16 character start/end offsets. These are
character offsets for display, distinct from Java's token positions for phrases.

| Mode | Verified highlight rule |
| --- | --- |
| Keyword | Complete source tokens in distinct filtered query terms; no substring match inside javascript for java |
| Phrase | Individual tokens belonging to complete consecutive normalized phrase occurrences, including stop words |
| Boolean | Operands only from AND groups fully satisfied by the document token set; AND precedes OR |

Repeated query terms/overlapping phrase occurrences do not duplicate marks.
AND and OR are not Boolean highlight terms. A satisfied group's words may be far
apart; highlights do not claim a single local passage satisfies every condition.
An OR result need not satisfy every alternative. Invalid/unverifiable matching
renders plain text. A length-changing Unicode lowercase conversion cannot reuse
source offsets safely, so this module conservatively omits highlights.

`snippet()` chooses a source slice around the first verified token, targeting
200 UTF-16 code units with preceding context. It avoids cutting verified tokens
or surrogate pairs and adds ellipses only when source content was omitted.
An unusually long matched token can exceed the target. Original capitalization,
punctuation and whitespace come from source slices, not reconstructed sentences.
Empty documents and failed/no-trustworthy excerpts have neutral messages rather
than fabricated content. A preview is labeled Preview, not summary.

`render()` appends text nodes and `<mark>` elements with `textContent`. It never
uses document content as HTML. Angle brackets, ampersands, quotes and script-like
text remain literal. Reader filename/query parameters also never create HTML.
The reader preserves full text line breaks with CSS `white-space: pre-wrap` and
uses the same module as result previews. Raw/download responses remain unchanged.

`AbortController` cancels obsolete fetches. `latestRequestId` also invalidates
responses arriving despite cancellation, including after body decoding. New
searches, raw edits, mode/limit changes and Advanced Search edits invalidate old
results/previews. Preview failures affect only their preview, not the result link,
server ordering or enabled search controls. The controller is retained after
search completion so pending previews can still be cancelled.

## H. Limits and verification

This is a localhost-only academic prototype with fictional `.txt` data. It has no
real IIT private records, network deployment, login/authorization, PDF/OCR,
metadata/date filters, fuzzy/semantic search, full Bangla/Unicode IR, persistence,
uploads or HTTP rebuild. Boolean parentheses, NOT, wildcards and multiword
operands are unsupported. Snippets/highlights require JavaScript. Native GET can
still open JSON. Extra full-text preview requests are acceptable for these tiny
collections, not an evaluated architecture for a large production corpus.

Fresh November 9 Phase 3.5D verification of the baseline: **100 Java, 320 HTTP,
94 browser-model and 33 highlighting/reader passes = 547**, with 0 failures and
one skipped Windows symlink fixture. Compilation used Temurin 25.0.3+9; five JS
syntax checks and seven PowerShell parses passed. Both HTTP collections passed
ownership checks and cleanup. Counts are named checks, not every assertion.
The old 287 total and temporary seven-case Boolean truth table are historical;
they are not added to 547. See the [README evidence](../README.md#testing-and-verification-record).

The student reported manual Microsoft Edge acceptance of 3.5A-3.5C on both
collections, covering keyword/Top-K, phrases, grouped Advanced Search, open/download,
snippets, mode-specific highlights, reader and raw access. Codex did not independently
run those browser checks. No comprehensive accessibility/viewport/device audit,
new benchmark or production security guarantee is claimed. Wider manual coverage
remains unverified. Preparing this manual does not complete student viva practice.

## I. Twenty-five viva questions

1. **What is an inverted index?** A term-to-document mapping; this project stores
   frequency counts and separate complete token positions.
2. **Why not scan every file for every search?** Postings identify matching
   documents after startup indexing; retrieval does not reread source files.
3. **Is Basic Keyword Search strict AND?** No. Any searchable term can create a
   candidate, then the TF-IDF-style score orders candidates.
4. **How does Boolean search differ?** It applies explicit AND/OR set conditions
   and returns unique filenames in filename order, without relevance scores.
5. **Why repeat computer in both groups?** AND precedes OR; the repetition makes
   each complete alternative require computer.
6. **Does the parser support parentheses?** No. Parentheses may explain intended
   logic in documentation, but the builder submits an expanded supported string.
7. **Did Advanced Search add an algorithm?** No. It composes a query for the
   existing Boolean evaluator using `mode=boolean`.
8. **What is TF?** Raw term occurrences in one document, not the query's repetition count.
9. **What is IDF here?** `ln((N+1)/(df+1))+1`, making rarer indexed terms contribute more.
10. **Is the score cosine similarity?** No. It is a sum of raw TF times smoothed
    IDF, without document-length normalization.
11. **What happens when scores tie?** Earlier Java filename order wins; full
    insertion sort is stable and the heap comparator uses the same tie rule.
12. **Why hide scores?** To simplify browser presentation while Java still ranks
    and the existing numeric JSON contract stays compatible.
13. **What does K mean?** At most K highest-ranked keyword matches; fewer are
    returned when fewer documents match.
14. **Why a worst-first heap?** Its root is the easiest retained result to replace
    when a better candidate arrives, using logarithmic repairs and at most K entries.
15. **Does Top-K avoid scoring every candidate?** No. It scores visited posting
    contributions and reduces selection/sorting work, not relevance computation.
16. **Why keep stop-word positions?** Removing positions could turn separated
    words into a false phrase; phrase search must include the full token sequence.
17. **Can Java, programming match java programming?** Yes, after lowercase ASCII
    tokenization punctuation separates words; literal punctuation is not required.
18. **Why retain original bytes?** Retrieval uses normalized tokens, but reading
    and downloading should preserve the indexed original content exactly.
19. **Why not resolve a browser filename as a path?** A path could expose
    unintended files. Exact snapshot lookup restricts access to indexed records.
20. **What if a source file changes after startup?** Search and access keep the
    prior snapshot until successful rebuild or restart.
21. **What if rebuilding fails?** The new structures are not published; the old
    index and byte snapshots remain. Concurrent rebuilds are not guaranteed.
22. **How do snippets avoid fabricated content?** They slice the original text
    using verified token offsets and render those characters safely.
23. **Why verify a complete phrase before highlighting?** Isolated query words
    could occur without the requested adjacency; marking them would mislead users.
24. **Why both abort and request IDs?** Cancellation can race with a response;
    request identity prevents obsolete text from appearing under current results.
25. **What is needed before real IIT deployment?** Agreed document rights,
    identity and per-document authorization, secure hosting, persistence/rebuild
    policy, operational testing and evaluation. A localhost allowlist is insufficient.

Before the viva, trace one keyword score, one heap replacement, one phrase start
and one Boolean expansion by hand. Explain the distinction between independently
executed automated checks, student-reported browser acceptance and skipped tests.
Do not claim unmeasured speedups or capabilities that belong to future phases.

[Learning index](README.md) · [Demo runbook](../docs/MIDTERM_DEMO_RUNBOOK_2026-11.md)
