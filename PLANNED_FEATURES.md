# Planned Features and Supervisor Decisions

This document separates completed implementation from remaining plans and
unconfirmed supervisor decisions. The [README](README.md) explains the current
application and run commands. On November 9, clean local main and fetched
origin/main were verified at `ab6eacc43b9b8424f332304f18729255ef45d3a0`.
The original November planning checkpoint was `bfb28f676a73443a9cc383cb9ccad52b415b94c8`;
Phase 3.4B was integrated earlier at `39f835359860866b504a487eef929a273c41c614`.
Listing a future feature here does not establish approval or implementation.

## Supervisor-feedback checkpoint: November 2026

The student reports that the supervisor considers the existing algorithmic work
sufficient for the November 12, 2026 SPL-1 midterm and wants practical usability
improvements. This records the student's account, without invented quotations,
a precise meeting date or blanket approval of later proposals.

The reported priorities are Advanced Search keyword rows with AND/OR over the
existing Boolean engine, opening and downloading indexed text, contextual
snippets and highlighting, and hiding browser keyword scores while preserving
ranking. Midterm work stays on localhost and the current text-file scope.
Fuzzy/semantic and other new retrieval algorithms are not current priorities;
PDF, images/OCR, Bangla processing and date filters are deferred after midterm.

The clarified Phase 3.5A design uses AND-linked single-word inputs within each
Advanced Search group and OR between complete groups, with Add AND Word, Add OR
Group, remove controls, validation and an expression preview. For the intended
`computer AND (java OR python)`, compose groups computer AND java and computer AND
python, generating `computer AND java OR computer AND python` with `mode=boolean`.
`computer AND java OR python` would wrongly allow python-only matches. Parentheses
are explanatory, not supported parser syntax; the evaluator and operand/stop-word
restrictions remain unchanged. No nested query grammar or new ranking is planned.
Basic Keyword Search remains OR-like candidate retrieval with TF-IDF-style ranking,
not strict AND or identical to score-free Boolean OR. Hide only browser numerical
scores; preserve ranking, Top-K and the HTTP numeric score contract. The detailed
plan retains the matching table; expansion and retrieval behavior have been tested.
The seven temporary Phase 3.5A truth-table checks are historical evidence, not
part of the persistent 547-check suite.

The [detailed Phase 3.5 plan](docs/MIDTERM_SUPERVISOR_PLAN_2026-11.md) defines
acceptance criteria, tests, boundaries and risks. Phase 3.5R is integrated;
3.5A, 3.5B and 3.5C are completed and integrated. Phase 3.5D is active
stabilization, awaiting acceptance. Application features are now frozen.
The earlier November 9-10 implementation window is superseded: November 9 is
stabilization/documentation, November 10 slides/speech, and November 11 rehearsal,
code explanation and viva preparation. No slides are created in Part 1.

The implementation decisions are now fixed: exact indexed filenames map to
private original-byte snapshots; `/document/view` and `/document/download` remain
raw-byte routes; `/reader.html` uses the same browser matcher as snippets.
Phrase highlights require complete normalized occurrences. Boolean highlights
include only operands from satisfied AND groups, evaluated document-wide. Real IIT
ingestion, identity, permissions, document rights and deployment architecture
still require agreement. The earlier November 1 agenda is retained below as a
historical record; this checkpoint updates the current priorities.

## Current implementation

Phases 1 and 2 established the midterm baseline. Phases 3.1-3.4 are now also
complete and integrated; Phases 3.5A-3.5C now add usability without new retrieval
algorithms. Current capabilities include:

- A custom Java IR core with in-memory frequency postings and complete zero-based
  positional postings. Positions include stop words, gaps and repeated occurrences;
  searchable frequencies exclude the configured twelve stop words.
- Three retrieval algorithms: ranked keyword search over distinct filtered terms,
  exact normalized phrase matching using positions, and explicit Boolean AND/OR
  using document-ID sets. AND takes precedence over OR. Phrase and Boolean
  matches are unique documents in filename order, without ranking scores.
- Optimized keyword Top-K through `searchTopK(query, k)`: sparse posting-driven
  scoring and a manually implemented bounded binary heap retain the exact
  ranking prefix and numerical scores of full keyword search. It does not call
  full search then truncate or sort every matching document. Candidate scoring
  is still required; core optimization does not establish browser-level speedup.
- A CLI and a separate Java HTTP server that reuse the same IR core. The CLI
  supports keyword search and manual rebuilds; the HTTP server builds once at
  startup and exposes all three retrieval modes.
