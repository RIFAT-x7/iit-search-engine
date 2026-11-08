# Run only against the selected unchanged fictional collection on the owned test server.
param([ValidateSet('default', 'phrase-demo')][string] $Collection = 'default')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
Add-Type -AssemblyName System.Net.Http
$script:passed = 0
$script:failed = 0
$baseUrl = 'http://127.0.0.1:8080'
$routes = @('/document/view', '/document/download')
$folder = if ($Collection -eq 'default') { 'data' } else { 'data/phrase_samples' }
$otherFolder = if ($Collection -eq 'default') { 'data/phrase_samples' } else { 'data' }

function Assert-Check([bool] $Condition, [string] $Message) {
    if (-not $Condition) { throw $Message }
}

function Test-Case([string] $TestLabel, [scriptblock] $Check) {
    try { & $Check; $script:passed++; Write-Host "PASS: $TestLabel" }
    catch { $script:failed++; Write-Host "FAIL: $TestLabel - $($_.Exception.Message)" }
}

function Send-Request([string] $Path, [string] $Method = 'GET') {
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), "$baseUrl$Path")
    $response = $null
    try {
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        return [pscustomobject]@{
            Status = [int] $response.StatusCode
            ContentType = [string] $response.Content.Headers.ContentType
            Bytes = $bytes
            Body = [Text.Encoding]::UTF8.GetString($bytes)
            Nosniff = if ($response.Headers.Contains('X-Content-Type-Options')) { $response.Headers.GetValues('X-Content-Type-Options') -join ', ' } else { '' }
            Disposition = if ($response.Content.Headers.Contains('Content-Disposition')) { $response.Content.Headers.GetValues('Content-Disposition') -join ', ' } else { '' }
            Allow = $response.Content.Headers.Allow -join ', '
        }
    } finally {
        if ($null -ne $response) { $response.Dispose() }
        $request.Dispose()
    }
}

function Assert-Error([string] $Path, [int] $Status) {
    $r = Send-Request $Path
    Assert-Check ($r.Status -eq $Status) "Expected $Status for $Path; got $($r.Status)"
    Assert-Check ($r.ContentType -eq 'application/json; charset=utf-8') 'Expected JSON error'
    $expected = if ($Status -eq 400) { 'Bad request: Invalid document name.' } else { 'Document not found.' }
    $json = $r.Body | ConvertFrom-Json
    Assert-Check (($json.PSObject.Properties.Name -join ',') -ceq 'error') 'Unexpected error schema'
    Assert-Check ($json.error -ceq $expected) 'Error must not disclose supplied paths or filesystem details'
    Assert-Check ($r.Disposition -eq '') 'Errors must not attach a filename'
}

function Assert-RawMalformed([string] $Target) {
    # Preserve literal invalid percent escapes; HttpClient may rewrite them.
    $tcp = [Net.Sockets.TcpClient]::new()
    $reader = $null
    try {
        if (-not $tcp.ConnectAsync('127.0.0.1', 8080).Wait(5000)) { throw 'TCP connection timed out' }
        $stream = $tcp.GetStream()
        $stream.ReadTimeout = 5000
        $stream.WriteTimeout = 5000
        $bytes = [Text.Encoding]::ASCII.GetBytes("GET $Target HTTP/1.1`r`nHost: 127.0.0.1:8080`r`nConnection: close`r`n`r`n")
        $stream.Write($bytes, 0, $bytes.Length)
        $reader = [IO.StreamReader]::new($stream)
        $status = $reader.ReadLine()
        Assert-Check ($status -match '^HTTP/1\.[01] 400(?: |$)') "Expected HTTP-stack 400; got $status"
    } finally {
        if ($null -ne $reader) { $reader.Dispose() }
        $tcp.Dispose()
    }
}

