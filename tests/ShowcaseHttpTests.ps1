# Run by the ownership-checked runner, never starts/stops processes itself.
param([ValidateSet('default', 'phrase-demo', 'showcase')][string] $Collection = 'showcase')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
Add-Type -AssemblyName System.Net.Http
$script:passed = 0
$script:failed = 0
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
            Status = [int] $response.StatusCode; Bytes = $bytes
            Body = [Text.Encoding]::UTF8.GetString($bytes)
            ContentType = [string] $response.Content.Headers.ContentType
            Nosniff = if ($response.Headers.Contains('X-Content-Type-Options')) { $response.Headers.GetValues('X-Content-Type-Options') -join ', ' } else { '' }
            Disposition = if ($response.Content.Headers.Contains('Content-Disposition')) { $response.Content.Headers.GetValues('Content-Disposition') -join ', ' } else { '' }
            Allow = $response.Content.Headers.Allow -join ', '
        }
    } finally { if ($null -ne $response) { $response.Dispose() }; $request.Dispose() }
}
function Read-Search([string] $Query, [string] $Mode = 'keyword', [string] $Limit = '') {
    $suffix = if ($Limit) { "&k=$Limit" } else { '' }
    $r = Send-Request "/search?q=$([Uri]::EscapeDataString($Query))&mode=$Mode$suffix"
    Assert-Check ($r.Status -eq 200 -and $r.ContentType -ceq 'application/json; charset=utf-8') 'Search contract'
    return ($r.Body | ConvertFrom-Json)
}
function Assert-Names($Results, [string[]] $Expected) {
    $actual = @($Results | ForEach-Object { $_.filename })
    Assert-Check (($actual -join '|') -ceq ($Expected -join '|')) "Unexpected filenames: $($actual -join ', ')"
}
function Assert-DocumentError([string] $RequestPath, [int] $ExpectedStatus) {
    $r = Send-Request $RequestPath
    $expectedBody = if ($ExpectedStatus -eq 400) { '{"error":"Bad request: Invalid document name."}' } else { '{"error":"Document not found."}' }
    Assert-Check ($r.Status -eq $ExpectedStatus -and $r.Body -ceq $expectedBody) 'Unsafe identifier response or disclosure'
    Assert-Check ($r.ContentType -ceq 'application/json; charset=utf-8' -and -not $r.Disposition) 'Error headers'
}
$handler = [Net.Http.HttpClientHandler]::new()
$handler.UseProxy = $false
$handler.AllowAutoRedirect = $false
$client = [Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(5)
try {
    $files = @(Get-ChildItem -LiteralPath data/showcase -File -Filter *.txt | Sort-Object Name)
    Assert-Check ($files.Count -eq 25) 'Showcase fixture count must be exactly 25'
    if ($Collection -ne 'showcase') {
        Test-Case 'Original collection does not serve any showcase snapshot' {
            foreach ($file in $files) {
                foreach ($route in @('/document/view', '/document/download')) {
                    Assert-DocumentError "${route}?name=$([Uri]::EscapeDataString($file.Name))" 404
                }
            }
        }
        Test-Case 'Original search excludes showcase-only vocabulary' {
            foreach ($mode in @('keyword', 'phrase', 'boolean')) {
                $json = Read-Search 'tabletop' $mode
                Assert-Check (@($json.results).Count -eq 0) 'Nested showcase was indexed into an original collection'
            }
        }
    } else {
        foreach ($file in $files) {
            foreach ($route in @('/document/view', '/document/download')) {
                Test-Case "$route preserves original showcase bytes: $($file.Name)" {
                    $encoded = [Uri]::EscapeDataString($file.Name)
                    $r = Send-Request "${route}?name=$encoded"
                    Assert-Check ($r.Status -eq 200 -and $r.ContentType -ceq 'text/plain; charset=utf-8' -and $r.Nosniff -ceq 'nosniff') 'Original text headers'
                    Assert-Check ([Convert]::ToBase64String($r.Bytes) -ceq [Convert]::ToBase64String([IO.File]::ReadAllBytes($file.FullName))) 'Original bytes changed'
                    if ($route -eq '/document/download') {
                        $expectedHeader = 'attachment; filename="' + $file.Name + '"; filename*=UTF-8' + "''" + $encoded
                        Assert-Check ($r.Disposition -ceq $expectedHeader) 'Attachment header'
                    } else { Assert-Check (-not $r.Disposition) 'Raw view forced attachment' }
                }
            }
        }
        Test-Case 'Keyword schema, measured Java ranking and numeric score remain correct' {
            $json = Read-Search 'java'
            Assert-Check (($json.PSObject.Properties.Name -join ',') -ceq 'query,results') 'Keyword schema'
            Assert-Check ($json.results.Count -eq 23) 'Java match count'
            Assert-Names $json.results[0..4] @('java_programming_practice.txt','phrase_order_exercise.txt','boolean_query_workshop.txt','laboratory_guidelines.txt','search_indexing_notes.txt')
            $score = $json.results[0].score
            Assert-Check ($score -is [double] -or $score -is [decimal] -or $score -is [int] -or $score -is [long]) 'Score must be numeric'
            Assert-Check ([Math]::Abs([double]$score - 5.400213538367682) -le 1e-12) 'Measured Java score'
            Assert-Check (($json.results[0].PSObject.Properties.Name -join ',') -ceq 'filename,score') 'Result schema'
        }
        foreach ($limit in @(1,3,5,10,100)) {
            Test-Case "Showcase Top $limit is the exact full-response prefix" {
                foreach ($query in @('java','software','java search','networking security')) {
                    $all = Read-Search $query
                    $top = Read-Search $query 'keyword' ([string]$limit)
                    Assert-Check ($top.results.Count -eq [Math]::Min($limit,$all.results.Count)) 'Prefix count'
                    for ($i = 0; $i -lt $top.results.Count; $i++) {
                        Assert-Check ($top.results[$i].filename -ceq $all.results[$i].filename) 'Prefix filename'
                        Assert-Check ([BitConverter]::DoubleToInt64Bits([double]$top.results[$i].score) -eq [BitConverter]::DoubleToInt64Bits([double]$all.results[$i].score)) 'Prefix score bits'
                    }
                }
            }
        }
        Test-Case 'Complete and reversed phrases have measured distinct matches' {
            $json = Read-Search 'java search' 'phrase'
            Assert-Check (($json.PSObject.Properties.Name -join ',') -ceq 'query,mode,results' -and $json.mode -ceq 'phrase') 'Phrase schema'
            Assert-Names $json.results @('information_retrieval_studio.txt','java_programming_practice.txt','search_indexing_notes.txt')
            Assert-Names (Read-Search 'search java' 'phrase').results @('phrase_order_exercise.txt')
            Assert-Check (($json.results[0].PSObject.Properties.Name -join ',') -ceq 'filename') 'Unexpected phrase score'
        }
        Test-Case 'Stop words retain positional significance' {
            Assert-Names (Read-Search 'in the reading room' 'phrase').results @('library_reading_circle.txt','research_seminar.txt')
            Assert-Names (Read-Search 'in reading room' 'phrase').results @()
        }
        Test-Case 'Two AND groups preserve Boolean precedence and filename ordering' {
            $json = Read-Search 'java AND programming OR computer AND networking' 'boolean'
            Assert-Check ($json.mode -ceq 'boolean') 'Boolean mode'
            Assert-Names $json.results @('boolean_query_workshop.txt','java_programming_practice.txt','networking_packet_lab.txt','phrase_order_exercise.txt')
            Assert-Check (($json.results[0].PSObject.Properties.Name -join ',') -ceq 'filename') 'Unexpected Boolean score'
            Assert-Names (Read-Search 'networking OR security' 'boolean').results @('boolean_query_workshop.txt','cybersecurity_tabletop.txt','database_catalogue.txt','networking_packet_lab.txt','operating_systems_notes.txt','software_testing_workshop.txt')
        }
        Test-Case 'Unknown queries are successful empty lists in every mode' {
            foreach ($mode in @('keyword','phrase','boolean')) { Assert-Names (Read-Search 'zzzznomatchingterm' $mode).results @() }
        }
        Test-Case 'Both original collections are unavailable from showcase' {
            foreach ($folder in @('data','data/phrase_samples')) {
                foreach ($original in Get-ChildItem -LiteralPath $folder -File -Filter *.txt) {
                    foreach ($route in @('/document/view','/document/download')) { Assert-DocumentError "${route}?name=$([Uri]::EscapeDataString($original.Name))" 404 }
                }
            }
        }
        Test-Case 'Unindexed showcase README, unknown and double-encoded names are not found' {
            foreach ($name in @('README.md','unknown.txt','%2e%2e%2fsecret.txt')) {
                foreach ($route in @('/document/view','/document/download')) { Assert-DocumentError "${route}?name=$([Uri]::EscapeDataString($name))" 404 }
            }
        }
        Test-Case 'Traversal, absolute paths and invalid parameter structures are rejected' {
            foreach ($query in @('', '?name=', '?name=+', '?name', '?name=a.txt&name=b.txt', '?name=a.txt&extra=1', '?name=../README.md', '?name=..%2FREADME.md', '?name=..%5CREADME.md', '?name=C%3A%5CWindows%5Cwin.ini', '?name=%2Fetc%2Fpasswd', '?name=%FF', '?name=%0D%0AX-Test%3Ayes')) {
                foreach ($route in @('/document/view','/document/download')) { Assert-DocumentError "$route$query" 400 }
            }
        }
        Test-Case 'Known routes reject unsupported methods; unknown suffixes remain 404' {
            foreach ($route in @('/search','/document/view','/document/download','/reader.html')) {
                $r = Send-Request $route 'POST'
                Assert-Check ($r.Status -eq 405 -and $r.Allow -ceq 'GET') 'Method rejection'
                Assert-Check ((Send-Request "$route/extra").Status -eq 404) 'Unknown suffix'
            }
        }
        Test-Case 'Reader and scripts remain fixed assets and health remains OK' {
            Assert-Check ((Send-Request '/health').Body -ceq 'OK') 'Health'
            foreach ($asset in @(@('/reader.html','web/reader.html'),@('/reader.js','web/reader.js'),@('/highlight.js','web/highlight.js'))) {
                $r = Send-Request "$($asset[0])?name=..%2FREADME.md"
                $local = [IO.File]::ReadAllBytes((Join-Path (Get-Location).Path $asset[1]))
                Assert-Check ($r.Status -eq 200 -and [Convert]::ToBase64String($r.Bytes) -ceq [Convert]::ToBase64String($local)) 'Fixed asset contract'
            }
        }
        Test-Case 'Unsupported Boolean grammar and nonkeyword K remain rejected' {
            foreach ($path in @('/search?q=%28java%29&mode=boolean','/search?q=java%20AND&mode=boolean','/search?q=java&mode=phrase&k=3','/search?q=java&mode=boolean&k=3')) {
                $r = Send-Request $path
                Assert-Check ($r.Status -eq 400 -and $r.ContentType -ceq 'application/json; charset=utf-8') 'Invalid query accepted'
                Assert-Check (($r.Body | ConvertFrom-Json).error.StartsWith('Bad request: ')) 'Error schema'
            }
        }
    }
} finally { $client.Dispose(); $handler.Dispose() }
Write-Host "Showcase HTTP collection: $Collection; total passed: $script:passed; total failed: $script:failed"
if ($script:failed -gt 0) { exit 1 }
