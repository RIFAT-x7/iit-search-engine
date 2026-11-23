# Phase 5B demonstration runbook — November 2026

**Student:** Rifat Al Maruf, BSSE1705; IIT, University of Dhaka; SPL-1

**Record:** November 23, 2026; draft for review

**Integrated application:** `0532b2b951286c724cce6ecbaf602da2d5370510`

Use fictional documents only. This runbook demonstrates implemented functionality;
it does not claim that the student has rehearsed it or that the manual checklist
below has passed. The [midterm runbook](MIDTERM_DEMO_RUNBOOK_2026-11.md) preserves
its earlier dated scope. See the [Phase 5 guide](../learnings/04-phase5-live-document-management.md)
for implementation and viva explanations.

## 1. Environment and evidence

- Windows; IntelliJ IDEA **2026.1.4** with a PowerShell terminal.
- Eclipse Temurin **JDK 25.0.3+9**, invoked explicitly; PATH may select Java 8.
- Microsoft Edge; repository root as the working directory.
- Node.js only for optional model/syntax tests, not for serving the application.
- Previously executed full regression: **790 passed, 0 failed, 2 Windows symlink
  fixtures skipped**. Later unchanged-code integration: **613 focused passes**,
  no failures/skips. This documentation task is not another full regression run.
- Prior Codex-controlled **headless Edge** acceptance verified live upload/search,
  replacement, cancelled/confirmed deletion, refresh, lock/reload and widths
  320/390/768/1280 on one Java server. Basic Tab/focus was checked.
- **Pending:** headed Edge/native Windows file-picker usability and the student's
  timed rehearsal. Headless automation and Node DOM/fetch models do not establish
  those manual outcomes or comprehensive accessibility.

## 2. Safe compilation and managed startup

### Terminal A: compile, check port and configure access

```powershell
Set-Location -LiteralPath 'D:\iit_drive\IIT\spl1\iit-search-engine'
git status --short --branch
$projectJdk = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'
& "$projectJdk\bin\javac.exe" -version
& "$projectJdk\bin\java.exe" -version
& "$projectJdk\bin\javac.exe" -encoding UTF-8 -d out\app src\*.java tests\*.java
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed; do not demonstrate stale classes' }
$listeners = @([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object { $_.Port -eq 8080 })
if ($listeners.Count) { throw 'Port 8080 is occupied. Identify the owner; do not stop an unknown process.' }

# Generate a temporary secret without writing its value into shell history/output.
$adminTokenBytes = New-Object byte[] 32
$adminRandom = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $adminRandom.GetBytes($adminTokenBytes) } finally { $adminRandom.Dispose() }
$env:IIT_SEARCH_ADMIN_TOKEN = [BitConverter]::ToString($adminTokenBytes).Replace('-', '').ToLowerInvariant()
[Array]::Clear($adminTokenBytes, 0, $adminTokenBytes.Length)
# Trusted local desktop only: paste into the manager; do not share/screenshot it.
Set-Clipboard -Value $env:IIT_SEARCH_ADMIN_TOKEN
try { & "$projectJdk\bin\java.exe" -cp out\app SearchServer --managed }
finally { Remove-Item Env:\IIT_SEARCH_ADMIN_TOKEN -ErrorAction SilentlyContinue }
```

Keep terminal A visible. Expected log: `Collection: data/managed`, the current
document count, and `Listening on http://127.0.0.1:8080`. The directory is created
if absent; an existing valid collection is loaded, not emptied. `data/managed/`
and private `.iit-store` files are ignored by Git. Do not use this runbook to alter
personal documents or the three tracked demo collections.

The token must be 32–256 ASCII letters/digits/underscores/hyphens; this generator
produces 64 hex characters from 32 random bytes. Never print/store a real token
in documents, Git, URLs, recordings or logs. Clipboard/history/sync can retain
secrets: use only a trusted local machine and clear the clipboard after pasting.
The password field clears after submission; do not save it in a password manager.

### Terminal B and Edge

Type these URLs in Edge (use the exact host, not `localhost`, `file://` or a LAN IP):

- Public Search: **http://127.0.0.1:8080/**
- Document Manager: **http://127.0.0.1:8080/admin.html**

Paste the copied token into the password field and select **Unlock manager**.
After pasting, terminal B can overwrite the clipboard with a harmless value:

```powershell
Set-Clipboard -Value ' '
```

That overwrites the current clipboard; it does not erase clipboard history/sync.
The manager reveals controls only after an authenticated inventory response.
Admin page/assets are unavailable in default, phrase-demo and showcase modes.
The page has no credentials; protected API operations all require the bearer token.

### Stop only your own server