- `GET /search?q=...&mode=...` accepts keyword, phrase or boolean, plus the
  separate `GET /health` endpoint. Missing mode
  preserves legacy keyword JSON with numeric scores; phrase/Boolean responses
  include mode and filenames only. Invalid/duplicate modes and invalid Boolean
  expressions produce escaped descriptive JSON HTTP 400.
- Optional keyword-only `k` calls `searchTopK()` directly, reusing legacy keyword
  JSON. Absent K means unrestricted search, without a default server limit.
  Accept one ASCII-decimal positive int through 2147483647, including leading
  zeros. Reject blanks, zero, negatives/signs, fractions, exponents, Unicode
  digits, overflow, duplicate K and K supplied with phrase/Boolean mode.
- A plain HTML/CSS/JavaScript client with three accessible radio modes, in-page
  results, responsive styling and error/loading feedback. The keyword limit
  select offers All results (default), Top 1, Top 3, Top 5 and Top 10. It is
  disabled outside keyword mode and preserves the chosen limit across modes;
  only limited keyword requests send K. JavaScript-free keyword GET remains
  unrestricted because the select begins disabled and has no form name. Mode or limit changes
  clear results and cancel/invalidate stale requests; safe textContent renders
  output in server order, without client-side sorting or truncation.
- Two separate fixed fictional corpora: default startup loads the six immediate
  `.txt` files in `data/`; `--phrase-demo` loads only the nine samples in
  `data/phrase_samples/`. Neither is an official IIT document collection.
- Grouped Advanced Search, browser-only hiding of numerical keyword scores,
  exact filename links and separate original downloads. Basic Keyword Search
  still ranks OR-like candidates; Advanced Search remains filename-ordered Boolean
  matching through `mode=boolean`, with no `k` and no new parser grammar.
- Original-byte snapshots published after successful indexing; failed builds
  preserve the prior index and bytes. Request names only look up indexed records,
  never resolve arbitrary filesystem paths. Source changes do not alter snapshots
  until a deliberate successful rebuild/restart. This is not per-user authorization.
- Shared safe source-offset highlights and contextual previews; a complete reader
  preserves text/whitespace and links to unchanged raw text/download endpoints.
  Failed previews do not remove results; query/group edits invalidate stale work.
