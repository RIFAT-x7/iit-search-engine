# Phase 3.4A: Optimized Top-K ranked retrieval

**Learning Manual #13**

**Student:** Rifat Al Maruf (BSSE1705), IIT, University of Dhaka

**Course:** SPL-1 | **Record date:** November 1, 2026

## 1. Objective, checkpoint and reading guide

Phase 3.4A adds `List<SearchResult> searchTopK(String query, int k)` to the Java
core. It returns the same first K documents and exact numerical scores as
`search(query)`, using sparse scoring and a bounded binary heap instead of
ranking every match with insertion sort.

This manual describes implementation commit
`700f3e2e63d35e00196099d6c1b3a22a79e94dd2` on `feat/optimized-top-k`.
At this documentation checkpoint, local and fetched remote main remain at
`f56078b429c6e7001826a22d82b240d8c0c31553`. The feature is published on its
branch, not yet integrated into main. Top-K is **Java-core-only**: there is no
Top-K HTTP parameter, browser control or CLI menu option.

Read alongside:

- [SearchEngine.java](../src/SearchEngine.java): full ranking, Top-K and heap operations.
- [SearchResult.java](../src/SearchResult.java): document/score result objects.
- [InvertedIndex.java](../src/InvertedIndex.java): existing frequency postings.
- [TextProcessor.java](../src/TextProcessor.java): shared query normalization.
- [Document.java](../src/Document.java): immutable ID and filename metadata.
- [TopKSearchTests.java](../tests/TopKSearchTests.java): all 26 tests and optional benchmark.
- Earlier explanations: [Phase 1 ranking](01-phase1-foundations.md),
  [positional indexing](03-01-positional-index.md),
  [phrase retrieval](03-02-exact-phrase-search.md),
  [Boolean retrieval](03-04-boolean-search.md) and
  [browser Boolean integration](03-05-browser-boolean-search.md).

