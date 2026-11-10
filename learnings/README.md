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
    25 viva questions. Reviewed and integrated with Part 1; student practice is pending.

**CURRENT, November 10:** approved showcase application checkpoint
`28a26ab986351b5d99d29aba26e873e30793c931`, independently source-reviewed and
fast-forward integrated into local main; Premium UI remains the earlier
`143ab55` checkpoint. Part 1 documentation integrated at
`cdc3b504f231d9fc961648970318f103c6543b59`. The original regression freshly
passed 562 checks before and after the optional showcase: 100 Java, 320 HTTP,
107 browser-model, 35 highlighting/reader; zero failures, one Windows symlink
fixture skipped. Showcase checks add 18 Java and 70 HTTP passes (650 overall).
The student confirmed all six showcase Edge scenarios on November 10, 2026,
in addition to earlier 3.5A-3.5C/Premium UI acceptance. These are student-reported
manual checks, not independently performed Codex browser tests. The three fixed
collections remain isolated, with showcase optional and original datasets unchanged.
The application is frozen; slides, timed rehearsal and viva practice remain pending.
See the [project README](../README.md) for current commands and evidence, and the
[demo runbook](../docs/MIDTERM_DEMO_RUNBOOK_2026-11.md) for a reproducible presentation.
The earlier seven Java/16 HTTP, 287-check and November 9 547-check records remain historical baselines.
Each manual keeps the historical checkpoint it explains, so an older description
of a planned feature may refer to work completed in a later milestone.

Supplement: [collection selection and adaptive Advanced Search](../data/showcase/README.md#learning-note-collection-selection-and-adaptive-fields).
This concise note explains the new startup option and the integrated Premium
UI layout without rewriting any of the 15 dated manuals or adding a new algorithm.

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
