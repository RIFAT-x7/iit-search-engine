# Final repository audit and optional showcase — November 10, 2026

**Student:** Rifat Al Maruf, BSSE1705; IIT, University of Dhaka; SPL-1

**Approved starting main:** `143ab553095b95b572ae55016de3850180054fef`

**Working branch:** `feature/final-audit-showcase-corpus`

This is an implementation/audit record for independent review. No commit, push,
merge, UI redesign, new retrieval algorithm or deployment is part of this task.
The student explicitly requested the optional fictional corpus after a healthy
baseline. This does not imply a supervisor meeting quotation or approval of
real-document ingestion. Presentation, speech, timed rehearsal and viva practice
remain separate preparation work before the November 12 midterm.

## 1. Preconditions and audit scope

Clean local main, empty index and fetched origin/main matched the full approved
SHA. No unexpected files or local commits were present, the target branch did
not exist, and port 8080 was free. The branch was created from that checkpoint.
The complete original regression passed before modifications.

Inspection covered the repository inventory, all seven Java classes, search and
reader frontend files, original Java/HTTP/Node suites, both original corpora,
README/roadmap/supervisor plan/runbook, learning index and the 15 dated manuals.
Historical source excerpts and older feature claims remain associated with their
milestone; they are not rewritten to pretend those features existed earlier.
Local documentation links/anchors, documented commit objects and PowerShell
command-block syntax were checked separately from application execution.

## 2. Findings by severity

Line references to findings below identify the **starting `143ab55` versions**;
current line numbers move as current-checkpoint sections are added.

| Severity | Location at starting checkpoint | Finding and disposition |
| --- | --- | --- |
| Critical/blocking | Backend, frontend and regression review | None identified within the supported fictional localhost scope. This is not an exhaustive production-security guarantee. Baseline passed before optional implementation. |
| Major | Retrieval/API/collection review | None identified. Existing exact scores, ranking, phrase/Boolean semantics, snapshot bytes and collection isolation remained supported by strict tests. |
| Minor/documentation | README.md:29, 34, 189, 524, 739; learnings/README.md:59 | Current overview still named ab6eacc and/or 547 with old 94/33 client counts. Added the approved Premium UI checkpoint and fresh 562 original-suite record; retained the dated 547 record as history. |
| Minor/documentation | README.md:30, 473, 984; PLANNED_FEATURES.md:131; docs/MIDTERM_SUPERVISOR_PLAN_2026-11.md:315, 372; docs/MIDTERM_DEMO_RUNBOOK_2026-11.md:9, 29, 279 | Part 1 documents were still described as awaiting review/integration. Corrected current status to reviewed/integrated at cdc3b50; preparation tasks remain unchecked. |
| Minor/documentation | README.md:218; docs/MIDTERM_DEMO_RUNBOOK_2026-11.md:7 | The accepted Midnight Scholar/adaptive UI was absent from the current architecture/status explanation. Recorded the integrated 143ab55 UI and student-reported Edge acceptance, without adding UI changes. |
| Minor/documentation | learnings/03-01-positional-index.md:388 and learnings/03-02-exact-phrase-search.md:346 | An older README heading rename broke two links to #http-integration-smoke-tests-phase-25a. Restored a compatible explicit anchor in README; neither manual was edited. |
| Optional improvement | src/SearchServer.java startup selector; data/showcase/ | Added exactly one fixed startup choice and an isolated 25-document original fictional corpus. Added dedicated tests rather than changing old golden expectations. |

Known limitations remain: Windows symlink creation is unavailable; no full
screen-reader/device/security audit, relevance benchmark or performance study
was performed. None is represented as a passing test. No unresolved material
application defect was found during this audit.

## 3. Source-grounded invariants

- [TextProcessor](../src/TextProcessor.java) manually scans lowercase ASCII
  letters/digits. Keyword processing removes twelve stop words; complete tokens
  retain them for positions. UTF-8 transport is not full Unicode/Bangla IR.
- [SearchEngine](../src/SearchEngine.java), build at line 28 and snapshot lookup
  at line 71, strictly decodes original UTF-8 bytes, builds fresh structures,
  rejects symlinks and uses NOFOLLOW_LINKS before publishing a successful build.
  Failed builds preserve the previous index and bytes. Returned arrays are
  cloned; HTTP requests do not resolve user names as paths. Concurrent rebuild
  publication is not claimed.
