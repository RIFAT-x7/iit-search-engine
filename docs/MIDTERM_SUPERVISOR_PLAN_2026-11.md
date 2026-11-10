# IIT Search Engine: Supervisor Feedback and Midterm Plan

**Student:** Rifat Al Maruf, BSSE1705

**Institute/course:** IIT, University of Dhaka; SPL-1, third semester

**Planning record:** November 2026; no precise meeting date is asserted.

**Midterm:** November 12, 2026

**Repository:** RIFAT-x7/iit-search-engine

This record began as the November supervisor replanning checkpoint and now
tracks implementation and acceptance. The original rationale and requirements
are retained below. Phases 3.5A-3.5C and the reviewed 3.5D Part 1 documentation
are integrated. Presentation/rehearsal/viva preparation remains open.
Retrieval and UI features are frozen for midterm.
The student supervises, ChatGPT plans and audits, and Codex implements and reports
within the authorized milestone. Independent student
review is required before a subsequent implementation instruction.

## CURRENT checkpoint: November 10, 2026

The final audit started from verified clean main/origin main at
`143ab553095b95b572ae55016de3850180054fef` (Premium Midnight Scholar and adaptive
Advanced Search). The approved showcase application checkpoint is now
`28a26ab986351b5d99d29aba26e873e30793c931`, independently source-reviewed on
GitHub and fast-forward integrated into local main. Part 1 documentation
integrated at `cdc3b504f231d9fc961648970318f103c6543b59`. Original regression
rerun before and after the optional corpus: **562 passed, 0 failed, 1 skipped**:
100 Java, 320 HTTP, 107 browser-model, 35 highlighting/reader. The Windows
symlink fixture remains unexecuted because creation privileges are unavailable.

The student explicitly authorized a final audit and isolated optional showcase:
25 original fictional documents selected only by `SearchServer --showcase`.
No changes to algorithms, UI, raw-document contracts or the original collections.
Dedicated checks add 18 Java + 70 HTTP passes (650 overall, zero failures,
one skip). On November 10, 2026, the student confirmed all six showcase Edge
acceptance scenarios; this is student-reported manual verification, not Codex
browser automation. All three fixed collections are available; the showcase
remains optional and the original six/nine-file collections are unchanged.
See the [audit](FINAL_REPOSITORY_AUDIT_2026-11.md).
This later bounded instruction is not a claimed supervisor approval or a new
phase of retrieval work. Earlier dated evidence and planning rationale remain below.

Existing functional, Premium UI and showcase Edge acceptance is **student-reported**.
It does not establish a comprehensive accessibility/device matrix. The application
is frozen. Slides, speech, timed rehearsal and viva practice are pending.

## A. Supervisor feedback

### Reported by the student

According to the student's account of the supervisor meeting:

- The current search engine is sufficiently developed algorithmically for midterm.
- Improve practical usability: make Boolean retrieval a user-facing Advanced
  Search interface inspired by IEEE Xplore, with multiple keyword inputs and
  AND/OR combinations.
- Let users open text documents in the browser and download them. Show relevant
  contextual snippets and highlight matching keywords in results and the viewer.
- Hide numerical TF-IDF-style scores from browser users while retaining ranking.
- Keep the current text-file scope and localhost operation until midterm.
  Date filters, PDF, images and Bangla text/PDF processing belong after midterm.
- Fuzzy, semantic and other new retrieval algorithms are not midterm priorities.
- A usable system for the supervisor and IIT is a longer-term goal, subject to
  subsequent design and permission decisions.

These are paraphrases of the student's report, not independently verified
meeting quotations or blanket supervisor approvals.

### Implemented capabilities verified by source inspection

At the original planning checkpoint, main was
`bfb28f676a73443a9cc383cb9ccad52b415b94c8`; Phase 3.4B integrated at `39f8353`.
At the November 9 stabilization start, clean local and fetched remote main were
`ab6eacc43b9b8424f332304f18729255ef45d3a0`. Phases through 3.5C are integrated.
The source table below describes that current application.