- Fresh November 9 Phase 3.5D regression: **100 Java passed, 1 Windows symlink
  fixture skipped; 320 HTTP passed; 94 browser-model and 33 highlighting/reader
  passed; 547 passed, 0 failed overall**. Temurin JDK 25.0.3 compilation, five
  JavaScript syntax checks and seven PowerShell parses passed. Both collections
  ran through the ownership-checked runner; port 8080 was free after cleanup.
  The historical Phase 3.4 evidence remains **287/287** (86 Java, 159 HTTP, 42
  browser model), not the current total. See the
  [verification record](README.md#testing-and-verification-record).
- **Student-reported manual Edge acceptance** of 3.5A-3.5C: keyword/Top-K,
  phrases, grouped Advanced Search, opening/downloading, contextual snippets,
  keyword/phrase/Boolean highlighting, reader and raw-text access on both
  collections. Codex did not independently perform these manual checks; no
  comprehensive keyboard, responsive/zoom, assistive-technology or device matrix
  coverage is inferred. Automated models are not a real-browser suite.
- A localhost-only server configuration at `127.0.0.1:8080`. Cross-computer
  access and IIT network deployment are not implemented.
- Fourteen published learning manuals, listed in the [learning journal](learnings/README.md),
  covering foundations through [optimized Top-K core retrieval](learnings/03-06-optimized-top-k.md)
  and [Top-K HTTP/browser integration](learnings/03-07-top-k-http-browser.md).
  Earlier manuals retain their historical milestone context. One new
  [consolidated Phase 3.5 manual](learnings/03-08-phase-35-midterm-features.md)
  and a [midterm demo runbook](docs/MIDTERM_DEMO_RUNBOOK_2026-11.md) are prepared
  in this documentation branch for independent review; main integration and
  final stabilization acceptance remain pending.

Browser results show filename links, downloads and authentic previews without
numerical scores. Keyword JSON retains its original numeric scores and schema.
Fixed reader assets do not add a general file-serving route. There is no login,
per-document authorization, PDF/OCR, partial/fuzzy/semantic retrieval, persistence,
metadata/date filtering or general Bangla/Unicode IR. Boolean parentheses, NOT,
phrase operands and wildcards remain unsupported. UTF-8 transport alone does
not establish multilingual search. The skipped symlink fixture limits security
evidence; no production filesystem-security claim is made.

## Proposed IIT deployment

The proposed final service would be available only within the approved IIT
internal network. The supervisor still needs to confirm the deployment arrangement.

- The main IIT server computer would store original documents, the index, and all
  backend processing, including ingestion, indexing, retrieval, and ranking.
- Clients would use browsers to submit searches, view permitted results, and access
  specifically permitted files through controlled server endpoints.
- Clients must not receive the complete index or access to the internal server
  filesystem. Responses expose only the information permitted for that user.
- Network membership alone must never imply authorization. Being connected to
  IIT's network does not grant permission to search or open every document.

The current server still runs only on localhost with fictional documents.

## Identity and document access

The final service may need login accounts or another way to verify credentials.
That choice is still open. Authentication verifies who a user is. Server-side
authorization decides which searches, result information, and documents that
user may access. A login alone would not grant access to every document.

For future real documents, viewing and downloading require appropriate authorization.
The server would need to check permissions for every request, including direct
requests to a document endpoint. Hiding a browser link would not protect a file.
For the fictional localhost midterm prototype, viewing and downloading are
requested in the student's report. Download policy for real IIT documents
remains a question for the supervisor.

Document endpoints must use validated document identifiers that the server
maps to approved records, rather than arbitrary paths supplied by a client.
The implemented midterm mapping uses exact indexed filenames, not numeric IDs
or client paths. Numeric IDs still depend on sorted filenames; future stable
identities, renamed-file link lifetimes and per-user permissions need design.

Network controls and access restrictions must be in place and checked before
real IIT documents are exposed. Permissions also need to cover filenames,
metadata, snippets, and search matches, since these can reveal restricted
information. Prototype indexed-document endpoints are implemented for fictional
localhost data; no login or authorization system is implemented.

## PDF extraction, OCR, and Bengali support

All items in this section are deferred beyond midterm. The revised Phases 5-6
are proposals requiring further scope/tool decisions, not current requirements.

- **Text-based PDF extraction is planned:** extract an existing text layer from
  authorized PDFs, then preprocess and index that text. This is not OCR.
- **OCR is a separate possible capability:** scanned PDFs and images may contain
  only pictures of text and may require optical character recognition to produce
  searchable text. Whether OCR belongs in SPL-1, which tools to use, and how to
  check its accuracy are questions for the supervisor.
- **Bengali text indexing is undecided:** it would require an agreed approach to
  tokenization and retrieval beyond the current ASCII word processing.
- **Bengali OCR is also undecided:** recognizing Bengali from images is a
  separate decision from indexing Bengali text already available digitally.

Bengali support is not a confirmed requirement. Approval to ingest documents
would not automatically include OCR or Bengali support. Future ingestion must
use public or explicitly authorized documents.

## Roadmap

Milestones through Phase 3.4 are complete and integrated. The November feedback
replaced the earlier generic remaining-Phase-3 fuzzy priority with Phase 3.5
usability work, now implemented through 3.5C. Fuzzy retrieval is deferred after midterm and requires
explicit justification and agreement. The November 1 questions below retain
their original historical context. Future phases in this table are proposals,
not blanket supervisor approvals.

| Phase | Status | Scope |
| --- | --- | --- |
| 1-2.4 | Completed | Custom IR core, CLI, baseline tests, HTTP health/search, JSON API, and HTML/CSS/JavaScript browser client. |
| 2.5A | Completed | Sixteen repeatable HTTP integration smoke tests covering existing routes, JSON results, validation errors, and routing behavior. |
| 2.5B | Completed | Fast-forward integration of `feat/browser-client` into `main`, preserving all 11 feature commits. |
| 3.1A | Completed and integrated | Positional inverted index alongside searchable frequency postings; thirteen focused tests and learning manual. |
| 3.2A | Completed and integrated | Exact phrase retrieval in the Java core; eighteen tests and separate nine-document fictional corpus. |
| 3.2B | Completed and integrated | Browser/HTTP phrase mode, fixed --phrase-demo startup, HTTP/client regression coverage and learning manual. |
| 3.3A | Completed and integrated | Core Boolean AND/OR retrieval with AND precedence, strict validation, twenty-two tests and learning manual. |
| 3.3B | Completed and integrated | Browser/HTTP Boolean mode, shared score-free JSON, 175-check integration verification and Manual #12. |
| 3.4A | Completed and integrated | Optimized bounded-heap Top-K Java retrieval, exact ranking/score compatibility, 26 core checks and Manual #13; checkpoint 498de05. |
| 3.4B | Completed and integrated | Strict keyword K parameter, browser result-limit select, 287-check integration verification and Manual #14; checkpoint 39f8353. |
| 3.5R | Completed and integrated | Original planning plus grouped semantics correction; checkpoint `13ea98f`. |
| 3.5A | Completed and integrated (`35482fa`) | Advanced Search AND words within OR groups, add/remove controls, validation and preview; existing evaluator and mode=boolean compatibility; hide browser scores, preserve ranked keyword retrieval/HTTP scores/Top-K. |
| 3.5B | Completed and integrated (`e1f433c`) | Exact indexed-filename snapshots; raw view and exact original download; collection isolation. |
| 3.5C | Completed and integrated (`ab6eacc`) | Source excerpts, shared safe highlighting, reader and unchanged raw/download routes. |
| 3.5D | Active; awaiting review and acceptance | Fresh regression and documentation Part 1; student-reported Edge acceptance recorded; manual/runbook draft; later slides, rehearsal and viva practice pending. |
| 4 | Deferred; proposed after midterm | Document metadata and date filters; metadata model and date meaning need agreement. |
| 5 | Deferred; proposed after midterm | Authorized document ingestion and text-based PDF extraction; rights and permitted collection need agreement. |
| 6 | Deferred; proposed after midterm | Bangla/Unicode-aware text processing and multilingual retrieval; image/OCR work separately scoped and evaluated. |
| 7 | Deferred; proposed after midterm | Document permissions, persistent indexing/rebuild design, performance/evaluation and approved IIT deployment architecture. |
| Future algorithms | Deferred; not implemented | Partial/fuzzy, semantic or other additions only when explicitly justified and agreed after midterm. |

Access controls cannot wait until Phase 7 if nonpublic files or shared network
access are introduced earlier. The necessary identity checks, authorization,
and network restrictions must be agreed, implemented, and verified before that
exposure.

Reported functional Edge acceptance is recorded above. Broader keyboard-only,
zoom/reflow, screen-reader and device-matrix checks remain unverified; they are
not a claim that the student must repeat already accepted functional scenarios.
Documentation branch publication is separately authorized for GitHub review;
main integration and final stabilization acceptance still require the next
authorized review workflow.

## Questions for the supervisor: November 1, 2026

This dated list was prepared when Phase 2 was the current baseline. It is retained
as a historical discussion agenda, not a record of answers. Positional indexing,
phrase retrieval, Boolean retrieval and optimized keyword Top-K have since been
implemented through Phase 3.4B; that progress does not confirm what the supervisor
required for the midterm or authorize future document/deployment scope.

1. **Midterm scope:** Is the current Phase 2 application sufficient for the
   midterm presentation? Are any changes needed before the demonstration?
2. **Advanced IR before midterm:** Are positional indexing, Boolean queries,
   phrase search, or other advanced algorithms expected before the midterm?
3. **Deployment:** Should the final application run centrally on an IIT server
   and be accessible only through the IIT network? Which computers or network
   segments may connect, and who will approve the network controls?
4. **Accounts:** Are individual accounts or credential verification required?
   Which account system may be used, and who manages access and revocation?
   If anonymous access is allowed, what public material may it expose?
5. **Document permissions:** Which documents may be collected, who authorizes
   their use, and which users may search them? Should filenames, metadata,
   snippets, and the existence of a match also be restricted?
6. **Viewing and downloading:** Should users view documents in the browser,
   download them, or both? Do these actions need different permissions?
7. **PDF and OCR:** Is text-based PDF extraction required? Is OCR needed for
   scanned PDFs or images? Which tools, languages, and accuracy checks are suitable?
8. **Bengali:** Is Bengali text indexing required? Is Bengali OCR also required,
   or should the project remain focused on English documents?
9. **Final deliverables:** What minimum algorithms, dataset size, Top-K policy,
   relevance measures, performance comparison, and other deliverables are expected?

Historical status when this November 1 agenda was recorded:
No supervisor answers or approvals for these decisions were documented in the
repository. Midterm sufficiency, remaining algorithm requirements, deployment,
identity, document permissions, viewing/downloading, PDF/OCR, Bengali scope and
final deliverables therefore remained unconfirmed. The November 2026 feedback
checkpoint above now records the student's account of midterm sufficiency,
usability priorities and deferrals. It does not settle real-document permissions,
deployment or final deliverables; completed code is not evidence of approval.
