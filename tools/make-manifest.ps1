<#
  Writes latest.json for a GitHub release. The phone app and the PC tray read it from
    https://github.com/WRL-SPR/pc-remote/releases/latest/download/latest.json
  to find out about new versions, show the release notes and check the downloaded file's SHA-256.

  Every release must attach latest.json together with PcRemote.apk and the PcRemoteAgent setup.

    pwsh tools/make-manifest.ps1 -NotesFile notes.txt
#>
param(
    [Parameter(Mandatory)][string]$NotesFile,
    [string]$Apk,
    [string]$Out
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $Apk) { $Apk = Join-Path $root 'PcRemote.apk' }
if (-not $Out) { $Out = Join-Path $root 'dist\latest.json' }

$gradle = Get-Content (Join-Path $root 'android\app\build.gradle.kts') -Raw
$appVersion = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$appCode = [int][regex]::Match($gradle, 'versionCode\s*=\s*(\d+)').Groups[1].Value
$iss = Get-Content (Join-Path $root 'pc-agent\installer\PcRemoteAgent.iss') -Raw
$agentVersion = [regex]::Match($iss, '#define AppVersion "([^"]+)"').Groups[1].Value
$setup = Join-Path $root "dist\PcRemoteAgent-Setup-$agentVersion.exe"

foreach ($f in $Apk, $setup) { if (-not (Test-Path $f)) { throw "Missing $f - build it first." } }

$manifest = [ordered]@{
    app       = [ordered]@{
        version     = $appVersion
        versionCode = $appCode
        file        = 'PcRemote.apk'
        size        = (Get-Item $Apk).Length
        sha256      = (Get-FileHash $Apk -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    agent     = [ordered]@{
        version = $agentVersion
        file    = Split-Path -Leaf $setup
        size    = (Get-Item $setup).Length
        sha256  = (Get-FileHash $setup -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    notes     = (Get-Content $NotesFile -Raw -Encoding UTF8).Trim()
    published = (Get-Date -Format 'yyyy-MM-dd')
}
$json = $manifest | ConvertTo-Json -Depth 4
[IO.File]::WriteAllText($Out, $json, (New-Object Text.UTF8Encoding $false))
Write-Host "latest.json: app $appVersion ($appCode), agent $agentVersion -> $Out"
