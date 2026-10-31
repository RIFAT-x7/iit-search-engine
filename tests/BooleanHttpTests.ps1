# Targets an already-running localhost server. The runner owns server startup/cleanup.
param([ValidateSet('default', 'phrase-demo')][string] $Collection = 'default')
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

function Assert-BooleanResponse($Response, [string] $Query, [string[]] $Expected = @()) {
    $json = Read-Json $Response
    Assert-Check (($json.PSObject.Properties.Name | Sort-Object) -join ',' -ceq 'mode,query,results') 'Unexpected Boolean root properties'
    Assert-Check ($json.query -ceq $Query -and $json.mode -ceq 'boolean') 'Expected decoded query and Boolean mode'
    Assert-Check ($json.results -is [array]) 'Expected results array'
    $actual = @($json.results | ForEach-Object {
        Assert-Check (($_.PSObject.Properties.Name -join ',') -ceq 'filename') 'Boolean results must contain only filename, without scores'
        $_.filename
    })
    Assert-Check ($actual.Count -eq $Expected.Count -and ($actual -join '|') -ceq ($Expected -join '|')) "Unexpected Boolean filenames/order for [$Query]: $actual"
}

function Assert-Boolean([string] $Query, [string[]] $Expected = @()) {
    $encoded = [Uri]::EscapeDataString($Query)
    Assert-BooleanResponse (Send-Request "/search?q=$encoded&mode=boolean") $Query $Expected
}

function Assert-Error([string] $Query, [string] $Reason) {
    $encoded = [Uri]::EscapeDataString($Query)
    $json = Read-Json (Send-Request "/search?q=$encoded&mode=boolean") 400
    Assert-Check (($json.PSObject.Properties.Name -join ',') -ceq 'error') 'Expected error-only JSON object'
    Assert-Check ($json.error -is [string] -and $json.error.Contains($Reason)) "Expected error containing: $Reason"
}

if ($Collection -eq 'default') {
    $java = @('academic_notice.txt', 'java_programming.txt')
    $both = @('java_programming.txt')
    $networking = @('networking.txt')
    $either = @('academic_notice.txt', 'java_programming.txt', 'networking.txt')
    $groups = @('java_programming.txt', 'networking.txt')
} else {
    $java = @('information_retrieval_workshop.txt', 'java_programming_lab.txt', 'networking_lab.txt', 'reverse_order_exercise.txt', 'seminar_discussion.txt')
    $both = @('java_programming_lab.txt', 'seminar_discussion.txt')
    $networking = @('networking_lab.txt')
    $either = $java
    $groups = @('java_programming_lab.txt', 'networking_lab.txt', 'seminar_discussion.txt')
}

