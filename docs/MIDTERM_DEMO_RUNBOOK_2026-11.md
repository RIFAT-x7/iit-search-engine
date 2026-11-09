# Midterm demonstration runbook — November 2026

**Student:** Rifat Al Maruf, BSSE1705; IIT, University of Dhaka; SPL-1

**Midterm:** November 12, 2026

**Frozen application:** `ab6eacc43b9b8424f332304f18729255ef45d3a0`

**Record:** November 9; Phase 3.5D Part 1 draft, awaiting independent review

Use fictional data only, on localhost. No feature development or redesign is
part of this runbook. Slides/speech are planned November 10; rehearsal, code
explanation and viva preparation November 11. Those activities are not completed
by writing this document.

## 1. Evidence and remaining manual work

- **Fresh Codex automated checks, November 9:** 100 Java, 320 HTTP, 94 browser-model
  and 33 highlighting/reader passes = **547 passed, 0 failed**, plus **1 skipped**
  Windows symlink fixture. Five JavaScript syntax checks, seven PowerShell parses
  and Temurin JDK 25.0.3 compilation passed. Both collections were tested.
- **Student-reported manual Microsoft Edge acceptance:** Basic Keyword Search/
  Top-K, exact phrases, grouped Advanced Search, opening/downloading, previews,
  keyword/phrase/Boolean highlights, reader and raw access worked on both collections.
  Codex did not independently perform those manual browser checks.
- Sample query results below were also freshly inspected through the existing
  Java engine on both permanent corpora; that inspection is not another named
  regression suite or an independently performed Edge demonstration.
- **Still pending:** student rehearsal of this timed runbook, slides/speech,
  code/viva practice, independent GitHub documentation acceptance and main
  integration. Branch publication for review does not complete those items. Comprehensive
  keyboard-only, screen-reader, zoom/reflow, slow-network and device-matrix manual
  coverage is unverified; it is not inferred from functional Edge acceptance.
- Symlink creation was denied by Windows privilege policy. Do not report that
  fixture as passed or claim production-grade filesystem security.

## 2. Prerequisites and compilation

Use Windows, IntelliJ IDEA's PowerShell terminal, Git, Microsoft Edge and the
configured Eclipse Temurin **25.0.3+9** JDK. Node.js is required for the JS test
models, not for serving the website. No npm install, Maven, Gradle or extra search
library is required. Commands assume the existing repository-root working directory.

```powershell
Set-Location -LiteralPath 'D:\iit_drive\IIT\spl1\iit-search-engine'
git status --short --branch
$projectJdk = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'
& "$projectJdk\bin\javac.exe" -version
& "$projectJdk\bin\java.exe" -version
& "$projectJdk\bin\javac.exe" -encoding UTF-8 -d out\app src\*.java tests\BaselineTests.java tests\PositionalIndexTests.java tests\PhraseSearchTests.java tests\BooleanSearchTests.java tests\TopKSearchTests.java tests\IndexedDocumentTests.java
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed; do not demonstrate stale classes' }
```

Expected compiler version: `javac 25.0.3`; runtime identifies Temurin 25.0.3+9.
Do not silently use `java`/`javac` from PATH, which previously selected Java 8.
IntelliJ's project/run JDK should match this explicit installation. Compilation
writes ignored `out/app`; source and corpora are not changed.

## 3. Start, open and restart safely

### Default six-document collection

In terminal A at the repository root, first inspect port 8080:

```powershell
$listeners = @([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object { $_.Port -eq 8080 })
if ($listeners.Count) { throw 'Port 8080 occupied. Identify the listener; do not kill an unknown process.' }
& "$projectJdk\bin\java.exe" -cp out\app SearchServer
```

Expected startup lines identify `Collection: data`, `Indexed 6 documents.` and
`Listening on http://127.0.0.1:8080`. Keep that console visible. The command keeps
running while serving. The fixed listener binds to 127.0.0.1, not all interfaces.

Open Edge from terminal B, or type the URL into Microsoft Edge:

```powershell
Start-Process 'microsoft-edge:http://127.0.0.1:8080/'
```

Use `http://127.0.0.1:8080/`, not a `file:` URL for web/index.html. If the Edge
protocol is unavailable, open Edge manually and enter that address.

