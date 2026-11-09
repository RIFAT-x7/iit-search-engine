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
10. [Phase 3.2B: Browser-based exact phrase search](03-03-browser-phrase-search.md) —
    Browser/API modes, legacy JSON compatibility, cancellation and request IDs,
    fixed demo startup, automated checks, and user-verified Edge behavior.
11. [Phase 3.3A: Core Boolean retrieval](03-04-boolean-search.md) —
    Frequency-posting sets, explicit AND/OR parsing, precedence, strict operands,
    twenty-two tests, preserved retrieval behavior, and verification evidence.
12. [Phase 3.3B: Browser-based Boolean retrieval](03-05-browser-boolean-search.md) —
    Three browser/API modes, shared filename-only JSON, validation and safe rendering,
    cancellation, fixed collections, regression coverage, and verification evidence.
13. [Phase 3.4A: Optimized Top-K retrieval](03-06-optimized-top-k.md) —
    Exact keyword ranking prefixes, sparse scoring, a bounded binary heap,
    worked traces, complexity, twenty-six tests, benchmarks and viva preparation.
14. [Phase 3.4B: Top-K HTTP and browser integration](03-07-top-k-http-browser.md) —
    Strict optional limits, direct core dispatch, accessible browser selection,
    asynchronous race protection, compatibility and regression evidence.

15. [Phase 3.5: Midterm features and complete pipeline](03-08-phase-35-midterm-features.md) —
    One consolidated manual for grouped Advanced Search, ranking/Top-K, phrases,
    indexed original-byte access, source snippets, shared safe highlights and
    25 viva questions. Prepared for Phase 3.5D review; student practice is pending.

Phases through 3.5C are integrated at `ab6eacc43b9b8424f332304f18729255ef45d3a0`.
The application feature set is frozen. Fresh November 9 stabilization checks
passed 547 named checks: 100 Java, 320 HTTP, 94 browser-model and 33 presentation/
reader checks; 0 failed and 1 Windows symlink fixture skipped. The student
separately reported successful manual Edge acceptance of 3.5A-3.5C on both
collections; Codex did not independently perform those browser checks.
See the [project README](../README.md) for current commands and evidence, and the
[demo runbook](../docs/MIDTERM_DEMO_RUNBOOK_2026-11.md) for a reproducible presentation.
The earlier seven Java/16 HTTP and 287-check records remain historical baselines.
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
Continue with positional and phrase retrieval, Boolean evaluation, then the
Top-K core and HTTP/browser manuals. Manual #15 connects these algorithms to the
current usability features. Existing fourteen manuals are preserved without
rewriting their historical milestone descriptions.

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
automated end-to-end browser test suite. The current committed Node models use
simulated DOM/fetch behavior. A source
walkthrough, a mock, and a real browser interaction provide different evidence.

Return to the [project README](../README.md).
