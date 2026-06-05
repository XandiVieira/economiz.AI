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
    # 3) Publish the URL.
    Set-Content -Path $urlFile -Value $url -Encoding ascii

    # 4) Update CORS_ORIGINS: keep non-tunnel entries, add the new tunnel URL.
    if (Test-Path $envFile) {
        $lines = Get-Content $envFile
        $newLines = foreach ($line in $lines) {
            if ($line -match '^CORS_ORIGINS=') {
                $val = $line.Substring('CORS_ORIGINS='.Length)
                $parts = $val.Split(',') | Where-Object { $_ -notmatch 'trycloudflare\.com' -and $_ -ne '' }
                $parts += $url
                'CORS_ORIGINS=' + ($parts -join ',')
            } else { $line }
        }
        Set-Content -Path $envFile -Value $newLines -Encoding ascii
        Log "CORS_ORIGINS updated with $url"

        # 5) Restart the app so it picks up the new CORS origin.
        & docker context use desktop-linux *> $null
        & cmd /c "docker compose --profile server up -d 2>&1" | Out-Null
        Log "app container recreated for new CORS"
    }
}

# 6) Block on cloudflared so the Scheduled Task treats it as long-running.
Log "waiting on cloudflared (pid $($proc.Id))"
Wait-Process -Id $proc.Id
Log "cloudflared exited"