Use **Ctrl+C in terminal A**, the console running this instance. Normal shutdown
stops the HTTP server/workers and closes the store lock; the PowerShell `finally`
removes this shell's token environment variable. Then verify:

```powershell
$remaining = @([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object { $_.Port -eq 8080 })
if ($remaining.Count) { Write-Warning '8080 is still occupied; verify PID, executable and command line before any action.' }
```

If Ctrl+C does not finish, do not repeatedly launch new servers or kill an unknown
Java process. Establish the exact process's PID, executable and command line first.
Never delete the lock file to bypass a live owner. Force termination may interrupt
a mutation and require reconciliation. Lock the browser before stopping normally.
For restart, repeat startup with a freshly generated token; documents are independent
of the token. Web asset edits require restart because assets load at startup.

## 3. Supervisor demonstration: 3–5 minutes, one running server

### Prepare fictional local input files before the timer

Terminal B, repository root: create a unique ignored input folder. This only
prepares local file-picker inputs; it does not modify the managed collection.

```powershell
$demoInput = Join-Path (Get-Location).Path ('out\phase5b-demo-input-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $demoInput -ErrorAction Stop | Out-Null
$demoUtf8 = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText((Join-Path $demoInput 'phase5b_notice.txt'), 'Fictional SPL demonstration. Amberquartz robotics workshop on Monday.', $demoUtf8)
[IO.File]::WriteAllText((Join-Path $demoInput 'revision_local.txt'), 'Fictional SPL demonstration. Cobaltviolet database clinic on Tuesday.', $demoUtf8)
Write-Output $demoInput
```

Both files are valid small UTF-8 text, with distinctive ASCII terms compatible
with the tokenizer. No IIT private information is included. Open that displayed
folder in the native file picker. If `phase5b_notice.txt` already exists in managed
inventory, do not overwrite it: choose a fresh fictional filename and adapt the
sequence. Record the initial inventory and avoid touching unrelated documents.

| Time | Action | Expected evidence to show |
| --- | --- | --- |
| 0:00–0:40 | Show terminal A still running; unlock manager | Inventory loads after authentication; no restart |
| 0:40–1:20 | Upload `phase5b_notice.txt`; wait for confirmed success | Inventory adds the exact name; View text shows original fictional text |
| 1:20–1:50 | Public Search: keyword `amberquartz`; optionally phrase `amberquartz robotics` | New document appears; open its reader/download |
| 1:50–2:50 | Manager: Replace that target, choose `revision_local.txt`, read target and confirm | Managed name remains `phase5b_notice.txt`; new bytes shown |
| 2:50–3:20 | Search again: `amberquartz` then `cobaltviolet` | Old document match disappears; new term appears under retained name |
| 3:20–4:10 | Delete: cancel once and show it remains; delete again and confirm | Inventory removes it; search `cobaltviolet` no longer returns it |
| 4:10–4:40 | Revisit its raw-text URL; Refresh inventory; Lock administration | `/document/view?name=phase5b_notice.txt` returns 404; controls hide |

If other documents happen to contain the query, judge the presence/absence of the
specific demo document, not necessarily the total count. Rerun Search after each
change; an already rendered result list does not push-update itself. Do not restart
the Java server anywhere in this timed lifecycle. Show terminal A unchanged.

Explain: "The store safely changes the disk file, rebuilds the complete index,
and publishes one snapshot. Searches can use the previous snapshot during the
rebuild. Success means the new snapshot is available; this is not per-document
posting-level incremental indexing."

Leave the fictional managed document deleted after confirmed completion. Local
input files are ignored under the unique `out/` folder; remove only your identified
inputs later if desired. Do not recursively clean unrelated folders. These steps
are a rehearsal plan, not a record that the manual demonstration has passed.

## 4. Recovery and troubleshooting

