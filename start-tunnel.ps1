# economizai Cloudflare quick-tunnel launcher with CORS auto-sync.
#
# Invoked by the "economizai - cloudflare tunnel" Scheduled Task at logon.
# Also runnable manually. Keeps cloudflared in the foreground (the task keeps
# it alive; on exit the task restarts it).
#
# What it does each start:
#   1. launch cloudflared quick tunnel -> http://localhost:8080
#   2. capture the random https://*.trycloudflare.com URL
#   3. write it to current-tunnel-url.txt (so you can always find the live URL)
#   4. update .env CORS_ORIGINS (swap the old tunnel entry for the new one)
#   5. restart the app container so the new origin is allowed for browser FE
#   6. block on cloudflared (stays connected)
# ---------------------------------------------------------------------------
$ErrorActionPreference = "Continue"
$repo    = "C:\Users\Xandi\OneDrive\Documents\projects\economiz.AI"
$cf      = "C:\Users\Xandi\AppData\Local\Microsoft\WinGet\Packages\Cloudflare.cloudflared_Microsoft.Winget.Source_8wekyb3d8bbwe\cloudflared.exe"
$logFile = Join-Path $repo "tunnel.log"
$urlFile = Join-Path $repo "current-tunnel-url.txt"
$envFile = Join-Path $repo ".env"
$cfLog   = Join-Path $repo "cf-quick.log"

function Log($m){ Add-Content -Path $logFile -Value ("{0}  {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $m) }

Set-Location $repo
if (-not (Test-Path $cf)) { Log "cloudflared not found at $cf"; exit 1 }

# Fresh cf log so URL parsing is unambiguous.
Remove-Item $cfLog -ErrorAction SilentlyContinue

# 1) Start cloudflared in the background, output to cfLog.
Log "starting cloudflared quick tunnel"
$proc = Start-Process -FilePath $cf -ArgumentList @("tunnel","--url","http://localhost:8080","--no-autoupdate","--logfile",$cfLog) -PassThru -WindowStyle Hidden

# 2) Wait for the URL (up to ~40s).
$url = $null
for ($i=0; $i -lt 20; $i++) {
    Start-Sleep -Seconds 2
    if (Test-Path $cfLog) {
        $m = Select-String -Path $cfLog -Pattern 'https://[a-z0-9-]+\.trycloudflare\.com' -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($m) { $url = ($m.Matches[0].Value); break }
    }
}
if (-not $url) { Log "no tunnel URL after ~40s; leaving cloudflared running anyway"; }
else {
    Log "tunnel URL = $url"
    # 3) Publish the raw tunnel URL locally (for reference/debugging).
    Set-Content -Path $urlFile -Value $url -Encoding ascii

    # 4) Push the new tunnel URL into the Worker's KV ("origin" key). The Worker
    #    proxies the PERMANENT https://economizai.economizai.workers.dev URL to
    #    whatever is stored here, so the public link auto-follows the tunnel and
    #    the FE never sees a change.
    $worker = Join-Path $repo "tunnel-proxy-worker"
    if (Test-Path $worker) {
        Push-Location $worker
        & cmd /c ('npx --yes wrangler kv key put --binding TUNNEL --remote origin "' + $url + '" 2>&1') | Out-Null
        Pop-Location
        Log "KV origin updated -> $url (permanent URL now follows this tunnel)"
    } else {
        Log "tunnel-proxy-worker folder missing; skipped KV update"
    }
    # NOTE: CORS does NOT need updating per-restart. The FE always uses the
    # permanent workers.dev URL, which is a fixed CORS origin set once in .env.
}

# 6) Block on cloudflared so the Scheduled Task treats it as long-running.
Log "waiting on cloudflared (pid $($proc.Id))"
Wait-Process -Id $proc.Id
Log "cloudflared exited"
