# Shared helpers for the BugTrack start/stop scripts.

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$MySqlHome = Join-Path $env:USERPROFILE '.bugtrack-mysql'
$MySqlIni = Join-Path $MySqlHome 'my.ini'
$MySqlServiceName = 'BugTrackMySQL'

function Write-Step([string]$message) {
    Write-Host "  $message"
}

function Test-Port([int]$port) {
    [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
}

function Wait-Port([int]$port, [int]$seconds, [string]$what) {
    for ($i = 0; $i -lt $seconds; $i++) {
        if (Test-Port $port) { return }
        Start-Sleep -Seconds 1
    }
    throw "$what did not start within $seconds seconds."
}

# The newest installed MySQL Server's bin folder (for example C:\Program Files\MySQL\MySQL Server 8.4\bin).
function Find-MySqlBin([string]$exe) {
    $installs = Get-Item -Path 'C:\Program Files\MySQL\MySQL Server *' -ErrorAction SilentlyContinue |
        Sort-Object { [version]($_.Name -replace '^MySQL Server ', '') } -Descending
    foreach ($install in $installs) {
        $path = Join-Path $install.FullName "bin\$exe"
        if (Test-Path $path) { return $path }
    }
    throw "MySQL Server was not found under C:\Program Files\MySQL."
}

# True when application-local.properties enables email and points it at the local test server on port 1025.
function Use-TestMailServer {
    $local = Join-Path $ProjectRoot 'application-local.properties'
    if (-not (Test-Path $local)) { return $false }
    $settings = Get-Content $local
    ($settings -match '^\s*app\.mail\.enabled\s*=\s*true\s*$') -and ($settings -match '^\s*spring\.mail\.port\s*=\s*1025\s*$')
}

function Test-PythonSmtpd {
    if (-not (Get-Command python -ErrorAction SilentlyContinue)) { return $false }
    # Windows PowerShell treats anything a program prints to stderr as an error; this check only needs the exit code.
    $ErrorActionPreference = 'Continue'
    & python -W ignore -c "import smtpd" 2>&1 | Out-Null
    $LASTEXITCODE -eq 0
}

# The MySQL root password saved when the local database was set up, or $null.
function Get-MySqlRootPassword {
    $file = Join-Path $MySqlHome 'root-password.txt'
    if (-not (Test-Path $file)) { return $null }
    ((Get-Content $file -Raw) -replace '^\s*root password:\s*', '').Trim()
}

function Get-PortOwner([int]$port) {
    $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($conn) { Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue }
}

# Shuts MySQL down cleanly (lets it finish writing), unlike killing the process.
function Stop-MySqlCleanly {
    $password = Get-MySqlRootPassword
    if (-not $password) { throw "Cannot stop MySQL cleanly: $MySqlHome\root-password.txt was not found." }
    $mysqladmin = Find-MySqlBin 'mysqladmin.exe'
    $env:MYSQL_PWD = $password
    try {
        & $mysqladmin -u root -h 127.0.0.1 -P 3306 shutdown 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'mysqladmin shutdown failed.' }
    } finally {
        Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    }
    for ($i = 0; $i -lt 60; $i++) {
        if (-not (Test-Port 3306)) { return }
        Start-Sleep -Seconds 1
    }
    throw 'MySQL did not stop within 60 seconds.'
}