| Source | Current behavior |
| --- | --- |
| [TextProcessor.java](../src/TextProcessor.java) | Manual lowercase ASCII letter/digit tokens; punctuation separates words; keyword processing removes twelve stop words. Complete tokens retain stop words for positions. |
| [InvertedIndex.java](../src/InvertedIndex.java) | In-memory frequency postings (term to document-ID/count mappings) and positional postings. |
| [SearchEngine.java](../src/SearchEngine.java) | TF-IDF-style ranked keyword retrieval, exact normalized phrase retrieval and Boolean AND/OR with AND precedence. Optimized Top-K uses sparse scoring and a bounded heap, preserving the full ranking prefix. Original-byte snapshots use exact filename lookup and defensive copies, replaced only after successful builds. |
| [Document.java](../src/Document.java) | A document currently holds an ID and filename, not original text or a stored path. IDs are assigned in sorted filename order during indexing. |
| [SearchServer.java](../src/SearchServer.java) | Built-in JDK HttpServer binds to 127.0.0.1:8080; fixed search/reader assets, health/search, indexed raw view and original download routes. Request names do not resolve filesystem paths. Keyword JSON retains numeric scores; phrase/Boolean JSON has filenames without scores. |
| [index.html](../web/index.html) and [app.js](../web/app.js) | Keyword, phrase and Boolean modes, keyword Top-K choices, loading/error feedback, cancellation/request-ID protection and safe text rendering. Advanced Search groups compose existing Boolean queries. Scores are hidden in the browser; filenames link to a reader, with downloads and source previews. Shared highlight.js verifies source tokens for both pages. |

The separate CLI retains keyword search and manual rebuilds. Default startup
indexes six immediate text files in `data/`; `--phrase-demo` instead indexes nine
files in `data/phrase_samples/`. Both collections are fictional. Fourteen
historical [learning manuals](../learnings/README.md) are retained, plus one new
[consolidated Phase 3.5 manual](../learnings/03-08-phase-35-midterm-features.md)
reviewed and integrated with Part 1 at `cdc3b50`. See the [README](../README.md) for
architecture, build/run commands, limitations and historical checkpoints.

The previous Codex-reported integration evidence is **287/287 checks: 86 Java,
159 HTTP and 42 JavaScript client-model checks**. These are historical results,
not the current stabilization run. Historical November 9 evidence is **547 passed,
0 failed, 1 skipped**: 100 Java, 320 HTTP, 94 browser-model and 33 presentation/
reader passes, with one skipped Windows symlink fixture. Temurin 25.0.3 compilation,
five JavaScript checks and seven PowerShell parses passed. Both HTTP collections
passed ownership verification and cleanup; port 8080 was free afterward.

The student explicitly reports successful **manual Microsoft Edge acceptance**
of Basic Keyword Search/Top-K, exact phrases, grouped Advanced Search, opening
and downloading, contextual snippets, keyword/phrase/Boolean highlighting,
highlighted reader and raw text on both collections. This was not independently
performed by Codex. It does not establish keyboard-only, screen-reader, responsive/
zoom, slow-network or device-matrix coverage. Such broader checks remain
unverified, rather than being invented as completed scenarios.
The symlink fixture could not create a link because Windows denied the required
privilege; source rejection/NOFOLLOW_LINKS checks are not a substitute for that
execution. No claim of production-grade filesystem security is made.

### Planned work and proposals

Phases 3.5R, 3.5A, 3.5B and 3.5C are integrated. Phase 3.5D is active
stabilization, not complete until accepted. The sections below retain the
requirements and now record the chosen implementation and evidence. Phases 4-7
remain post-midterm proposals requiring further agreement. No new feature,
algorithm or UI redesign is authorized for the frozen midterm application.

## B. Why we are changing priorities

Algorithm development is paused because the existing classical IR engine covers
the current midterm algorithm scope according to the reported feedback. Users
now need clearer results and a way to read the documents they find. These changes
make the implemented retrieval useful in a demonstration without altering its
meaning or numerical ranking.

The schedule must leave time to understand and explain the code. Adding fuzzy
or semantic retrieval would introduce matching, evaluation and explanation work
without answering the supervisor's immediate usability requests. Correctness,
scope control and preparation take priority over feature quantity.

## C. Phase 3.5R: Documentation and planning checkpoint

**Status: completed and integrated at `13ea98fac32a9e6bd9220f78a18979b3dc93e1c9`.**

The original checkpoint formalized scope in this plan, README and roadmap only.
Its two commits were `9543bc0` (reported feedback and revised scope) and
`13ea98f` (grouped Advanced Search semantics). The original acceptance required
source/link/Git/whitespace audits, correctly attributed feedback, retained
historical records and no application implementation in that checkpoint.
Those historical three-file workflow instructions did not authorize publication
of the six-file Phase 3.5D draft. Later separate publication and integration
instructions completed its review/main integration at `cdc3b50`.

