<#
  Run by the uninstaller (elevated) before the program files are deleted.
    always         stops the agent and tray icon, removes their tasks, the firewall rule and registry key
    -RestorePower  puts back the Fast Startup and network adapter wake / power-saving values saved by -SetupWol
    -PurgeData     deletes C:\ProgramData\PcRemoteAgent (config, token, logs)
#>
param(
    [switch]$PurgeData,
    [switch]$RestorePower
)

. (Join-Path $PSScriptRoot 'agent-common.ps1')

Stop-AgentProcess
Stop-TrayProcess
Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
Unregister-ScheduledTask -TaskName $TrayTaskName -Confirm:$false -ErrorAction SilentlyContinue
Get-NetFirewallRule -DisplayName $FirewallRuleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
Remove-Item -Path $RegKey -Recurse -Force -ErrorAction SilentlyContinue

if ($RestorePower -and (Test-Path $WolStatePath)) {
    $state = Get-Content -LiteralPath $WolStatePath -Raw | ConvertFrom-Json
    if ($null -eq $state.hiberbootEnabled) {
        Remove-ItemProperty -Path $PowerKey -Name HiberbootEnabled -ErrorAction SilentlyContinue
    } else {
        Set-ItemProperty -Path $PowerKey -Name HiberbootEnabled -Value ([int]$state.hiberbootEnabled) -Type DWord
    }
    foreach ($a in @($state.adapters)) {
        if (-not $a) { continue }
        $wake = @{}
        if ($a.wakeOnMagicPacket -in 'Enabled', 'Disabled') { $wake.WakeOnMagicPacket = $a.wakeOnMagicPacket }
        if ($a.PSObject.Properties['wakeOnPattern'] -and $a.wakeOnPattern -in 'Enabled', 'Disabled') { $wake.WakeOnPattern = $a.wakeOnPattern }
        if ($wake.Count) { Set-NetAdapterPowerManagement -Name $a.name @wake -ErrorAction SilentlyContinue }

        $changed = $false
        foreach ($adv in @($a.advanced)) {
            if ($adv -and $null -ne $adv.value -and $adv.value -ne '') {
                Set-NetAdapterAdvancedProperty -Name $a.name -RegistryKeyword $adv.keyword -RegistryValue $adv.value `
                    -NoRestart -ErrorAction SilentlyContinue
                $changed = $true
            }
        }
        if ($changed) { Restart-NetAdapter -Name $a.name -Confirm:$false -ErrorAction SilentlyContinue }
    }
    Remove-Item -LiteralPath $WolStatePath -Force
}

if ($PurgeData) {
    Remove-Item -LiteralPath $DataDir -Recurse -Force -ErrorAction SilentlyContinue
}
exit 0
