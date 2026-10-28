# Requires Windows PowerShell 5.1 or later and an already-running SearchServer.
# Run against the unchanged six-file fictional corpus, not real IIT documents.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
Add-Type -AssemblyName System.Net.Http
$baseUrl = 'http://127.0.0.1:8080'
$script:passed = 0
$script:failed = 0

function Assert-Check([bool] $Condition, [string] $Message) {
    if (-not $Condition) { throw $Message }
}

function Test-Case([string] $Name, [scriptblock] $Check) {
    try {
        & $Check
        $script:passed++
        Write-Host "PASS: $Name"
    } catch {
        $script:failed++
        Write-Host "FAIL: $Name - $($_.Exception.Message)"
    }
}

function Send-Request([string] $Path, [string] $Method = 'GET') {
    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::new($Method), "$baseUrl$Path")
    $response = $null
    try {
        # Do not call EnsureSuccessStatusCode: 400/404/405 are testable responses.
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        return [pscustomobject]@{
            Status = [int] $response.StatusCode
            ContentType = [string] $response.Content.Headers.ContentType
            Allow = $response.Content.Headers.Allow -join ', '
            Body = $body
        }
    } catch {
        throw "Request $Method $Path failed. Start SearchServer at $baseUrl. $($_.Exception.Message)"
    } finally {
        if ($null -ne $response) { $response.Dispose() }
        $request.Dispose()
    }
}

function Read-Json($Response, [int] $ExpectedStatus) {
    Assert-Check ($Response.Status -eq $ExpectedStatus) "Expected HTTP $ExpectedStatus, got $($Response.Status)"
    Assert-Check ($Response.ContentType -eq 'application/json; charset=utf-8') 'Expected JSON content type'
    return ($Response.Body | ConvertFrom-Json)
}

function Assert-JsonError([string] $Path) {
    $json = Read-Json (Send-Request $Path) 400
    Assert-Check ($json.error -is [string] -and $json.error.StartsWith('Bad request: ')) 'Expected descriptive JSON error'
}

function Assert-Score($Actual, [double] $Expected) {
    Assert-Check ($Actual -is [double] -or $Actual -is [int] -or $Actual -is [long] -or $Actual -is [decimal]) 'Score must be a JSON number'
    Assert-Check ([Math]::Abs([double] $Actual - $Expected) -le 1e-12) "Unexpected score: $Actual; expected $Expected"
}

function Assert-MalformedRequest([string] $Path) {
    # HttpClient/Uri can escape a literal '%' before sending. Preserve the exact
    # invalid request target with TCP. The JDK HTTP stack rejects it before the
    # application's JSON handler, so only HTTP 400 is required here.
    $tcp = [System.Net.Sockets.TcpClient]::new()
    $reader = $null
    try {
        $connection = $tcp.ConnectAsync('127.0.0.1', 8080)
        if (-not $connection.Wait(5000)) { throw 'Connection timed out' }
        $stream = $tcp.GetStream()
        $stream.ReadTimeout = 5000
        $stream.WriteTimeout = 5000
        $bytes = [Text.Encoding]::ASCII.GetBytes("GET $Path HTTP/1.1`r`nHost: 127.0.0.1:8080`r`nConnection: close`r`n`r`n")
        $stream.Write($bytes, 0, $bytes.Length)
        $reader = [IO.StreamReader]::new($stream)
        $statusLine = $reader.ReadLine()
        Assert-Check ($statusLine -match '^HTTP/1\.[01] 400(?: |$)') "Expected HTTP 400 for raw $Path; got $statusLine"
    } finally {
        if ($null -ne $reader) { $reader.Dispose() }
        $tcp.Dispose()
    }
}

