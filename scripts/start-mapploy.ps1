param([switch]$NoBrowser)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$backendDirectory = Join-Path $projectRoot 'backend'
$frontendDirectory = Join-Path $projectRoot 'prototype'
$logDirectory = Join-Path $projectRoot '.mapploy\logs'
$runStamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$frontendUrl = 'http://127.0.0.1:4173'
$backendUrl = 'http://127.0.0.1:8081'
$ollamaUrl = 'http://127.0.0.1:11434'
$startedProcesses = [System.Collections.Generic.List[System.Diagnostics.Process]]::new()

function Get-LocalJson([string]$Url) {
    try { return Invoke-RestMethod -Uri $Url -TimeoutSec 3 }
    catch { return $null }
}

function Test-Frontend {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $frontendUrl -TimeoutSec 3
        return $response.StatusCode -eq 200 -and $response.Content -match '<title>\s*Mapploy'
    } catch { return $false }
}

function Test-Backend {
    $health = Get-LocalJson "$backendUrl/actuator/health"
    if ($null -eq $health -or $health.status -ne 'UP') { return $false }
    $status = Get-LocalJson "$backendUrl/api/status"
    return $null -ne $status -and $null -ne $status.feeds -and $null -ne $status.ollama
}

function Test-Port([int]$Port) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $connection = $client.ConnectAsync('127.0.0.1', $Port)
        return $connection.Wait(1000) -and $client.Connected
    } catch { return $false }
    finally { $client.Dispose() }
}

