# Optional fictional showcase collection

## Purpose and provenance

These 25 English text documents were written originally for this repository
during the November 10, 2026 final audit, with AI assistance under the student's
explicit corpus instruction. Every scenario is invented. None is an official
IIT notice, real policy, private student record, copied course handout or external
download. Their descriptions of possible workshops/rehearsals are fictional
content, not evidence that those events or tests occurred.

This collection gives the existing classical engine more vocabulary and varied
matches for demonstrations. It is not a judged relevance dataset, benchmark,
proof of retrieval quality or permission to ingest real documents. The original
six-file and nine-file collections are unchanged and remain the standard demo.

## Start and return safely

Use PowerShell at the repository root. Stop only your own existing server using
its console Ctrl+C or IntelliJ Stop, then check for listeners. Never kill an
unknown process to make the port available.

```powershell
$projectJdk = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'
& "$projectJdk\bin\javac.exe" -encoding UTF-8 -d out\app src\*.java tests\*.java
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
$listeners = @([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object { $_.Port -eq 8080 })
if ($listeners.Count) { throw 'Port 8080 occupied. Identify the listener; do not stop an unknown process.' }
& "$projectJdk\bin\java.exe" -cp out\app SearchServer --showcase
```

Expected log: `Collection: data/showcase`, `Indexed 25 documents.`, then
`Listening on http://127.0.0.1:8080`. Open that address in Microsoft Edge.
There is no browser/API collection selector. Do not pass a folder path.
Only no arguments, exactly `--phrase-demo`, or exactly `--showcase` are accepted;
multiple flags and path arguments are rejected with exit status 1.

To return: Ctrl+C your showcase process, check port 8080 again, then use:

```powershell
# Default: six immediate data/*.txt files.
& "$projectJdk\bin\java.exe" -cp out\app SearchServer
# Alternatively, after stopping the default server: nine phrase samples.
# & "$projectJdk\bin\java.exe" -cp out\app SearchServer --phrase-demo
```

Refresh the page and close reader tabs from the prior collection. Only one
collection is indexed per server. Loading is not recursive; README.md is not
indexed. Original bytes and the fixed frontend assets are loaded at startup.
Changed/deleted source files do not change the snapshot until a successful
rebuild/restart. No HTTP rebuild exists.

## Measured statistics and inventory

Measured with the actual SearchEngine and independently checked by the new
Java suite: **25 documents, 1,336 unique searchable terms, 3,808 processed tokens**.
Processed tokens exclude the twelve keyword stop words. Normalized positional
tokens include those stop words and have a different count. Whitespace-delimited
word counts below include titles/provenance; they are not IR token counts.

| File | Words | Main purpose |
| --- | ---: | --- |
| [algorithms_clinic.txt](algorithms_clinic.txt) | 183 | Linear/binary search and ordering assumptions |
| [api_contract_review.txt](api_contract_review.txt) | 192 | JSON, errors and score compatibility |
| [boolean_query_workshop.txt](boolean_query_workshop.txt) | 187 | AND groups and OR alternatives |
| [cybersecurity_tabletop.txt](cybersecurity_tabletop.txt) | 188 | Path, header and rendering boundaries |
| [data_structures_lab.txt](data_structures_lab.txt) | 191 | Lists, maps, sets and bounded heaps |
| [database_catalogue.txt](database_catalogue.txt) | 192 | Keys, normalization and text lookup |
| [document_access_review.txt](document_access_review.txt) | 187 | Snapshots, reader, raw view and download |
| [ethics_and_permissions.txt](ethics_and_permissions.txt) | 186 | Rights and authorization decisions |
| [heap_ranking_notes.txt](heap_ranking_notes.txt) | 189 | Ranking prefixes and scoring costs |
| [information_retrieval_studio.txt](information_retrieval_studio.txt) | 190 | Comparing retrieval modes |
| [java_programming_practice.txt](java_programming_practice.txt) | 179 | Java, UTF-8 and request tracing |
| [laboratory_guidelines.txt](laboratory_guidelines.txt) | 186 | Reproducible environment and process safety |
| [library_reading_circle.txt](library_reading_circle.txt) | 181 | Reading context and relevance judgment |
| [networking_packet_lab.txt](networking_packet_lab.txt) | 184 | Networking and separated query words |
| [object_oriented_design.txt](object_oriented_design.txt) | 186 | Responsibilities and encapsulation |
| [operating_systems_notes.txt](operating_systems_notes.txt) | 188 | Processes, file snapshots and permissions |
| [phrase_order_exercise.txt](phrase_order_exercise.txt) | 187 | Reversed order and stop-word positions |
| [project_planning_board.txt](project_planning_board.txt) | 186 | Milestone review and demonstration fallback |
| [prototype_demonstration.txt](prototype_demonstration.txt) | 190 | Honest demonstration sequence |
| [requirements_interview.txt](requirements_interview.txt) | 188 | Observable acceptance criteria |
| [research_seminar.txt](research_seminar.txt) | 185 | Reproducibility versus relevance evaluation |
| [search_indexing_notes.txt](search_indexing_notes.txt) | 190 | Index building and collection isolation |
| [software_testing_workshop.txt](software_testing_workshop.txt) | 190 | Test layers and skipped evidence |
| [student_workshop.txt](student_workshop.txt) | 187 | Accessible adaptive word fields |
| [version_control_review.txt](version_control_review.txt) | 191 | Scoped publication and integration |

## Reproducible query examples