- Keyword scoring at lines 87/133 is raw TF times `ln((N+1)/(df+1))+1`, summed
  over distinct query terms. Stable manual insertion sort (line 100) preserves
  filename ties. Sparse Top-K (line 121) uses a bounded worst-first heap and
  returns the exact unrestricted prefix. No algorithm changed in this task.
- Phrase matching (line 236) aligns complete consecutive normalized positions,
  including stop words. Boolean matching (line 279) intersects AND groups before
  the OR union, rejects unsupported grammar and returns filename order.
- [SearchServer](../src/SearchServer.java), lines 15–28, now chooses only data,
  data/phrase_samples or data/showcase. Binding (line 40) stays 127.0.0.1:8080.
  Exact route checks, strict query/name decoding, safe disposition and raw-byte
  helpers are unchanged. Unknown-route 404/unsupported-method 405 bodies are
  empty; document-not-found 404 and invalid-name 400 responses are generic JSON.
- [app.js](../web/app.js), lines 77–80, still joins AND words inside OR groups.
  Mode values, native GET fallback, keyword-only Top-K, hidden browser scores,
  server ordering, input values, validation and focus behavior are unchanged.
  AbortController/request-ID guards (lines 31–47, 208, 255 onward) protect both
  search and previews. Filenames/source content use safe DOM text operations.
- [styles.css](../web/styles.css), lines 112, 127, 130, retains three/two/one-field
  container-based wrapping with expanding incomplete rows; line 249 honors
  reduced motion. No frontend file changed in this task.
- [highlight.js](../web/highlight.js) keeps actual source offsets, complete phrase
  occurrences and only operands from satisfied Boolean groups. Reader/raw view/
  original downloads remain separate and unchanged.

## 4. Optional collection and measured examples

See the [provenance, full inventory, commands and learning note](../data/showcase/README.md).
There are **25 documents**, each **179–192 whitespace-delimited words** including
title/provenance. Actual engine statistics are **1,336 searchable terms and
3,808 processed tokens**, independently checked against file scanning.

| Example | Actual observation |
| --- | --- |
| Keyword java | 23 matches; first java_programming_practice.txt; first API score 5.400213538367682 |
| Keyword java search | 25 OR-like ranked matches; Top 5: java_programming_practice.txt, algorithms_clinic.txt, search_indexing_notes.txt, phrase_order_exercise.txt, document_access_review.txt |
| Top 3 / 5 / 10 | Exact prefixes of unrestricted ranking; no client sorting/truncation or score display |
| Phrase java search | information_retrieval_studio.txt; java_programming_practice.txt; search_indexing_notes.txt |
| Phrase search java | phrase_order_exercise.txt |
| Phrase in the reading room | library_reading_circle.txt; research_seminar.txt |
| Phrase in reading room | No matches; stop words occupy positions |
| Boolean java AND programming OR computer AND networking | boolean_query_workshop.txt; java_programming_practice.txt; networking_packet_lab.txt; phrase_order_exercise.txt |
| Any mode zzzznomatchingterm | Successful empty results |

The change does not add a browser switch or accept arbitrary directory arguments.
Stop only your own server, verify the port and restart without arguments to
return to the standard six-file collection; `--phrase-demo` still selects nine.
The [standard runbook](MIDTERM_DEMO_RUNBOOK_2026-11.md) keeps its original timed
sequence and adds the showcase as optional, not as a replacement.

## 5. Fresh verification and exact counting