function Start-LocalService([string]$Name, [string]$Executable, [string[]]$Arguments, [string]$Directory) {
    $outputLog = Join-Path $logDirectory "$Name-$runStamp.out.log"
    $errorLog = Join-Path $logDirectory "$Name-$runStamp.err.log"
    Write-Host "Starting $Name..."
    $process = Start-Process -FilePath $Executable -ArgumentList $Arguments -WorkingDirectory $Directory `
        -WindowStyle Hidden -RedirectStandardOutput $outputLog -RedirectStandardError $errorLog -PassThru
    $startedProcesses.Add($process)
    return $process
}

function Wait-Ready([string]$Name, [scriptblock]$Check, [int]$Seconds, [System.Diagnostics.Process]$Process) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($null -ne $Process -and $Process.HasExited) {
            throw "$Name exited before it was ready. See $logDirectory\$Name-$runStamp.*.log"
        }
        if (& $Check) { Write-Host "$Name is ready."; return }
        Start-Sleep -Seconds 1
    }
    throw "$Name did not become ready within $Seconds seconds. See $logDirectory"
}

function Set-JavaEnvironment {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    foreach ($installationRoot in @("$env:ProgramFiles\Java", "$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Microsoft")) {
        if (Test-Path -LiteralPath $installationRoot) {
            $candidates += Get-ChildItem -LiteralPath $installationRoot -Directory -Filter 'jdk-25*' |
                Sort-Object Name -Descending | Select-Object -ExpandProperty FullName
        }
    }
    foreach ($candidate in $candidates | Select-Object -Unique) {
        $javaExecutable = Join-Path $candidate 'bin\java.exe'
        if (!(Test-Path -LiteralPath $javaExecutable) -or !(Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe'))) { continue }
        $version = & $javaExecutable --version
        if ($LASTEXITCODE -eq 0 -and ($version -join ' ') -match '^(?:java|openjdk) 25(?:[.\s]|$)') {
            $env:JAVA_HOME = $candidate
            $env:Path = "$candidate\bin;$env:Path"
            return
        }
    }
    throw 'JDK 25 was not found. Install JDK 25 or set JAVA_HOME to its Windows installation directory.'
}

# Serialise launchers for this project so two clicks do not start duplicate services.
$hasher = [System.Security.Cryptography.SHA256]::Create()
$projectHash = [BitConverter]::ToString($hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($projectRoot.ToLowerInvariant()))).Replace('-', '')
$hasher.Dispose()
$launcherMutex = [System.Threading.Mutex]::new($false, "Local\Mapploy-$projectHash")
$ownsMutex = $false

try {
    try { $ownsMutex = $launcherMutex.WaitOne(0) }
    catch [System.Threading.AbandonedMutexException] { $ownsMutex = $true }
    if (!$ownsMutex) { throw 'Another Mapploy launcher is already running. Wait for it to finish.' }

    New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
    Write-Host 'Starting Mapploy'

    $frontendReady = Test-Frontend
    $backendReady = Test-Backend
    if (!$frontendReady -and (Test-Port 4173)) { throw 'Port 4173 is occupied by an unrecognised or unready service. Wait for it or stop it before retrying.' }
    if (!$backendReady -and (Test-Port 8081)) { throw 'Port 8081 is occupied by an unrecognised or unready service. Wait for it or stop it before retrying.' }

    # Check required runtimes before starting any new services.
    if (!$backendReady) { Set-JavaEnvironment }
    if (!$frontendReady) {
        $nodeCommand = Get-Command node.exe -ErrorAction SilentlyContinue
        if ($null -eq $nodeCommand) { throw 'Node.js 22 or newer was not found in PATH.' }
        $nodeVersion = & $nodeCommand.Source --version
        if ($LASTEXITCODE -ne 0 -or $nodeVersion -notmatch '^v(\d+)\.' -or [int]$Matches[1] -lt 22) { throw 'Node.js 22 or newer is required.' }
        if (!(Test-Path -LiteralPath (Join-Path $frontendDirectory 'node_modules\maplibre-gl\dist\maplibre-gl.mjs'))) {
            $npmCommand = Get-Command npm.cmd -ErrorAction SilentlyContinue
            if ($null -eq $npmCommand) { throw 'npm was not found. Install Node.js including npm.' }
            Write-Host 'Installing frontend dependencies (first start only)...'
            $install = Start-Process -FilePath $npmCommand.Source -ArgumentList 'ci' -WorkingDirectory $frontendDirectory `
                -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logDirectory "npm-$runStamp.out.log") `
                -RedirectStandardError (Join-Path $logDirectory "npm-$runStamp.err.log") -Wait -PassThru
            if ($install.ExitCode -ne 0) { throw "Frontend dependency installation failed. See $logDirectory" }
        }
    }

    # All services use loopback. These environment changes affect only this launcher and its children.
    $env:OLLAMA_HOST = '127.0.0.1:11434'
    $env:OLLAMA_BASE_URL = $ollamaUrl
    $env:OLLAMA_MODEL = 'gemma3:4b'
    $env:SERVER_ADDRESS = '127.0.0.1'
    $ollama = Get-LocalJson "$ollamaUrl/api/tags"
    if ($null -eq $ollama -or $null -eq $ollama.models) {
        if (Test-Port 11434) { throw 'Port 11434 is occupied but does not respond as Ollama.' }
        $ollamaCommand = Get-Command ollama.exe -ErrorAction SilentlyContinue
        if ($null -eq $ollamaCommand) { throw 'Ollama was not found in PATH. Install Ollama and run: ollama pull gemma3:4b' }
        $ollamaProcess = Start-LocalService 'ollama' $ollamaCommand.Source @('serve') $projectRoot
        Wait-Ready 'ollama' { $tags = Get-LocalJson "$ollamaUrl/api/tags"; $null -ne $tags -and $null -ne $tags.models } 30 $ollamaProcess
        $ollama = Get-LocalJson "$ollamaUrl/api/tags"
    } else { Write-Host 'Ollama is already running.' }
    if ('gemma3:4b' -notin @($ollama.models.name)) {
        Write-Warning 'gemma3:4b is missing. Mapploy can use its rule-based reports. Enable local AI with: ollama pull gemma3:4b'
    }

    if (!$backendReady) {
        $env:PORT = '8081'
        $backendProcess = Start-LocalService 'backend' (Join-Path $backendDirectory 'mvnw.cmd') `
            @('spring-boot:run', '-Dspring-boot.run.profiles=dev') $backendDirectory
        Wait-Ready 'backend' { Test-Backend } 180 $backendProcess
    } else { Write-Host 'Backend is already running.' }

    if (!$frontendReady) {
        $env:PORT = '4173'
        $frontendProcess = Start-LocalService 'frontend' $nodeCommand.Source @('server.mjs') $frontendDirectory
        Wait-Ready 'frontend' { Test-Frontend } 30 $frontendProcess
    } else { Write-Host 'Frontend is already running.' }

    Write-Host "Mapploy is ready: $frontendUrl"
    Write-Host "Logs: $logDirectory"
    Write-Host 'Services keep running after this terminal closes. Run the same script to open Mapploy again.'
    if (!$NoBrowser) {
        try { Start-Process -FilePath $frontendUrl | Out-Null }
        catch { Write-Warning "The browser could not be opened automatically. Open $frontendUrl manually." }
    }
} catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    # Stop only processes created by this failed launch, including Maven's Spring child.
    foreach ($process in $startedProcesses) {
        if (!$process.HasExited) { & taskkill.exe /PID $process.Id /T /F 2>&1 | Out-Null }
    }
    exit 1
} finally {
    if ($ownsMutex) { $launcherMutex.ReleaseMutex() }
    $launcherMutex.Dispose()
}
