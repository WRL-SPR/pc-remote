# Shared names and helpers for the agent scripts. Dot-source this file.

$TaskName = 'PcRemoteAgent'
$TrayTaskName = 'PcRemoteAgentTray'
# Non-secret settings readable by every user (the tray icon needs the port without admin rights).
$RegKey = 'HKLM:\SOFTWARE\PcRemoteAgent'
$FirewallRuleName = 'PC Remote Agent'
$DataDir = Join-Path $env:ProgramData 'PcRemoteAgent'
$AgentConfigPath = Join-Path $DataDir 'agent-config.json'
# Original power settings, saved before -SetupWol changes them, so uninstall can restore them.
$WolStatePath = Join-Path $DataDir 'wol-state.json'
$PowerKey = 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Power'
# NIC advanced settings (registry keywords, so they work in any Windows language) that -SetupWol
# turns off: pattern wake, and Realtek power saving that can stop the NIC hearing the magic packet.
$NicAdvancedKeys = '*WakeOnPattern', 'PowerSavingMode', 'EnableGreenEthernet'

# Windows groups windows and notifications by this ID. The installer puts the same ID on the Start
# menu shortcut, so the taskbar and notifications show "PC 리모트 에이전트" and its icon instead of
# Windows PowerShell.
$AppUserModelId = 'WRL-SPR.PcRemoteAgent'

function Set-AppIdentity {
    Add-Type -Namespace PcRemote -Name Shell -ErrorAction SilentlyContinue -MemberDefinition @'
[DllImport("shell32.dll", CharSet = CharSet.Unicode)]
public static extern int SetCurrentProcessExplicitAppUserModelID(string appId);
'@
    [void][PcRemote.Shell]::SetCurrentProcessExplicitAppUserModelID($AppUserModelId)
}

function Get-AppIconPath {
    $p = Join-Path $PSScriptRoot 'app.ico'
    if (Test-Path $p) { $p } else { $null }
}

function Get-AgentVersion {
    $v = (Get-ItemProperty -Path $RegKey -Name Version -ErrorAction SilentlyContinue).Version
    if ($v) { [string]$v } else { $null }
}

# ---------- updates ----------
# Each GitHub release carries latest.json (see tools/make-manifest.ps1) with the newest versions,
# release notes and SHA-256 of the files.
$ReleaseBase = 'https://github.com/WRL-SPR/pc-remote/releases/latest/download'

function Get-LatestRelease {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $wc = New-Object Net.WebClient
    $wc.Encoding = [Text.Encoding]::UTF8
    $wc.Headers['User-Agent'] = 'PcRemoteAgent'
    try { $wc.DownloadString("$ReleaseBase/latest.json") | ConvertFrom-Json } finally { $wc.Dispose() }
}

function Test-NewerVersion([string]$candidate, [string]$current) {
    if (-not $candidate) { return $false }
    if (-not $current) { return $true }
    try { return [version]$candidate -gt [version]$current } catch { return $false }
}

# Downloads the agent setup named in the manifest, checks its SHA-256 and runs it silently
# (same options and token as before; the installer restarts the agent and the tray).
function Install-AgentUpdate($release) {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $file = Join-Path $env:TEMP $release.agent.file
    $wc = New-Object Net.WebClient
    $wc.Headers['User-Agent'] = 'PcRemoteAgent'
    try { $wc.DownloadFile("$ReleaseBase/$($release.agent.file)", $file) } finally { $wc.Dispose() }
    $hash = (Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($hash -ne $release.agent.sha256) {
        Remove-Item $file -Force -ErrorAction SilentlyContinue
        throw '받은 설치 파일이 손상되었거나 릴리스 정보와 다릅니다. 다시 시도해 주세요.'
    }
    $setupArgs = '/SILENT /SUPPRESSMSGBOXES /NORESTART /SP-'
    $isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
    if ($isAdmin) { Start-Process $file -ArgumentList $setupArgs }
    else { Start-Process $file -ArgumentList $setupArgs -Verb RunAs }
}

function New-AgentToken {
    $chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'.ToCharArray()
    $bytes = New-Object byte[] 24
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
}

function Save-AgentConfig([int]$Port, [string]$Token) {
    @{ port = $Port; token = $Token } | ConvertTo-Json | Set-Content -LiteralPath $AgentConfigPath -Encoding ASCII
}

function Stop-AgentProcess {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" |
        Where-Object CommandLine -like '*PcAgent.ps1*' |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

function Stop-TrayProcess {
    Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" |
        Where-Object CommandLine -like '*\tray.ps1*' |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

function Restart-AgentTask {
    Stop-AgentProcess
    Start-ScheduledTask -TaskName $TaskName
}
