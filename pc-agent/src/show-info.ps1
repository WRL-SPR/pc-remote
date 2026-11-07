<#
  "연결 정보 보기": shows what to enter in the Android app (token, IP, MAC), with copy buttons and
  a way to issue a new token. Needs admin rights because only Administrators/SYSTEM can read the token.

  -RenderTo <png>  draws the window with sample data to an image instead of showing it (previews).
#>
param(
    [string]$RenderTo
)

. (Join-Path $PSScriptRoot 'agent-common.ps1')
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
$title = 'PC 리모트 에이전트'

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin -and -not $RenderTo) {
    try {
        Start-Process powershell.exe -Verb RunAs -WindowStyle Hidden `
            -ArgumentList "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$PSCommandPath`""
    } catch {
        [System.Windows.Forms.MessageBox]::Show('토큰을 보려면 관리자 권한이 필요합니다.', $title) | Out-Null
    }
    return
}

if ($RenderTo) {
    $config = [pscustomobject]@{ port = 9876; token = 'Xk7PqR2mWv9TzH4nLc8sYb3D' }
    $adapters = @(
        [pscustomobject]@{ Name = '이더넷'; Wired = $true; Ip = '192.168.0.10'; Mac = '1C:69:7A:12:34:56' }
        [pscustomobject]@{ Name = 'Wi-Fi'; Wired = $false; Ip = '192.168.0.23'; Mac = 'A4:B1:C1:9E:20:7F' }
    )
    $running = $true
} else {
    Add-Type @'
using System.Runtime.InteropServices;
public static class PcRemoteDpi { [DllImport("user32.dll")] public static extern bool SetProcessDPIAware(); }
'@
    [void][PcRemoteDpi]::SetProcessDPIAware()
    Set-AppIdentity
    if (-not (Test-Path $AgentConfigPath)) {
        [System.Windows.Forms.MessageBox]::Show('설정 파일이 없습니다. 프로그램을 다시 설치해 주세요.', $title) | Out-Null
        return
    }
    $config = Get-Content -LiteralPath $AgentConfigPath -Raw | ConvertFrom-Json
    $adapters = @(Get-NetAdapter -Physical | Where-Object Status -eq 'Up' | Sort-Object { $_.MediaType -ne '802.3' } | ForEach-Object {
        [pscustomobject]@{
            Name  = $_.Name
            Wired = $_.MediaType -eq '802.3'
            Ip    = (Get-NetIPAddress -InterfaceIndex $_.ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue |
                     Select-Object -First 1).IPAddress
            Mac   = $_.MacAddress -replace '-', ':'
        }
    })
    $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    $listening = Get-NetTCPConnection -LocalPort $config.port -State Listen -ErrorAction SilentlyContinue
    $running = $task -and $task.State -eq 'Running' -and $listening
}

[System.Windows.Forms.Application]::EnableVisualStyles()

$ink = [Drawing.Color]::FromArgb(24, 27, 35)
$muted = [Drawing.Color]::FromArgb(94, 100, 116)
$accent = [Drawing.Color]::FromArgb(54, 81, 212)
$panelBg = [Drawing.Color]::FromArgb(245, 246, 250)
$fontName = 'Malgun Gothic'
$mono = New-Object Drawing.Font('Consolas', 11)

$version = if ($RenderTo) { '1.2.0' } else { Get-AgentVersion }
$form = New-Object Windows.Forms.Form
$form.Text = if ($version) { "$title $version" } else { $title }
if (Get-AppIconPath) { $form.Icon = New-Object Drawing.Icon (Get-AppIconPath) }
$form.Font = New-Object Drawing.Font($fontName, 10)
$form.AutoScaleDimensions = New-Object Drawing.SizeF(96, 96)
$form.AutoScaleMode = 'Dpi'
$form.FormBorderStyle = 'FixedDialog'
$form.MaximizeBox = $false
$form.MinimizeBox = $false
$form.StartPosition = 'CenterScreen'
$form.BackColor = [Drawing.Color]::White

function Add-Label([string]$text, [int]$x, [int]$y, [int]$w, [int]$h, [float]$size = 10, [bool]$bold = $false, $color = $ink, $font = $null) {
    $l = New-Object Windows.Forms.Label
    $l.Text = $text
    $l.SetBounds($x, $y, $w, $h)
    $l.ForeColor = $color
    $l.BackColor = [Drawing.Color]::Transparent
    $style = if ($bold) { [Drawing.FontStyle]::Bold } else { [Drawing.FontStyle]::Regular }
    $l.Font = if ($font) { $font } else { [Drawing.Font]::new($fontName, [float]$size, $style) }
    $form.Controls.Add($l)
    $l
}