The following results were measured through the actual engine and checked over
HTTP; they are not inferred from titles. Results listed are in returned order.
Use the corresponding browser mode; ordinary quotes are unnecessary for phrases.

### Ranked keyword search

`java` matches **23** documents. Its first ten filenames are:

1. java_programming_practice.txt
2. phrase_order_exercise.txt
3. boolean_query_workshop.txt
4. laboratory_guidelines.txt
5. search_indexing_notes.txt
6. algorithms_clinic.txt
7. api_contract_review.txt
8. cybersecurity_tabletop.txt
9. data_structures_lab.txt
10. database_catalogue.txt

All returns 23; Top 3/5/10 returns the corresponding exact prefix. The API's first
score is `5.400213538367682`, while browser scores remain hidden. These values
depend on this exact corpus. No score threshold or relevance percentage is used.

`java search` is OR-like ranked keyword retrieval and matches **25** documents.
Its Top 5 is java_programming_practice.txt, algorithms_clinic.txt,
search_indexing_notes.txt, phrase_order_exercise.txt, document_access_review.txt.
The first score is `6.713871097222723`. Matching only one query term can still
make a document eligible; this is not strict AND.

`networking security` matches **6**: networking_packet_lab.txt,
boolean_query_workshop.txt, database_catalogue.txt, cybersecurity_tabletop.txt,
operating_systems_notes.txt, software_testing_workshop.txt. `software` matches
all 25, with varying raw term frequencies and filename tie-breaking.

### Phrase and Boolean comparisons

| Mode | Query | Exact filenames |
| --- | --- | --- |
| Phrase | java search | information_retrieval_studio.txt; java_programming_practice.txt; search_indexing_notes.txt |
| Phrase | search java | phrase_order_exercise.txt |
| Phrase | java programming | boolean_query_workshop.txt; java_programming_practice.txt |
| Phrase | java and programming | boolean_query_workshop.txt; phrase_order_exercise.txt |
| Phrase | in the reading room | library_reading_circle.txt; research_seminar.txt |
| Phrase | in reading room | None: the stop-word gap is significant |
| Advanced | java AND programming | boolean_query_workshop.txt; java_programming_practice.txt; phrase_order_exercise.txt |
| Advanced | networking OR security | boolean_query_workshop.txt; cybersecurity_tabletop.txt; database_catalogue.txt; networking_packet_lab.txt; operating_systems_notes.txt; software_testing_workshop.txt |
| Advanced | java AND programming OR computer AND networking | boolean_query_workshop.txt; java_programming_practice.txt; networking_packet_lab.txt; phrase_order_exercise.txt |
| Any mode | zzzznomatchingterm | None |

Networking_packet_lab.txt contains java and search at separated positions, so
it qualifies for `java AND search` but not the exact phrase `java search`.
For the mixed Advanced example, enter java/programming in group 1 and
computer/networking in group 2. The builder sends the displayed expression
without parentheses through existing `mode=boolean`; no ranking is added.

Open java_programming_practice.txt from results to inspect source previews and
the highlighted reader. Raw/view/download contracts are unchanged, for example:

```text
/document/view?name=java_programming_practice.txt
/document/download?name=java_programming_practice.txt
/reader.html?name=java_programming_practice.txt&q=java+search&mode=phrase
```

Only snapshots from this collection can be served. Names from default/demo,
README.md and unknown valid filenames give 404 JSON; invalid path syntax gives
400 JSON. Downloads are exact original bytes, not highlighted HTML.

## Dedicated checks

After compilation and with no manual server listening:

```powershell
& "$projectJdk\bin\java.exe" -cp out\app ShowcaseCorpusTests
if ($LASTEXITCODE -ne 0) { throw 'Showcase Java failed' }
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tests\RunHttpIntegrationTests.ps1
if ($LASTEXITCODE -ne 0) { throw 'Managed HTTP failed' }
```

Fresh results: showcase Java **18/18**; showcase HTTP **2/2** on default,
**2/2** on phrase-demo and **66/66** on showcase (70 total). The managed runner
retains every original HTTP suite (320 checks) and verifies executable, command
line, PID and loopback ownership before stopping only its own processes. Original
regression is separately **562/562**, with one skipped Windows symlink fixture.
The combined count is **650 passed, zero failed, one skipped**, not a relevance
benchmark. Supporting startup-argument/log guards are excluded from these counts.

Student Edge acceptance of this optional collection is pending independent review.
Prior acceptance of the Premium UI does not prove acceptance of these new examples.

## Learning note: collection selection and adaptive fields

The startup selector chooses one fixed directory before calling the same
`SearchEngine.buildIndex`. Nothing in query parameters selects a directory.
An inverted index changes when its input changes; the retrieval algorithm and
formula do not. More documents can change IDF, scores and rankings naturally.
Preserving old datasets preserves their old expectations.

Premium Advanced Search stores arrays such as `[["java", "programming"],
["computer", "networking"]]`. Inner arrays join with AND; groups join with OR.
CSS flex-wrap limits wide rows to three equal fields. Flex-grow fills the last
row (four fields become 3+1; five become 3+2). Container-width rules switch to
two, then one. This is a layout decision independent of query order. Adding or
removing controls rerenders labels and restores focus; typing preserves input.
Entrance motion applies only to added fields and is disabled for reduced motion.
No new parser, ranking method or nested Boolean syntax is introduced.

[Standard demo runbook](../../docs/MIDTERM_DEMO_RUNBOOK_2026-11.md) ·
[Project README](../../README.md) · [Learning index](../../learnings/README.md)