## D. Phase 3.5A: Advanced Search and result presentation

**Status: completed and integrated at `35482faceaeac37be8ed87361a307b20ef628d9f`.**

**Basic Keyword Search** retains the existing ranked keyword behavior. For
`computer java python`, a document containing any searchable query term may
appear: candidate retrieval is OR-like, not strict Boolean AND. TF-IDF-style
scores rank the matches; more matching terms or stronger term contributions may
rank a document higher, without guaranteeing that term count alone determines
order. This is not identical to explicit Boolean OR: Boolean results have no
relevance ranking and remain in filename order.

The browser's Boolean mode is now **Advanced Search**, distinct from Basic
Keyword Search. Its simple IEEE Xplore-inspired grouped builder implements:

- Each keyword input supplies one searchable Boolean operand.
- Within each group, words are linked by AND: every condition in that group
  must match the same document, without requiring adjacent words.
- Between complete groups, use OR: matching any complete group is sufficient.
- Provide **Add AND Word**, **Add OR Group**, and remove-word/group controls.
- Show a readable expression preview and validate groups before submission.

This is a query-composition layer around the existing Boolean retrieval method,
not a new algorithm. Users build complete AND groups explicitly; for a shared
condition, repeat that word in each applicable group. For example:

| Group | AND-linked words |
| --- | --- |
| 1 | computer AND java |
| 2 | computer AND python |

The intended logical condition is `computer AND (java OR python)`. Submit the
supported expansion `computer AND java OR computer AND python` with
`mode=boolean`. Do not generate `computer AND java OR python`: under existing
precedence that could match a document containing only python.

| Searchable words present in the document | Expected grouped match |
| --- | --- |
| computer + java | MATCH |
| computer + python | MATCH |
| computer + java + python | MATCH |
| computer only | NO MATCH |
| java only | NO MATCH |
| python only | NO MATCH |
| java + python without computer | NO MATCH |

The evaluator remains unchanged: AND takes precedence over OR. `X AND Y OR Z`
means `(X AND Y) OR Z`. If the intended condition is `X AND (Y OR Z)`, the builder
must represent groups X AND Y and X AND Z and generate `X AND Y OR X AND Z`.
Parentheses here explain logical meaning only; the parser does not accept them.
The builder joins words inside each group with AND, then complete groups with OR.

Retain existing backend `mode=boolean` compatibility. Respect single-word
operand, stop-word and syntax restrictions; do not silently drop an invalid
condition or reinterpret multiple words as one operand. Do not promise parser
parentheses, arbitrary nested queries, NOT, wildcards, multiword Boolean operands,
metadata-field search, date filters or a new Advanced Search ranking algorithm.

Hide numerical keyword scores only in browser presentation. Preserve Basic
Keyword Search's internal TF-IDF-style scoring, ranking/ties, numeric HTTP score
contract, keyword-only limits and exact Top-K behavior. The browser must retain
server order. The integrated browser hides numerical scores; the API still returns them.

Retained acceptance criteria and test expectations (implementation status above):

- Add/remove words and groups with keyboard-accessible labeled controls and
  predictable focus. Preserve valid group structure and define minimum word/group
  counts. Reject empty/malformed groups or clearly handle them without silently
  changing the intended logic; validate before submission and allow correction.
- Automated query-expansion tests must verify the exact generated expressions
  above and preview/submission agreement. Automated retrieval tests must cover all
  seven matching-table cases, including rejection of python-only and java+python
  without computer. Test AND-only, OR-only, multiple AND groups, case handling,
  unknown terms, stop-word operands and unsupported syntax. Keep the Boolean
  evaluator unchanged and existing HTTP `mode=boolean` requests compatible.
- Ranked keyword retrieval, exact phrase and Top-K functionality remain unchanged;
  Boolean requests send no `k`.
  Test mode/limit transitions, stale responses, loading, empty and error states.
- Browser results contain no numerical keyword scores, but retain the same order
  and Top-K selection. HTTP tests still verify numeric scores and legacy no-K JSON.
- Client-model expectations were updated for the Advanced Search label and hidden
  scores, retaining behavioral safety coverage. Fresh core/HTTP/JavaScript checks
  passed; functional Edge acceptance is separately student-reported.