### Nine-document phrase demo

Press **Ctrl+C in terminal A**, the console of the server you started. Confirm
port 8080 is free with the same listener check, then launch:

```powershell
& "$projectJdk\bin\java.exe" -cp out\app SearchServer --phrase-demo
```

Expected: `Collection: data/phrase_samples`, `Indexed 9 documents.` and the same
localhost address. Refresh the search page in Edge after switching; close old
reader tabs to avoid confusing names from the previous collection. A name unique
to the other collection correctly returns 404. Never run both servers on 8080.

The server loads the selected collection, original-byte snapshots and six fixed
web assets **once at startup**. There is no browser collection selector, upload,
automatic file refresh or HTTP rebuild route. A source file edited/deleted after
startup does not alter its indexed snapshot; restart deliberately to refresh.
Do not edit demonstration files during the frozen midterm work.

If the process does not stop normally, identify its PID, executable, full command
line and ownership before any targeted cleanup. Do not use blanket Java-process
termination or stop an unidentified listener. The test runner enforces these
checks for its own servers; it does not take ownership of your manual server.

## 4. A 3–5 minute supervisor sequence

The times below are a rehearsal target, not a measured or already rehearsed demo.
Compile/test beforehand; begin with the default server and Edge page ready.

| Time | Action and explanation | Expected observation |
| --- | --- | --- |
| 0:00–0:25 | State the goal and boundaries: manually implemented classical IR over fictional text, localhost only. | Explain Java retrieval versus browser presentation; no private IIT data. |
| 0:25–1:00 | Basic Keyword Search: java, All results, then Top 1. | All: java_programming.txt then academic_notice.txt; Top 1: java_programming.txt. No numerical browser scores; source previews appear. |
| 1:00–1:40 | Advanced Search: first group java AND programming; Add OR Group, computer AND networking. Read preview before Search. | Preview is java AND programming OR computer AND networking; results java_programming.txt then networking.txt. Both conditions of each group apply to the whole document. |
| 1:40–2:20 | Open java_programming.txt, inspect highlights; use View Raw Text, return to the reader with Back, then Download Original. | Reader opens safely in a new tab; raw view is plain text; download is the original .txt, without added highlighting markup. |
| 2:20–2:45 | Ctrl+C your default server, check free port, start --phrase-demo, refresh Edge. | Startup shows the independent nine-file collection. |
| 2:45–3:35 | Exact Phrase Search: java search. Open one result to show verified complete-phrase token highlights. | information_retrieval_workshop.txt then java_programming_lab.txt. networking_lab.txt is absent because its words are separated; reverse_order_exercise.txt is absent because order is reversed. |
| 3:35–4:10 | Change phrase to search java, then optionally error error or in the seminar room. | Reverse order gives reverse_order_exercise.txt; repeated/stop-word examples use the table below. |
| 4:10–4:35 | Advanced Search: add an empty AND word or type java python into one field, then correct it. | Clear validation; no malformed Boolean request. Optionally use zzzznomatchingterm in Basic mode for No results found. |
| 4:35–4:55 | Conclude with the regression/evidence boundary and future scope. | 547 automated passes, 1 skipped fixture; Edge functional acceptance is student-reported. PDF/Bangla/date/deployment remain deferred. |

If time is short, omit optional phrase variations and explain them from the table.
Keep working search, reader and original access ahead of visual polish. Do not
claim new algorithms, a measured speedup or production security.

## 5. Exact query/result reference

Results below are in returned order. Type phrase text without quotation marks.
In the enhanced Advanced Search builder, use one operand per field; do not paste
an entire expression into one word input. `AND`/`OR` shown here are generated
between fields/groups or supplied to the direct Boolean API.

### Default: data/ (six files)

| Mode / limit | Input or generated expression | Expected filenames |
| --- | --- | --- |
| Basic / All | java | java_programming.txt; academic_notice.txt |
| Basic / Top 1 | java | java_programming.txt |
| Basic / Top 10 | java | Same two files as All; only two matches exist |
| Exact Phrase | computer networking | networking.txt |
| Exact Phrase | project presentation | academic_notice.txt |
| Advanced | java AND programming | java_programming.txt |
| Advanced, two AND groups | java AND programming OR computer AND networking | java_programming.txt; networking.txt |
| Basic | zzzznomatchingterm | No results found |