$handler = [Net.Http.HttpClientHandler]::new()
$handler.UseProxy = $false
$handler.AllowAutoRedirect = $false
$client = [Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(5)
try {
    $files = @(Get-ChildItem -LiteralPath $folder -File -Filter *.txt | Sort-Object Name)
    $count = if ($Collection -eq 'default') { 6 } else { 9 }
    Assert-Check ($files.Count -eq $count) 'Unexpected permanent collection fixture count'
    foreach ($file in $files) {
        foreach ($route in $routes) {
            Test-Case "$route exact original bytes for $($file.Name)" {
                $name = [Uri]::EscapeDataString($file.Name)
                $r = Send-Request "${route}?name=$name"
                Assert-Check ($r.Status -eq 200) 'Expected successful document response'
                Assert-Check ($r.ContentType -eq 'text/plain; charset=utf-8') 'Wrong document content type'
                Assert-Check ($r.Nosniff -ceq 'nosniff') 'Missing nosniff'
                $expectedBytes = [IO.File]::ReadAllBytes($file.FullName)
                Assert-Check ([Convert]::ToBase64String($r.Bytes) -ceq [Convert]::ToBase64String($expectedBytes)) 'Original raw bytes changed'
                if ($route -eq '/document/download') {
                    $expectedHeader = 'attachment; filename="' + $file.Name + '"; filename*=UTF-8' + "''" + $name
                    Assert-Check ($r.Disposition -ceq $expectedHeader) "Unexpected attachment header: $($r.Disposition)"
                } else {
                    Assert-Check ($r.Disposition -eq '') 'View must not force attachment'
                }
            }
        }
    }
    Test-Case 'Every filename unique to the other collection stays unavailable' {
        $selectedNames = @($files | ForEach-Object { $_.Name })
        foreach ($other in (Get-ChildItem -LiteralPath $otherFolder -File -Filter *.txt)) {
            if ($selectedNames -cnotcontains $other.Name) {
                foreach ($route in $routes) { Assert-Error "${route}?name=$([Uri]::EscapeDataString($other.Name))" 404 }
            }
        }
    }
    foreach ($name in @('unknown.txt', 'README.md', 'SearchEngine.java', '%2e%2e%2fsecret.txt', 'Java_Programming.txt')) {
        Test-Case "Unindexed exact filename is unavailable: $name" {
            foreach ($route in $routes) { Assert-Error "${route}?name=$([Uri]::EscapeDataString($name))" 404 }
        }
    }
    foreach ($query in @(
        '', '?q=java', '?name=', '?name=+', '?name=%20%20', '?name=a.txt&name=b.txt',
        '?name=a.txt&%6Eame=a.txt', '?name=a.txt&extra=1', '?name=a.txt&', '?name',
        '?Name=a.txt', '?name=a.txt&&name=b.txt', '?name=%FF', '?name=%C3%28',
        '?name=%C0%AF', '?name=%ED%A0%80', '?name=%00', '?name=%0D%0AX-Test%3Ayes.txt',
        '?name=%7F', '?name=%C2%85', '?name=.', '?name=..', '?name=../secret.txt',
        '?name=..%2Fsecret.txt', '?name=..%5Csecret.txt', '?name=%2Fetc%2Fpasswd',
        '?name=C%3A%5CWindows%5Cwin.ini', '?name=C%3Asecret.txt', '?name=%5C%5Cserver%5Cshare',
        '?name=notes.txt%3Asecret', '?name=data%2Fjava_programming.txt'
    )) {
        Test-Case "Invalid document request returns safe 400: $query" {
            foreach ($route in $routes) { Assert-Error "$route$query" 400 }
        }
    }
    Test-Case 'Double-encoded traversal is decoded once and never resolves a path' {
        foreach ($route in $routes) {
            Assert-Error "${route}?name=%252e%252e%252fsecret.txt" 404
            Assert-Error "${route}?name=%252e%252e%255csecret.txt" 404
        }
    }
    Test-Case 'Known document routes reject unsupported methods before query validation' {
        foreach ($route in $routes) {
            foreach ($method in @('POST', 'PUT', 'DELETE', 'HEAD')) {
                $r = Send-Request $route $method
                Assert-Check ($r.Status -eq 405 -and $r.Allow -eq 'GET') 'Expected 405 and Allow: GET'
            }
        }
    }
    Test-Case 'Unknown and suffixed routes retain 404 path precedence' {
        foreach ($path in @('/document', '/document/view/', '/document/download/extra', '/document/unknown')) {
            foreach ($method in @('GET', 'POST')) {
                $r = Send-Request $path $method
                Assert-Check ($r.Status -eq 404) 'Unknown path must be 404'
            }
        }
    }
    foreach ($suffix in @('%', '%GG')) {
        Test-Case "Malformed literal percent escape is rejected by the HTTP stack: $suffix" {
            foreach ($route in $routes) { Assert-RawMalformed "${route}?name=$suffix" }
        }
    }
    Test-Case 'Document errors leave health, ranked scores and direct Boolean API intact' {
        Assert-Check ((Send-Request '/health').Body -ceq 'OK') 'Health changed'
        $keyword = (Send-Request '/search?q=java&k=1').Body | ConvertFrom-Json
        Assert-Check ($keyword.results.Count -eq 1) 'Top-K result count changed'
        $score = $keyword.results[0].score
        Assert-Check ($score -is [double] -or $score -is [int] -or $score -is [long] -or $score -is [decimal]) 'Score must be a JSON number'
        $unrestricted = (Send-Request '/search?q=java').Body | ConvertFrom-Json
        Assert-Check ($keyword.results[0].filename -ceq $unrestricted.results[0].filename) 'Top-K ranking prefix changed'
        Assert-Check ([Math]::Abs([double] $score - [double] $unrestricted.results[0].score) -le 1e-12) 'Top-K score value changed'
        $boolean = Send-Request '/search?q=java%20AND%20programming&mode=boolean'
        Assert-Check ($boolean.Status -eq 200) 'Direct Boolean request failed'
    }
} finally {
    $client.Dispose()
    $handler.Dispose()
}
Write-Host "Document collection: $Collection; total passed: $script:passed; total failed: $script:failed"
if ($script:failed -gt 0) { exit 1 }
