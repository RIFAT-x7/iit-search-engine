# Learning journal

**Rifat Al Maruf (BSSE1705), IIT, University of Dhaka**

**Project:** IIT Search Engine, SPL-1

These manuals explain the code and ideas behind the completed milestones, with
worked examples, test records, and viva questions. They were prepared with AI
assistance and should be read alongside the source code.

## Available notes

1. [Phase 1: Foundations](01-phase1-foundations.md) — Git, Java setup, the CLI
   search pipeline, ranking, and baseline regression tests.
2. [Phase 2.1: HTTP and client-server foundation](02-01-http-health-endpoint.md) —
   The health endpoint, HTTP concepts, verification history, and current limits.
3. [Phase 2.2A and 2.2B: HTTP search integration](02-02-http-search-api.md) —
   Startup indexing, query parsing and strict decoding, IR integration, and
   historical ranked plain-text responses before JSON.
4. [Task 2.3: JSON search API](02-03-json-search-api.md) —
   The JSON response contract, serialization and escaping, HTTP helpers,
   worked traces, and verification boundaries.
5. [Phase 2.4A: HTML browser client and architecture](02-04-html-browser-client.md) —
   Semantic HTML, native GET fallback, accessibility attributes, fixed asset
   routing, and the current browser-to-Java request flow.
6. [Phase 2.4B: JavaScript search integration](02-05-javascript-search-client.md) —
   The actual submit handler, DOM APIs, async fetch, JSON error handling, safe
   ordered rendering, repeated submissions, and mocked versus manual testing.
7. [Phase 2.4C: Responsive CSS](02-06-responsive-css.md) —
   Source-based selectors, cascade, box model, typography, flexbox, responsive
   layout, contrast, keyboard feedback, and verification limits.
8. [Phase 3.1A: Positional inverted index](03-01-positional-index.md) —
   Complete token positions, stop words, frequency compatibility, immutable
   snapshots, rebuilds, thirteen focused tests, and future phrase retrieval.
9. [Phase 3.2A: Exact phrase retrieval](03-02-exact-phrase-search.md) —
   Common phrase starts, positional offsets, two-pointer matching, eighteen
   tests, the separate fictional corpus, and preserved keyword ranking.

Phases 1 and 2 are complete and integrated into `main`. The seven Java regression
tests and 16 [HTTP smoke tests](../tests/HttpSmokeTests.ps1) support the current
midterm baseline. See the [project README](../README.md) for commands and results.
Each manual keeps the historical checkpoint it explains, so an older description
of a planned feature may refer to work completed in a later milestone.

## How to use the notes

Read the manuals in the numbered order. Phase 1 explains the IR core used by
the later HTTP milestones. Phase 2.1 introduces the server and health endpoint;
Phase 2.2 explains how search was connected through validated URL parameters;
Task 2.3 then explains the move from plain text to JSON responses. Read the
frontend manuals next: HTML defines the document and native submission,
JavaScript consumes that JSON API within the page, and CSS controls presentation
and responsive layout. Indexing, TF-IDF-style scoring, and ranking stay in Java.
Continue with Phase 3.1A for positional indexing beneath the existing keyword search.
Phase 3.2A then uses those positions for exact phrase matching in the Java core.

The manuals include implementation explanations, worked examples, verification
records and limitations, viva questions, and self-practice exercises. Read each
alongside its linked source and the historical commit identified in the text.
Earlier statements about planned features refer to that manual's milestone;
the root README describes the current implementation.

Distinguish previously recorded results from commands offered for a later
check. Run the relevant checks before changing behavior. Complete reflection
prompts personally; they contain no invented personal experiences or answers.

Verification records identify Codex-reported automated/static checks separately
from the user's manual Microsoft Edge confirmations. They do not imply an
automated browser test suite or newly committed frontend test files. A source
walkthrough, a mock, and a real browser interaction provide different evidence.

Return to the [project README](../README.md).
