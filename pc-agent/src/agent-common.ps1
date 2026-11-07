# Shared names and helpers for the agent scripts. Dot-source this file.

$TaskName = 'PcRemoteAgent'
$FirewallRuleName = 'PC Remote Agent'
$DataDir = Join-Path $env:ProgramData 'PcRemoteAgent'
$AgentConfigPath = Join-Path $DataDir 'agent-config.json'
# Original power settings, saved before -SetupWol changes them, so uninstall can restore them.
$WolStatePath = Join-Path $DataDir 'wol-state.json'
$PowerKey = 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Power'

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

function Restart-AgentTask {
    Stop-AgentProcess
    Start-ScheduledTask -TaskName $TaskName
}
