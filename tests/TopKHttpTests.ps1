# Targets an already-running localhost server; the integration runner owns its lifecycle.
param([ValidateSet('default', 'phrase-demo')][string] $Collection = 'default')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
Add-Type -AssemblyName System.Net.Http
$script:passed = 0
$script:failed = 0
$numericError = 'k must be a decimal integer from 1 to 2147483647.'

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

function Read-Keyword($Response, [string] $Query) {
    $json = Read-Json $Response
    Assert-Check (($json.PSObject.Properties.Name -join ',') -ceq 'query,results') 'Keyword root schema/order changed'
    Assert-Check ($json.query -ceq $Query -and $json.results -is [array]) 'Expected decoded query and results array'
    foreach ($result in $json.results) {
        Assert-Check (($result.PSObject.Properties.Name -join ',') -ceq 'filename,score') 'Result schema/order changed'
        Assert-Check ($result.filename -is [string]) 'Expected filename string'
        Assert-Check ($result.score -is [double] -or $result.score -is [int] -or $result.score -is [long] -or $result.score -is [decimal]) 'Expected numeric score'
    }
    return $json
}

function Assert-Error([string] $Path, [string] $Reason = $numericError) {
    $json = Read-Json (Send-Request $Path) 400
    Assert-Check (($json.PSObject.Properties.Name -join ',') -ceq 'error') 'Expected error-only JSON object'
    Assert-Check ($json.error -is [string] -and $json.error -ceq "Bad request: $Reason") "Unexpected diagnostic: $($json.error)"
}

function Assert-Prefix([string] $Query, [int] $K, [string] $ModeSuffix = '') {
    $encoded = [Uri]::EscapeDataString($Query)
    $full = Send-Request "/search?q=$encoded"
    $all = Read-Keyword $full $Query
    $limited = Send-Request "/search?q=$encoded&k=$K$ModeSuffix"
    $top = Read-Keyword $limited $Query
    $count = [Math]::Min($K, $all.results.Count)
    Assert-Check ($top.results.Count -eq $count) 'Incorrect Top-K result count'
    for ($i = 0; $i -lt $count; $i++) {
        Assert-Check ($top.results[$i].filename -ceq $all.results[$i].filename) "Wrong filename/order at $i"
        Assert-Check ([BitConverter]::DoubleToInt64Bits([double] $top.results[$i].score) -eq [BitConverter]::DoubleToInt64Bits([double] $all.results[$i].score)) "Score bits differ at $i"
    }
    # Preserve exact wire score literals too: avoid a JSON reserializer changing
    # decimal formatting or a client parser concealing a last-bit difference.
    # These are complete result objects in the fixed keyword JSON contract.
    $objects = @([regex]::Matches($full.Body, '\{"filename":"(?:\\.|[^"\\])*","score":-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[Ee][+-]?[0-9]+)?\}') | ForEach-Object { $_.Value })
    Assert-Check ($objects.Count -eq $all.results.Count) 'Could not identify full response result objects'
    $prefix = @()
    for ($i = 0; $i -lt $count; $i++) { $prefix += $objects[$i] }
    $headerEnd = $full.Body.IndexOf(',"results":[')
    Assert-Check ($headerEnd -ge 0) 'Missing keyword JSON results field'
    $expected = $full.Body.Substring(0, $headerEnd) + ',"results":[' + ($prefix -join ',') + ']}'
    Assert-Check ($limited.Body -ceq $expected) 'Top-K JSON is not the exact original response prefix'
}

if ($Collection -eq 'default') {
    $javaNames = @('java_programming.txt', 'academic_notice.txt')
    $tieNames = @('academic_notice.txt', 'algorithms.txt', 'information_retrieval.txt')
    $documentCount = 6
    $phrasePath = '/search?q=computer%20networking&mode=phrase'
    $phraseBody = '{"query":"computer networking","mode":"phrase","results":[{"filename":"networking.txt"}]}'
    $booleanBody = '{"query":"java AND programming","mode":"boolean","results":[{"filename":"java_programming.txt"}]}'
} else {
    $javaNames = @('java_programming_lab.txt', 'information_retrieval_workshop.txt', 'networking_lab.txt', 'reverse_order_exercise.txt', 'seminar_discussion.txt')
    $tieNames = @('academic_announcement.txt', 'algorithms_coursework.txt', 'information_retrieval_workshop.txt')
    $documentCount = 9
    $phrasePath = '/search?q=java%20search&mode=phrase'
    $phraseBody = '{"query":"java search","mode":"phrase","results":[{"filename":"information_retrieval_workshop.txt"},{"filename":"java_programming_lab.txt"}]}'
    $booleanBody = '{"query":"java AND programming","mode":"boolean","results":[{"filename":"java_programming_lab.txt"},{"filename":"seminar_discussion.txt"}]}'
}

