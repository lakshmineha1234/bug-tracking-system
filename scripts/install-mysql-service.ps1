# Registers BugTrack's MySQL as a Windows service that starts automatically with Windows.
# Run as administrator. Use -Remove to unregister it again (the database files are not touched).
param([switch]$Remove)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    throw 'Run this as administrator: right-click install-mysql-service.cmd and choose "Run as administrator".'
}

$mysqld = Find-MySqlBin 'mysqld.exe'
$service = Get-Service -Name $MySqlServiceName -ErrorAction SilentlyContinue

if ($Remove) {
    if (-not $service) { Write-Step "There is no $MySqlServiceName service."; return }
    if ($service.Status -eq 'Running') {
        Write-Step 'Stopping the service...'
        Stop-Service -Name $MySqlServiceName
    }
    & $mysqld --remove $MySqlServiceName | Out-Null
    Write-Step "Service $MySqlServiceName removed. Start MySQL with start.cmd from now on."
    return
}

if ($service) {
    Write-Step "The $MySqlServiceName service already exists (status: $($service.Status))."
    if ($service.Status -ne 'Running') { Start-Service -Name $MySqlServiceName; Write-Step 'Service started.' }
    return
}
if (-not (Test-Path $MySqlIni)) { throw "MySQL settings were not found at $MySqlIni." }

# A MySQL started by start.cmd uses the same data folder, so it must stop before the service takes over.
if (Test-Port 3306) {
    Write-Step 'Stopping the MySQL that is running now...'
    Stop-MySqlCleanly
}

Write-Step "Registering the $MySqlServiceName service..."
& $mysqld --install $MySqlServiceName "--defaults-file=$MySqlIni"
if ($LASTEXITCODE -ne 0) { throw 'mysqld --install failed.' }
Set-Service -Name $MySqlServiceName -StartupType Automatic
Start-Service -Name $MySqlServiceName
Wait-Port 3306 60 'MySQL'
Write-Step "MySQL now runs as the Windows service $MySqlServiceName and starts with Windows."