| Symptom | Action and boundary |
| --- | --- |
| Startup fails; missing/invalid token | Regenerate in terminal A before launch; exact allowed format is above. No real token in error output. Also check working directory, assets/storage and port; startup diagnostic is intentionally generic. |
| HTTP 401 | Missing/wrong/malformed bearer token. Unlock with this server's current token; a restarted server may use a new one. Never move the secret into a URL. |
| HTTP 403 | Use exact `http://127.0.0.1:8080`; Host, loopback peer and supplied Origin must match. Do not disable security checks or enable CORS. |
| Invalid UTF-8 or NUL (400) | Save the local file as genuine UTF-8 plain text, not UTF-16/legacy ANSI. Extension alone does not validate content. Choose and submit corrected bytes after authoritative rejection. |
| Unsupported format/media type (415 or client rejection) | One `.txt` file only; no PDF/DOCX or multipart. API callers send raw `text/plain; charset=utf-8`. |
| Duplicate/colliding name (409) | Refresh inventory; use a new name for upload or explicitly select Replace. Case/Unicode-normalization collisions are rejected. No rename feature exists. |
| Missing replace/delete target (404) | Refresh inventory and select a current document. Never assume a stale row still exists. |
| Oversized document (413) | At most 1,048,576 bytes inclusive. Actual stream bytes are bounded even without trustworthy Content-Length. |
| Timeout, network loss, overload or malformed success | Completion may be uncertain. Do not automatically resubmit. Refresh, inspect View text/Search, and acknowledge only after checking. Abort/Lock does not cancel a server mutation reliably. |
| Uncertain replacement | A retained filename proves nothing about old/new content. Compare the actual text and distinctive old/new search terms before deciding on another write. |
| Uncertain deletion | Refresh inventory, verify document absence and raw-text 404, and search again. Do not blindly repeat DELETE. If checks fail, the outcome remains unresolved. |
| Confirmed mutation, inventory refresh failed | The mutation is confirmed; refresh the list before more writes. Do not re-upload/re-delete just because the list refresh failed. |
| Port occupied | Stop launch. Identify the owner; use Ctrl+C only for your own server. Never kill an unidentified listener to free 8080. |
| Store already open/lock held | Close the known owning process normally. A leftover filename is not itself a held OS lock. Do not remove `.iit-store/lock` to bypass ownership. |
| External/offline edits or storage 503 | Stop external editing. Preserve files and staging evidence. The running backend can explicitly `reload()`; there is no HTTP reload endpoint. A controlled restart revalidates visible documents; invalid collections fail startup. |
| Rollback failure/recovery required | Do not keep mutating or delete backups. Preserve evidence, repair the intended visible collection under review, then reconcile/reopen. Old published search data may still work but does not prove disk matches it. |

### Restart source of truth

Visible valid `.txt` files in the managed root are loaded and reindexed on reopen.
Private `next`/`previous` staging/backup files are checked but not replayed; a later
permitted mutation may reclaim them. They are not a durable version history.
An interrupted, unacknowledged change may survive restart. Atomic moves plus one
memory publication are not a crash-atomic disk/index transaction or a power-loss
durability guarantee. Keep the root private to the store while open; its lock does
not prevent arbitrary filesystem programs from writing.

Refresh inventory only reads the published list. It does not reload files or prove
replacement contents. The browser's unresolved-warning state survives Lock within
the page but is lost on full reload/navigation; record an uncertain target yourself
and reconcile it after unlocking again. No automatic retry or rollback is inferred.

## 5. Pending headed Edge acceptance

All boxes are deliberately unchecked. Record actual date, browser version, outcome
and notes during the student's separate manual session; documentation does not
complete acceptance. Use fictional owned files and a disposable token only.

- [ ] Start the compiled managed server with a random token; confirm one running instance.
- [ ] Open the manager in headed Microsoft Edge and unlock; wrong token fails safely.
- [ ] Use keyboard/Tab and native Windows file selection; cancel a picker safely.
- [ ] Upload the fictional file; immediately search/open/download it without restarting.
- [ ] Replace using differently named `revision_local.txt`; verify retained target name,
  old terms absent and new terms present in text and search.
- [ ] Cancel deletion and verify no change; confirm deletion and verify inventory,
  search and original-byte 404.
- [ ] Refresh inventory and test empty/loading states; Lock hides controls and reload
  requires a new unlock. Check Back to Search navigation.
- [ ] Inspect desktop/mobile widths and visible focus; no horizontal overflow. This
  basic check does not substitute for a comprehensive accessibility audit.
- [ ] Separately test restart persistence: upload an owned fictional document, stop
  normally, restart with a fresh token, verify its exact text/search, then delete it.
- [ ] If an interrupted request occurs, reconcile contents and inventory before any
  retry; do not induce failures against personal files just for rehearsal.
- [ ] Lock, safely stop the owned server, clear the token/clipboard, verify port 8080
  is free and no unintended demo documents remain. Never bypass process ownership.

## 6. Optional verification commands

Compilation and full test commands are maintained in the
[README](../README.md#build-run-and-test). Stop your own server before either HTTP
runner; both need a free port and retain process-identity safeguards. The admin
runner uses an owned temporary collection and disposable credentials; do not run
`AdminHttpTests` directly against your normal managed folder.

For a documentation-only update, source/link/command checks and compilation are
sufficient unless a discrepancy needs investigation. Do not call a historical
790-check record a fresh execution. Node browser models, real HTTP tests, headless
Edge automation and headed manual acceptance are four distinct kinds of evidence.
