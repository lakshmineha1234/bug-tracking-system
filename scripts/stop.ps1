# Stops BugTrack: the app, the test mail server, and MySQL (cleanly). A MySQL Windows service is left running
# unless -IncludeService is given.
param([switch]$IncludeService)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

# The app (the Java process serving port 8080) and the Maven process that launched it.
$app = Get-PortOwner 8080
if ($app) {
    Write-Step 'Stopping BugTrack...'
    Stop-Process -Id $app.Id -Force
    Get-CimInstance Win32_Process -Filter "name='java.exe'" |
        Where-Object { $_.CommandLine -match 'plexus-classworlds' -and $_.CommandLine -match [regex]::Escape($ProjectRoot) } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Write-Step 'BugTrack stopped.'
} else {
    Write-Step 'BugTrack is not running.'
}

# The test mail server.
$mail = Get-PortOwner 1025
if ($mail) {
    Stop-Process -Id $mail.Id -Force
    Write-Step 'Test mail server stopped.'
}

# MySQL.
if (-not (Test-Port 3306)) {
    Write-Step 'MySQL is not running.'
} else {
    $service = Get-Service -Name $MySqlServiceName -ErrorAction SilentlyContinue
    if ($service -and $service.Status -eq 'Running' -and -not $IncludeService) {
        Write-Step "MySQL runs as the Windows service $MySqlServiceName and was left running."
    } else {
        Write-Step 'Stopping MySQL...'
        Stop-MySqlCleanly
        Write-Step 'MySQL stopped.'
    }
}