$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.UseProxy = $false
$handler.AllowAutoRedirect = $false
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(5)
try {
    Test-Case 'Boolean AND intersects document sets' {
        Assert-Boolean 'java AND programming' $both
        Assert-Boolean 'programming AND java' $both
    }
    Test-Case 'Boolean OR unions document sets' {
        Assert-Boolean 'java OR networking' $either
        Assert-Boolean 'networking OR java' $either
    }
    Test-Case 'AND takes precedence before OR in either position' {
        Assert-Boolean 'java OR networking AND laboratory' $either
        Assert-Boolean 'networking AND laboratory OR java' $either
    }
    Test-Case 'Multiple AND groups combine through OR' {
        Assert-Boolean 'java AND programming OR networking AND laboratory' $groups
        Assert-Boolean 'java AND absent OR networking AND laboratory' $networking
    }
    Test-Case 'Operators ignore case and operands normalize punctuation' {
        Assert-Boolean 'JAVA, aNd PROGRAMMING.' $both
        Assert-Boolean 'java oR networking' $either
    }
    Test-Case 'Unknown terms are successful empty sets, not errors' {
        Assert-Boolean 'zzzznomatchingterm'
        Assert-Boolean 'java AND zzzznomatchingterm'
        Assert-Boolean 'zzzznomatchingterm OR java' $java
    }
    Test-Case 'Single-term Boolean expressions work' { Assert-Boolean 'networking' $networking }
    Test-Case 'Repeated terms return unique documents in filename order without scores' {
        Assert-Boolean 'java OR java OR networking OR java' $either
        Assert-Boolean 'java AND java' $java
    }
    Test-Case 'Missing operators are JSON 400' {
        Assert-Error 'java programming' 'Expected AND or OR'
        Assert-Error 'java-programming' 'normalize to one term'
    }
    Test-Case 'Missing operands and consecutive operators are JSON 400' {
        Assert-Error 'AND java' 'Expected an operand'
        Assert-Error 'java OR' 'Missing operand'
        Assert-Error 'java AND OR programming' 'Expected an operand'
    }
    Test-Case 'Unsupported grouping, NOT, phrase operands and wildcards are JSON 400' {
        foreach ($query in @('(java)', 'java AND (programming OR networking)', '"java programming"', 'java*', 'jav?')) {
            Assert-Error $query 'Unsupported Boolean syntax'
        }
        Assert-Error 'NOT java' 'NOT is not supported'
        Assert-Error 'java NOT programming' 'Expected AND or OR'
    }
    Test-Case 'Stop-word operands are rejected without changing the expression' {
        foreach ($stop in @('a', 'an', 'the', 'is', 'are', 'of', 'to', 'in', 'for', 'with')) {
            Assert-Error "java AND $stop" 'Stop-word operand'
        }
        Assert-Error 'absent AND the' 'Stop-word operand'
    }
    Test-Case 'Punctuation-only operands are JSON 400' { Assert-Error '!!!' 'normalize to one term' }
    Test-Case 'Error JSON escapes quotes, backslashes and control characters' {
        $operand = '"\path' + [char]1
        $query = "java AND $operand"
        $encoded = [Uri]::EscapeDataString($query)
        $response = Send-Request "/search?q=$encoded&mode=boolean"
        $json = Read-Json $response 400
        Assert-Check ($json.error -ceq "Bad request: Unsupported Boolean syntax in operand: $operand") 'Escaped diagnostic did not round-trip'
        Assert-Check ($response.Body.Contains('\"') -and $response.Body.Contains('\\') -and $response.Body.Contains('\u0001')) 'Expected escaped JSON characters'
        Assert-Check (($json.PSObject.Properties.Name -join ',') -ceq 'error') 'Error contract changed'
    }
    Test-Case 'Duplicate q and mode validation remains strict for Boolean requests' {
        foreach ($path in @('/search?q=java&q=networking&mode=boolean', '/search?q=java&%71=java&mode=boolean')) {
            $json = Read-Json (Send-Request $path) 400
            Assert-Check ($json.error.Contains('Duplicate q')) 'Expected duplicate q error'
        }
        foreach ($path in @('/search?q=java&mode=boolean&mode=boolean', '/search?q=java&mode=boolean&%6Dode=phrase')) {
            $json = Read-Json (Send-Request $path) 400
            Assert-Check ($json.error.Contains('Duplicate mode')) 'Expected duplicate mode error'
        }
    }
    Test-Case 'Invalid and incorrectly capitalized mode values remain JSON 400' {
        foreach ($mode in @('Boolean', 'other', '')) {
            $json = Read-Json (Send-Request "/search?q=java&mode=$mode") 400
            Assert-Check ($json.error.Contains('mode must be keyword or phrase or boolean')) 'Expected allowed-mode diagnostic'
        }
    }
    Test-Case 'Boolean mode still requires nonblank q' {
        foreach ($path in @('/search?mode=boolean', '/search?q=&mode=boolean', '/search?q=+++&mode=boolean')) {
            $json = Read-Json (Send-Request $path) 400
            Assert-Check ($json.error.Contains('nonblank q')) 'Expected nonblank query diagnostic'
        }
    }
    Test-Case 'Encoded mode names, values and plus spaces preserve decoding' {
        Assert-BooleanResponse (Send-Request '/search?mo%64e=%62oolean&q=JAVA+and+PROGRAMMING') 'JAVA and PROGRAMMING' $both
        Assert-Boolean "java`tAND`nprogramming" $both
        $json = Read-Json (Send-Request '/search?q=java&mode=boolean&extra=a%26b')
        Assert-Check ($json.mode -ceq 'boolean') 'Decoded ignored parameter changed mode'
        $json = Read-Json (Send-Request '/search?q=java&mode=%FF') 400
        Assert-Check ($json.error.Contains('valid UTF-8')) 'Invalid encoding was accepted'
    }
    Test-Case 'Legacy keyword JSON stays unchanged without mode or with keyword mode' {
        $legacy = Send-Request '/search?q=java'
        $explicit = Send-Request '/search?q=java&mode=keyword'
        $json = Read-Json $legacy
        Assert-Check ($legacy.Body -ceq $explicit.Body) 'Legacy and explicit keyword JSON differ'
        Assert-Check (($json.PSObject.Properties.Name | Sort-Object) -join ',' -ceq 'query,results') 'Keyword root contract changed'
        foreach ($result in $json.results) {
            Assert-Check (($result.PSObject.Properties.Name | Sort-Object) -join ',' -ceq 'filename,score') 'Keyword result contract changed'
        }
        if ($Collection -eq 'default') {
            Assert-Check ($legacy.Body -ceq '{"query":"java","results":[{"filename":"java_programming.txt","score":5.541893581161611},{"filename":"academic_notice.txt","score":1.8472978603872037}]}') 'Exact legacy JSON changed'
        }
    }
    Test-Case 'Phrase JSON keeps its existing exact filename-only contract' {
        if ($Collection -eq 'default') {
            $response = Send-Request '/search?q=computer%20networking&mode=phrase'
            $expected = '{"query":"computer networking","mode":"phrase","results":[{"filename":"networking.txt"}]}'
        } else {
            $response = Send-Request '/search?q=java%20search&mode=phrase'
            $expected = '{"query":"java search","mode":"phrase","results":[{"filename":"information_retrieval_workshop.txt"},{"filename":"java_programming_lab.txt"}]}'
        }
        Read-Json $response | Out-Null
        Assert-Check ($response.Body -ceq $expected) 'Exact phrase JSON changed'
    }
    Test-Case 'Server accepts valid searches and health after malformed Boolean input' {
        Assert-Error 'java AND' 'Missing operand'
        Assert-Boolean 'java AND programming' $both
        Read-Json (Send-Request '/search?q=java&mode=keyword') | Out-Null
        Read-Json (Send-Request '/search?q=java&mode=phrase') | Out-Null
        $health = Send-Request '/health'
        Assert-Check ($health.Status -eq 200 -and $health.Body -ceq 'OK') 'Server did not remain responsive'
    }
    Test-Case 'Boolean mode searches only the selected fixed collection' {
        if ($Collection -eq 'default') {
            Assert-Boolean 'computer AND networking' @('networking.txt')
            Assert-Boolean 'java AND search'
        } else {
            Assert-Boolean 'computer AND networking'
            Assert-Boolean 'java AND search' @('information_retrieval_workshop.txt', 'java_programming_lab.txt', 'networking_lab.txt', 'reverse_order_exercise.txt')
        }
    }
} finally {
    $client.Dispose()
    $handler.Dispose()
}
Write-Host "Boolean collection: $Collection; total passed: $script:passed; total failed: $script:failed"
if ($script:failed -gt 0) { exit 1 }
exit 0
