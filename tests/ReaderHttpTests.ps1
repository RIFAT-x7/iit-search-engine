# Fixed reader assets are presentation only; document access remains an indexed allowlist lookup.
param([ValidateSet('default', 'phrase-demo')][string] $Collection = 'default')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
Add-Type -AssemblyName System.Net.Http
$script:passed = 0
$script:failed = 0
$folder = if ($Collection -eq 'default') { 'data' } else { 'data/phrase_samples' }
$otherFolder = if ($Collection -eq 'default') { 'data/phrase_samples' } else { 'data' }
function Assert-Check([bool] $Condition, [string] $Message) { if (-not $Condition) { throw $Message } }
function Test-Case([string] $TestLabel, [scriptblock] $Check) {
    try { & $Check; $script:passed++; Write-Host "PASS: $TestLabel" }
    catch { $script:failed++; Write-Host "FAIL: $TestLabel - $($_.Exception.Message)" }
}
function Send-Request([string] $RequestPath, [string] $Method = 'GET') {
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), "http://127.0.0.1:8080$RequestPath")
    $response = $null
    try {
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        return [pscustomobject]@{
            Status = [int] $response.StatusCode
            ContentType = [string] $response.Content.Headers.ContentType
            Bytes = $bytes
            Body = [Text.Encoding]::UTF8.GetString($bytes)
            Allow = $response.Content.Headers.Allow -join ', '
            Nosniff = if ($response.Headers.Contains('X-Content-Type-Options')) { $response.Headers.GetValues('X-Content-Type-Options') -join ', ' } else { '' }
            Disposition = if ($response.Content.Headers.Contains('Content-Disposition')) { $response.Content.Headers.GetValues('Content-Disposition') -join ', ' } else { '' }
        }
    } finally {
        if ($null -ne $response) { $response.Dispose() }
        $request.Dispose()
    }
}
$handler = [Net.Http.HttpClientHandler]::new()
$handler.UseProxy = $false
$handler.AllowAutoRedirect = $false
$client = [Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(5)
try {
    foreach ($asset in @(
        @('/reader.html', 'text/html; charset=utf-8', 'web/reader.html'),
        @('/reader.js', 'text/javascript; charset=utf-8', 'web/reader.js'),
        @('/highlight.js', 'text/javascript; charset=utf-8', 'web/highlight.js')
    )) {
        Test-Case "Fixed asset $($asset[0]) has exact bytes and correct type" {
            $r = Send-Request $asset[0]
            Assert-Check ($r.Status -eq 200) 'Expected successful static asset response'
            Assert-Check ($r.ContentType -ceq $asset[1]) "Unexpected MIME type: $($r.ContentType)"
            $expected = [IO.File]::ReadAllBytes((Join-Path (Get-Location).Path $asset[2]))
            Assert-Check ([Convert]::ToBase64String($r.Bytes) -ceq [Convert]::ToBase64String($expected)) 'Static asset bytes changed'
        }
        Test-Case "Fixed asset $($asset[0]) rejects unsupported methods" {
            foreach ($method in @('POST', 'PUT', 'DELETE', 'HEAD')) {
                $r = Send-Request $asset[0] $method
                Assert-Check ($r.Status -eq 405 -and $r.Allow -ceq 'GET') 'Expected 405 with Allow: GET'
            }
        }
    }
    Test-Case 'Reader query parameters cannot choose server-side files or reflect input into HTML' {
        $plain = Send-Request '/reader.html'
        foreach ($query in @('?name=..%2FREADME.md&q=%3Cscript%3E&mode=keyword', '?name=C%3A%5CWindows%5Cwin.ini', '?name=%252e%252e%252fsecret.txt')) {
            $r = Send-Request "/reader.html$query"
            Assert-Check ($r.Status -eq 200 -and $r.Body -ceq $plain.Body) 'Reader must always be the same fixed HTML asset'
        }
    }
    foreach ($path in @('/reader.html/', '/reader.html/secret', '/reader.js/extra', '/highlight.js/', '/web/reader.html')) {
        Test-Case "Unknown reader suffix/path stays 404: $path" {
            foreach ($method in @('GET', 'POST')) { Assert-Check ((Send-Request $path $method).Status -eq 404) 'Unexpected reader path accepted' }
        }
    }
    $files = @(Get-ChildItem -LiteralPath $folder -File -Filter *.txt | Sort-Object Name)
    foreach ($file in $files) {
        Test-Case "Reader source and original download retain exact bytes: $($file.Name)" {
            $encodedName = [Uri]::EscapeDataString($file.Name)
            $originalBytes = [IO.File]::ReadAllBytes($file.FullName)
            foreach ($route in @('/document/view', '/document/download')) {
                $r = Send-Request "${route}?name=$encodedName"
                Assert-Check ($r.Status -eq 200) 'Indexed document unavailable'
                Assert-Check ($r.ContentType -ceq 'text/plain; charset=utf-8' -and $r.Nosniff -ceq 'nosniff') 'Original text headers changed'
                Assert-Check ([Convert]::ToBase64String($r.Bytes) -ceq [Convert]::ToBase64String($originalBytes)) 'Original document bytes altered'
                if ($route -eq '/document/download') {
                    $expectedHeader = 'attachment; filename="' + $file.Name + '"; filename*=UTF-8' + "''" + $encodedName
                    Assert-Check ($r.Disposition -ceq $expectedHeader) 'Original attachment filename changed'
                } else { Assert-Check ($r.Disposition -eq '') 'Raw text should not force a download' }
            }
        }
    }
    Test-Case 'Other collection documents remain unavailable through original endpoints' {
        $selected = @($files | ForEach-Object { $_.Name })
        foreach ($other in (Get-ChildItem -LiteralPath $otherFolder -File -Filter *.txt)) {
            if ($selected -cnotcontains $other.Name) {
                foreach ($route in @('/document/view', '/document/download')) {
                    $r = Send-Request "${route}?name=$([Uri]::EscapeDataString($other.Name))"
                    Assert-Check ($r.Status -eq 404) 'Cross-collection document leaked'
                    Assert-Check ($r.Body -ceq '{"error":"Document not found."}') 'Unexpected path/content disclosure'
                }
            }
        }
    }
    Test-Case 'Reader leaves invalid original identifiers rejected without filesystem disclosure' {
        foreach ($query in @('?name=..%2FREADME.md', '?name=C%3A%5CWindows%5Cwin.ini', '?name=%2Fetc%2Fpasswd', '?name=..%5Csecret.txt', '?name=a.txt&name=b.txt', '?name=a.txt&extra=1')) {
            foreach ($route in @('/document/view', '/document/download')) {
                $r = Send-Request "$route$query"
                Assert-Check ($r.Status -eq 400) 'Invalid name accepted'
                Assert-Check ($r.Body -ceq '{"error":"Bad request: Invalid document name."}') 'Error contains unexpected input or path details'
            }
        }
    }
    Test-Case 'Reader leaves health, search schemas, numeric ranking and Top-K prefix unchanged' {
        $health = Send-Request '/health'
        Assert-Check ($health.Status -eq 200 -and $health.Body -ceq 'OK') 'Health response changed'
        $all = (Send-Request '/search?q=java&mode=keyword').Body | ConvertFrom-Json
        $top = (Send-Request '/search?q=java&mode=keyword&k=1').Body | ConvertFrom-Json
        Assert-Check (($all.PSObject.Properties.Name -join ',') -ceq 'query,results') 'Keyword response schema changed'
        Assert-Check ($top.results.Count -eq 1 -and $top.results[0].filename -ceq $all.results[0].filename) 'Top-K ordering changed'
        $score = $top.results[0].score
        Assert-Check ($score -is [double] -or $score -is [decimal] -or $score -is [int] -or $score -is [long]) 'Numeric score contract changed'
        Assert-Check ([Math]::Abs([double] $score - [double] $all.results[0].score) -le 1e-12) 'Score value changed'
        foreach ($mode in @('phrase', 'boolean')) {
            $q = if ($mode -eq 'phrase') { 'java%20programming' } else { 'java%20AND%20programming' }
            $r = Send-Request "/search?q=$q&mode=$mode"
            Assert-Check ($r.Status -eq 200) "$mode contract failed"
            $json = $r.Body | ConvertFrom-Json
            Assert-Check (($json.PSObject.Properties.Name -join ',') -ceq 'query,mode,results') "$mode schema changed"
        }
    }
} finally { $client.Dispose(); $handler.Dispose() }
Write-Host "Reader collection: $Collection; total passed: $script:passed; total failed: $script:failed"
if ($script:failed -gt 0) { exit 1 }
