# IIT Search Engine: Supervisor Feedback and Midterm Plan

**Student:** Rifat Al Maruf, BSSE1705

**Institute/course:** IIT, University of Dhaka; SPL-1, third semester

**Planning record:** November 2026; no precise meeting date is asserted.

**Midterm:** November 12, 2026

**Repository:** RIFAT-x7/iit-search-engine

This is a documentation and planning checkpoint. It does not implement the
features below. The student supervises, ChatGPT plans and audits, and Codex
implements and reports within the authorized milestone. Independent student
review is required before a subsequent implementation instruction.

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

At the start of this task, clean local main and fetched origin/main both pointed
to `bfb28f676a73443a9cc383cb9ccad52b415b94c8`. Phases through 3.4 are completed;
the earlier Phase 3.4B implementation integration is `39f8353`.

| Source | Current behavior |
| --- | --- |
| [TextProcessor.java](../src/TextProcessor.java) | Manual lowercase ASCII letter/digit tokens; punctuation separates words; keyword processing removes twelve stop words. Complete tokens retain stop words for positions. |
| [InvertedIndex.java](../src/InvertedIndex.java) | In-memory frequency postings (term to document-ID/count mappings) and positional postings. |
| [SearchEngine.java](../src/SearchEngine.java) | TF-IDF-style ranked keyword retrieval, exact normalized phrase retrieval and Boolean AND/OR with AND precedence. Optimized Top-K uses sparse scoring and a bounded heap, preserving the full ranking prefix. |
| [Document.java](../src/Document.java) | A document currently holds an ID and filename, not original text or a stored path. IDs are assigned in sorted filename order during indexing. |
| [SearchServer.java](../src/SearchServer.java) | Built-in JDK HttpServer binds to 127.0.0.1:8080; fixed assets, health and search routes only. Keyword JSON has numeric scores; phrase/Boolean JSON has filenames without scores. |
| [index.html](../web/index.html) and [app.js](../web/app.js) | Keyword, phrase and Boolean modes, keyword Top-K choices, loading/error feedback, cancellation/request-ID protection and safe text rendering. Keyword scores are currently visible; filenames are plain text. |

The separate CLI retains keyword search and manual rebuilds. Default startup
indexes six immediate text files in `data/`; `--phrase-demo` instead indexes nine
files in `data/phrase_samples/`. Both collections are fictional. There are fourteen
[learning manuals](../learnings/README.md). See the [README](../README.md) for
architecture, build/run commands, limitations and historical checkpoints.

The previous Codex-reported integration evidence is **287/287 checks: 86 Java,
159 HTTP and 42 JavaScript client-model checks**. These are historical results,
not fresh executions for this planning task. Previous Edge confirmations are
student-reported; the Node DOM/fetch model is not a real browser test.

### Planned work and proposals

Phases 3.5A-3.5D below are pending midterm work. Phase 3.5R is the current
documentation milestone and remains in progress until reviewed integration.
Phases 4-7 are post-midterm proposals requiring further agreement. Exact document
routes, record mappings, row interaction details and phrase/Boolean highlighting
policies must be settled from actual source during implementation.

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

**Status: in progress until reviewed integration into main.**

Formalize the revised scope in this document and update the README and roadmap.
Only those three files belong to this milestone. Audit the documents against
source, test scripts, links and Git state; commit and push the documentation
branch for independent review. Publishing the branch does not complete integration.
Do not merge into main or begin Phase 3.5A in this task.

Acceptance: all planned features are labeled pending, reported feedback is
attributed, historical records/manual links are retained, and only the three
authorized Markdown files change. Run both unstaged and staged whitespace checks,
review the full staged diff and verify the published branch SHA/status and
unchanged main. No new application regression or manual browser results are claimed.

## D. Phase 3.5A: Advanced Search and result presentation

**Status: pending implementation.**

**Basic Keyword Search** retains the existing ranked keyword behavior. For
`computer java python`, a document containing any searchable query term may
appear: candidate retrieval is OR-like, not strict Boolean AND. TF-IDF-style
scores rank the matches; more matching terms or stronger term contributions may
rank a document higher, without guaranteeing that term count alone determines
order. This is not identical to explicit Boolean OR: Boolean results have no
relevance ranking and remain in filename order.

Rename the browser's Boolean Search mode to **Advanced Search** and distinguish
it clearly from Basic Keyword Search. Plan a simple IEEE Xplore-inspired grouped
keyword builder for the November 12 midterm:

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
server order. The current browser still shows scores until this phase is implemented.

Acceptance criteria and test expectations:

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
- Update client-model assertions that currently require the Boolean label and
  visible score strings; retain their behavioral safety coverage. Run relevant
  core/HTTP regressions and JavaScript checks, then verify controls in Edge.