- Preserve safe DOM rendering, responsive readability and accessible status/focus
  feedback. Define and test the native GET fallback for the revised interface.

## E. Phase 3.5B: Text document viewing and downloading

**Status: completed and integrated at `e1f433cdfec11b9dfc70d0ca9a9eb156186f3f97`.**

Chosen design: SearchEngine retains strictly decoded original UTF-8 bytes in a
private exact-filename map alongside the successful index. The accessor returns
defensive copies; a failed rebuild preserves prior search structures and bytes.
`GET /document/view?name=...` returns original bytes with text/plain UTF-8 and
nosniff. `GET /document/download?name=...` returns those same bytes with a safe
ASCII/UTF-8 attachment filename. Invalid syntax is 400, valid unindexed names
404, and unsupported methods 405 with Allow: GET. Exactly one nonempty name and
no extra parameters are accepted; decoding is strict and performed once.
Browser requests never open a user-derived path. Collection isolation remains.
The Phase 3.5C primary link opens the separate reader; raw routes are unchanged.

Link each result to its corresponding indexed text document. Provide browser
viewing, preferably in a new tab, and a download action for the original `.txt`
file. Only files belonging to the currently indexed and allowed fictional
collection may be served. The default collection must not expose the separate
demo collection, or vice versa.

Use document IDs or a validated mapping to indexed records, never unrestricted
browser-supplied filesystem paths. Current IDs are collection/startup-dependent;
review their mapping and link lifetime rather than claiming stable global IDs.
Document still stores only ID/filename; SearchEngine owns the original-byte
snapshot. Files changed or removed after startup continue serving their indexed
bytes until a successful rebuild/restart. This is intentional for the small
fictional collection, not persistent storage or concurrent rebuild support.

Choose correct response content types (UTF-8 text for raw text; HTML for a safe
viewer when used), safe Content-Disposition values/filenames for downloads and
escaped or safe text rendering. Preserve the original download bytes rather than
returning highlighted markup. Keep loopback binding and fictional data only.
These prototype routes are not a production authorization system.

Acceptance criteria and HTTP/browser test expectations:

- Open and download results from each collection independently; downloaded bytes
  match the selected original `.txt`, and viewer text preserves original content.
- Unknown, malformed and wrong-collection identifiers, traversal attempts including
  encoded variants, absolute paths and unindexed files never expose filesystem
  content. Avoid filesystem-path leakage in errors.
- Test supported methods, content types, disposition/header safety and the fixed
  response contracts described above; preserve existing search behavior.
- Test missing/unreadable files and special characters safely. Verify existing
  routes, search JSON, ranking and both collection startup choices remain correct.
- In Edge, check result-link identity, new-tab behavior, keyboard link access,
  readable text, download filename and original content. Record real observations.

## F. Phase 3.5C: Search snippets and highlighting

**Status: completed and integrated at `ab6eacc43b9b8424f332304f18729255ef45d3a0`.**

The implemented shared module is [highlight.js](../web/highlight.js), used by
[app.js](../web/app.js) and [reader.js](../web/reader.js). Both fetch original
text through the unchanged raw endpoint. Keyword highlights use complete tokens
excluding stop words; phrase highlights are individual tokens belonging to a
verified complete normalized phrase; Boolean highlights use only operands from
satisfied AND groups, evaluated across the whole document. Operators are not
highlighted as terms. Invalid/unverifiable highlighting queries render plain text.

Snippets use an original slice around the first verified match, targeting 200
UTF-16 code units and avoiding token/surrogate cuts. Ellipses indicate omitted
source. Very long matched tokens can exceed the target; length-changing Unicode
case conversion omits highlights conservatively. Missing/failed previews show a
neutral fallback without removing results. Shared AbortController/request-ID
checks prevent obsolete responses from rendering. The reader is a fixed static
page with safe DOM rendering, raw-view and original-download actions.

Show a short contextual excerpt around a trustworthy match for each result when
one can be identified. Use original document text, preserving case and punctuation;
do not fabricate summaries or reconstruct source sentences from normalized tokens.
Highlight relevant matches in the snippet and browser document viewer. Highlighting
is a presentation aid; it does not retrieve documents or change relevance scores.

