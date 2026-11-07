<#
  PC Remote Agent
  Tiny HTTP server that lets the Android app shut down / restart / sleep / hibernate this PC.

    GET  /ping                               -> {"ok":true,"v":2}   (no auth, used for online status)
    POST /shutdown|restart|sleep|hibernate   signed request:
         X-Time: <unix seconds>  X-Nonce: <random hex>
         X-Signature: hex(HMAC-SHA256(token, "<action>\n<time>\n<nonce>"))

  The token itself never crosses the network, so the port can be forwarded from the router
  for use outside home: a captured request can't be replayed (nonce) or reused later (time window).

  Served through Windows' own HTTP server (http.sys via HttpListener): it only hands over
  complete requests, so idle connections (browser preconnects, port scanners on the forwarded
  port) can't stall the agent and make the app's status check time out.

  Installed by the setup program as a SYSTEM scheduled task that starts at boot.
  -Prefix  listener prefix override, e.g. http://localhost:19876/ for testing without admin rights.
#>
param(
    [string]$ConfigPath = (Join-Path $PSScriptRoot 'agent-config.json'),
    [switch]$DryRun,
    [string]$Prefix
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

function Send-Json($response, [int]$code, $obj) {
    $body = [Text.Encoding]::UTF8.GetBytes(($obj | ConvertTo-Json -Compress))
    $response.StatusCode = $code
    $response.ContentType = 'application/json'
    $response.ContentLength64 = $body.Length
    $response.KeepAlive = $false
    $response.OutputStream.Write($body, 0, $body.Length)
}

if (-not $Prefix) { $Prefix = "http://+:$port/" }
$listener = [System.Net.HttpListener]::new()
$listener.Prefixes.Add($Prefix)
$listener.IgnoreWriteExceptions = $true   # the phone may hang up first; that's not an agent error
$listener.Start()
Write-Log "Listening on $Prefix (DryRun=$DryRun)"

# Status checks arrive every few seconds; log each device only now and then, so the log shows
# whether the phone reaches this PC (and from where) without filling up.
$lastPingLog = @{}

while ($listener.IsListening) {
    $context = $listener.GetContext()
    $pending = $null
    $remote = '?'
    try {
        $request = $context.Request
        $response = $context.Response
        $remote = $request.RemoteEndPoint.Address.ToString()
        $path = $request.Url.AbsolutePath.ToLowerInvariant()
        $name = $path.TrimStart('/')

        if ($request.HttpMethod -eq 'GET' -and $path -eq '/ping') {
            Send-Json $response 200 @{ ok = $true; v = 2 }
            $last = $lastPingLog[$remote]
            if (-not $last -or ((Get-Date) - $last).TotalMinutes -ge 30) {
                Write-Log "$remote status check (logged at most every 30 min per device)"
                $lastPingLog[$remote] = Get-Date
            }
        } elseif ($request.HttpMethod -eq 'POST' -and $actions.ContainsKey($name)) {
            $rejected = Test-Signature $name $request.Headers
            if (-not $rejected) {
                Send-Json $response 200 @{ ok = $true; action = $name }
                Write-Log "$remote -> $name"
                $pending = $name
            } else {
                Start-Sleep -Seconds 1  # slow down guessing
                Send-Json $response 403 @{ ok = $false; error = $rejected }
                Write-Log "$remote rejected ($rejected) $path"
            }
        } else {
            Send-Json $response 404 @{ ok = $false; error = 'not found' }
        }
    } catch {
        Write-Log "Request error from ${remote}: $_"
    } finally {
        try { $context.Response.Close() } catch {}
    }

    # Run the action only after the response has been sent.
    if ($pending) {
        if ($DryRun) {
            Write-Log "DryRun: would run '$pending'"
        } else {
            try { & $actions[$pending] } catch { Write-Log "Action '$pending' failed: $_" }
        }
    }
}