- Preserve safe DOM rendering, responsive readability and accessible status/focus
  feedback. Define and test the native GET fallback for the revised interface.

## E. Phase 3.5B: Text document viewing and downloading

**Status: pending implementation; exact routes/interface decided after source review.**

Link each result to its corresponding indexed text document. Provide browser
viewing, preferably in a new tab, and a download action for the original `.txt`
file. Only files belonging to the currently indexed and allowed fictional
collection may be served. The default collection must not expose the separate
demo collection, or vice versa.

Use document IDs or a validated mapping to indexed records, never unrestricted
browser-supplied filesystem paths. Current IDs are collection/startup-dependent;
review their mapping and link lifetime rather than claiming stable global IDs.
The current Document stores neither text nor a path, so choose how to retain or
safely resolve original text during this milestone. Account for files changed or
removed since startup without returning unrelated content.

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
- Test supported methods, content types, disposition/header safety and chosen
  error responses. Decide these route contracts before writing expectations.
- Test missing/unreadable files and special characters safely. Verify existing
  routes, search JSON, ranking and both collection startup choices remain correct.
- In Edge, check result-link identity, new-tab behavior, keyboard link access,
  readable text, download filename and original content. Record real observations.

## F. Phase 3.5C: Search snippets and highlighting

**Status: pending implementation.**

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
Boolean retrieval uses operands and document sets, not source offsets. Derive
and document their exact highlighting rules from the source, with tested examples.
Do not promise a contiguous raw phrase when punctuation differs, highlight
AND/OR as operators, or imply every OR operand occurs in every result. Whether
phrase highlights cover a verified span or individual terms must be explicitly
decided and tested. Keep indexing, TF-IDF, phrase and Boolean logic unchanged.

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

**Status: pending stabilization after the usability increments.**

- Compile with Temurin JDK 25.0.3 and run the relevant complete regression suites:
  [Baseline](../tests/BaselineTests.java), [positional](../tests/PositionalIndexTests.java),
  [phrase](../tests/PhraseSearchTests.java), [Boolean](../tests/BooleanSearchTests.java)
  and [Top-K](../tests/TopKSearchTests.java) Java tests;
  [HTTP runner](../tests/RunHttpIntegrationTests.ps1) and its original/phrase/Boolean/
  Top-K suites; and [client model](../tests/BrowserClientTests.js), with syntax checks.
  Extend relevant coverage for the new behavior; do not treat the old 287 count
  as proof of new features. Record actual fresh totals and failures.
- Test both the default six-document and nine-document phrase-demo collections.
  Preserve all prior algorithms, ranking, JSON score compatibility and Top-K.
- Verify Microsoft Edge manually, including mixed Advanced Search, keyword and
  phrase modes, opening/viewing, original downloads, snippets and highlights,
  errors, keyboard access and narrow layouts. The model alone cannot prove this.
- Review allowed-file mappings, path/header safety, rendering and error handling.
  Use the owned-server runner; never stop an unidentified port-8080 listener.
- Finish relevant learning documentation and viva study notes. The student should
  explain request flow, Boolean precedence, scoring, Top-K and safe document access.
- Integrate reviewed work into main only under the next authorized workflow.
  Prepare reproducible startup, queries, expected documents and cleanup steps for
  a fictional-data supervisor demonstration. Preserve known-working checkpoints.
- Protect November 11 for presentation, speech and code comprehension. No network
  deployment or new retrieval algorithm is needed for this demonstration.

## H. Midterm acceptance checklist

Future work remains unchecked until implemented and verified.

- [ ] Advanced Search label, AND words within OR groups and add/remove controls work.
- [ ] Group expansion passes the matching table and precedence is understandable.
- [ ] Ranked results preserve ordering/Top-K without visible numerical scores;
      HTTP numerical score compatibility is preserved.
- [ ] Open the correct indexed text document in the browser.
- [ ] Download the original text document with correct filename and bytes.
- [ ] Contextual snippets show authentic, readable excerpts or a safe fallback.
- [ ] Snippet and viewer highlighting follow tested matching rules safely.
- [ ] Existing keyword, exact phrase and Boolean behavior remains correct.
- [ ] Relevant complete regressions and syntax checks pass on both collections.
- [ ] Manual Microsoft Edge checks are performed and recorded separately.
- [ ] GitHub README, roadmap and learning documentation reflect actual behavior.
- [ ] Reviewed implementation is integrated and the demo is reproducible.
- [ ] Presentation slides and speech are prepared for November 12.
- [ ] Code explanation and viva preparation are completed with student practice.

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
| November 9-10, 2026 | Focused Phase 3.5 implementation in small increments, with relevant tests and documentation after each change. |
| November 11, 2026 | Protected slides, speech, code comprehension and viva preparation. |
| November 12, 2026 | Midterm examination and reproducible demonstration. |

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