$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.UseProxy = $false
$handler.AllowAutoRedirect = $false
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(5)
try {
    Test-Case 'GET /health: exact OK body' {
        $r = Send-Request '/health'
        Assert-Check ($r.Status -eq 200) 'Expected HTTP 200'
        Assert-Check ($r.Body -ceq 'OK') 'Expected exact OK without a newline'
    }
    Test-Case 'GET /: HTML search page' {
        $r = Send-Request '/'
        Assert-Check ($r.Status -eq 200) 'Expected HTTP 200'
        Assert-Check ($r.ContentType -eq 'text/html; charset=utf-8') 'Expected HTML content type'
        Assert-Check ($r.Body.Contains('<title>IIT Search Engine</title>') -and $r.Body.Contains('<form id="search-form"')) 'Expected IIT search page and form'
    }
    Test-Case 'GET /app.js: JavaScript asset' {
        $r = Send-Request '/app.js'
        Assert-Check ($r.Status -eq 200 -and $r.ContentType -eq 'text/javascript; charset=utf-8') 'Expected HTTP 200 and JavaScript content type'
        Assert-Check (-not [string]::IsNullOrWhiteSpace($r.Body)) 'Script must not be empty'
    }
    Test-Case 'GET /styles.css: CSS asset' {
        $r = Send-Request '/styles.css'
        Assert-Check ($r.Status -eq 200 -and $r.ContentType -eq 'text/css; charset=utf-8') 'Expected HTTP 200 and CSS content type'
        Assert-Check (-not [string]::IsNullOrWhiteSpace($r.Body)) 'Stylesheet must not be empty'
    }
    Test-Case 'GET /search?q=java: JSON ranking and numeric scores' {
        $json = Read-Json (Send-Request '/search?q=java') 200
        Assert-Check ($json.query -ceq 'java') 'Expected decoded query java'
        Assert-Check ($json.results -is [array] -and $json.results.Count -eq 2) 'Expected two results in a JSON array'
        Assert-Check ($json.results[0].filename -ceq 'java_programming.txt') 'Expected java_programming.txt first'
        Assert-Check ($json.results[1].filename -ceq 'academic_notice.txt') 'Expected academic_notice.txt second'
        # Bundled corpus: N=6, df(java)=2, raw TF values 3 and 1.
        $idf = [Math]::Log(7.0 / 3.0) + 1.0
        Assert-Score $json.results[0].score (3.0 * $idf)
        Assert-Score $json.results[1].score $idf
    }
    Test-Case 'Multi-word query: percent-encoded and plus spaces' {
        foreach ($path in @('/search?q=java%20programming', '/search?q=java+programming')) {
            $json = Read-Json (Send-Request $path) 200
            Assert-Check ($json.query -ceq 'java programming') 'Expected decoded multi-word query'
            Assert-Check ($json.results -is [array] -and $json.results.Count -eq 2) 'Expected two results'
            Assert-Check ($json.results[0].filename -ceq 'java_programming.txt' -and $json.results[1].filename -ceq 'academic_notice.txt') 'Unexpected multi-word ranking'
            Assert-Score $json.results[0].score (3.0 * ([Math]::Log(7.0 / 3.0) + 1.0) + [Math]::Log(7.0 / 2.0) + 1.0)
            Assert-Score $json.results[1].score ([Math]::Log(7.0 / 3.0) + 1.0)
        }
    }
    Test-Case 'Unknown keyword: empty JSON results array' {
        $json = Read-Json (Send-Request '/search?q=zzzznomatchingterm') 200
        Assert-Check ($json.results -is [array] -and $json.results.Count -eq 0) 'Expected an empty JSON array'
    }
    Test-Case 'Missing query: JSON 400' { Assert-JsonError '/search' }
    Test-Case 'Empty query: JSON 400' { Assert-JsonError '/search?q=' }
    Test-Case 'Whitespace query: JSON 400' { Assert-JsonError '/search?q=%20%20' }
    Test-Case 'Duplicate q parameters: JSON 400' { Assert-JsonError '/search?q=java&q=network' }
    Test-Case 'Invalid UTF-8 percent encoding: JSON 400' { Assert-JsonError '/search?q=%FF' }
    Test-Case 'Incomplete percent escape: HTTP 400' { Assert-MalformedRequest '/search?q=%' }
    Test-Case 'Invalid percent escape: HTTP 400' { Assert-MalformedRequest '/search?q=%ZZ' }
    Test-Case 'POST /search: HTTP 405 and Allow GET' {
        $r = Send-Request '/search?q=java' 'POST'
        Assert-Check ($r.Status -eq 405 -and $r.Allow -ceq 'GET') 'Expected HTTP 405 and Allow: GET'
    }
    Test-Case 'Unknown path: HTTP 404' {
        $r = Send-Request '/unknown'
        Assert-Check ($r.Status -eq 404) 'Expected HTTP 404'
    }
} finally {
    $client.Dispose()
}

Write-Host "Total passed: $script:passed"
Write-Host "Total failed: $script:failed"
if ($script:failed -gt 0) { exit 1 }
exit 0
