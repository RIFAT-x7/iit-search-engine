# Run against an already-running server; use -Collection phrase-demo for --phrase-demo startup.
param([ValidateSet('default', 'phrase-demo')][string] $Collection = 'phrase-demo')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
Add-Type -AssemblyName System.Net.Http
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

function Send-Request([string] $Path) {
    $response = $client.GetAsync("http://127.0.0.1:8080$Path").GetAwaiter().GetResult()
    try {
        return [pscustomobject]@{
            Status = [int] $response.StatusCode
            ContentType = [string] $response.Content.Headers.ContentType
            Body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        }
    } finally { $response.Dispose() }
}

function Read-Json($Response, [int] $Status = 200) {
    Assert-Check ($Response.Status -eq $Status) "Expected HTTP $Status, got $($Response.Status)"
    Assert-Check ($Response.ContentType -ceq 'application/json; charset=utf-8') 'Expected JSON content type'
    return ($Response.Body | ConvertFrom-Json)
}

function Assert-Error([string] $Path, [string] $Reason) {
    $json = Read-Json (Send-Request $Path) 400
    Assert-Check ($json.error -is [string] -and $json.error.Contains($Reason)) "Expected JSON error containing: $Reason"
}

function Assert-Phrase([string] $Query, [string[]] $Expected = @()) {
    $encoded = [Uri]::EscapeDataString($Query)
    $json = Read-Json (Send-Request "/search?q=$encoded&mode=phrase")
    Assert-Check (($json.PSObject.Properties.Name | Sort-Object) -join ',' -ceq 'mode,query,results') 'Unexpected phrase root properties'
    Assert-Check ($json.query -ceq $Query -and $json.mode -ceq 'phrase') 'Expected decoded query and phrase mode'
    Assert-Check ($json.results -is [array]) 'Expected results array'
    $actual = @($json.results | ForEach-Object {
        Assert-Check (($_.PSObject.Properties.Name -join ',') -ceq 'filename') 'Phrase results must contain only filename, without scores'
        $_.filename
    })
    Assert-Check ($actual.Count -eq $Expected.Count -and ($actual -join '|') -ceq ($Expected -join '|')) "Unexpected phrase filenames/order for [$Query]: $actual"
}

