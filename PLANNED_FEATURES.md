# Planned Features and Supervisor Decisions

This document records the current implementation, proposed final architecture,
revised incremental roadmap, and decisions awaiting supervisor approval.
It supplements the [project README](README.md), which describes current behavior
and verification history. Proposed features below are not implemented or approved
merely because they appear here. This documentation task authorizes no new
functionality, network binding changes, or security implementation.

## 1. Current implementation

Phases 1 and 2 are completed, including the HTML, JavaScript, and CSS milestones,
Phase 2.5A HTTP smoke tests, and Phase 2.5B fast-forward integration into `main`.
The current `main` application is the stable midterm demonstration baseline.
The current prototype provides:

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
  (completed Phase 2.5A). Verification records are described in the README;
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

## 2. Proposed final IIT deployment architecture

The proposed service would operate within the approved IIT internal network,
not on the public internet. Deployment details require supervisor approval.

- The main IIT server computer stores original documents, the index, and all
  backend processing, including ingestion, indexing, retrieval, and ranking.
- Clients use browsers to submit searches, view permitted results, and access
  specifically permitted files through controlled server endpoints.
- Clients must not receive the complete index or access to the internal server
  filesystem. Responses expose only the information permitted for that user.
- Network membership alone must never imply authorization. Being connected to
  IIT's network does not grant permission to search or open every document.

This is a deployment proposal, not the current localhost configuration or a
claim that real IIT documents are ready to be exposed.

## 3. Security and document access decisions

Login or credential-based identity verification may be required, subject to
supervisor approval. Authentication verifies who a requester is; server-side
authorization determines which searches, result information, and documents that
requester may access. These are separate concerns.

Document viewing and possible downloading require appropriate authorization.
The server must enforce approved permissions for each request, including direct
requests to document endpoints; hiding a browser link is insufficient. Whether
downloading is allowed at all remains a supervisor decision.

Future document endpoints must accept validated document identifiers and resolve
them to approved server-side records. They must not accept arbitrary
client-supplied file paths. Current document IDs depend on sorted filenames, so
the identifier design must be reviewed before introducing persistent file links.

Appropriate network controls and access restrictions must be implemented and
verified before exposing real IIT documents. Permission rules must also address
whether filenames, metadata, snippets, and search matches may disclose restricted
information. Required controls are a prerequisite for the relevant exposure,
even if the corresponding roadmap phase appears later.

**No security implementation is authorized by this documentation task.** The
identity mechanism, permission model, network configuration, and implementation
scope require a separately reviewed and approved milestone.

## 4. Documents, PDF extraction, and OCR

- **Text-based PDF extraction is planned:** extract an existing text layer from
  authorized PDFs, then preprocess and index that text. This is not OCR.
- **OCR is a separate possible capability:** scanned PDFs and images may contain
  only pictures of text and may require optical character recognition to produce
  searchable text. OCR scope, tools, quality checks, and inclusion in SPL-1 remain
  subject to supervisor approval.
- **Bengali text indexing is undecided:** it would require an agreed approach to
  tokenization and retrieval beyond the current ASCII word processing.
- **Bengali OCR is also undecided:** recognizing Bengali from images is a
  separate decision from indexing Bengali text already available digitally.

Bengali support must not be treated as mandatory until supervisor confirmation.
Approval to ingest documents does not automatically approve OCR or either form
of Bengali support. Only public or explicitly authorized documents may be used
for future ingestion work.

## 5. Revised incremental roadmap

This roadmap refines the README's future-work list into reviewable milestones.
The README's list is a capability summary; the sequence below is the proposed
revised implementation order. It does not mark any future milestone completed
or authorize branch integration or implementation.

| Phase | Status | Scope |
| --- | --- | --- |
| 1-2.4 | Completed | Custom IR core, CLI, baseline tests, HTTP health/search, JSON API, and HTML/CSS/JavaScript browser client. |
| 2.5A | Completed | Sixteen repeatable HTTP integration smoke tests covering existing routes, JSON results, validation errors, and routing behavior. |
| 2.5B | Completed | `git merge --ff-only feat/browser-client` integrated all 11 feature commits into `main` at `2cc096be10997422d6a6ea1397fa24dc779529ed`; the normal push succeeded. |
| 3 | Planned | Advanced IR algorithms: positional indexing, explicit Boolean queries, phrase retrieval, partial/fuzzy retrieval, and Top-K results. |
| 4 | Planned; document scope pending approval | Authorized document ingestion and text-based PDF extraction; OCR only if separately approved. |
| 5 | Planned; access policy pending approval | Result metadata, snippets, and controlled file viewing/downloading, subject to approved permissions. |
| 6 | Planned; deployment and identity decisions pending approval | IIT internal network deployment and approved authentication/authorization. |
| 7 | Planned | Persistence, rebuild support, evaluation, final testing, and documentation. The existing CLI already supports manual rebuilds; persistence and the remaining rebuild design are future work. |

**Phase order does not permit delaying necessary access controls.** If nonpublic
files or shared network access are introduced before Phase 6, the required
identity, authorization, and network controls must be approved, implemented, and
verified before that exposure. Phase 6 is not permission to defer these controls.

Broader browser, zoom/reflow, accessibility, and usability checks in the README
remain planned verification work across applicable milestones. Each milestone
requires a concrete scope, review, and suitable checks. This document does not
authorize a merge, commit, or push.

## 6. Questions requiring supervisor approval

1. **Network access:** Which IIT network segments and client computers may reach
   the service? Who approves the server location, network controls, and access
   restrictions? Is any access beyond the approved internal network permitted?
2. **Accounts and identity:** Must all users log in? Which credentials or account
   provider may be used, who provisions accounts, and how is access revoked?
   If any anonymous access is approved, exactly which public material may it see?
3. **Document permissions:** Which documents may be ingested, who authorizes
   their use, and which users or groups may search each collection? Must result
   existence, filenames, metadata, and snippets also be restricted?
4. **Viewing and downloading:** Is browser viewing allowed, is downloading
   allowed, and do they need different permissions? Which file types and direct
   document access rules are acceptable?
5. **PDF scope:** Which text-based PDFs are in scope, which extraction tools are
   acceptable, and how should extraction failures and text quality be reviewed?
6. **OCR scope:** Are scanned PDFs/images required for SPL-1? If so, which
   languages, tools, quality targets, and manual review process are approved?
7. **Bengali support:** Is Bengali text indexing required? Is Bengali OCR
   separately required? Neither is mandatory until explicitly confirmed.
8. **Milestone acceptance:** What retrieval behavior, Top-K policy, evaluation
   collection, relevance measures, performance targets, and final demonstration
   are expected? What checks are required before branch integration and before
   real-document or shared-network use?

Record the supervisor's answers and approved milestone scope before implementing
the affected features. Until then, these questions remain open decisions.