Temurin JDK **25.0.3+9** compiled src/*.java and all tests. The original suite
was executed before changes and again after the implementation. The following
table counts the final execution once; baseline reruns are not added together.

| Group | Passed | Failed | Skipped |
| --- | ---: | ---: | ---: |
| Original Java: baseline / positional / phrase / Boolean / Top-K | 7 + 13 + 18 + 22 + 26 | 0 | 0 |
| Original indexed-document Java | 14 | 0 | 1 |
| Original HTTP default: smoke / phrase / Boolean / Top-K / document / reader | 16 + 13 + 22 + 32 + 55 + 21 = 159 | 0 | 0 |
| Original HTTP phrase-demo: phrase / Boolean / Top-K / document / reader | 22 + 22 + 32 + 61 + 24 = 161 | 0 | 0 |
| Browser model | 107 | 0 | 0 |
| Highlighting/reader model | 35 | 0 | 0 |
| **Original subtotal** | **562** | **0** | **1** |
| New showcase Java | 18 | 0 | 0 |
| New showcase HTTP on default / phrase-demo / showcase | 2 + 2 + 66 = 70 | 0 | 0 |
| **Combined repeatable total** | **650** | **0** | **1** |

Five JavaScript syntax checks and all eight PowerShell test scripts parse.
The new tests cover original bytes of every showcase document, numerical scores,
Top-K score bits, normalized phrase adjacency, stop-word gaps, AND/OR membership,
invalid queries, forbidden paths, unknown names and all three collection boundaries.
Original Java/HTTP/Node assertions were not weakened or deleted. Only the existing
HTTP runner changed to add the third collection and update fixed usage expectations.
Its original executable/PID/command-line, port, loopback and cleanup checks remain.

Fresh final HTTP ownership evidence: verified default PID **25188**, phrase-demo
PID **3220**, showcase PID **15684**, each the configured Temurin java.exe with
the expected SearchServer command and owned 127.0.0.1 listener. Each was verified
again before cleanup. Port 8080 was free afterward. Six unsupported startup
forms and all startup folder/count logs also passed; these supporting guards
are excluded from named-suite totals.

Raw download comparison uses byte arrays/Base64 equality, not just decoded strings.
Generic JSON error checks establish no supplied filesystem path/content disclosure
for the tested cases. Source modification/deletion, defensive copies, malformed
UTF-8 and failed rebuild preservation remain covered by freshly passing original
tests. The symlink fixture alone skipped because Windows reported a missing
creation privilege; no privileges or system settings were changed.

## 6. Review boundaries and remaining work

Exact scope: **7 modified files and 29 new files (36 total)**.

- Modified: README.md, PLANNED_FEATURES.md,
  docs/MIDTERM_SUPERVISOR_PLAN_2026-11.md, docs/MIDTERM_DEMO_RUNBOOK_2026-11.md,
  learnings/README.md, src/SearchServer.java, tests/RunHttpIntegrationTests.ps1.
- Added: tests/ShowcaseCorpusTests.java, tests/ShowcaseHttpTests.ps1, this audit
  record, data/showcase/README.md and the 25 .txt files in its exact inventory.
- No staged changes; ignored compilation/verification artifacts remain under out/.

The original 15 text documents and phrase README were hash-compared to their
starting versions; all bytes are unchanged. All 15 older learning manuals also
remain byte-for-byte unchanged. The UI, shared highlighting module, CLI, indexing
and retrieval classes and original test files (except the additive managed runner)
remain unchanged. No dependencies, upload, authentication, PDF/OCR, metadata/date
filters, fuzzy/semantic retrieval or network deployment were added.

The browser providers returned no targetable graphical browser; the Windows
computer-use helper reported a missing native pipe. Consequently **no new real
browser visual check is claimed**. The student's existing Premium UI acceptance
is student-reported evidence. Student Edge acceptance of showcase queries,
previews, reader and original download remains pending. Automated models/HTTP
checks do not establish a graphical accessibility or device matrix result.

Documentation link/anchor and command-block checks pass, including a compatible
README anchor restored for older manuals: 23 Markdown documents, 322 local
links/anchors, 30 documented PowerShell blocks and 31 referenced full commit IDs
checked. Current checkpoints/counts are distinct
from dated historical evidence. `git diff --check` passes. The working tree is
intentionally dirty on the feature branch with an empty staging area; main and
origin/main remain at the approved starting SHA. **No commit, push or merge.**

## Integration and acceptance addendum — November 10, 2026

The observations above record the original audit before publication; its pending
statuses, branch state and test executions are historical. The approved showcase
commit `28a26ab986351b5d99d29aba26e873e30793c931` was subsequently published,
independently source-reviewed on GitHub and fast-forward integrated into local
main from `143ab553095b95b572ae55016de3850180054fef`, without a merge commit.

The student personally reported that all six showcase Edge scenarios passed on
November 10: java returned 23; java search Top 3 matched the ranked prefix;
phrase java search returned three; phrase search java returned only
phrase_order_exercise.txt; the mixed AND/OR query returned four; and reader,
highlighting and original download worked. These are student-reported manual
checks, not independently automated browser tests.

The complete regression evidence remains 650 passed, 0 failed, 1 Windows symlink
fixture skipped; Java/HTTP were not rerun for this documentation-only closure.
Fresh integration checks passed five JavaScript syntax checks, 107 browser-model
and 35 highlighting/reader checks. The symlink privilege limitation remains.
All three fixed collections are available; showcase stays optional and the
original six/nine-file collections and 15 historical manuals are unchanged.
The application is frozen. Slides, actual rehearsal and viva preparation remain
separate pending work. This addendum records the application commit, not the
separate documentation-only commit that follows it.
