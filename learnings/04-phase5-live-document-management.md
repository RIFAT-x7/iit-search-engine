# Phase 5: Live document management

**Learning Manual #16**

**Student:** Rifat Al Maruf (BSSE1705), IIT, University of Dhaka

**Course:** SPL-1, third semester

**Record:** November 23, 2026; documentation draft for review

**Integrated application:** `0532b2b951286c724cce6ecbaf602da2d5370510`

**Current closure addendum, November 24:** Phase 5 implementation is complete;
consolidated documentation is integrated at `52c6778`. Final closure documentation
is in Stage 1 review; see [evidence and limits](#k-evidence-and-limits).

This AI-assisted guide explains implemented Phases 5A and 5B. Read it beside
[SearchEngine](../src/SearchEngine.java), [DocumentStore](../src/DocumentStore.java),
[SearchServer](../src/SearchServer.java), [AdminDocumentHandler](../src/AdminDocumentHandler.java)
and [admin.js](../web/admin.js). Earlier manuals describe their dated checkpoints;
they are not rewritten to imply that live administration existed at midterm.
Use the [runbook](../docs/PHASE_5B_DEMO_RUNBOOK_2026-11.md) for commands and rehearsal.

## A. The problem and the retained IR core

Originally the web server indexed its selected folder before listening. A person
could search, read and download documents but could not upload or change them
through the running application. Editing a disk file did not update its indexed
terms or the original bytes served to the browser.

Now `SearchServer --managed` opens a separate persistent collection. An authorized
administrator can add, replace and delete text files through the Document Manager.
A successful response means the updated index has already been published. The
same Java server keeps running, and a subsequent search uses the new collection.
An existing search result page must still be searched/refreshed again.

The academic algorithms are retained: manual ASCII tokenization, frequency and
positional postings, TF-IDF-style keyword scores, stable filename ties, bounded-heap
Top-K, positional phrase alignment and Boolean AND-before-OR. Management changes
the collection lifecycle, not the meaning of a query. No external IR library,
database, framework or new ranking algorithm was introduced.

## B. Why one EngineState matters

Publishing separate `documents`, `index` and `indexedDocuments` fields can mix
generations. Suppose a reader sees new postings with document ID 2 while its
metadata still contains only one old document. It could return the wrong filename
or fail an ID lookup. Safe individual fields would not make their combination safe.

`SearchEngine` instead owns a private static `EngineState` containing the ordered
documents, inverted index and filename-to-original-byte map. Statistics come from
that same generation. The simplified idea is:

```java
// Conceptual excerpt; see SearchEngine.java for the complete builder.
private volatile EngineState state;
// Build newDocuments, newIndex and newIndexedDocuments privately first.
state = new EngineState(newDocuments, newIndex, newIndexedDocuments);
```

There is one publication assignment after all reading, decoding and indexing
succeed. `volatile` provides visibility of the fully constructed generation to
other threads. The builder exclusively owns the new structures; once published,
that generation is never mutated. This ownership rule is essential: `volatile`
alone would not make later changes to an ordinary map safe.

Every public retrieval/statistics operation captures `EngineState snapshot = state`
and uses that captured generation throughout its work. Phrase/Boolean helpers use
the captured structures too. Original-byte access returns a defensive clone;
positional getters do not expose mutable internals.

```text
Time ->       build candidate B ................ publish B
Reader 1:     capture A ---- complete using A
Reader 2:                                             capture B ---- use B
Failure:      discard candidate; A remains published
```

Two separate calls can see different generations. A search followed by a later
document-view request is not one combined transaction; replacement/deletion may
happen between them. The promise is coherence within each operation.

## C. Why writers are serialized but readers are not

`buildIndex(String folderPath)` is synchronized. Without writer serialization,
an earlier slow scan could publish after a later scan and overwrite newer state.
Only rebuild writers take this monitor; retrieval methods do not wait for the
filesystem scan. A missing folder, invalid UTF-8 or failed read leaves the previous
usable generation published.

`DocumentStore` serializes its lifecycle operations and also holds the engine's
writer monitor around disk change/rebuild/rollback. The lock order is store then
engine. Retrieval takes neither monitor. This prevents overlapping store mutations
and prevents a competing direct rebuild during temporary disk changes. It does
not authorize arbitrary callers to rebuild the owned engine.

Full builds sort filenames and assign contiguous IDs starting at 1 again. Top-K
and other retrieval metadata lookups still depend on that arrangement. A document
ID is not a persistent identity; management uses the exact managed filename.

## D. DocumentStore: persistent files with one owner

`DocumentStore(Path)` creates/opens one explicitly configured directory; its parent
must already exist. The HTTP server always selects `data/managed/`. It never lets
an HTTP caller choose another root or change demonstration collections.

The small API is `add`, `replace`, `delete`, `reload`, `listDocumentNames`,
`getMaxDocumentBytes`, `getSearchEngine` and `close`. Add/replace accept byte arrays
or caller-owned streams. The store consumes at most limit+1 stream bytes to detect
overflow and does not close a caller's stream. The HTTP handler owns/closes its
request stream. Listing returns a sorted, read-only published inventory.

The lifetime file lock is inside private `.iit-store/lock`. It prevents two
cooperating stores from opening the same collection. `close()` releases the
channel/lock; constructor failures close acquired resources. The lock is not a
defence against arbitrary programs editing the directory. Keep it private to the
running store. `getSearchEngine()` is intended for retrieval, but its public type
also exposes `buildIndex`; integration code must preserve that ownership rule.

### Validation before changing persistent documents

- Names must be safe `.txt` filenames, not paths: no separators, traversal syntax,
  drive/alternate-stream colon, forbidden Windows characters, controls, reserved
  device names or unsafe outer whitespace/trailing characters. Length is bounded.
- Safe Unicode filenames are accepted. NFC-normalized case-insensitive collision
  checks prevent ambiguous duplicates. This does not add Bangla tokenization.
- Text must decode strictly as UTF-8 and contain no NUL. Original bytes, including
  accepted BOM/line endings, are preserved rather than normalized or re-encoded.
- The default limit is **1 MiB = 1,048,576 bytes inclusive**, configurable through
  the Java store constructor. Empty text is allowed; an empty file has no terms.
- The root, ancestors, entries and bookkeeping files undergo directory/regular-file
  and identity checks, including `NOFOLLOW_LINKS` protections. Unsafe entries and
  unexpected private files fail closed. These checks are not a complete adversarial
  filesystem guarantee; Windows symlink fixtures were skipped for privilege reasons.

Before mutation, a scan compares current names/bytes with the published inventory.
Detected external changes cause rejection and require explicit reconciliation.
This is detection at the scan/check boundaries, not a filesystem watcher or an
isolation guarantee against simultaneous non-cooperating writers.

## E. Add, replace and delete: stage, move, rebuild

All three operations follow a simple protocol:

```text
Validate input and collection -> prepare expected names and recovery bytes
  -> stage/force files -> check expected target bytes -> atomic filesystem move
  -> full SearchEngine.buildIndex -> one generation publication -> inventory update
```

| Operation | Persistent change before rebuilding | Search result after success |
| --- | --- | --- |
| Add `notice.txt` | Stage new bytes in private `next`; move to a new target | New terms/positions/bytes available; existing documents retained |
| Replace `notice.txt` | Stage new bytes and old-byte `previous` backup; move new target into place | Old terms vanish, new terms appear; filename stays `notice.txt` |
| Delete `notice.txt` | Move target into private `previous` | No results, postings or indexed bytes for that document |

`stage` forces the written file through its channel. Moves require filesystem
`ATOMIC_MOVE`; there is no unsafe copy/delete fallback. Duplicate additions and
missing replace/delete targets are rejected. Original indexed bytes are retained
as the expected old content for checks and recovery.

If the move/rebuild fails normally, the old engine generation remains usable.
The store checks whether the original is already intact, otherwise restores it
only when the observed bytes match the expected operation state. It does not
blindly overwrite unexpected content. It then checks the collection against the
old published state and reports the failure.

If rollback itself fails, the exception reports that failure and the store marks
recovery required. Further mutations and inventory listing are blocked; old engine
retrieval remains possible. Repair and a successful `reload()` are necessary before
normal store use. The HTTP layer exposes a generic service error, not private paths.

## F. Restart policy and the limit of atomic moves

An atomic rename and a volatile assignment belong to different systems. They are
not a single crash-atomic transaction. Power loss or process termination can occur
after the disk change but before publication or response delivery. Even forced file
data is not a proof of full power-loss durability for directory updates.

On reopening, valid visible `.txt` files are the source of truth and a complete
index is rebuilt. An interrupted, unacknowledged change may therefore survive.
Private `next`/`previous` leftovers are checked but never automatically replayed.
They remain until a subsequent permitted mutation clears/reuses them. They are not
a version history, recycle bin or guaranteed backup. Do not delete or replay them
as a troubleshooting shortcut; preserve evidence before manual repair.

`reload()` validates the whole collection and rebuilds, clearing recovery-required
state only after success. Failed reload keeps the old published engine state. It
is a backend API, not an HTTP route or browser button. Refresh inventory only asks
for the current published list. Intentional offline changes need controlled
reopening or backend reconciliation, with no concurrent filesystem editors.

## G. HTTP administration and lifecycle

`SearchServer --managed` validates `IIT_SEARCH_ADMIN_TOKEN` before opening one store,
then uses that store's engine for all public searches and document reads. Default,
phrase-demo and showcase modes do not open the store or expose admin routes/assets.

| Exact endpoint | Meaning |
| --- | --- |
| `GET /admin/documents` | Sorted filename inventory; no query or body |
| `POST /admin/documents?name=...` | Create; raw UTF-8 text body; 201 after publication |
| `PUT /admin/documents?name=...` | Replace existing target; raw body; 200 after publication |
| `DELETE /admin/documents?name=...` | Delete existing target; no body; 200 after publication |

Success JSON contains `status` and `filename` for mutations; listing has a
`documents` array. Queries decode strictly, rejecting malformed UTF-8/percent
escapes, duplicate or unexpected parameters. Media type/encoding and declared
length are checked; an actual stream bound also covers chunked bodies. Storage
validation is delegated to the store, rather than reimplemented in the handler.

The token must match `[A-Za-z0-9_-]{32,256}` and should be randomly generated.
It travels only in `Authorization: Bearer ...`. The handler compares fixed-length
SHA-256 digests using `MessageDigest.isEqual`, rejects duplicate/malformed headers,
requires a loopback peer and exact Host `127.0.0.1:8080`, and checks an Origin header
against `http://127.0.0.1:8080`. No-Origin command-line clients still need the token.
There is no CORS permission, account database or cookie authentication.

These controls suit a local academic prototype. Plain HTTP does not provide TLS
confidentiality, and possession of the token grants all supported administration.
Public search and original documents remain unauthenticated; do not ingest private
documents assuming that the admin lock makes reading private.

Four workers with a bounded queue of 64 let an independent search overlap a
rebuild. Store writes remain serialized. This is not unlimited availability: busy
workers, queue exhaustion or time limits may prevent/delay a response. The browser
must account for that. Normal shutdown stops the server/executor and closes the
store; startup failures clean up acquired resources. Only owned verification
processes may be stopped by tests.

## H. Browser requests, token lifetime and safe rendering

The separate [admin page](../web/admin.html) is available only in managed mode.
It contains no credentials. Unlock first verifies the pasted password-field token
using authenticated inventory GET. Controls appear only after a valid response.
The field is cleared; the token stays in the script closure, with no persistent
storage, cookie, URL or logging. Lock/page departure clears the application reference
and invalidates requests. This is not JavaScript memory zeroization or server-side
token revocation; another holder can still use the configured bearer token.

Uploads send the selected `File` itself as the fetch body with
`text/plain; charset=utf-8`. No `.text()` conversion changes original bytes.
`URLSearchParams` encodes the filename. Client size/extension checks provide quick
feedback; server validation remains authoritative. Replace sends the existing
managed filename even if the replacement local file is named `revision.txt`.
Replace/delete require explicit native confirmation.

Untrusted filenames/errors render with `textContent` and safe DOM elements, not
HTML interpolation. Dedicated assets have no-store/nosniff headers and a restrictive
CSP. Requests are relative same-origin URLs, omit cookies and reject redirects.

## I. Async state and honest uncertain outcomes

The browser permits one operation at a time. A generation counter and abort signal
protect against stale async responses: lock or a newer session invalidates the old
generation, and late success/error/cleanup cannot restore its UI state. The 45-second
browser timeout aborts its request; abort is never proof of server cancellation.

A valid success response must match the expected status and filename. After it,
the UI refreshes inventory. If that refresh fails, it retains the confirmed mutation
outcome and asks for another refresh before further writes.

Network loss, timeouts, overload/storage errors or malformed success responses can
leave completion unknown. The browser does not automatically retry. It blocks more
writes, asks for inventory refresh and content inspection, then requires the human
acknowledgment **I've checked the collection**. Refresh alone does not remove an
uncertain replacement warning: the same filename might hold old or new content.
Use View text and Search to inspect it. A new page load loses this in-memory warning;
the administrator must still remember and reconcile an interrupted operation.

The handler sends the response after mutation handling, so response-transmission
failure cannot roll back an already published change. A server success is not a
guarantee that the browser received it.

## J. Full rebuilding versus future incremental indexing

Current mutation cost includes validating/scanning the collection and rebuilding
all postings. It is straightforward to test and preserves the established IR core,
but costs time proportional to collection content and temporarily retains old and
candidate generations in memory.

Genuine incremental indexing would remove the old document's frequency/positional
postings, correct term/token/document counts, insert replacements and preserve
stable identity with sparse-safe metadata lookup. It must still publish coherent
state. That work is deferred; calling current full rebuilding "incremental postings"
would hide the missing removal and ID-management work.

## K. Evidence and limits

The previously executed full checkpoint has **790 passes, 0 failures, 2 skips**:
177 Java, 390 existing HTTP, 38 administrative HTTP, 4 actual startup checks and
181 Node browser/highlight/admin model groups. Skips are Windows symlink fixture
creation privileges. Later unchanged-code integration repeated **613** of those
checks, with no failures/skips. Do not add reruns to the full count.

[RuntimeRebuildTests](../tests/RuntimeRebuildTests.java) coordinates concurrent
readers/writers and publication during individual queries. [ManagedDocumentTests](../tests/ManagedDocumentTests.java)
covers validation, lifecycle, rollback/recovery, reopen and concurrent mutation.
[AdminHttpTests](../tests/AdminHttpTests.java), run through its
[owned runner](../tests/RunAdminHttpTests.ps1), exercises real HTTP and overlapping
search during a paused rebuild. [AdminBrowserTests](../tests/AdminBrowserTests.js)
uses a DOM/fetch model for token, safe-rendering, stale-response and uncertainty flows.
Reflection used to force internal failure/interleavings is test-only.

Prior Codex-controlled real **headless Edge** acceptance exercised the lifecycle
and public Search on one server, lock/reload, basic focus and responsive widths.
It is stronger evidence than a model for those flows, but did not validate headed
Edge's native Windows picker. The later automated acceptance session recorded a
fresh **790/0/2** full run and separately verified persistence of an existing
document across a controlled restart with a new token, exact bytes and search.
These recorded runs are not added together and were not rerun for Stage 1 closure.

The student separately reports successful administrator authentication, native
Windows file selection, upload/read/search of `spl_test.txt` using `amberquartz`,
replacement removing that old match and exposing `cobaltviolet`, then deletion.
Those are student-reported manual Edge outcomes; no wider headed checklist is inferred.
The student clarifies that the document was deleted and not re-uploaded before
the manual server restart (`35532` to `21064`, same collection, fresh token, health
`OK`). Existing-document persistence was therefore not personally repeated.
The student accepts the automated persistence evidence and waives that optional
manual repetition. An absent document remaining absent does not prove persistence.

At Stage 1 closure, ownership-verified PID `21064` stopped normally and port 8080
was verified free; the isolated collection and evidence were preserved. The
[central closure record](../README.md#phase-5-final-closure) holds the approved
checkpoints and cleanup details. Comprehensive accessibility, production security,
crash/power-loss durability and large-collection performance remain unverified.
This documentation-only closure establishes no new application or browser-test results.

## L. Viva questions and self-practice

1. Why is one volatile holder better than three separately assigned fields?
2. Why does volatile visibility still require no mutation after publication?
3. Why can a reader finish using an old generation after a new one is published?
4. Why are rebuild writers serialized while searches are not synchronized?
5. Why is the managed filename an identity, while the numeric ID is not stable?
6. What happens if rebuild succeeds but the HTTP response is lost?
7. Why is inventory refresh insufficient to prove a replacement's contents?
8. Why does an atomic file move not create a crash-atomic disk/index transaction?
9. What would posting-level deletion require beyond the current full rebuild?
10. Why does admin authentication not make public document reads private?

Trace one fictional add/replace/delete from the browser to publication. Explain
the normal rollback path and the distinct rollback-failure path in your own words.
Rehearse the runbook without restarting the Java server between mutations. Record
actual manual outcomes separately; this guide does not invent student practice.

Return to the [learning index](README.md) or [project README](../README.md).