Prefer case-insensitive matching at the ASCII letter/digit token boundaries used
by the current search. A generic regex word boundary may differ from that rule,
so verify punctuation, digits and neighboring characters explicitly. Bound snippet
length and context during implementation, distinguish truncation with ellipses,
and keep excerpts readable. If no trustworthy excerpt exists, show a clear neutral
fallback or omit it; do not invent matching text.

Phrase retrieval uses normalized consecutive positions, including stop words;
Boolean retrieval uses operands and document sets, not source offsets. The
implemented rules above were derived from source and tested with explicit examples.
Do not promise a contiguous raw phrase when punctuation differs, highlight
AND/OR as operators, or imply every OR operand occurs in every result. Highlighting
individual tokens of verified phrase occurrences is now explicitly decided and tested.
Indexing, TF-IDF, phrase and Boolean retrieval logic remain unchanged.

Acceptance criteria, limitations and test expectations:

- Snippet text is an actual bounded excerpt containing a verified match when
  available, with useful surrounding context. Test beginning/end of document,
  multiple matches, repeated terms and long documents.
- Test case variants, exact word boundaries, punctuation, digits, stop words,
  empty matches and unavailable text. Never create empty highlights or loops.
- Test phrase/Boolean examples against actual retrieval semantics; a highlight
  must not falsely claim a phrase or satisfied AND group in one local passage.
- Treat query text, filenames and document text as untrusted display content.
  Use safe text nodes/DOM elements or correct escaping, never unchecked HTML
  injection. Test `<`, `>`, `&`, quotes and script-like strings in snippet/viewer.
- Confirm viewer and snippet highlighting agree with the documented policy;
  downloads remain original text. Run relevant Java/HTTP/client checks and Edge
  checks. ASCII-based highlighting is not a claim of Bangla/Unicode support.

## G. Phase 3.5D: Midterm stabilization

**Status: Part 1 documentation reviewed and integrated; final preparation remains active.**

Retrieval/UI features are frozen. The integrated six-file documentation includes
current README/roadmap/plan/index, one consolidated manual and the
[demo runbook](MIDTERM_DEMO_RUNBOOK_2026-11.md). Part 1 initially prepared a draft;
later explicit review/publication/integration completed it at `cdc3b50`.
That does not complete presentation readiness. Slides/speech are planned
November 10; rehearsal, code
comprehension and student viva practice November 11. Phase 3.5D remains open
until accepted; automated success is not presentation readiness.

- Compile with Temurin JDK 25.0.3 and run the relevant complete regression suites:
  [Baseline](../tests/BaselineTests.java), [positional](../tests/PositionalIndexTests.java),
  [phrase](../tests/PhraseSearchTests.java), [Boolean](../tests/BooleanSearchTests.java),
  [Top-K](../tests/TopKSearchTests.java) and
  [indexed-document](../tests/IndexedDocumentTests.java) Java tests;
  [HTTP runner](../tests/RunHttpIntegrationTests.ps1) and its original/phrase/Boolean/
  Top-K/document/reader suites; [client model](../tests/BrowserClientTests.js) and
  [highlighting/reader model](../tests/HighlightPresentationTests.js), with syntax checks.
  Existing suites were run unchanged; do not treat the old 287 count
  as proof of new features. Record actual fresh totals and failures.
- Test both the default six-document and nine-document phrase-demo collections.
  Preserve all prior algorithms, ranking, JSON score compatibility and Top-K.
- Functional Edge acceptance for both collections is student-reported above.
  Errors, complete keyboard-only, narrow layouts, screen-reader and zoom/reflow
  are not inferred from that functional confirmation. The models alone cannot
  prove those broader manual observations.
- Review allowed-file mappings, path/header safety, rendering and error handling.
  Use the owned-server runner; never stop an unidentified port-8080 listener.
- Finish relevant learning documentation and viva study notes. The student should
  explain request flow, Boolean precedence, scoring, Top-K and safe document access.
- The reviewed Part 1 documents are already integrated into main. Integrate any
  later reviewed branch only under a separately authorized workflow.
  Prepare reproducible startup, queries, expected documents and cleanup steps for
  a fictional-data supervisor demonstration. Preserve known-working checkpoints.
- Protect November 10 for slides/speech and November 11 for rehearsal/viva. No network
  deployment or new retrieval algorithm is needed for this demonstration.

## H. Midterm acceptance checklist

Checked implementation items are supported by integrated source, the fresh
regression and/or the separately attributed student report; they do not claim
exhaustive testing. Publication/preparation items remain unchecked.

