<#
  Run by the uninstaller (elevated) before the program files are deleted.
    always         stops the agent, removes the boot task and the firewall rule
    -RestorePower  puts back the Fast Startup / wake-on-magic-packet values saved by -SetupWol
    -PurgeData     deletes C:\ProgramData\PcRemoteAgent (config, token, logs)
#>
param(
    [switch]$PurgeData,
    [switch]$RestorePower
)

. (Join-Path $PSScriptRoot 'agent-common.ps1')

Stop-AgentProcess
Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
Get-NetFirewallRule -DisplayName $FirewallRuleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule

if ($RestorePower -and (Test-Path $WolStatePath)) {
    $state = Get-Content -LiteralPath $WolStatePath -Raw | ConvertFrom-Json
    if ($null -eq $state.hiberbootEnabled) {
        Remove-ItemProperty -Path $PowerKey -Name HiberbootEnabled -ErrorAction SilentlyContinue
    } else {
        Set-ItemProperty -Path $PowerKey -Name HiberbootEnabled -Value ([int]$state.hiberbootEnabled) -Type DWord
    }
    foreach ($a in @($state.adapters)) {
        if ($a.wakeOnMagicPacket -in 'Enabled', 'Disabled') {
            Set-NetAdapterPowerManagement -Name $a.name -WakeOnMagicPacket $a.wakeOnMagicPacket -ErrorAction SilentlyContinue
        }
    }
    Remove-Item -LiteralPath $WolStatePath -Force
}

if ($PurgeData) {
    Remove-Item -LiteralPath $DataDir -Recurse -Force -ErrorAction SilentlyContinue
}
exit 0
