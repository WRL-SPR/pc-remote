<#
  Regenerates app.ico (same design as the Android launcher icon).
  256px is stored as PNG; 48/32/16px as classic 32-bit DIBs, because .NET Framework's
  System.Drawing (used by the tray icon) can't read PNG-compressed small entries.
#>
Add-Type -AssemblyName System.Drawing

function New-IconBitmap([int]$s) {
    $bmp = New-Object Drawing.Bitmap $s, $s
    $g = [Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $r = [float]($s * 0.22)
    $path = New-Object Drawing.Drawing2D.GraphicsPath
    $path.AddArc(0, 0, 2 * $r, 2 * $r, 180, 90)
    $path.AddArc($s - 2 * $r - 1, 0, 2 * $r, 2 * $r, 270, 90)
    $path.AddArc($s - 2 * $r - 1, $s - 2 * $r - 1, 2 * $r, 2 * $r, 0, 90)
    $path.AddArc(0, $s - 2 * $r - 1, 2 * $r, 2 * $r, 90, 90)
    $path.CloseFigure()
    $brush = New-Object Drawing.Drawing2D.LinearGradientBrush((New-Object Drawing.Point 0, 0), (New-Object Drawing.Point $s, $s),
        [Drawing.Color]::FromArgb(79, 107, 255), [Drawing.Color]::FromArgb(27, 42, 133))
    $g.FillPath($brush, $path)

    # Material "desktop" glyph (24 units) in the middle 70%, with a play mark on the screen.
    $k = $s * 0.7 / 24; $ox = $s * 0.15; $oy = $s * 0.17
    $white = [Drawing.Brushes]::White
    $screen = New-Object Drawing.Drawing2D.GraphicsPath
    $screen.AddRectangle((New-Object Drawing.RectangleF(($ox + 1 * $k), ($oy + 2 * $k), (22 * $k), (16 * $k))))
    $screen.AddRectangle((New-Object Drawing.RectangleF(($ox + 3 * $k), ($oy + 4 * $k), (18 * $k), (12 * $k))))
    $g.FillPath($white, $screen)
    $g.FillRectangle($white, ($ox + 10 * $k), ($oy + 18 * $k), (4 * $k), (2 * $k))
    $g.FillRectangle($white, ($ox + 8 * $k), ($oy + 20 * $k), (8 * $k), (2 * $k))
    $cyan = New-Object Drawing.SolidBrush([Drawing.Color]::FromArgb(127, 227, 255))
    $tri = [Drawing.PointF[]]@(
        (New-Object Drawing.PointF(($ox + 9.6 * $k), ($oy + 6.6 * $k))),
        (New-Object Drawing.PointF(($ox + 9.6 * $k), ($oy + 13.4 * $k))),
        (New-Object Drawing.PointF(($ox + 15.2 * $k), ($oy + 10 * $k))))
    $g.FillPolygon($cyan, $tri)
    $g.Dispose()
    $bmp
}

function ConvertTo-Dib([Drawing.Bitmap]$bmp) {
    $s = $bmp.Width
    $ms = New-Object IO.MemoryStream
    $w = New-Object IO.BinaryWriter $ms
    # BITMAPINFOHEADER; height is doubled for the XOR + AND masks
    $w.Write([int]40); $w.Write([int]$s); $w.Write([int]($s * 2)); $w.Write([int16]1); $w.Write([int16]32)
    $w.Write([int]0); $w.Write([int]0); $w.Write([int]0); $w.Write([int]0); $w.Write([int]0); $w.Write([int]0)
    for ($y = $s - 1; $y -ge 0; $y--) {          # bottom-up BGRA pixels
        for ($x = 0; $x -lt $s; $x++) {
            $c = $bmp.GetPixel($x, $y)
            $w.Write([byte]$c.B); $w.Write([byte]$c.G); $w.Write([byte]$c.R); $w.Write([byte]$c.A)
        }
    }
    $maskRow = [int]([Math]::Ceiling($s / 32) * 4)   # AND mask: all zero (alpha does the work)
    $w.Write((New-Object byte[] ($maskRow * $s)))
    $w.Flush()
    , $ms.ToArray()
}

$entries = foreach ($s in 256, 48, 32, 16) {
    $bmp = New-IconBitmap $s
    if ($s -eq 256) {
        $ms = New-Object IO.MemoryStream
        $bmp.Save($ms, [Drawing.Imaging.ImageFormat]::Png)
        $data = $ms.ToArray()
    } else {
        $data = ConvertTo-Dib $bmp
    }
    $bmp.Dispose()
    [pscustomobject]@{ Size = $s; Data = $data }
}

$out = New-Object IO.MemoryStream
$w = New-Object IO.BinaryWriter $out
$w.Write([uint16]0); $w.Write([uint16]1); $w.Write([uint16]$entries.Count)
$offset = 6 + 16 * $entries.Count
foreach ($e in $entries) {
    $w.Write([byte]($e.Size % 256)); $w.Write([byte]($e.Size % 256)); $w.Write([byte]0); $w.Write([byte]0)
    $w.Write([uint16]1); $w.Write([uint16]32); $w.Write([uint32]$e.Data.Length); $w.Write([uint32]$offset)
    $offset += $e.Data.Length
}
foreach ($e in $entries) { $w.Write([byte[]]$e.Data) }
$w.Flush()
[IO.File]::WriteAllBytes((Join-Path $PSScriptRoot 'app.ico'), $out.ToArray())
Write-Host "app.ico written ($($out.Length) bytes)"