- [x] Advanced Search label, AND words within OR groups and add/remove controls.
- [x] Correct grouped expansion, preview/submission agreement and AND precedence.
      The seven-case temporary engine truth table belongs to prior 3.5A evidence;
      current persistent browser and Boolean suites pass without counting it again.
- [x] Ranking/Top-K preserved without visible scores; keyword HTTP scores retained.
- [x] Open the corresponding indexed text in the reader and unchanged raw endpoint.
- [x] Download original bytes with validated filename/header handling.
- [x] Contextual source snippets and neutral unavailable/empty fallback.
- [x] Shared safe snippet/reader highlights under documented matching rules.
- [x] Existing keyword, phrase and Boolean retrieval regression passes.
- [x] Fresh original regression and syntax checks, both collections: 562 passed,
      0 failed; one symlink fixture skipped (not a pass).
- [x] Student-reported manual functional Microsoft Edge acceptance recorded above.
- [x] Phase 3.5A-3.5C reviewed implementation integrated into main.
- [x] Six-file documentation/manual/runbook independently reviewed and integrated
      into main at `cdc3b50`; preserved as a historical milestone.
- [x] Premium UI/adaptive Advanced Search integrated at `143ab55`; student-reported Edge acceptance.
- [x] Optional showcase independently source-reviewed, accepted in Edge by the
      student on November 10, 2026, and fast-forward integrated at `28a26ab`.
- [ ] Runbook sequence rehearsed end-to-end by the student with timing confirmed.
- [ ] Presentation slides and speech prepared (planned November 10).
- [ ] Student code explanation, rehearsal and viva practice completed (November 11).

**Unverified coverage:** the Windows symlink fixture and comprehensive
keyboard/assistive-technology, responsive/zoom, slow-network and device-matrix
manual testing. No additional such checks are represented as passed.

## I. Post-midterm roadmap

These phases are proposals for later discussion, not blanket supervisor approvals.

| Phase | Proposed scope | Decisions required |
| --- | --- | --- |
| 4 | Document metadata and date filters | Which fields exist, how they are indexed and what each date means. No current metadata-field support is implied. |
| 5 | Authorized document ingestion and text-based PDF extraction | Document rights, permitted sources, extraction tools and failure handling. Extracting an existing text layer is not OCR. |
| 6 | Bangla/Unicode-aware text processing and multilingual retrieval | Tokenization, normalization, language scope and evaluation. Image/OCR work must be separately scoped and evaluated, including recognition accuracy. |
| 7 | Document permissions, persistent indexing, performance/evaluation and approved IIT deployment architecture | Identity/access policy, secure hosting, storage/rebuild lifecycle, evaluation data and deployment authority. Existing CLI manual rebuilds are already implemented. |

Future fuzzy, semantic or other retrieval additions require explicit justification
and agreement after midterm. The IIT-usable product goal does not remove document
rights, authorization, security or deployment requirements. Permission checks
must precede exposure of nonpublic documents or shared-network access, regardless
of phase numbering. Network membership and hidden browser links are not access
controls; filenames, snippets and match existence can also disclose information.

## J. Time and risk management

| Date | Working allocation |
| --- | --- |
| November 9, 2026 | Freeze implemented 3.5A-3.5C; fresh full regression and Phase 3.5D Part 1 documentation review. |
| November 10, 2026 | Next work session: slides and speech preparation; no slides in this task. |
| November 11, 2026 | Rehearsal, code explanation and viva preparation. |
| November 12, 2026 | Midterm examination and reproducible demonstration. |

The original plan allocated November 9-10 to implementation/documentation and
November 11 to presentation preparation. The updated allocation above protects
November 10-11 now that features are frozen.

Small increments keep failures attributable and give the student reviewable code
to study. Review each increment before proceeding and keep a known-working Git
checkpoint. The schedule is aggressive: new document mapping and safe rendering
need deliberate checks, while UI changes will require updating score/label test
expectations without weakening algorithm coverage.

**Contingency rule:** prioritize working Advanced Search, view/download and basic
context/highlighting over visual polish, metadata filters or extra algorithms.
If essential work cannot pass its checks within the available time, report the
remaining gap and agree on a reduced demonstration; do not claim it complete or
sacrifice the November 11 preparation window. No high-end architecture or network
deployment should consume this midterm work period.