A contextual keyword preview comes from the actual text of java_programming.txt,
including matching Java tokens. In its reader, the beginning includes
`Fictional demonstration data.` and `Java programming uses classes and objects.`
The original contains line breaks and further text; no summary is generated.
Exact excerpt boundaries are implementation-dependent around verified tokens,
so do not promise an invented fixed snippet string.

### Phrase demo: data/phrase_samples/ (nine files)

| Mode / limit | Query | Expected filenames |
| --- | --- | --- |
| Basic / All | java | java_programming_lab.txt; information_retrieval_workshop.txt; networking_lab.txt; reverse_order_exercise.txt; seminar_discussion.txt |
| Basic / Top 1 | java | java_programming_lab.txt |
| Exact Phrase | java search | information_retrieval_workshop.txt; java_programming_lab.txt |
| Exact Phrase | search java | reverse_order_exercise.txt |
| Exact Phrase | error error | java_programming_lab.txt |
| Exact Phrase | project presentation | academic_announcement.txt; project_presentation.txt |
| Exact Phrase | in the seminar room | academic_announcement.txt; project_presentation.txt |
| Advanced | java AND programming | java_programming_lab.txt; seminar_discussion.txt |

Phrase results are filename-ordered, not relevance-ranked. The two separated
error occurrences in software_engineering_coursework.txt do not satisfy the
consecutive phrase error error. Phrase normalization retains stop words and
allows punctuation between consecutive normalized words.

## 6. Direct API and original-file examples

Use terminal B while the **default** server is running. These commands inspect
responses independently of the page; they are not a new browser feature.

```powershell
curl.exe --noproxy '*' -i 'http://127.0.0.1:8080/health'
curl.exe --noproxy '*' -i 'http://127.0.0.1:8080/search?q=java&mode=keyword&k=1'
curl.exe --noproxy '*' -i 'http://127.0.0.1:8080/search?q=java%20AND&mode=boolean'
curl.exe --noproxy '*' -i 'http://127.0.0.1:8080/document/view?name=java_programming.txt'
curl.exe --noproxy '*' -i 'http://127.0.0.1:8080/document/download?name=java_programming.txt'
curl.exe --noproxy '*' -i 'http://127.0.0.1:8080/document/view?name=..%2FREADME.md'
```

Expected statuses: **200, 200, 400, 200, 200, 400**. The malformed Boolean
response is `{"error":"Bad request: Missing operand after AND."}`.
The invalid document response is the generic
`{"error":"Bad request: Invalid document name."}`, without filesystem paths.
A valid unknown name such as unknown.txt instead returns 404 JSON. Fixed unknown
routes return empty 404; unsupported methods on known routes return 405 with
Allow: GET. Malformed request targets may be rejected by the JDK before JSON.

Default `java` Top 1 JSON still has filename java_programming.txt and numeric
score **5.541893581161611**. Hiding the browser score does not change this API.
Raw and download return text/plain UTF-8 plus nosniff. Download adds attachment
Content-Disposition with safe filename/UTF-8 filename* encoding; bytes are the
original snapshot. The reader is a separate fixed HTML resource, for example:

```text
http://127.0.0.1:8080/reader.html?name=java_programming.txt&q=java&mode=keyword
```

No browser parameter resolves an arbitrary filesystem path. This prototype
allowlist is not per-user authorization and is unsuitable for private real data.

## 7. Repeatable regression commands

Compile using section 2 first. Stop your manually launched server before the
managed HTTP runner; never make it terminate an existing unidentified process.

