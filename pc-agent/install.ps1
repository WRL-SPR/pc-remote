#Requires -RunAsAdministrator
<#
  Installs PC Remote Agent:
    - copies PcAgent.ps1 to C:\ProgramData\PcRemoteAgent
    - creates a random token (kept on reinstall)
    - opens the port in Windows Firewall (Private networks only)
    - registers a scheduled task that runs the agent as SYSTEM at boot

  -SetupWol  also disables Fast Startup and enables "wake on magic packet" on wired adapters.
#>
param(
    [int]$Port = 9876,
    [switch]$SetupWol
)

$ErrorActionPreference = 'Stop'
$taskName = 'PcRemoteAgent'
$dir = Join-Path $env:ProgramData 'PcRemoteAgent'
$cfgPath = Join-Path $dir 'agent-config.json'

New-Item -ItemType Directory -Force -Path $dir | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'PcAgent.ps1') -Destination $dir -Force

if (Test-Path $cfgPath) {
    $token = (Get-Content -LiteralPath $cfgPath -Raw | ConvertFrom-Json).token
}
if ([string]::IsNullOrWhiteSpace($token)) {
    $chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'.ToCharArray()
    $bytes = New-Object byte[] 24
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $token = -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
}
@{ port = $Port; token = $token } | ConvertTo-Json | Set-Content -LiteralPath $cfgPath -Encoding ASCII
# Only SYSTEM and Administrators may read the token.
icacls $cfgPath /inheritance:r /grant:r '*S-1-5-18:F' '*S-1-5-32-544:F' | Out-Null

Get-NetFirewallRule -DisplayName 'PC Remote Agent' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName 'PC Remote Agent' -Direction Inbound -Protocol TCP -LocalPort $Port `
    -Action Allow -Profile Private | Out-Null

$action = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$dir\PcAgent.ps1`""
$trigger = New-ScheduledTaskTrigger -AtStartup
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Principal $principal `
    -Settings $settings -Force | Out-Null
Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
Start-ScheduledTask -TaskName $taskName

if ($SetupWol) {
    # Fast Startup is a hybrid shutdown that often prevents WOL from a full power-off.
    Set-ItemProperty -Path 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Power' -Name HiberbootEnabled -Value 0
    Write-Host 'Fast Startup disabled.'
    Get-NetAdapter -Physical | Where-Object { $_.MediaType -eq '802.3' } | ForEach-Object {
        try {
            Set-NetAdapterPowerManagement -Name $_.Name -WakeOnMagicPacket Enabled -ErrorAction Stop
            powercfg /deviceenablewake "$($_.InterfaceDescription)" 2>$null | Out-Null
            Write-Host "Wake on magic packet enabled: $($_.Name)"
        } catch {
            Write-Warning "Could not change '$($_.Name)': set 'Wake on Magic Packet' manually in Device Manager."
        }
    }
}

Write-Host ''
Write-Host '=== PC Remote Agent installed ===' -ForegroundColor Green
Write-Host "Port : $Port"
Write-Host "Token: $token" -ForegroundColor Yellow
Write-Host ''
Write-Host 'Enter these in the Android app:'
Get-NetAdapter -Physical | Where-Object Status -eq 'Up' | ForEach-Object {
    $ip = (Get-NetIPAddress -InterfaceIndex $_.ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue).IPAddress -join ', '
    Write-Host ("  {0,-20} IP: {1,-16} MAC: {2}" -f $_.Name, $ip, ($_.MacAddress -replace '-', ':'))
}
$profiles = Get-NetConnectionProfile | Where-Object NetworkCategory -eq 'Public'
if ($profiles) {
    Write-Warning "Network '$($profiles.Name -join ', ')' is Public. The firewall rule only covers Private networks;"
    Write-Warning "set it to Private in Settings > Network & internet, or the app cannot reach the agent."
}
