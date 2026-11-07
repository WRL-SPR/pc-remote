<#
  Tray icon for PC 리모트 에이전트. Shows whether the agent answers (colour icon = running,
  grey = stopped, with a notification when it stops) and gives quick access to the connection
  info, an agent restart and the log. Started at logon by the 'PcRemoteAgentTray' task, elevated
  for administrators. Closing it does not stop the agent.

  -RenderTo <png>  draws the menu (with sample status) to an image instead of running (previews).
  -NoElevate       don't ask for admin rights (status only; for testing).
#>
param(
    [string]$RenderTo,
    [switch]$NoElevate
)

. (Join-Path $PSScriptRoot 'agent-common.ps1')
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
$title = 'PC 리모트 에이전트'

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin -and -not $RenderTo -and -not $NoElevate) {
    # Started from the Start menu: ask for admin rights once; if refused, keep running with status only.
    try {
        Start-Process powershell.exe -Verb RunAs -WindowStyle Hidden `
            -ArgumentList "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$PSCommandPath`""
        return
    } catch {}
}

if (-not $RenderTo) {
    $created = $false
    $mutex = New-Object Threading.Mutex($true, 'Local\PcRemoteAgentTray', [ref]$created)
    if (-not $created) { return }   # already running in this session
}

$port = (Get-ItemProperty -Path $RegKey -Name Port -ErrorAction SilentlyContinue).Port
if (-not $port) { $port = 9876 }

# ---------- icons ----------
$iconPath = Join-Path $PSScriptRoot 'app.ico'
$baseIcon = if (Test-Path $iconPath) { New-Object Drawing.Icon($iconPath, 16, 16) } else { [Drawing.SystemIcons]::Application }
$colorIcon = New-Object Drawing.Icon($baseIcon, [Windows.Forms.SystemInformation]::SmallIconSize)
$grayIcon = & {
    $src = $colorIcon.ToBitmap()
    $dst = New-Object Drawing.Bitmap($src.Width, $src.Height)
    $g = [Drawing.Graphics]::FromImage($dst)
    $matrix = New-Object Drawing.Imaging.ColorMatrix(, [float[][]]@(
        [float[]]@(0.3, 0.3, 0.3, 0, 0), [float[]]@(0.59, 0.59, 0.59, 0, 0), [float[]]@(0.11, 0.11, 0.11, 0, 0),
        [float[]]@(0, 0, 0, 0.6, 0), [float[]]@(0, 0, 0, 0, 1)))
    $attrs = New-Object Drawing.Imaging.ImageAttributes
    $attrs.SetColorMatrix($matrix)
    $g.DrawImage($src, (New-Object Drawing.Rectangle(0, 0, $src.Width, $src.Height)), 0, 0, $src.Width, $src.Height, 'Pixel', $attrs)
    $g.Dispose()
    [Drawing.Icon]::FromHandle($dst.GetHicon())
}

# ---------- status ----------
function Test-AgentRunning {
    try {
        $req = [Net.HttpWebRequest]::Create("http://127.0.0.1:$port/ping")
        $req.Timeout = 1500
        $resp = $req.GetResponse()
        $ok = [int]$resp.StatusCode -eq 200
        $resp.Close()
        $ok
    } catch { $false }
}

# ---------- menu ----------
$menu = New-Object Windows.Forms.ContextMenuStrip
$menu.Font = New-Object Drawing.Font('Malgun Gothic', 9.5)
$menu.ShowImageMargin = $false

# A label (not a disabled item) so the status colour shows.
$statusItem = New-Object Windows.Forms.ToolStripLabel('상태 확인 중…')
$statusItem.Font = New-Object Drawing.Font('Malgun Gothic', 9.5, [Drawing.FontStyle]::Bold)
$statusItem.Padding = New-Object Windows.Forms.Padding(4, 4, 4, 4)
[void]$menu.Items.Add($statusItem)
[void]$menu.Items.Add((New-Object Windows.Forms.ToolStripSeparator))

$infoItem = $menu.Items.Add('연결 정보 보기 (토큰 · IP · MAC)')
$infoItem.Font = New-Object Drawing.Font('Malgun Gothic', 9.5, [Drawing.FontStyle]::Bold)
$restartItem = $menu.Items.Add('에이전트 다시 시작')
$logItem = $menu.Items.Add('로그 보기')
[void]$menu.Items.Add((New-Object Windows.Forms.ToolStripSeparator))
$exitItem = $menu.Items.Add('트레이 아이콘 닫기 (에이전트는 계속 실행)')

$restartItem.Enabled = $isAdmin
$logItem.Enabled = $isAdmin

$notify = New-Object Windows.Forms.NotifyIcon
$notify.ContextMenuStrip = $menu
$script:running = $null

function Update-Status {
    $now = Test-AgentRunning
    if ($now) {
        $statusItem.Text = "●  실행 중 · 포트 $port"
        $statusItem.ForeColor = [Drawing.Color]::FromArgb(22, 163, 74)
        $notify.Icon = $colorIcon
        $notify.Text = "$title · 실행 중"
    } else {
        $statusItem.Text = '●  에이전트가 응답하지 않음'
        $statusItem.ForeColor = [Drawing.Color]::FromArgb(211, 47, 47)
        $notify.Icon = $grayIcon
        $notify.Text = "$title · 멈춤"
        if ($script:running -eq $true) {
            $notify.ShowBalloonTip(5000, $title, '에이전트가 응답하지 않습니다. 휴대폰에서 이 PC를 끄거나 절전시킬 수 없습니다.', 'Warning')
        }
    }
    $script:running = $now
}

function Open-Info {
    Start-Process powershell.exe -WindowStyle Hidden `
        -ArgumentList "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$(Join-Path $PSScriptRoot 'show-info.ps1')`""
}

if ($RenderTo) {
    # Preview: sample "running" state, menu drawn off-screen.
    $statusItem.Text = "●  실행 중 · 포트 $port"
    $statusItem.ForeColor = [Drawing.Color]::FromArgb(22, 163, 74)
    $restartItem.Enabled = $true
    $logItem.Enabled = $true
    $menu.Show(-3000, -3000)
    $bmp = New-Object Drawing.Bitmap($menu.Width, $menu.Height)
    $menu.DrawToBitmap($bmp, (New-Object Drawing.Rectangle(0, 0, $menu.Width, $menu.Height)))
    $bmp.Save($RenderTo, [Drawing.Imaging.ImageFormat]::Png)
    $menu.Close()
    return
}

$infoItem.Add_Click({ Open-Info })
$notify.Add_DoubleClick({ Open-Info })
$restartItem.Add_Click({
    $statusItem.Text = '●  다시 시작하는 중…'
    try { Restart-AgentTask } catch {}
    Start-Sleep -Seconds 2
    Update-Status
})
$logItem.Add_Click({
    $log = Join-Path $DataDir 'agent.log'
    if (Test-Path $log) { Start-Process notepad.exe $log }
    else { [Windows.Forms.MessageBox]::Show('아직 로그가 없습니다.', $title) | Out-Null }
})

$context = New-Object Windows.Forms.ApplicationContext
$exitItem.Add_Click({
    $notify.Visible = $false
    $context.ExitThread()
})

Update-Status
$notify.Visible = $true

$timer = New-Object Windows.Forms.Timer
$timer.Interval = 5000
$timer.Add_Tick({ Update-Status })
$timer.Start()

[Windows.Forms.Application]::Run($context)
$timer.Stop()
$notify.Dispose()
$mutex.ReleaseMutex()
