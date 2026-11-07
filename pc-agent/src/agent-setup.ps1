<#
  Run by the installer (elevated) after the files are copied:
    - creates C:\ProgramData\PcRemoteAgent (SYSTEM/Administrators only) with the config and token
      (an existing token is kept, so reinstalling does not break the phone app)
    - opens the port in Windows Firewall for Private networks
    - registers the boot-time SYSTEM task that runs PcAgent.ps1 from the install folder

  -SetupWol  disables Fast Startup and enables "wake on magic packet" on wired adapters, saving the
             original values first so the uninstaller can put them back.
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
        # Record the originals only once, so a reinstall doesn't overwrite them with our own values.
        if (-not (Test-Path $WolStatePath)) {
            $hiberboot = (Get-ItemProperty -Path $PowerKey -Name HiberbootEnabled -ErrorAction SilentlyContinue).HiberbootEnabled
            $adapters = @($wired | ForEach-Object {
                $pm = Get-NetAdapterPowerManagement -Name $_.Name -ErrorAction SilentlyContinue
                if ($pm) { @{ name = $_.Name; wakeOnMagicPacket = [string]$pm.WakeOnMagicPacket } }
            })
            @{ hiberbootEnabled = $hiberboot; adapters = $adapters } |
                ConvertTo-Json -Depth 3 | Set-Content -LiteralPath $WolStatePath -Encoding UTF8
        }

        Set-ItemProperty -Path $PowerKey -Name HiberbootEnabled -Value 0 -Type DWord
        Write-Host 'Fast Startup disabled.'
        foreach ($nic in $wired) {
            try {
                Set-NetAdapterPowerManagement -Name $nic.Name -WakeOnMagicPacket Enabled -ErrorAction Stop
                powercfg /deviceenablewake "$($nic.InterfaceDescription)" 2>$null | Out-Null
                Write-Host "Wake on magic packet enabled: $($nic.Name)"
            } catch {
                Write-Warning "Could not change '$($nic.Name)': $_"
            }
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
