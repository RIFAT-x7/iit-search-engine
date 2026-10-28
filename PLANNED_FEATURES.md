# Planned Features and Supervisor Decisions

This is the plan for the work after the midterm baseline, along with questions
for the supervisor. The [README](README.md) explains how the current application
works and how to run it. The plans below may change after discussion; listing a
feature here does not mean it has been approved or implemented.

## Current implementation

Phases 1 and 2 are complete. The application on `main` is the stable midterm
demonstration baseline, with:

- A custom Java Information Retrieval core with an in-memory inverted index,
  raw term-frequency postings, and TF-IDF-style ranking over distinct query terms.
  Current multi-word retrieval uses ranked OR semantics and returns all matches.
- A CLI and a separate Java HTTP server that reuse the same IR core. The CLI
  supports manual index rebuilds; the HTTP server builds its index at startup.
- A JSON search API at `GET /search?q=...`, with query validation and ranked
  filenames and numeric scores, plus `GET /health`.
- A plain HTML/CSS/JavaScript browser client with in-page search results,
  loading/error feedback, and responsive styling. Java performs retrieval and
  ranking on the server.
- Six fictional demonstration text documents in `data/`, not real IIT records.
- Seven dependency-free IR regression tests in `tests/BaselineTests.java`, plus
  16 repeatable HTTP integration smoke tests in `tests/HttpSmokeTests.ps1`
  from Phase 2.5A. Verification records are in the README;
  there is no automated browser end-to-end suite.
- A localhost-only server configuration at `127.0.0.1:8080`. Cross-computer
  access and IIT network deployment are not implemented.
- Seven learning manuals, listed in the [learning journal](learnings/README.md),
  covering foundations, HTTP health, HTTP search, JSON, HTML, JavaScript, and CSS.
  Earlier manuals retain their historical milestone context.

Current results contain filenames and scores as text. There are no document
preview/download routes, login, or document permission system. The server serves
only its fixed browser assets, health endpoint, and search API. PDF extraction,
OCR, positional postings, Boolean query parsing, phrase search, partial/fuzzy
retrieval, Top-K limits, snippets, and index persistence remain future work.
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

The next phases build on the completed midterm baseline. Their scope can be
adjusted after the supervisor discussion.

| Phase | Status | Scope |
| --- | --- | --- |
| 1-2.4 | Completed | Custom IR core, CLI, baseline tests, HTTP health/search, JSON API, and HTML/CSS/JavaScript browser client. |
| 2.5A | Completed | Sixteen repeatable HTTP integration smoke tests covering existing routes, JSON results, validation errors, and routing behavior. |
| 2.5B | Completed | Fast-forward integration of `feat/browser-client` into `main`, preserving all 11 feature commits. |
| 3 | Planned | Advanced IR algorithms: positional indexing, explicit Boolean queries, phrase retrieval, partial/fuzzy retrieval, and Top-K results. |
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

These questions are still unanswered. I'll discuss them with my supervisor
before deciding which features to work on next.
