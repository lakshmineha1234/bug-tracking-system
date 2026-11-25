# Starts BugTrack on this computer: MySQL (unless it is already running, for example as a service),
# the local test mail server (only if application-local.properties uses it), then the app.
# Each runs in its own minimized window; close that window (or run stop.cmd) to stop it.
param([switch]$NoBrowser)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

$appUrl = 'http://localhost:8080'

# 1. MySQL
if (Test-Port 3306) {
    Write-Step 'MySQL is already running.'
} else {
    $mysqld = Find-MySqlBin 'mysqld.exe'
    if (-not (Test-Path $MySqlIni)) { throw "MySQL settings were not found at $MySqlIni." }
    Write-Step 'Starting MySQL...'
    Start-Process -FilePath $mysqld -ArgumentList "--defaults-file=`"$MySqlIni`"", '--console' -WindowStyle Minimized
    Wait-Port 3306 60 'MySQL'
    Write-Step 'MySQL is running.'
}

# 2. Test mail server, if the local settings send email to it
if (Use-TestMailServer) {
    if (Test-Port 1025) {
        Write-Step 'Test mail server is already running.'
    } elseif (-not (Test-PythonSmtpd)) {
        Write-Warning ('The local settings send email to a test mail server on port 1025, but Python with the ' +
            'smtpd module (Python 3.11 or earlier) was not found. Emails will be logged as not sent.')
    } else {
        Write-Step 'Starting the test mail server (emails appear in its window)...'
        Start-Process -FilePath 'python' -ArgumentList '-u', '-W', 'ignore', '-m', 'smtpd', '-n', '-c', 'DebuggingServer', '127.0.0.1:1025' -WindowStyle Minimized
        Wait-Port 1025 20 'The test mail server'
        Write-Step 'Test mail server is running.'
    }
}

# 3. The app
if (Test-Port 8080) {
    Write-Step "BugTrack is already running at $appUrl"
} else {
    Write-Step 'Starting BugTrack (the first start can take a minute)...'
    $runCmd = Join-Path $ProjectRoot 'run.cmd'
    Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', "title BugTrack app && `"$runCmd`"" -WindowStyle Minimized
    $ready = $false
    for ($i = 0; $i -lt 240 -and -not $ready; $i++) {
        try {
            Invoke-WebRequest -UseBasicParsing "$appUrl/api/auth/csrf" -TimeoutSec 2 | Out-Null
            $ready = $true
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    if (-not $ready) { throw "BugTrack did not start within 4 minutes. Check the 'BugTrack app' window for errors." }
    Write-Step "BugTrack is running at $appUrl"
}

if (-not $NoBrowser) { Start-Process $appUrl }