function Add-Button([string]$text, [int]$x, [int]$y, [int]$w, [bool]$primary = $false) {
    $b = New-Object Windows.Forms.Button
    $b.Text = $text
    $b.SetBounds($x, $y, $w, 34)
    $b.FlatStyle = 'Flat'
    $b.FlatAppearance.BorderColor = if ($primary) { $accent } else { [Drawing.Color]::FromArgb(213, 216, 226) }
    $b.BackColor = if ($primary) { $accent } else { [Drawing.Color]::White }
    $b.ForeColor = if ($primary) { [Drawing.Color]::White } else { $ink }
    $b.Cursor = 'Hand'
    $form.Controls.Add($b)
    $b
}

function Add-CopyButton([int]$x, [int]$y, [scriptblock]$getValue) {
    $b = Add-Button '복사' $x $y 76
    $b.Tag = $getValue
    $b.Add_Click({ param($s) [Windows.Forms.Clipboard]::SetText((& $s.Tag)); $s.Text = '복사됨' })
}

$W = 540
$y = 22
[void](Add-Label '휴대폰 앱에 아래 정보를 입력하세요' 24 $y ($W - 48) 30 13 $true)
$y += 38
if ($running) {
    [void](Add-Label "●  실행 중 · 포트 $($config.port)" 24 $y 300 22 10 $true ([Drawing.Color]::FromArgb(22, 163, 74)))
} else {
    [void](Add-Label '●  에이전트가 실행되고 있지 않습니다' 24 $y 400 22 10 $true ([Drawing.Color]::FromArgb(211, 47, 47)))
}
$y += 40

[void](Add-Label '토큰' 24 $y 200 20 9 $false $muted)
$y += 24
$tokenBox = New-Object Windows.Forms.TextBox
$tokenBox.Text = $config.token
$tokenBox.ReadOnly = $true
$tokenBox.Font = $mono
$tokenBox.BackColor = $panelBg
$tokenBox.BorderStyle = 'FixedSingle'
$tokenBox.SetBounds(24, $y + 3, $W - 24 - 24 - 84, 28)
$form.Controls.Add($tokenBox)
Add-CopyButton ($W - 24 - 76) $y { $tokenBox.Text }
$y += 52

[void](Add-Label 'PC 네트워크' 24 $y 200 20 9 $false $muted)
$y += 26
foreach ($a in $adapters) {
    $kind = if ($a.Wired) { '유선 · WOL 사용 가능' } else { '무선 · WOL 비권장' }
    [void](Add-Label "$($a.Name)  ($kind)" 24 $y ($W - 48) 22 10 $true)
    $y += 28
    foreach ($row in @(@('IP 주소', $a.Ip), @('MAC 주소', $a.Mac))) {
        [void](Add-Label $row[0] 36 ($y + 7) 80 20 9 $false $muted)
        [void](Add-Label ([string]$row[1]) 120 ($y + 5) 300 22 0 $false $ink $mono)
        $value = [string]$row[1]
        Add-CopyButton ($W - 24 - 76) $y { $value }.GetNewClosure()
        $y += 40
    }
    $y += 8
}

[void](Add-Label '공유기에서 이 PC에 고정 IP를 지정해 두면 IP가 바뀌지 않습니다.' 24 $y ($W - 48) 20 9 $false $muted)
$y += 36

$sep = New-Object Windows.Forms.Label
$sep.BorderStyle = 'Fixed3D'
$sep.SetBounds(0, $y, $W, 2)
$form.Controls.Add($sep)
$y += 16

$regen = Add-Button '토큰 새로 만들기' 24 $y 150
$regen.Add_Click({
    $answer = [Windows.Forms.MessageBox]::Show(
        "새 토큰을 만들면 휴대폰 앱에 저장된 토큰도 새 값으로 바꿔야 합니다.`n계속할까요?",
        '토큰 새로 만들기', 'YesNo', 'Warning')
    if ($answer -eq 'Yes') {
        $newToken = New-AgentToken
        Save-AgentConfig -Port $config.port -Token $newToken
        Restart-AgentTask
        $tokenBox.Text = $newToken
    }
})
$close = Add-Button '닫기' ($W - 24 - 110) $y 110 $true
$close.Add_Click({ $form.Close() })
$form.AcceptButton = $close
$form.CancelButton = $close
$form.ActiveControl = $close
$y += 34 + 20

$form.ClientSize = New-Object Drawing.Size($W, $y)

if ($RenderTo) {
    # Child controls only paint once the window exists on screen, so show it off-screen briefly.
    $form.StartPosition = 'Manual'
    $form.Location = New-Object Drawing.Point(-10000, -10000)
    $form.ShowInTaskbar = $false
    $form.Add_Shown({
        $form.Refresh()
        $bmp = New-Object Drawing.Bitmap($form.Width, $form.Height)
        $form.DrawToBitmap($bmp, (New-Object Drawing.Rectangle(0, 0, $form.Width, $form.Height)))
        $bmp.Save($RenderTo, [Drawing.Imaging.ImageFormat]::Png)
        $bmp.Dispose()
        $form.Close()
    })
}
[void]$form.ShowDialog()
