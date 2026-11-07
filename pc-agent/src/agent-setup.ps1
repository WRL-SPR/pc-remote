<#
  Run by the installer (elevated) after the files are copied:
    - creates C:\ProgramData\PcRemoteAgent (SYSTEM/Administrators only) with the config and token
      (an existing token is kept, so reinstalling does not break the phone app)
    - opens the port in Windows Firewall for Private networks
    - registers the boot-time SYSTEM task that runs PcAgent.ps1 from the install folder

  -SetupWol  disables Fast Startup; on wired adapters enables "wake on magic packet", disables
             pattern wake and NIC power saving (Realtek Power Saving Mode / Green Ethernet). The
             original values are saved first so the uninstaller can put them back.
  -NoTray    don't start the tray icon at logon.
#>
param(
    [Parameter(Mandatory)][string]$InstallDir,
    [int]$Port = 9876,
    [switch]$SetupWol,
    [switch]$NoTray
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'agent-common.ps1')

New-Item -ItemType Directory -Force -Path $DataDir | Out-Null
icacls $DataDir /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
Start-Transcript -LiteralPath (Join-Path $DataDir 'setup.log') -Force | Out-Null

try {
    $token = $null
    if (Test-Path $AgentConfigPath) {
        $token = (Get-Content -LiteralPath $AgentConfigPath -Raw | ConvertFrom-Json).token
    }
    if ([string]::IsNullOrWhiteSpace($token)) { $token = New-AgentToken }
    Save-AgentConfig -Port $Port -Token $token

    # Leftover from the earlier script-only install, which ran the agent from the data folder.
    Remove-Item -LiteralPath (Join-Path $DataDir 'PcAgent.ps1') -Force -ErrorAction SilentlyContinue

    Get-NetFirewallRule -DisplayName $FirewallRuleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    New-NetFirewallRule -DisplayName $FirewallRuleName -Direction Inbound -Protocol TCP -LocalPort $Port `
        -Action Allow -Profile Private | Out-Null

    $agentScript = Join-Path $InstallDir 'PcAgent.ps1'
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' `
        -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$agentScript`" -ConfigPath `"$AgentConfigPath`""
    $trigger = New-ScheduledTaskTrigger -AtStartup
    $principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable `
        -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1)
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Principal $principal `
        -Settings $settings -Force | Out-Null

    New-Item -Path $RegKey -Force | Out-Null
    Set-ItemProperty -Path $RegKey -Name Port -Value $Port -Type DWord

    # Tray icon at every user's logon, elevated for administrators (so it can show the token).
    # A running tray from an earlier version is closed; the installer starts the new one.
    Stop-TrayProcess
    Unregister-ScheduledTask -TaskName $TrayTaskName -Confirm:$false -ErrorAction SilentlyContinue
    if (-not $NoTray) {
        $users = (New-Object Security.Principal.SecurityIdentifier 'S-1-5-32-545').Translate([Security.Principal.NTAccount]).Value
        $trayAction = New-ScheduledTaskAction -Execute 'powershell.exe' `
            -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$(Join-Path $InstallDir 'tray.ps1')`""
        $traySettings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
            -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances Parallel
        Register-ScheduledTask -TaskName $TrayTaskName -Action $trayAction -Trigger (New-ScheduledTaskTrigger -AtLogOn) `
            -Principal (New-ScheduledTaskPrincipal -GroupId $users -RunLevel Highest) -Settings $traySettings -Force | Out-Null
    }

    if ($SetupWol) {
        $wired = @(Get-NetAdapter -Physical | Where-Object MediaType -eq '802.3')

        # Record the originals once per setting: a reinstall must not overwrite them with our own
        # values, but settings added in later versions are still recorded the first time.
        $state = if (Test-Path $WolStatePath) { Get-Content -LiteralPath $WolStatePath -Raw | ConvertFrom-Json } else { $null }
        $hiberboot = if ($state) { $state.hiberbootEnabled }
                     else { (Get-ItemProperty -Path $PowerKey -Name HiberbootEnabled -ErrorAction SilentlyContinue).HiberbootEnabled }
        $saved = @{}
        foreach ($a in @($state.adapters)) { if ($a) { $saved[$a.name] = $a } }

        $adapters = @(foreach ($nic in $wired) {
            $old = $saved[$nic.Name]
            $pm = Get-NetAdapterPowerManagement -Name $nic.Name -ErrorAction SilentlyContinue
            $advanced = @(foreach ($kw in $NicAdvancedKeys) {
                $prev = if ($old) { @($old.advanced) | Where-Object { $_ -and $_.keyword -eq $kw } | Select-Object -First 1 }
                if ($prev) {
                    @{ keyword = $kw; value = [string]$prev.value }
                } else {
                    $p = Get-NetAdapterAdvancedProperty -Name $nic.Name -RegistryKeyword $kw -ErrorAction SilentlyContinue
                    if ($p) { @{ keyword = $kw; value = [string]@($p.RegistryValue)[0] } }
                }
            })
            @{
                name              = $nic.Name
                wakeOnMagicPacket = if ($old -and $old.wakeOnMagicPacket) { [string]$old.wakeOnMagicPacket } elseif ($pm) { [string]$pm.WakeOnMagicPacket } else { $null }
                wakeOnPattern     = if ($old -and $old.PSObject.Properties['wakeOnPattern']) { [string]$old.wakeOnPattern } elseif ($pm) { [string]$pm.WakeOnPattern } else { $null }
                advanced          = $advanced
            }
        })
        @{ hiberbootEnabled = $hiberboot; adapters = $adapters } |
            ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $WolStatePath -Encoding UTF8

        Set-ItemProperty -Path $PowerKey -Name HiberbootEnabled -Value 0 -Type DWord
        Write-Host 'Fast Startup disabled.'
        foreach ($nic in $wired) {
            try {
                # Magic packet only: pattern wake would also wake the PC on ordinary traffic,
                # such as the phone app's status checks right after a sleep command.
                Set-NetAdapterPowerManagement -Name $nic.Name -WakeOnMagicPacket Enabled -WakeOnPattern Disabled -ErrorAction Stop
                powercfg /deviceenablewake "$($nic.InterfaceDescription)" 2>$null | Out-Null
                Write-Host "Wake on magic packet only: $($nic.Name)"
            } catch {
                Write-Warning "Could not change wake settings of '$($nic.Name)': $_"
            }
            $changed = $false
            foreach ($kw in $NicAdvancedKeys) {
                $p = Get-NetAdapterAdvancedProperty -Name $nic.Name -RegistryKeyword $kw -ErrorAction SilentlyContinue
                if ($p -and [string]@($p.RegistryValue)[0] -ne '0') {
                    try {
                        Set-NetAdapterAdvancedProperty -Name $nic.Name -RegistryKeyword $kw -RegistryValue 0 -NoRestart -ErrorAction Stop
                        $changed = $true
                        Write-Host "$kw off: $($nic.Name)"
                    } catch {
                        Write-Warning "Could not change $kw on '$($nic.Name)': $_"
                    }
                }
            }
            # Advanced settings take effect when the adapter restarts (a few seconds offline).
            if ($changed) { Restart-NetAdapter -Name $nic.Name -Confirm:$false -ErrorAction SilentlyContinue }
        }
    }

    Restart-AgentTask
    Write-Host "Agent installed on port $Port."
} catch {
    Write-Error $_
    Stop-Transcript | Out-Null
    exit 1
}
Stop-Transcript | Out-Null