```powershell
New-Item -ItemType Directory -Force -Path out\verification-temp | Out-Null
$verificationTemp = Join-Path (Get-Location).Path 'out\verification-temp'
foreach ($suite in @('BaselineTests','PositionalIndexTests','PhraseSearchTests','BooleanSearchTests','TopKSearchTests','IndexedDocumentTests')) {
    & "$projectJdk\bin\java.exe" "-Djava.io.tmpdir=$verificationTemp" -cp out\app $suite
    if ($LASTEXITCODE -ne 0) { throw "Java suite failed: $suite" }
}
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tests\RunHttpIntegrationTests.ps1
if ($LASTEXITCODE -ne 0) { throw 'HTTP suite failed; inspect output and ownership safely' }
foreach ($file in @('web/app.js','web/highlight.js','web/reader.js','tests/BrowserClientTests.js','tests/HighlightPresentationTests.js')) {
    node.exe --check $file
    if ($LASTEXITCODE -ne 0) { throw "JavaScript syntax failed: $file" }
}
node.exe tests\BrowserClientTests.js
if ($LASTEXITCODE -ne 0) { throw 'Browser model failed' }
node.exe tests\HighlightPresentationTests.js
if ($LASTEXITCODE -ne 0) { throw 'Highlighting/reader model failed' }
Get-ChildItem -LiteralPath tests -Filter *.ps1 | ForEach-Object {
    $syntaxTokens = $null; $syntaxErrors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($_.FullName, [ref]$syntaxTokens, [ref]$syntaxErrors) | Out-Null
    if ($syntaxErrors.Count) { throw "PowerShell syntax failed: $($_.Name)" }
}
git diff --check
```

| Suite | Fresh November 9 result |
| --- | ---: |
| Java: baseline / positional / phrase / Boolean / Top-K | 7 / 13 / 18 / 22 / 26 passed |
| Indexed-document Java | 14 passed; 1 symlink fixture skipped |
| HTTP default: original / phrase / Boolean / Top-K / document / reader | 16 / 13 / 22 / 32 / 55 / 21 passed |
| HTTP demo: phrase / Boolean / Top-K / document / reader | 22 / 22 / 32 / 61 / 24 passed |
| Browser model / highlighting-reader | 94 / 33 passed |
| **Combined** | **547 passed, 0 failed, 1 skipped** |

The runner validates executable, command line and loopback listener PID before
owned cleanup; it confirmed port 8080 free after both servers. Startup-argument
and collection-log checks support the run but are excluded from 547. The temporary
seven-case Phase 3.5A Boolean truth table is historical and not double-counted.
No optional synthetic benchmark is required or rerun for this stabilization task.

If CIM/process identity access is denied, stop and request explicit authorization
for the existing safety checks. Do not remove checks, elevate automatically,
change Windows settings or stop an unrelated process. If a regression fails,
stop and diagnose; source/test fixes require new approval in this frozen scope.

## 8. Troubleshooting and final rehearsal checklist

| Symptom | Safe response |
| --- | --- |
| Wrong Java version or class-version error | Use the explicit Temurin path for compiler and runtime; recompile successfully. |
| Missing data/web files | Run from the repository root, including IntelliJ's run working directory. |
| Address already in use | Stop your own confirmed server console; identify any other listener without killing it. |
| Old UI or collection results | Stop your own server, restart with correct flag and refresh Edge; assets are loaded once. |
| Advanced Search validation | One ASCII word per field, no stop words; fill every group or remove unused fields/groups. |
| No phrase match | Check mode, order, full normalized adjacency, spelling and selected collection. |
| Preview unavailable | Keep result usable; check raw view and server connection. Do not invent an excerpt. |
| Other-collection filename returns 404 | Expected isolation; restart the correct collection, then search again. |
| Reader has no marks | Query/mode may be empty, invalid or unverifiable; original text remains readable. |
| Page opens raw JSON | Native fallback is working; use HTTP origin and check script loading for enhanced UI. |
| Symlink test says skipped | Record the privilege limitation; do not change privileges or count it as passed. |

- [ ] Student rehearses the exact sequence and confirms a 3–5 minute duration.
- [ ] Student can explain ranking, AND precedence, positions, snapshots and safe marks.
- [ ] Slides/speech are prepared November 10; code/viva rehearsal November 11.
- [ ] Published documentation branch is independently reviewed before main integration.

Functional Edge acceptance is already student-reported in section 1. The unchecked
items above are future preparation, not a claim that those accepted feature checks
were absent. At the end of a demonstration, stop only your own server with Ctrl+C
and confirm the port is free.

[Project README](../README.md) · [Supervisor plan](MIDTERM_SUPERVISOR_PLAN_2026-11.md) ·
[Consolidated learning manual](../learnings/03-08-phase-35-midterm-features.md)
