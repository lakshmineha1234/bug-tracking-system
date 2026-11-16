# Downloads the Maven version named in maven-wrapper.properties into
# %USERPROFILE%\.m2\wrapper\dists on first use, then prints the Maven home folder.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$props = Join-Path $PSScriptRoot 'maven-wrapper.properties'
$url = ((Get-Content $props) | Where-Object { $_ -match '^distributionUrl=' }) -replace '^distributionUrl=', ''
if (-not $url) { throw "distributionUrl is missing from $props" }

$name = [IO.Path]::GetFileNameWithoutExtension(([Uri]$url).AbsolutePath)
$dest = Join-Path $env:USERPROFILE ".m2\wrapper\dists\$name"

if (-not (Test-Path (Join-Path $dest 'bin\mvn.cmd'))) {
    [Console]::Error.WriteLine("Downloading $url")
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $zip = Join-Path $env:TEMP "$name.zip"
    $tmp = "$dest.tmp"
    Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $zip
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    Expand-Archive -Path $zip -DestinationPath $tmp
    New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
    Move-Item -Path (Get-ChildItem $tmp | Select-Object -First 1).FullName -Destination $dest
    Remove-Item -Recurse -Force $tmp
    Remove-Item -Force $zip
}

Write-Output $dest
