# Compile first. Runs both fixed collections on port 8080 and stops only its own servers.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
$javaPath = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\java.exe'

function Assert-FreePort {
    $listeners = [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
    if (@($listeners | Where-Object { $_.Port -eq 8080 }).Count) {
        throw 'Port 8080 is occupied. No existing process will be stopped.'
    }
}

function Read-OwnedIdentity($Process) {
    $identity = Get-CimInstance Win32_Process -Filter "ProcessId = $($Process.Id)"
    if ($identity.ExecutablePath -ne $javaPath -or $identity.CommandLine -notmatch '\-cp out\\app SearchServer(?: |$)') {
        throw 'Process identity mismatch; no process will be stopped.'
    }
    return $identity
}

function Start-TestProcess([string] $Arguments) {
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $javaPath
    $info.Arguments = "-cp out\app SearchServer $Arguments"
    $info.WorkingDirectory = (Get-Location).Path
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $info
    if (-not $process.Start()) { throw 'Could not start test process' }
    return $process
}

function Stop-OwnedProcess($Process) {
    if (-not $Process.HasExited) {
        Read-OwnedIdentity $Process | Out-Null
        $Process.Kill()
        $Process.WaitForExit()
        Write-Host "Stopped verified owned server PID=$($Process.Id)"
    }
}

Assert-FreePort
foreach ($unsupported in @('--unknown', 'data', '--phrase-demo --phrase-demo')) {
    $process = Start-TestProcess $unsupported
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    try {
        if (-not $process.WaitForExit(5000)) { throw 'Unsupported startup arguments did not exit promptly' }
        $output = $stdout.GetAwaiter().GetResult()
        $errorText = $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 1 -or -not $errorText.Contains('Usage: SearchServer [--phrase-demo]') -or $output.Contains('Listening')) {
            throw "Unsupported arguments were not rejected clearly: $unsupported"
        }
        Write-Host "PASS: Rejected startup arguments: $unsupported"
    } finally {
        Stop-OwnedProcess $process
        $process.Dispose()
    }
    Assert-FreePort
}

foreach ($collection in @('default', 'phrase-demo')) {
    Assert-FreePort
    $arguments = if ($collection -eq 'phrase-demo') { '--phrase-demo' } else { '' }
    $process = Start-TestProcess $arguments
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    try {
        $identity = Read-OwnedIdentity $process
        Write-Host "Owned server PID=$($process.Id); executable=$($identity.ExecutablePath); command=$($identity.CommandLine)"
        $ready = $false
        for ($attempt = 0; $attempt -lt 50; $attempt++) {
            if ($process.HasExited) { throw 'Server exited during startup' }
            try {
                $request = [Net.HttpWebRequest]::Create('http://127.0.0.1:8080/health')
                $request.Proxy = $null
                $request.Timeout = 500
                $response = $request.GetResponse()
                $response.Dispose()
                $ready = $true
                break
            } catch { Start-Sleep -Milliseconds 100 }
        }
        if (-not $ready) { throw 'Server did not become ready' }
        $listener = Get-NetTCPConnection -LocalPort 8080 -State Listen
        if ($listener.OwningProcess -ne $process.Id -or $listener.LocalAddress -ne '127.0.0.1') { throw 'Unexpected listener ownership or binding' }
        if ($collection -eq 'default') {
            & powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tests\HttpSmokeTests.ps1
            if ($LASTEXITCODE -ne 0) { throw 'Original HTTP suite failed' }
        }
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tests\PhraseHttpTests.ps1 -Collection $collection
        if ($LASTEXITCODE -ne 0) { throw "Phrase HTTP tests failed for $collection" }
    } finally {
        Stop-OwnedProcess $process
        $output = $stdout.GetAwaiter().GetResult()
        $errorText = $stderr.GetAwaiter().GetResult()
        $process.Dispose()
        Write-Host $output
        if ($errorText) { Write-Host $errorText }
    }
    $folder = if ($collection -eq 'phrase-demo') { 'data/phrase_samples' } else { 'data' }
    $count = if ($collection -eq 'phrase-demo') { 9 } else { 6 }
    if (-not $output.Contains("Collection: $folder`r`n") -or -not $output.Contains("Indexed $count documents.")) {
        throw 'Startup log does not identify the expected collection and document count'
    }
    Write-Host "PASS: $collection collection startup log and document count"
    Assert-FreePort
}
Write-Host 'PASS: Both server collections tested; owned processes stopped; port 8080 is free.'
