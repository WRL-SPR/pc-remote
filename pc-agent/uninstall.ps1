#Requires -RunAsAdministrator
$ErrorActionPreference = 'SilentlyContinue'
Stop-ScheduledTask -TaskName 'PcRemoteAgent'
Unregister-ScheduledTask -TaskName 'PcRemoteAgent' -Confirm:$false
Get-NetFirewallRule -DisplayName 'PC Remote Agent' | Remove-NetFirewallRule
# The task runs powershell.exe; stop the copy that is running our script.
Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" |
    Where-Object CommandLine -like '*PcRemoteAgent\PcAgent.ps1*' |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Remove-Item -Recurse -Force (Join-Path $env:ProgramData 'PcRemoteAgent')
Write-Host 'PC Remote Agent removed.'