$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.UseProxy = $false
$handler.AllowAutoRedirect = $false
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(5)
try {
    Test-Case 'Absent k preserves legacy JSON and does not impose a default limit' {
        $legacy = Send-Request '/search?q=java'
        $explicit = Send-Request '/search?q=java&mode=keyword'
        $json = Read-Keyword $legacy 'java'
        Read-Keyword $explicit 'java' | Out-Null
        Assert-Check ($legacy.Body -ceq $explicit.Body) 'Missing/explicit keyword mode differ'
        $names = @($json.results | ForEach-Object { $_.filename })
        Assert-Check (($names -join '|') -ceq ($javaNames -join '|')) 'Unexpected collection or java ranking'
        $all = Read-Keyword (Send-Request '/search?q=fictional') 'fictional'
        Assert-Check ($all.results.Count -eq $documentCount) 'Unbounded request lost matching documents'
        if ($Collection -eq 'default') {
            Assert-Check ($legacy.Body -ceq '{"query":"java","results":[{"filename":"java_programming.txt","score":5.541893581161611},{"filename":"academic_notice.txt","score":1.8472978603872037}]}') 'Legacy default JSON changed'
        }
    }
    Test-Case 'Top 1 is the exact full-response prefix' { Assert-Prefix 'fictional' 1; Assert-Prefix 'java' 1 }
    Test-Case 'Top 2 is the exact full-response prefix' { Assert-Prefix 'fictional' 2; Assert-Prefix 'java' 2 }
    Test-Case 'Top 3 is the exact full-response prefix' { Assert-Prefix 'fictional' 3; Assert-Prefix 'java' 3 }
    Test-Case 'Top 5 is the exact full-response prefix' { Assert-Prefix 'fictional' 5; Assert-Prefix 'java' 5 }
    Test-Case 'K greater than matches returns every match' { Assert-Prefix 'java' 100; Assert-Prefix 'fictional' 100 }
    Test-Case 'Integer.MAX_VALUE returns every match without proportional allocation' {
        Assert-Prefix 'java' ([int]::MaxValue)
        Assert-Prefix 'fictional' ([int]::MaxValue)
    }
    Test-Case 'Missing and explicit keyword mode select the same Top-K results' {
        foreach ($k in @(1, 2, 3, 5)) {
            Assert-Prefix 'java search' $k '&mode=keyword'
            $implicit = Send-Request "/search?q=java%20search&k=$k"
            $explicit = Send-Request "/search?k=$k&mode=keyword&q=java%20search"
            Read-Keyword $explicit 'java search' | Out-Null
            Assert-Check ($implicit.Body -ceq $explicit.Body) 'Parameter order or mode changed Top-K'
        }
    }
    Test-Case 'Multi-term, reordered and repeated queries retain exact score bits' {
        foreach ($query in @('java programming networking software search algorithms', 'algorithms search java', 'JAVA,java SEARCH search')) {
            foreach ($k in @(1, 3, 5)) { Assert-Prefix $query $k }
        }
    }
    Test-Case 'Equal-score filename ties remain correct at the cutoff' {
        $json = Read-Keyword (Send-Request '/search?q=fictional&k=3') 'fictional'
        $names = @($json.results | ForEach-Object { $_.filename })
        Assert-Check (($names -join '|') -ceq ($tieNames -join '|')) 'Filename tie order changed'
        Assert-Check ($json.results[0].score -eq $json.results[1].score -and $json.results[1].score -eq $json.results[2].score) 'Expected equal-score tie fixture'
    }
    Test-Case 'Unknown valid queries return successful empty arrays' {
        foreach ($k in @(1, 5, [int]::MaxValue)) { Assert-Prefix 'zzzznomatchingterm' $k }
    }
    Test-Case 'Punctuation-only and stop-word-only queries remain empty' {
        Assert-Prefix '!!!' 3
        Assert-Prefix 'and or the' 3
    }
    Test-Case 'Zero is rejected' { foreach ($k in @('0', '00', '000')) { Assert-Error "/search?q=java&k=$k" } }
    Test-Case 'Negative K is rejected' { foreach ($k in @('-1', '-2147483648')) { Assert-Error "/search?q=java&k=$k" } }
    Test-Case 'Empty and blank K are rejected' { foreach ($k in @('', '%20', '+++', '%09', '%0A')) { Assert-Error "/search?q=java&k=$k" } }
    Test-Case 'Nonnumeric K is rejected' { foreach ($k in @('abc', 'null', 'true', 'NaN', 'Infinity')) { Assert-Error "/search?q=java&k=$k" } }
    Test-Case 'Fractions and decimal separators are rejected' { foreach ($k in @('1.5', '3.0', '.5', '1,000')) { Assert-Error "/search?q=java&k=$k" } }
    Test-Case 'Values above the Java int range are rejected safely' {
        foreach ($k in @('2147483648', '4294967297', '999999999999999999999999999999')) { Assert-Error "/search?q=java&k=$k" }
    }
    Test-Case 'Signs, exponents, radix prefixes and other numeric syntax are rejected' {
        foreach ($k in @('+1', '%2B1', '1e3', '0x10', '0b11', '1_000', '--1', '1-', '1%2F2')) { Assert-Error "/search?q=java&k=$k" }
    }
    Test-Case 'Surrounding and internal whitespace are rejected' {
        foreach ($k in @('%201', '1%20', '1+0', '1%092')) { Assert-Error "/search?q=java&k=$k" }
    }
    Test-Case 'Non-ASCII decimal digits are rejected' {
        foreach ($k in @('%D9%A1', '%EF%BC%91')) { Assert-Error "/search?q=java&k=$k" }
    }
    Test-Case 'Duplicate k is rejected even when identical, encoded or blank' {
        foreach ($parameters in @('k=1&k=1', 'k=1&k=2', 'k=&k=1', 'k=1&k=', 'k=1&%6B=1')) {
            Assert-Error "/search?q=java&$parameters" 'Duplicate k parameter.'
        }
    }
    Test-Case 'K is rejected with phrase mode' {
        Assert-Error '/search?q=java&mode=phrase&k=1' 'k is supported only in keyword mode.'
        Assert-Error '/search?q=java&k=3&mode=phrase' 'k is supported only in keyword mode.'
    }
    Test-Case 'K is rejected with Boolean mode' {
        Assert-Error '/search?q=java%20AND%20programming&mode=boolean&k=2' 'k is supported only in keyword mode.'
        Assert-Error '/search?q=java&k=5&mode=boolean' 'k is supported only in keyword mode.'
    }
    Test-Case 'Phrase without K preserves exact filename-only JSON' {
        $response = Send-Request $phrasePath
        Read-Json $response | Out-Null
        Assert-Check ($response.Body -ceq $phraseBody) 'Phrase JSON changed'
    }
    Test-Case 'Boolean without K preserves exact filename-only JSON' {
        $response = Send-Request '/search?q=java%20AND%20programming&mode=boolean'
        Read-Json $response | Out-Null
        Assert-Check ($response.Body -ceq $booleanBody) 'Boolean JSON changed'
    }
    Test-Case 'Existing q validation is preserved with K' {
        foreach ($path in @('/search?k=1', '/search?q=&k=1', '/search?q=+++&k=1')) {
            Assert-Error $path 'A nonblank q parameter is required.'
        }
        Assert-Error '/search?q=java&%71=java&k=1' 'Duplicate q parameter.'
    }
    Test-Case 'Existing mode validation is preserved with K' {
        foreach ($mode in @('', 'Keyword', 'other')) {
            Assert-Error "/search?q=java&mode=$mode&k=1" 'mode must be keyword or phrase or boolean.'
        }
        Assert-Error '/search?q=java&mode=keyword&%6Dode=keyword&k=1' 'Duplicate mode parameter.'
    }
    Test-Case 'URL-decoded names, values, leading zeros and ignored parameters work' {
        $canonical = Send-Request '/search?q=java%20search&k=3'
        $encoded = Send-Request '/search?%6B=%30%30%33&mo%64e=%6Beyword&q=java+search&extra=a%26b'
        Read-Keyword $encoded 'java search' | Out-Null
        Assert-Check ($canonical.Body -ceq $encoded.Body) 'Valid decoded decimal or ignored parameter changed results'
        $upper = Send-Request '/search?q=java&K=1'
        $full = Send-Request '/search?q=java'
        Assert-Check ($upper.Body -ceq $full.Body) 'Parameter names must remain case-sensitive'
    }
    Test-Case 'UTF-8 and name=value validation remain strict for K' {
        Assert-Error '/search?q=java&k=%FF' 'Query parameters must use valid UTF-8.'
        Assert-Error '/search?q=java&k' 'Parameters must use name=value.'
        Assert-Error '/search?q=java&k=%25GG'
    }
    Test-Case 'Unsafe numeric input returns valid error JSON without reflecting raw text' {
        $unsafeValue = '"\path' + [char]1
        $encoded = [Uri]::EscapeDataString($unsafeValue)
        $response = Send-Request "/search?q=java&k=$encoded"
        $json = Read-Json $response 400
        Assert-Check ($json.error -ceq "Bad request: $numericError") 'Expected fixed descriptive numeric error'
        Assert-Check ($response.Body -ceq ('{"error":"Bad request: ' + $numericError + '"}')) 'Unexpected escaped error contract'
    }
    Test-Case 'Invalid requests do not stop subsequent Top-K, full search, other modes or health' {
        Assert-Error '/search?q=java&k=2147483648'
        Assert-Prefix 'java' 1
        $full = Read-Keyword (Send-Request '/search?q=fictional') 'fictional'
        Assert-Check ($full.results.Count -eq $documentCount) 'A previous limit leaked into full search'
        Assert-Check ((Send-Request $phrasePath).Body -ceq $phraseBody) 'Phrase stopped working'
        Assert-Check ((Send-Request '/search?q=java%20AND%20programming&mode=boolean').Body -ceq $booleanBody) 'Boolean stopped working'
        $health = Send-Request '/health'
        Assert-Check ($health.Status -eq 200 -and $health.Body -ceq 'OK') 'Server stopped responding'
    }
} finally {
    $client.Dispose()
    $handler.Dispose()
}
Write-Host "Top-K collection: $Collection; total passed: $script:passed; total failed: $script:failed"
if ($script:failed -gt 0) { exit 1 }
exit 0