These notes were prepared with Codex assistance. Source excerpts below are
short fragments of actual code, not separate replacement implementations.
Benchmark/test results are attributed historical reports, not independent
student measurements. See [verification evidence](#15-verification-evidence).

Study route: [scoring](#4-same-query-processing-and-exact-scoring),
[heap operations](#8-heap-operations-in-the-actual-code),
[worked trace](#9-six-document-worked-example-k--3),
[complexity](#12-time-and-memory-complexity),
[tests](#13-the-26-tests-in-registration-order), then
[viva preparation](#17-ten-supervisor-viva-questions).

## 2. What Top-K means and why it is useful

Ranked retrieval assigns a relevance score to each matching document. Top-K
means returning only the best K according to the existing ranking policy:

1. Higher score first.
2. Equal score: lexicographically earlier filename first.

For example, K=3 asks for the best three matches, not the first three posting
entries encountered. A later candidate may displace an earlier one. If only two
documents match, K=3 returns both. An unknown query returns none.

Calling `search()` and taking a prefix limits output, but still pays for the
original scoring array, document scan and complete insertion sort. The new
method reduces ranking work by keeping only the current best K. It still has
to compute the relevance scores of posting-driven candidates.

## 3. Previous full ranking and the new pipeline

The original `search()` allocates a `double[]` of N+1 scores, visits query
postings, scans all document metadata to collect positive-score matches, then
insertion-sorts the M results. Files were loaded in filename order, and the sort
moves a previous result only if its score is strictly lower:

```java
while (j >= 0 && results.get(j).getScore() < current.getScore()) {
    results.set(j + 1, results.get(j));
    j--;
}
results.set(j + 1, current);
```

Thus equal scores retain filename order: the sort is stable. That old method
remains unchanged and serves as the compatibility reference.

```text
Full search: query -> frequency postings -> N-sized score array
             -> scan N documents -> collect M matches -> insertion-sort M

Top-K:       query -> frequency postings -> sparse scores for M candidates
             -> bounded heap of at most K -> extract/reverse retained results
```

The optimization changes candidate storage and selection, not the relevance
definition. No new index representation or external search library is needed.

## 4. Same query processing and exact scoring

### 4.1 Distinct terms in their original query order

Actual declarations in `searchTopK()`:

```java
Set<String> queryTerms = new LinkedHashSet<>(processor.process(query));
Map<Integer, Double> scores = new HashMap<>();
```

- `Set<String>` is the interface: a collection of unique strings.
- `new LinkedHashSet<>(...)` constructs that set while preserving first occurrence
  order. `<>` lets Java infer the generic type from the declaration.
- `processor.process(query)` lowercases with `Locale.ROOT`, splits on characters
  outside ASCII letters/digits and removes the configured twelve stop words.
- `JAVA, java and search` becomes the distinct sequence `java, search`.
  Repeated query terms do not increase their weights.
- Phrase retrieval instead uses complete `tokenize()` output, retaining stop
  words. Top-K deliberately follows keyword `process()` semantics.

The twelve stop words are a, an, the, and, or, is, are, of, to, in, for and with.
AND/OR have no operator meaning in this keyword API. Quotes are separators,
not phrase syntax. Shared normalization preserves these existing behaviors.

### 4.2 Posting lookup and smoothed IDF

```java
Map<Integer, Integer> postings = index.getPostings(term);
if (postings.isEmpty()) {
    continue;
}
int documentFrequency = postings.size();
double idf = Math.log((documents.size() + 1.0)
        / (documentFrequency + 1.0)) + 1.0;
```

Line by line:

1. A posting map associates each matching **document ID** with its raw term count.
2. `isEmpty()` detects an absent term. `continue` skips this term's remaining work.
3. Its number of entries is document frequency, df: documents containing the term.
4. `documents.size()` is N, the number of indexed documents, including nonmatches.
5. `Math.log()` is the natural logarithm. `1.0` makes the arithmetic floating-point
   division and provides the same smoothing as the old method.

```text
idf(term) = ln((N + 1) / (df(term) + 1)) + 1
score(document) = sum of TF(term, document) * idf(term)
                 over distinct processed query terms
```

TF is the raw occurrence count. There is no length normalization or cosine
similarity. A term in every document has IDF=1. `getPostings()` exposes the
existing unmodifiable frequency map; unknown terms return an empty map.
Top-K neither changes postings nor uses positional snapshots for scoring.
Internally the frequency index is `Map<String, Map<Integer, Integer>>`:
term -> (document ID -> count). Complete positional postings remain available
to phrase search, including stop words, while these searchable frequency
postings exclude them.

### 4.3 Floating-point order is part of compatibility

Binary `double` arithmetic rounds; addition is not perfectly associative.
For illustration, `(1e16 + 1) + 1` can differ from `1e16 + (1 + 1)`.
That is a numerical illustration, not a score observed in these corpora.

The new method uses the same formula, multiplication and per-document addition
sequence as `search()`. The `LinkedHashSet` ensures terms are visited in the
same first-occurrence order. It does not sort query terms or round scores.
HashMap candidate iteration happens only **after scoring**, so it cannot reorder
the additions for a document. A differently ordered query may itself have tiny
rounding differences; Top-K must match full search for that same query.

## 5. Sparse score accumulation

`Map<Integer, Double>` describes keys and values; `HashMap` is the concrete
hash-table implementation. `Integer` and `Double` are wrapper types because
Java generic collections do not hold primitive `int` or `double` directly.
Java boxes/unboxes values when moving between these types.

Actual posting loop:

```java
for (Map.Entry<Integer, Integer> posting : postings.entrySet()) {
    int documentId = posting.getKey();
    scores.put(documentId, scores.getOrDefault(documentId, 0.0)
            + posting.getValue() * idf);
}
```

- `entrySet()` supplies key/value pairs. `Map.Entry<Integer, Integer>` names one
  posting pair; the enhanced `for` loop visits each pair.
- `getKey()` supplies the document ID; `getValue()` supplies its TF.
- `getOrDefault(id, 0.0)` reads the accumulated score, or zero for a first visit.
  It does not insert that default by itself.
- `put()` stores the old score plus this term's contribution. Later terms update
  the same ID, so a document cannot become multiple candidates.

Only IDs seen in query postings receive entries. For example, two matches among
300 documents need two score-map entries instead of a 301-slot score array.
This is sparse accumulation: storage follows matches rather than corpus size.
HashMap does not promise traversal order; correctness must not depend on it.

## 6. From candidate IDs to result objects

```java
for (Map.Entry<Integer, Double> candidate : scores.entrySet()) {
    if (candidate.getValue() > 0) {
        // buildIndex assigns contiguous IDs starting at 1, so metadata
        // lookup is O(1); no scan of all N documents is required.
        Document document = documents.get(candidate.getKey() - 1);
        heap.offer(new SearchResult(document, candidate.getValue()));
    }
}
```

This loop visits the score map, not every indexed document. The positivity check
matches the old search rule. The loader sorts filenames and assigns IDs 1, 2,
3, ... while appending documents to an ArrayList indexed 0, 1, 2, ... . Therefore
ID minus one gives the metadata index in constant time. This relies on the
existing loader's ID invariant; it is not a general lookup for arbitrary IDs.

`new SearchResult(document, score)` packages immutable document metadata and an
unrounded score. Its fields are final and it exposes getters, not setters.
The result lists are newly allocated for each call; clearing one returned list
does not change the index or later searches.

There is no `Files.readString()` in this retrieval path. File reading happened
during `buildIndex()`. Only postings, scores and document metadata are read now.

## 7. Binary heap: an invariant over an ArrayList

An ordinary list has positions but no automatic priority rule. A binary heap
stores a complete binary tree in a list and maintains a parent/child ordering
invariant. It is **not a globally sorted list**: siblings need not be sorted.

For zero-based list indices:

| Relationship | Formula | Example |
| --- | --- | --- |
| Parent of child c, when c > 0 | `(c - 1) / 2` using integer division | parent of 4 is 1 |
| Left child of parent p | `2 * p + 1` | left child of 1 is 3 |
| Right child of parent p | `2 * p + 2` | right child of 1 is 4 |

The tree is complete because insertion appends at the end and extraction
removes the last slot. Its height is logarithmic in its number of entries.

The implementation declares:

```java
private static class BoundedResultHeap {
    private final int limit;
    // Grow on demand rather than allocating k slots (k may be MAX_VALUE).
    private final List<SearchResult> entries = new ArrayList<>();
```

- `private` keeps this helper inside SearchEngine's implementation.
- `static` means each heap needs no implicit enclosing SearchEngine reference.
  Its own instance still has a limit and an entries list; those are not static.
- `final` prevents reassignment of these fields. The list's contents can change.
- `List<SearchResult>` is the interface; `ArrayList` supplies resizable indexed
  storage. It is not Java's ready-made PriorityQueue: heap maintenance is manual.
- `new ArrayList<>()` does not reserve K slots. It grows on demand, so passing
  `Integer.MAX_VALUE` does not itself cause a huge allocation. Actual memory
  still depends on how many matches must be scored and returned.

### Why the worst candidate belongs at the root

We need quick access to the result most suitable for eviction. The root is
the lowest-scoring retained candidate, with the latest filename among ties.
This is a min-heap by ranking quality, with reverse filename order for ties.

Invariant: **a parent is no better than either child**. Following this relation
from root to descendants makes the root a worst retained candidate. A new
candidate that cannot beat the root cannot enter the best K seen so far.

Actual comparison:

```java
private static boolean isWorse(SearchResult left, SearchResult right) {
    if (left.getScore() != right.getScore()) {
        return left.getScore() < right.getScore();
    }
    return left.getDocument().getFilename()
            .compareTo(right.getDocument().getFilename()) > 0;
}
```

Different scores are compared first; smaller is worse. Equal scores use
`String.compareTo()`: a positive value means the left filename is later and
therefore worse. This is case-sensitive Java lexicographic order, consistent
with the loader's filename order, not locale-aware or numeric filename sorting.

## 8. Heap operations in the actual code

### 8.1 Insert, replace or discard

```java
private void offer(SearchResult candidate) {
    if (entries.size() < limit) {
        entries.add(candidate);
        siftUp(entries.size() - 1);
    } else if (isWorse(entries.get(0), candidate)) {
        entries.set(0, candidate);
        siftDown(0);
    }
}
```

When space remains, `add()` appends one candidate and sift-up restores the
invariant. When full, replace the root only if the new candidate is better.
Otherwise do nothing: discard it. `set()` replaces a slot without increasing
size. Because appending is guarded by `size < limit`, the heap never exceeds K
entries. This also establishes by induction that it retains the best
min(K, candidates seen) results after each offer.

### 8.2 Sift-up after insertion

```java
private void siftUp(int child) {
    while (child > 0) {
        int parent = (child - 1) / 2;
        if (!isWorse(entries.get(child), entries.get(parent))) {
            break;
        }
        swap(child, parent);
        child = parent;
    }
}
```

The new last entry may be worse than its parent. Swap it upwards while this
violation exists. `!` negates the comparison; `break` exits the loop once the
parent is already no better than the child. Setting `child = parent` continues
at the new location. Each step climbs one level, not one arbitrary list slot.

### 8.3 Sift-down after root replacement

```java
while (parent < entries.size() / 2) {
    int worseChild = 2 * parent + 1;
    int right = worseChild + 1;
    if (right < entries.size()
            && isWorse(entries.get(right), entries.get(worseChild))) {
        worseChild = right;
    }
    if (!isWorse(entries.get(worseChild), entries.get(parent))) {
        break;
    }
    swap(parent, worseChild);
    parent = worseChild;
}
```

Start at root index 0. A node has a child exactly when its index is less than
`size / 2`. Compute the left index, then choose the worse of the two children
if a right child exists. `&&` checks the right bound before accessing it.
Swap only when that child is worse than the parent; continue down its branch.
Choosing the worse child restores the relation with both children at once.
The loop guard also avoids overflowing `2 * parent + 1` for a very large heap.

The `swap()` helper uses a temporary SearchResult reference and two `set()`
calls. Swapping changes positions, not scores, documents or heap size.

### 8.4 Extract the worst, then order the retained results

```java
private SearchResult removeWorst() {
    SearchResult worst = entries.get(0);
    SearchResult last = entries.remove(entries.size() - 1);
    if (!entries.isEmpty()) {
        entries.set(0, last);
        siftDown(0);
    }
    return worst;
}
```

Save the root, remove the last entry, move it to the vacant root and sift down.
Removing the last ArrayList entry avoids shifting the whole list. With one
entry, removal empties the list and the guard prevents an invalid `set(0, ...)`.
This private method is called only when the heap is nonempty.

The public method finishes with:

```java
List<SearchResult> results = new ArrayList<>();
while (!heap.entries.isEmpty()) {
    results.add(heap.removeWorst());
}
// Extractions are worst-to-best. Reverse only the retained entries.
Collections.reverse(results);
return results;
```

Extraction produces worst-to-best among the retained candidates. `reverse()`
reverses that list in place in linear time; it does not perform a comparison
sort. Only K' retained results are extracted and reversed. Unretained matches
are never assembled into a full ranked result list.

## 9. Six-document worked example: K = 3

**Illustrative fictional example, not measured corpus output.** Suppose the
query is java and all six documents contain it. Then N=df=6, IDF=1, so the
following invented raw TF counts are also the scores. No dataset files are
created for this explanation.

| Document | TF(java) | Score |
| --- | ---: | ---: |
| a.txt | 4 | 4.0 |
| b.txt | 7 | 7.0 |
| c.txt | 2 | 2.0 |
| d.txt | 6 | 6.0 |
| e.txt | 1 | 1.0 |
| f.txt | 8 | 8.0 |

Assume arrival order a, b, c, d, e, f for tracing. HashMap does not guarantee
this order; any arrival order must yield the same final ranking. Lists below
are **heap array order**, not sorted display order. The first entry is the root.

| Arrival | Decision and repair | Heap after operation |
| --- | --- | --- |
| a:4 | Space remains; append | [a:4] |
| b:7 | Append; 7 is not worse than parent 4 | [a:4, b:7] |
| c:2 | Append at index 2; swap with parent a:4 | [c:2, b:7, a:4] |
| d:6 | Full; 6 beats root 2. Replace c; swap d with worse child a:4 | [a:4, b:7, d:6] |
| e:1 | 1 cannot beat root 4; discard | [a:4, b:7, d:6] |
| f:8 | 8 beats root 4. Replace a; swap f with worse child d:6 | [d:6, b:7, f:8] |

At each step the root is no better than either child. Notice the child b:7 can
appear before d:6 in the array: a heap is not a sorted list.

Final extraction:

| Remove worst | Remaining heap | Output so far |
| --- | --- | --- |
| d:6 | [b:7, f:8] | [d:6] |
| b:7 | [f:8] | [d:6, b:7] |
| f:8 | [] | [d:6, b:7, f:8] |

Reversing gives the requested ranking: **f:8, b:7, d:6**. All six candidates
were scored, but only three were retained for final ordering.

## 10. A separate filename-tie example

This is also illustrative, not measured output. Let K=2 and each candidate
score 5.0. Arrivals are zeta.txt, beta.txt, alpha.txt, gamma.txt.

| Arrival | Heap array | Reason |
| --- | --- | --- |
| zeta:5 | [zeta:5] | First candidate |
| beta:5 | [zeta:5, beta:5] | zeta is later, so it is the worst root |
| alpha:5 | [beta:5, alpha:5] | alpha beats zeta; after replacement beta sifts above alpha |
| gamma:5 | [beta:5, alpha:5] | gamma is later than beta, so discard gamma |

Extraction gives beta then alpha; reversal gives **alpha.txt, beta.txt**.
Filename order decides which equal-score document survives the cutoff, not just
the display order afterwards. A higher score always takes priority over the
filename: zeta at 6 beats alpha at 5.

## 11. API boundaries, independence and rebuilds

The actual validation at the start of the method is:

```java
if (k < 1) {
    throw new IllegalArgumentException("Top-K limit must be at least 1.");
}
```

`throw new ...` constructs and reports an invalid-argument error rather than
silently returning no results. Valid K with empty, punctuation-only, unknown
or stop-word-only input returns a new empty list. K greater than the number of
matches returns every match. K=1 needs only a single retained candidate, so
its heap work per candidate is constant time.

Large K is a limit, not an allocation request: the score map grows with M and
the heap with K'=min(K,M). `Integer.MAX_VALUE` is accepted without preallocating
that many heap slots. It cannot make an actually enormous corpus free to process.
Null query text is not given a new contract; with valid K it reaches the same
TextProcessor null handling as existing keyword search.

The feature adds a separate method and private helpers. It does not replace
`search()`, `searchPhrase()` or `searchBoolean()`, and it does not add a browser
mode or HTTP route. The old methods retain their scoring or matching semantics.

`buildIndex()` still constructs fresh document/index structures and assigns
them only after all files load successfully. A successful rebuild refreshes
frequencies, metadata and IDs. Missing folders or invalid UTF-8 leave the old
usable structures in place. Top-K reads whichever complete index is current,
and retains no heap or score map between calls. This does not introduce a
guarantee of thread-safe concurrent rebuilds; that remains outside the design.

## 12. Time and memory complexity

### 12.1 Definitions and derivation

| Symbol | Meaning |
| --- | --- |
| N | Total indexed documents |
| P | Sum of posting entries visited across distinct searchable query terms |
| M | Number of matching candidate documents |
| K | Requested positive limit |
| K' | min(K,M), the number of retained/returned results |

Expected retrieval time, excluding query normalization and distinct-term
construction, is:

```text
O(P + M log(K'+1) + K' log(K'+1))
```

1. **Scoring: O(P).** Visit posting entries and update sparse scores with
   expected constant-time HashMap operations.
2. **Selection: O(M log(K'+1)).** Visit M candidates. Rejected candidates need
   only a root comparison; insertion/replacement can traverse heap height.
3. **Final ordering: O(K' log(K'+1)).** Extract K' times, then reverse in O(K').

Using log(K'+1) handles heaps of size zero/one without implying zero work.
The formula is an upper bound, not a claim that every candidate sifts the full
height. For a complete accounting, also include input scanning/token storage
and one lookup per distinct query term, including absent terms. If L is input
length and Q is distinct processed term count, these add processing costs such
as O(L+Q); an all-unknown query still requires reading and looking up its terms.

HashMap's constant-time update/lookup assumption is expected/amortized, not an
unconditional worst-case guarantee. Hash collisions, resizing, boxing and
allocation affect actual costs. Heap repairs have logarithmic path length;
ArrayList growth makes append amortized constant time, with occasional copying.
Filename comparisons also depend on string length; the displayed bounds treat
a score/filename comparison as constant cost.

### 12.2 Actual memory usage

Extra live storage, excluding the existing index and query-term storage, is:

```text
O(M + K')
```

- Sparse accumulator: O(M) ID/score entries, even when K=1.
- Heap: at most K' live result entries. ArrayList may have spare capacity, but
  its reference storage grows with retained entries rather than requested K.
- Returned list: O(K'). During extraction both heap backing storage and output
  can coexist; their total is still O(K').
- Boxing score updates and constructing a SearchResult for each positive
  candidate create temporary objects. The heap limit is a bound on retained
  entries, not on total allocations over the query's lifetime.

Thus Top-K saves ranking work and can save memory against an N-sized score
array for sparse queries, but it is not an O(K)-memory retrieval algorithm:
all M candidate scores are accumulated first. When K>=M, all matches are kept
and ordering costs O(M log(M+1)).

### 12.3 Comparing selection strategies

These bounds describe work after query preprocessing, using comparable scoring.
The efficient full-sort row is a comparison, not a change made in this project.

| Strategy | Candidate handling and ranking cost | What is ordered? |
| --- | --- | --- |
| Existing full search | O(P+N+M^2) worst case; insertion sort can be O(M) when already ordered | All M matches |
| Efficient full comparison sort | O(P+N+M log(M+1)) if keeping the old array/scan; sparse scoring could remove N | All M matches |
| New sparse bounded heap | Expected O(P+M log(K'+1)+K' log(K'+1)) | Only K' retained matches |

Insertion sort is easy to understand and can be good for tiny/already ordered
lists, but moving many results gives quadratic worst-case ranking work.
An efficient full sort improves that to M log M, yet still orders all matches.
A bounded heap is useful when only a small K is needed. HashMap and heap overhead
can outweigh savings for small collections; theoretical growth is not timing.

This is not WAND or a score-skipping algorithm. Such approaches can use score
upper bounds to avoid fully scoring some candidates. This implementation visits
all query postings and computes all candidate scores before selecting winners.

## 13. The 26 tests in registration order

The [test harness](../tests/TopKSearchTests.java) uses explicit AssertionError
checks, so it does not need `-ea` or JUnit. Each normal run registers these 26
named test groups; a group can contain many individual comparisons.

| # | Registered test / helper | What it verifies |
| ---: | --- | --- |
| 1 | Top 1 / testLimit(1) | Best single result equals the original prefix; highest TF wins even when its filename is later. |
| 2 | Top 2 / testLimit(2) | Exact two-result prefix, IDs, filenames and score bits. |
| 3 | Top 3 / testLimit(3) | Correct three-result selection and order. |
| 4 | Top 5 / testLimit(5) | Correct cutoff with more than five matches. |
| 5 | K equals matching count / testLimit(6) | All six positive matches returned in the original order. |
| 6 | K exceeds matching count / testLimit(20) | No padding, missing entries or duplicates when K is larger. |
| 7 | Integer.MAX_VALUE / testLimit(MAX_VALUE) | Very large K works on a small collection without proportional heap allocation. |
| 8 | testInvalidK | Zero, -1 and Integer.MIN_VALUE throw a descriptive IllegalArgumentException; a later valid query succeeds. |
| 9 | testTies | Equal-score filename order controls the cutoff; explicit expected top three and K=1 through 7 prefixes. |
| 10 | testNormalization | Case, punctuation, numbers, quotes, all ordinary keyword semantics and repeated query terms agree with full search. |
| 11 | testScoreOrder | Unequal TF/df in 97 generated documents; several query-term orders and K values match exact score bits. |
| 12 | testEmptyQueries | Empty, whitespace and punctuation-only inputs give no matches for valid K. |
| 13 | testUnknown | Fully unknown queries are empty; known/unknown mixtures preserve the correct prefix. |
| 14 | testStopWords | All twelve stop words alone yield no ranked matches; mixing them with searchable terms preserves keyword processing. |
| 15 | testEmptyIndex | An unbuilt engine and a successfully built empty folder return no results, including with very large K. |
| 16 | testIndependentCalls | Clearing/removing from returned lists cannot corrupt later calls; different queries and K values do not share state. |
| 17 | testDefaultCorpus | Original six files, statistics 6/75/120, known java filenames, several queries and K boundaries. |
| 18 | testDemoCorpus | Only the nine separate fictional files are loaded; four queries agree with full search at several K values. |
| 19 | testGenerated | Seeded collections of 1, 2, 7, 31, 128 and 257 documents; 60 generated queries per size and seven limits per query. |
| 20 | testSparseCandidates | A 300-document fixture has only two java matches; a test-only metadata wrapper rejects iteration and counts exactly two direct metadata lookups. |
| 21 | testNoFileScan | Delete the owned source files after indexing; Top-K still returns the same results from memory. |
| 22 | testNoDelegation | Override full search() to throw in a test subclass; Top-K still succeeds, proving it does not delegate there. |
| 23 | testHeap | Reflectively test the actual private heap with 250 seeded shuffled candidates, repeated scores and eight limits; check bounds/parent relations after every offer and compare complete extracted output to an independent ranking. |
| 24 | testRebuild | Unchanged rebuild is stable; changed TF, deleted files, shifted IDs and replacement collections use the rebuilt index. |
| 25 | testFailedRebuild | Missing folder and invalid UTF-8 after an earlier changed file preserve exact Top-K/full-search results and token statistics. |
| 26 | testCompatibility | Keyword scores/order, phrase results, Boolean precedence/intersection, positions and index statistics remain unchanged around Top-K calls. |

### How exact comparisons and structural checks work

The shared `prefix()` oracle runs full search in the **test**, builds its first
min(K,M) results, and compares the new API against that reference. This is
legitimate: the test evaluates compatibility rather than serving a user query.
Production delegation would defeat the optimization and is separately rejected
by the subclass test and source review.

The comparison includes:

```java
check(Double.doubleToLongBits(left.getScore()) == Double.doubleToLongBits(right.getScore()),
        "Exact score bits at " + i + ": " + left.getScore() + " vs " + right.getScore());
```

`doubleToLongBits()` represents each finite score's double bits as a long.
Equality therefore rejects rounding differences that an approximate tolerance
could conceal. Counts, document IDs and filenames are also compared at every
rank, so equal scores alone cannot hide swapped documents or duplicates.

Reflection lets testHeap access the private nested class, constructor, offer,
removeWorst and entries field using `getDeclared...` and `setAccessible(true)`.
This couples that structural test to the helper's names, but keeps test hooks
out of the public API. The test checks heap size and parent/child order after
each **offer**; it then verifies the entire extraction sequence after reversal.
It does not separately assert every internal parent relation after each removal.
Its independent comparator sorts a copy only inside the test oracle.

The sparse wrapper test catches both an enhanced-for document scan and excess
indexed metadata reads. The no-file-scan test removes only its owned fixtures.
Together with source inspection, these check structural requirements that
result equality alone could not distinguish from a full-search shortcut.

Fixtures use `Files.createTempDirectory()`, seeded Random values, explicitly
recorded paths and try-with-resources cleanup. Files are created in reverse
filename order to avoid relying on creation order. Cleanup removes only named
owned files and their own directory, without recursive deletion. Existing
six- and nine-document collections are read, never rewritten by the tests.

## 14. Previously reported exploratory benchmark

The optional `--benchmark` branch in the same test harness runs synthetic
temporary collections. It is separate from the 26 normal correctness groups.
It does not treat timings as pass/fail assertions.

### Methodology in the actual harness

- Collection sizes: 500, 2,000 and 5,000, generated with seed 1705+size.
- Queries: `java` and `java search networking`.
- Full search compared with Top-K limits 1, 5, 10 and 100.
- Every generated document includes java, so M=N for both benchmark queries.
- Each method/query gets 20 warmup calls and nine measured calls.
- Measured method order rotates by round to reduce a fixed ordering advantage.
  Warmup calls use the fixed limits order.
- `System.nanoTime()` brackets each query call. Consuming result size/first
  score through a volatile sink happens afterwards, outside the timed interval.
- Index building and fixture construction are excluded from query timings.
- The harness sorts nine timing samples and reports the middle one: the median.
  Raw output is nanoseconds; the historical report below divides by 1,000 for
  microseconds. Sorting timing samples is unrelated to production result sorting.

Optional command, **not executed during this documentation task**; compile the
current sources and test harness with the configured JDK first:

```powershell
& 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\java.exe' -cp out\app TopKSearchTests --benchmark
```

### Complete available historical table

The following values are transcribed from Codex's prior implementation-task
output available in the conversation. They are not newly measured here or
independently verified timings. Each cell is **median microseconds**.

| N = M | Query | Full search | K=1 | K=5 | K=10 | K=100 |
| ---: | --- | ---: | ---: | ---: | ---: | ---: |
| 500 | java | 212.2 | 58.7 | 94.3 | 69.2 | 125.4 |
| 500 | java search networking | 187.0 | 188.4 | 212.1 | 235.3 | 212.8 |
| 2,000 | java | 2142.7 | 127.5 | 129.4 | 127.3 | 165.9 |
| 2,000 | java search networking | 2187.8 | 573.0 | 355.4 | 304.3 | 305.9 |
| 5,000 | java | 7695.4 | 473.4 | 189.0 | 162.4 | 206.9 |
| 5,000 | java search networking | 9599.6 | 368.4 | 303.3 | 313.4 | 326.8 |

The larger measured cases favor Top-K against this project's insertion-sort
baseline. But at N=500 for the multi-term query, full search is 187.0 microseconds
and Top 10 is 235.3: the optimized structure is slower in that measured case.
K=1 is not always faster than K=10 in these samples. Do not turn the complexity
bound into a claim of monotonic timings or universal speedups.

### Why these numbers have limits

JIT compilation can optimize code as it warms up. Twenty warmups may not fully
stabilize compilation across methods or corpus sizes in a single JVM. Garbage
collection, boxing/result allocation, CPU scheduling, system load and memory
cache state can affect a timed call. A median reduces sensitivity to outliers
but does not remove systematic bias. Nine samples and one exploratory run are
not a rigorous multi-fork benchmark with confidence intervals.

The synthetic corpus has many matches and bounded generated TF values; real
queries, sparsity, filename lengths and K choices may behave differently.
Six- and nine-document demonstrations are correctness fixtures, not evidence
of scalability. The measured comparison is with existing insertion sort, not
with a separately implemented efficient full sort. Theory explains growth;
these measurements describe only the tested workload and environment.

## 15. Verification evidence

Keep the stages separate; counts below are named test groups, not every assertion.

| Stage | Evidence and attribution |
| --- | --- |
| A. Implementation task | Codex previously reported Temurin JDK 25.0.3 compilation and **201/201 automated checks**: 86 Java, 95 HTTP and 20 browser-client JavaScript. The 86 Java consist of the previous 60 plus 26 Top-K groups. |
| B. Implementation publication | Codex freshly recompiled with JDK 25.0.3 and reran **86/86 Java checks**, including all 26 Top-K groups. The previous 95 HTTP and 20 client results were reviewed, not rerun during publication. |
| C. Performance | The bounded synthetic benchmark above ran during implementation. It was **not rerun during publication**. There are no independent performance measurements in this manual. |
| D. This manual | Documentation-only verification of source excerpts, test descriptions, trace mathematics, benchmark transcription, links, Markdown, whitespace and exact two-file scope. No compilation, application suite, benchmark, HTTP request or browser check is rerun for this task. |

The reported 95 HTTP checks were 16 original + 13 default phrase + 22 demo
phrase + 22 default Boolean + 22 demo Boolean. The 86 Java checks were
7 baseline + 13 positional + 18 phrase + 22 Boolean + 26 Top-K.

The implementation run initially encountered sandbox process-inspection
permissions and a failed Stop-Process cleanup attempt. The previously reported
successful retry verified the owned process, used .NET cleanup, completed the
HTTP runner and confirmed port 8080 free. Publication did not launch servers.
These historical tooling events are not algorithm failures or fresh checks here.

Earlier user-reported Edge verification concerns the existing three browser
modes, as recorded in prior manuals. No new manual Edge checks or browser
Top-K verification are claimed: Top-K has no HTTP/browser integration yet.
The benchmark table is available from prior conversation output, rather than
a committed independent measurement artifact; its provenance remains that report.

## 16. Genuine limitations and possible future optimizations

- Every visited posting contribution is scored. There is no WAND, block maximum
  index, score upper-bound pruning or early termination of candidate scoring.
- The accumulator still needs O(M) storage even for a tiny K. Boxed hash-map
  updates and one candidate SearchResult allocation per positive match can add
  overhead. Reducing allocation or changing accumulators would need new evidence
  and exact-score compatibility tests.
- The optimization is most attractive when K is small relative to M. If K>=M,
  all matches must be returned and heap extraction orders all of them.
- Efficient full sorting could provide another comparison baseline in a future
  study. It is not implemented or substituted into the existing search method.
- Metadata lookup assumes the current contiguous ID-to-list-index mapping.
  Persistent/arbitrary IDs would require an explicit mapping design.
- Ranking remains raw TF times smoothed IDF. It does not add length normalization,
  improve relevance judgments, or make ASCII tokenization support general Unicode.
- Index persistence, concurrent rebuild guarantees, Top-K HTTP/browser controls,
  fuzzy retrieval and unrelated ingestion/account features are not added here.

These are possible directions for later reviewed work, not changes made by
this documentation milestone.

## 17. Ten supervisor viva questions

1. **What makes this a real Top-K optimization?** It scores posting-driven
   candidates and selects with a bounded heap; it never calls full search or
   completely sorts all unretained matches.
2. **Why is the worst result at the root?** It is the easiest candidate to evict;
   comparing one root tells whether an arriving candidate can improve the best K.
3. **What does AND mean inside a Top-K query?** Nothing special: this is ranked
   keyword processing, so and is removed as a stop word. Boolean is a separate API.
4. **Why LinkedHashSet rather than an unordered set?** It deduplicates while
   preserving the per-document contribution order needed for exact score bits.
5. **What does sparse scoring save?** It stores only matched IDs and avoids an
   N-sized score array and full metadata scan; it still scores all visited postings.
6. **How are score ties resolved?** A later filename is worse in the heap, so
   earlier filenames survive the cutoff and appear first after extraction/reversal.
7. **Why is Integer.MAX_VALUE safe as K?** K is only an upper bound; the ArrayList
   grows as entries arrive rather than preallocating K slots.
8. **Why may Top-K be slower on small data?** HashMap, boxing, allocation and heap
   overhead can exceed the cost of insertion-sorting a tiny matching list.
9. **Does the heap make the whole query O(log K)?** No. It still processes P
   postings, considers M candidates and orders K' retained results.
10. **Why can the tests call search() when production must not?** Full search is
    the independent compatibility oracle in tests; production delegation would
    pay the old full-ranking cost and defeat the required optimization.

## 18. Heap-tracing viva exercise with solution

Assume K=3 and already-computed candidates arrive as:

```text
h.txt:5, b.txt:5, f.txt:7, a.txt:5, e.txt:6, c.txt:7, g.txt:4
```

This is an illustrative exercise, not corpus output. Before looking below:

1. Draw array indices and tree positions after each offer. Mark the worst root.
2. Explain why a:5 can replace h:5, although neither has a higher score.
3. For the final tree, give the children of index 0 and their parent formulas.
4. Extract until empty and reverse. Which three documents survive?
5. Explain why each repair follows only one root-to-leaf or leaf-to-root path.

Solution:

| Arrival | Heap array after repair | Explanation |
| --- | --- | --- |
| h:5 | [h:5] | Insert |
| b:5 | [h:5, b:5] | Later filename h stays as worse root |
| f:7 | [h:5, b:5, f:7] | Insert, no upward violation |
| a:5 | [b:5, a:5, f:7] | Replace h; b is later than a and sifts above it |
| e:6 | [a:5, e:6, f:7] | Replace b; lower-score a becomes root |
| c:7 | [e:6, c:7, f:7] | Replace a; e is the worse child and moves above c |
| g:4 | [e:6, c:7, f:7] | Cannot beat root e:6; discard |

Indices 1 and 2 are root 0's children; `(1-1)/2` and `(2-1)/2` both give 0
using integer division. Extract e:6, leaving [f:7, c:7]: f is the worse tied
filename. Then extract f:7, then c:7. Reverse to obtain
**c.txt:7, f.txt:7, e.txt:6**.

Only the inserted/replaced path can violate a previously valid heap relation;
the other branches remain valid. That is why sift-up/down use logarithmic
height rather than scanning or sorting the whole heap.

## 19. Final revision sheet

- **WHAT:** exact first K ranked keyword results, with original double scores.
- **WHY:** avoid the old full insertion sort when only a small best subset is needed.
- **HOW:** process -> ordered distinct terms -> frequency postings -> sparse scores
  -> bounded worst-first heap -> extract worst-to-best -> reverse.
- **Scoring:** raw TF * (ln((N+1)/(df+1))+1), in original query-term order.
- **Heap rule:** parent no better than children; lower score or later tied filename is worse.
- **Offer:** append/sift-up if space; otherwise replace/sift-down only for a better candidate.
- **Indices:** parent=(child-1)/2; left=2*parent+1; right=2*parent+2.
- **Bounds:** positive K; logical heap size <= K; no proportional MAX_VALUE allocation.
- **Expected time:** O(P+M log(K'+1)+K' log(K'+1)), plus query preprocessing/lookups.
- **Extra storage:** O(M+K'), excluding existing index and query terms; not O(K) overall.
- **Preservation:** existing keyword, phrase, Boolean and rebuild behavior unchanged.
- **Evidence:** prior 201/201; publication fresh 86/86 Java; prior exploratory benchmark;
  documentation-only checks now. Measured gains are workload-specific.
- **Scope:** Java core only. No Top-K HTTP/browser integration yet.

Return to the [learning index](README.md).