$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.UseProxy = $false
$handler.AllowAutoRedirect = $false
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(5)
try {
    Test-Case 'Missing mode defaults to keyword; explicit keyword JSON is identical' {
        $legacy = Send-Request '/search?q=java'
        $explicit = Send-Request '/search?q=java&mode=keyword'
        $json = Read-Json $legacy
        Read-Json $explicit | Out-Null
        Assert-Check ($legacy.Body -ceq $explicit.Body) 'Default and explicit keyword bodies must be byte-for-byte equal'
        Assert-Check (($json.PSObject.Properties.Name | Sort-Object) -join ',' -ceq 'query,results') 'Keyword root contract changed'
        Assert-Check ($json.results.Count -gt 0) 'Expected java keyword matches'
        foreach ($result in $json.results) {
            Assert-Check (($result.PSObject.Properties.Name | Sort-Object) -join ',' -ceq 'filename,score') 'Keyword result contract changed'
            Assert-Check ($result.score -is [double] -or $result.score -is [int] -or $result.score -is [long] -or $result.score -is [decimal]) 'Expected numeric keyword score'
        }
        if ($Collection -eq 'default') {
            $expected = '{"query":"java","results":[{"filename":"java_programming.txt","score":5.541893581161611},{"filename":"academic_notice.txt","score":1.8472978603872037}]}'
            Assert-Check ($legacy.Body -ceq $expected) 'Exact legacy default-corpus JSON changed'
        }
    }
    Test-Case 'Invalid mode returns descriptive JSON 400' { Assert-Error '/search?q=java&mode=other' 'mode must be keyword or phrase' }
    Test-Case 'Empty mode is invalid' { Assert-Error '/search?q=java&mode=' 'mode must be keyword or phrase' }
    Test-Case 'Mode values are case-sensitive' { Assert-Error '/search?q=java&mode=Phrase' 'mode must be keyword or phrase' }
    Test-Case 'Duplicate mode is rejected, including identical values' {
        Assert-Error '/search?q=java&mode=phrase&mode=phrase' 'Duplicate mode'
        Assert-Error '/search?q=java&mode=keyword&mode=phrase' 'Duplicate mode'
    }
    Test-Case 'Encoded mode names participate in duplicate detection' {
        Assert-Error '/search?q=java&mode=phrase&%6Dode=keyword' 'Duplicate mode'
    }
    Test-Case 'Duplicate q remains invalid in phrase mode' { Assert-Error '/search?q=java&q=search&mode=phrase' 'Duplicate q' }
    Test-Case 'Missing, empty, and blank q remain invalid' {
        Assert-Error '/search?mode=phrase' 'nonblank q'
        Assert-Error '/search?q=&mode=phrase' 'nonblank q'
        Assert-Error '/search?q=+++&mode=phrase' 'nonblank q'
    }
    Test-Case 'Invalid UTF-8 in mode remains invalid' { Assert-Error '/search?q=java&mode=%FF' 'valid UTF-8' }
    Test-Case 'Unknown and punctuation-only phrases return empty arrays' {
        Assert-Phrase 'zzzznomatchingterm'
        Assert-Phrase '!?,--'
    }
    Test-Case 'Mode parameter without name=value remains invalid' { Assert-Error '/search?q=java&mode' 'name=value' }

    if ($Collection -eq 'default') {
        Test-Case 'Default corpus phrase search uses existing documents' {
            Assert-Phrase 'computer networking' @('networking.txt')
            Assert-Phrase 'networking computer'
        }
        Test-Case 'Default corpus has no fictional demo phrase' { Assert-Phrase 'java and java' }
    } else {
        Test-Case 'Adjacent phrase matches with filename-only results in filename order' {
            Assert-Phrase 'java search' @('information_retrieval_workshop.txt', 'java_programming_lab.txt')
        }
        Test-Case 'Reversed wording has different matches' { Assert-Phrase 'search java' @('reverse_order_exercise.txt') }
        Test-Case 'Intervening words prevent a false match' {
            Assert-Phrase 'java for a simple search' @('networking_lab.txt')
            Assert-Phrase 'java simple search'
        }
        Test-Case 'Stop words inside phrases are retained' { Assert-Phrase 'java and java' @('java_programming_lab.txt') }
        Test-Case 'Stop-word-only phrase matches' {
            Assert-Phrase 'in the' @('academic_announcement.txt', 'information_retrieval_workshop.txt', 'project_presentation.txt')
        }
        Test-Case 'Repeated terms require adjacency' {
            Assert-Phrase 'error error' @('java_programming_lab.txt')
            Assert-Phrase 'java java'
        }
        Test-Case 'Case and punctuation normalization' {
            Assert-Phrase 'JAVA,SEARCH!' @('information_retrieval_workshop.txt', 'java_programming_lab.txt')
        }
        Test-Case 'Long phrase returns multiple documents without scores' {
            Assert-Phrase 'in the seminar room' @('academic_announcement.txt', 'project_presentation.txt')
        }
        Test-Case 'Demo collection excludes original default documents' { Assert-Phrase 'computer networking' }
        Test-Case 'URL decoding supports plus spaces and encoded mode value' {
            $r = Send-Request '/search?mode=%70hrase&q=java+search'
            $expected = Send-Request '/search?q=java%20search&mode=phrase'
            Read-Json $r | Out-Null
            Assert-Check ($r.Body -ceq $expected.Body) 'Decoded mode and plus-space query mismatch'
        }
        Test-Case 'Unknown parameters remain ignored after decoding' {
            $r = Send-Request '/search?q=java%20search&mode=phrase&extra=a%26b'
            $expected = Send-Request '/search?q=java%20search&mode=phrase'
            Assert-Check ($r.Body -ceq $expected.Body) 'Unknown parameters changed phrase behavior'
        }
    }
} finally {
    $client.Dispose()
    $handler.Dispose()
}
Write-Host "Collection: $Collection; total passed: $script:passed; total failed: $script:failed"
if ($script:failed -gt 0) { exit 1 }
exit 0
