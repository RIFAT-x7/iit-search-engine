# Planned Features and Supervisor Decisions

This document separates completed implementation from remaining plans and
unconfirmed supervisor decisions. The [README](README.md) explains the current
application and run commands. At this synchronization checkpoint, main includes
Phase 3.3B at `2a228fc88eee32c35925d10208f6e3e835ef2599`.
Listing a future feature here does not establish approval or implementation.

## Current implementation

Phases 1 and 2 established the midterm baseline. Phases 3.1-3.3 are now also
complete and integrated, with:

- A custom Java IR core with in-memory frequency postings and complete zero-based
  positional postings. Positions include stop words, gaps and repeated occurrences;
  searchable frequencies exclude the configured twelve stop words.
- Three retrieval algorithms: ranked keyword search over distinct filtered terms,
  exact normalized phrase matching using positions, and explicit Boolean AND/OR
  using document-ID sets. AND takes precedence over OR. Phrase and Boolean
  matches are unique documents in filename order, without ranking scores.
- A CLI and a separate Java HTTP server that reuse the same IR core. The CLI
  supports keyword search and manual rebuilds; the HTTP server builds once at
  startup and exposes all three retrieval modes.
- `GET /search?q=...&mode=...` accepts keyword, phrase or boolean, plus the
  separate `GET /health` endpoint. Missing mode
  preserves legacy keyword JSON with numeric scores; phrase/Boolean responses
  include mode and filenames only. Invalid/duplicate modes and invalid Boolean
  expressions produce escaped descriptive JSON HTTP 400.
- A plain HTML/CSS/JavaScript client with three accessible radio modes, in-page
  results, responsive styling and error/loading feedback. Mode changes clear
  results and cancel/invalidate stale requests; safe textContent renders output.
- Two separate fixed fictional corpora: default startup loads the six immediate
  `.txt` files in `data/`; `--phrase-demo` loads only the nine samples in
  `data/phrase_samples/`. Neither is an official IIT document collection.
- Four Java suites, original/phrase/Boolean HTTP suites and a DOM/fetch client
  model: the latest integration run passed **175/175 checks, as reported by
  Codex**, using JDK 25.0.3. The user separately confirmed the two collection
  groups of Edge checks recorded in the [verification record](README.md#testing-and-verification-record).
  There is no automated end-to-end browser suite. This documentation sync does
  not rerun application tests or add browser-verification claims.
- A localhost-only server configuration at `127.0.0.1:8080`. Cross-computer
  access and IIT network deployment are not implemented.
- Twelve published learning manuals, listed in the [learning journal](learnings/README.md),
  covering foundations through browser Boolean retrieval.
  Earlier manuals retain their historical milestone context.

Current results contain plain-text filenames and keyword-only scores. There are
no document preview/download routes, login, or document permission system. The server serves
only its fixed browser assets, health endpoint, and search API. PDF extraction,
OCR, partial/fuzzy retrieval, Top-K limits, snippets, and index persistence remain
future work. Boolean parentheses/grouping, NOT, phrase operands and wildcards
are unsupported; stop-word operands are rejected. Keyword ranking and all-matches
behavior remain unchanged. There is no quotation-mark grammar in keyword search.
Current tokenization retains ASCII letters and digits; UTF-8 file reading and
JSON transport do not establish Bengali search support.

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

Document viewing and possible downloading require appropriate authorization.
The server would need to check permissions for every request, including direct
requests to a document endpoint. Hiding a browser link would not protect a file.
Whether downloading is allowed at all is a question for the supervisor.

Document endpoints must use validated document identifiers that the server
maps to approved records, rather than arbitrary paths supplied by a client.
Current IDs depend on sorted filenames, so they need review before being used
for persistent file links.

Network controls and access restrictions must be in place and checked before
real IIT documents are exposed. Permissions also need to cover filenames,
metadata, snippets, and search matches, since these can reveal restricted
information. No login, authorization system, or document access endpoint is
implemented yet.

## PDF extraction, OCR, and Bengali support

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

Completed milestones are recorded below separately from pending work. Phase 3
is partially complete overall: its 3.1-3.3 milestones are integrated, while Top-K
and partial/fuzzy retrieval remain pending. Future scope can change after
documented supervisor decisions.

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
| 3 (remaining) | Pending; not implemented | Top-K result limits and partial/fuzzy retrieval. Their policy, matching definition and evaluation still need agreement. |
| 4 | Planned; document scope pending approval | Authorized document ingestion and text-based PDF extraction; OCR only if separately approved. |
| 5 | Planned; access policy pending approval | Result metadata, snippets, and controlled file viewing/downloading, subject to approved permissions. |
| 6 | Planned; deployment and identity decisions pending approval | IIT internal network deployment and approved authentication/authorization. |
| 7 | Planned | Persistence, rebuild support, evaluation, final testing, and documentation. The existing CLI already supports manual rebuilds; persistence and the remaining rebuild design are future work. |

Access controls cannot wait until Phase 6 if nonpublic files or shared network
access are introduced earlier. The necessary identity checks, authorization,
and network restrictions must be agreed, implemented, and verified before that
exposure.

Broader browser, zoom/reflow, accessibility, and usability checks remain part of
the planned testing work.

## Questions for the supervisor: November 1, 2026

This dated list was prepared when Phase 2 was the current baseline. It is retained
as a historical discussion agenda, not a record of answers. Positional indexing,
phrase retrieval and Boolean retrieval have since been implemented through
Phase 3.3B; that progress does not confirm what the supervisor required for the
midterm or authorize future document/deployment scope.

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

No supervisor answers or approvals for these decisions are documented in the
repository. Midterm sufficiency, remaining algorithm requirements, deployment,
identity, document permissions, viewing/downloading, PDF/OCR, Bengali scope and
final deliverables therefore remain unconfirmed. Record actual decisions when
available; completed implementation is not evidence of supervisor approval.
