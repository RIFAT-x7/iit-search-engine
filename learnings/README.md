# Learning journal

**Rifat Al Maruf (BSSE1705), IIT, University of Dhaka**

**Project:** IIT Search Engine, SPL-1

This folder explains the ideas behind each completed development milestone. It
supports code review, revision, and viva preparation. The implementation and
verification results are evidence; the notes do not claim that planned features
already work.

## Available notes

1. [Phase 1: Foundations](01-phase1-foundations.md) — Git, Java setup, the CLI
   search pipeline, ranking, and baseline regression tests.
2. [Phase 2.1: HTTP and client-server foundation](02-01-http-health-endpoint.md) —
   The health endpoint, HTTP concepts, verification history, and current limits.
3. [Phase 2.2A and 2.2B: HTTP search integration](02-02-http-search-api.md) —
   Startup indexing, query parsing and strict decoding, IR integration, and
   historical ranked plain-text responses before JSON.
4. [Task 2.3: JSON search API](02-03-json-search-api.md) —
   The current JSON response contract, serialization and escaping, HTTP helpers,
   worked traces, and verification boundaries.

The Phase 2.2 and Task 2.3 manuals are prepared locally and pending inclusion
in a separate documentation commit from the JSON implementation.

## How to use the notes

Read the manuals in the numbered order. Phase 1 explains the IR core used by
the later HTTP milestones. Phase 2.1 introduces the server and health endpoint;
Phase 2.2 explains how search was connected through validated URL parameters;
Task 2.3 then explains the move from plain text to JSON responses.

The manuals include implementation explanations, worked examples, verification
records and limitations, viva questions, and self-practice exercises. Read each
alongside its linked source and the historical commit identified in the text.
Earlier statements about planned features refer to that manual's milestone;
the root README describes the current implementation.

Distinguish previously recorded results from commands offered for a later
check. Run the relevant checks before changing behavior. Complete reflection
prompts personally; they contain no invented personal experiences or answers.

Return to the [project README](../README.md).
