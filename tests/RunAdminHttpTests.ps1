# Compile first. Uses an owned temporary working directory, never data/managed in the checkout.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
$javaPath = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\java.exe'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$classPath = Join-Path $repoRoot 'out\app'
$fixtureParent = Join-Path $repoRoot 'out\verification-temp'
$fixture = Join-Path $fixtureParent ('admin-http-' + [Guid]::NewGuid().ToString('N'))
$token = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
$script:cleanupAllowed = $true

function Assert-FreePort {
    $listeners = [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
    if (@($listeners | Where-Object { $_.Port -eq 8080 }).Count) {
        throw 'Port 8080 is occupied. No existing process will be stopped.'
    }
}

function Read-OwnedIdentity($Process, [string] $MainClass) {
    $identity = Get-CimInstance Win32_Process -Filter "ProcessId = $($Process.Id)"
    if ($null -eq $identity -or $identity.ExecutablePath -ne $javaPath -or
        -not $identity.CommandLine.Contains($classPath) -or
        $identity.CommandLine -notmatch ('\s' + [regex]::Escape($MainClass) + '(?:\s|$)')) {
        throw 'Process identity mismatch; no process will be stopped.'
    }
    return $identity
}

function Invoke-OwnedJava([string] $MainClass, [AllowNull()] [string] $Credential, [int] $TimeoutSeconds) {
    Assert-FreePort
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $javaPath
    $info.Arguments = '-cp "' + $classPath + '" ' + $MainClass
    if ($MainClass -eq 'SearchServer') { $info.Arguments += ' --managed' }
    $info.WorkingDirectory = $fixture
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.EnvironmentVariables.Remove('IIT_SEARCH_ADMIN_TOKEN')
    if (-not [string]::IsNullOrEmpty($Credential)) { $info.EnvironmentVariables['IIT_SEARCH_ADMIN_TOKEN'] = $Credential }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $info
    if (-not $process.Start()) { throw 'Could not start owned Java process' }
    $script:cleanupAllowed = $false
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    try {
        if ($MainClass -eq 'AdminHttpTests') {
            Read-OwnedIdentity $process $MainClass | Out-Null
            Write-Host "Owned administrative test JVM PID=$($process.Id); executable=$javaPath"
        }
        # The normal path exits itself. Timeout cleanup is permitted only after identity checks.
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            throw 'Owned Java verification exceeded its time limit'
        }
        $output = $stdout.GetAwaiter().GetResult()
        $errorText = $stderr.GetAwaiter().GetResult()
        # Never echo credentials, including from an unexpected child diagnostic.
        if ($Credential -and ($output.Contains($Credential) -or $errorText.Contains($Credential))) {
            throw 'A child process disclosed its disposable credential; output withheld'
        }
        return [pscustomobject]@{ Code = $process.ExitCode; Output = $output; ErrorText = $errorText }
    } finally {
        if (-not $process.HasExited) {
            Read-OwnedIdentity $process $MainClass | Out-Null
            $listeners = @(Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)
            foreach ($listener in $listeners) {
                if ($listener.OwningProcess -ne $process.Id -or $listener.LocalAddress -ne '127.0.0.1') {
                    throw 'Listener identity mismatch; no process will be stopped'
                }
            }
            $process.Kill()
            $process.WaitForExit()
            Write-Host "Stopped verified owned test process PID=$($process.Id)"
        }
        $script:cleanupAllowed = $true
        $process.Dispose()
    }
}

Assert-FreePort
New-Item -ItemType Directory -Path $fixtureParent -Force | Out-Null
New-Item -ItemType Directory -Path $fixture | Out-Null
try {
    New-Item -ItemType File -Path (Join-Path $fixture '.admin-http-test-owned') | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $fixture 'web') | Out-Null
    foreach ($asset in @('index.html', 'app.js', 'styles.css', 'reader.html', 'reader.js', 'highlight.js')) {
        Copy-Item -LiteralPath (Join-Path $repoRoot "web\$asset") -Destination (Join-Path $fixture 'web')
    }
    foreach ($collection in @('data', 'data\phrase_samples', 'data\showcase')) {
        $destination = Join-Path $fixture $collection
        New-Item -ItemType Directory -Path $destination -Force | Out-Null
        Get-ChildItem -LiteralPath (Join-Path $repoRoot $collection) -Filter '*.txt' -File |
            ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $destination }
    }
    foreach ($case in @(
        @{ Label = 'missing'; Value = $null },
        @{ Label = 'blank'; Value = '   ' },
        @{ Label = 'too short'; Value = 'short' },
        @{ Label = 'unsafe format'; Value = ('x' * 32) + '!' }
    )) {
        $result = Invoke-OwnedJava 'SearchServer' $case.Value 10
        if ($result.Code -ne 1 -or $result.Output.Contains('Listening') -or
            -not $result.ErrorText.Contains('Server startup failed.') -or
            (Test-Path -LiteralPath (Join-Path $fixture 'data\managed'))) {
            throw "Managed startup did not reject $($case.Label) token configuration safely"
        }
        Assert-FreePort
        Write-Host "PASS: Actual managed startup rejects $($case.Label) token configuration"
    }
    $result = Invoke-OwnedJava 'AdminHttpTests' $token 150
    Write-Host $result.Output
    if ($result.ErrorText) { Write-Host $result.ErrorText }
    if ($result.Code -ne 0) { throw 'Administrative HTTP suite failed' }
    Assert-FreePort
    Write-Host 'PASS: Managed test processes exited; port 8080 is free.'
} finally {
    # Delete only this runner's verified, uniquely named fixture inside the ignored output directory.
    $resolvedFixture = [IO.Path]::GetFullPath($fixture)
    $resolvedParent = [IO.Path]::GetFullPath($fixtureParent).TrimEnd('\') + '\'
    if (-not $script:cleanupAllowed) {
        throw 'A test process could not be safely stopped; its owned fixture is preserved'
    }
    if (-not $resolvedFixture.StartsWith($resolvedParent, [StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-Path -LiteralPath (Join-Path $fixture '.admin-http-test-owned'))) {
        throw 'Fixture containment/ownership check failed; no cleanup performed'
    }
    Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
}
