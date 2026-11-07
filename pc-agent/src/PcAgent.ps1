<#
  PC Remote Agent
  Tiny HTTP server that lets the Android app shut down / restart / sleep / hibernate this PC.

    GET  /ping                               -> {"ok":true,"v":2}   (no auth, used for online status)
    POST /shutdown|restart|sleep|hibernate   signed request:
         X-Time: <unix seconds>  X-Nonce: <random hex>
         X-Signature: hex(HMAC-SHA256(token, "<action>\n<time>\n<nonce>"))

  The token itself never crosses the network, so the port can be forwarded from the router
  for use outside home: a captured request can't be replayed (nonce) or reused later (time window).

  Installed by the setup program as a SYSTEM scheduled task that starts at boot.
#>
param(
    [string]$ConfigPath = (Join-Path $PSScriptRoot 'agent-config.json'),
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

$config = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
$port = [int]$config.port
$token = [string]$config.token
if ([string]::IsNullOrWhiteSpace($token) -or $token.Length -lt 16) {
    throw 'Token is missing or shorter than 16 characters in agent-config.json.'
}

$logPath = Join-Path (Split-Path -Parent $ConfigPath) 'agent.log'

function Write-Log([string]$msg) {
    $line = '{0:yyyy-MM-dd HH:mm:ss} {1}' -f (Get-Date), $msg
    try {
        # Keep the log from growing forever.
        if ((Test-Path $logPath) -and (Get-Item $logPath).Length -gt 1MB) { Remove-Item $logPath -Force }
        Add-Content -LiteralPath $logPath -Value $line
    } catch {}
    Write-Host $line
}

$actions = @{
    shutdown  = { shutdown.exe /s /f /t 0 }
    restart   = { shutdown.exe /r /f /t 0 }
    hibernate = { shutdown.exe /h }
    sleep     = {
        Add-Type -AssemblyName System.Windows.Forms
        [void][System.Windows.Forms.Application]::SetSuspendState([System.Windows.Forms.PowerState]::Suspend, $false, $false)
    }
}

function Test-Equal([string]$x, [string]$y) {
    $a = [Text.Encoding]::UTF8.GetBytes($x)
    $b = [Text.Encoding]::UTF8.GetBytes($y)
    if ($a.Length -ne $b.Length) { return $false }
    $diff = 0
    for ($i = 0; $i -lt $a.Length; $i++) { $diff = $diff -bor ($a[$i] -bxor $b[$i]) }
    return $diff -eq 0
}

$maxSkewSeconds = 120
$seenNonces = @{}   # nonce -> unix time, kept for a few minutes to reject replays
$hmac = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($token))

# Returns $null when the request is genuine, otherwise the reason it was rejected.
function Test-Signature([string]$action, $headers) {
    $time = $headers['X-Time']
    $nonce = $headers['X-Nonce']
    $signature = $headers['X-Signature']
    if (-not $time -or -not $nonce -or -not $signature -or $nonce.Length -gt 64) { return 'bad signature' }
    $t = 0L
    if (-not [long]::TryParse($time, [ref]$t)) { return 'bad signature' }
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    if ([Math]::Abs($now - $t) -gt $maxSkewSeconds) { return 'clock skew' }

    foreach ($k in @($seenNonces.Keys)) {
        if ($now - $seenNonces[$k] -gt 2 * $maxSkewSeconds) { $seenNonces.Remove($k) }
    }
    if ($seenNonces.ContainsKey($nonce)) { return 'replayed' }

    $expected = -join ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes("$action`n$time`n$nonce")) |
        ForEach-Object { $_.ToString('x2') })
    if (-not (Test-Equal $signature.ToLowerInvariant() $expected)) { return 'bad signature' }

    $seenNonces[$nonce] = $now
    return $null
}

function Send-Response($stream, [int]$code, [string]$status, $obj) {
    $body = [Text.Encoding]::UTF8.GetBytes(($obj | ConvertTo-Json -Compress))
    $head = "HTTP/1.1 $code $status`r`nContent-Type: application/json`r`nContent-Length: $($body.Length)`r`nConnection: close`r`n`r`n"
    $headBytes = [Text.Encoding]::ASCII.GetBytes($head)
    $stream.Write($headBytes, 0, $headBytes.Length)
    $stream.Write($body, 0, $body.Length)
    $stream.Flush()
}

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Any, $port)
$listener.Start()
Write-Log "Listening on port $port (DryRun=$DryRun)"

while ($true) {
    $client = $listener.AcceptTcpClient()
    $pending = $null
    try {
        $client.ReceiveTimeout = 5000
        $client.SendTimeout = 5000
        $stream = $client.GetStream()
        $reader = New-Object System.IO.StreamReader($stream, [Text.Encoding]::ASCII)
        $remote = $client.Client.RemoteEndPoint.ToString()

        $requestLine = $reader.ReadLine()
        if (-not $requestLine) { continue }
        $headers = @{}
        while ($headers.Count -lt 50) {
            $line = $reader.ReadLine()
            if ([string]::IsNullOrEmpty($line)) { break }
            $i = $line.IndexOf(':')
            if ($i -gt 0) { $headers[$line.Substring(0, $i).Trim()] = $line.Substring($i + 1).Trim() }
        }

        $parts = $requestLine.Split(' ')
        $method = $parts[0]
        $path = if ($parts.Length -gt 1) { $parts[1].ToLowerInvariant() } else { '/' }
        $name = $path.TrimStart('/')

        if ($method -eq 'GET' -and $path -eq '/ping') {
            Send-Response $stream 200 'OK' @{ ok = $true; v = 2 }
        } elseif ($method -eq 'POST' -and $actions.ContainsKey($name)) {
            $rejected = Test-Signature $name $headers
            if (-not $rejected) {
                Send-Response $stream 200 'OK' @{ ok = $true; action = $name }
                Write-Log "$remote -> $name"
                $pending = $name
            } else {
                Start-Sleep -Seconds 1  # slow down guessing
                Send-Response $stream 403 'Forbidden' @{ ok = $false; error = $rejected }
                Write-Log "$remote rejected ($rejected) $path"
            }
        } else {
            Send-Response $stream 404 'Not Found' @{ ok = $false; error = 'not found' }
        }
    } catch {
        Write-Log "Request error: $_"
    } finally {
        $client.Close()
    }

    # Run the action only after the response has been sent and the socket closed.
    if ($pending) {
        if ($DryRun) {
            Write-Log "DryRun: would run '$pending'"
        } else {
            try { & $actions[$pending] } catch { Write-Log "Action '$pending' failed: $_" }
        }
    }
}
