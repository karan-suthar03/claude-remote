# Claude Remote bridge installer for Windows.
#
#   irm https://raw.githubusercontent.com/karan-suthar03/claude-remote/main/install.ps1 | iex
#
# Installs the bridge into %LOCALAPPDATA%\claude-remote, starts it now and at every sign-in,
# and prints the address to enter in the Claude Remote app. Run it again to update.
# Uninstall: powershell -File "$env:LOCALAPPDATA\claude-remote\server\scripts\install-windows.ps1" -Remove

$ErrorActionPreference = 'Stop'
$Repo = 'karan-suthar03/claude-remote'
$Branch = 'main'
$Dir = Join-Path $env:LOCALAPPDATA 'claude-remote'

function Step($t) { Write-Host "`n==> $t" -ForegroundColor Cyan }
function Fail($t) { Write-Host "`n$t" -ForegroundColor Red; exit 1 }

Write-Host 'Claude Remote: use Claude Code on this computer from your phone' -ForegroundColor White

# --- Node.js 20+ ---
Step 'Checking Node.js'
$node = Get-Command node -ErrorAction SilentlyContinue
if (-not $node) {
    if (Get-Command winget -ErrorAction SilentlyContinue) {
        Write-Host 'Node.js not found; installing Node.js LTS with winget...'
        winget install --id OpenJS.NodeJS.LTS -e --silent --accept-package-agreements --accept-source-agreements | Out-Null
        $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('Path', 'User')
        $node = Get-Command node -ErrorAction SilentlyContinue
    }
    if (-not $node) { Fail 'Node.js 20 or newer is required. Install it from https://nodejs.org and run this again.' }
}
$major = [int]((& node --version).TrimStart('v').Split('.')[0])
if ($major -lt 20) { Fail "Node.js $(& node --version) is too old; 20 or newer is required. Update it from https://nodejs.org." }
Write-Host "Node.js $(& node --version)"

# --- Tailscale ---
Step 'Checking Tailscale'
$ts = (Get-Command tailscale -ErrorAction SilentlyContinue).Source
if (-not $ts -and (Test-Path "$env:ProgramFiles\Tailscale\tailscale.exe")) { $ts = "$env:ProgramFiles\Tailscale\tailscale.exe" }
if (-not $ts) {
    Fail "Tailscale is required (it's how your phone reaches this computer privately).`nInstall it from https://tailscale.com/download, sign in, then run this again."
}
$ip = (& $ts ip -4 2>$null | Select-Object -First 1)
if (-not $ip) { Fail "Tailscale is installed but not connected. Open Tailscale, sign in, then run this again." }
Write-Host "Tailscale IP $ip"

# --- Download (no git needed) ---
Step "Downloading Claude Remote ($Repo@$Branch)"
$tmp = Join-Path ([IO.Path]::GetTempPath()) "claude-remote-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory $tmp | Out-Null
$zip = Join-Path $tmp 'src.zip'
Invoke-WebRequest "https://github.com/$Repo/archive/refs/heads/$Branch.zip" -OutFile $zip -UseBasicParsing
Expand-Archive $zip -DestinationPath $tmp
$src = Get-ChildItem $tmp -Directory | Select-Object -First 1
# Stop a running bridge before replacing its files.
Stop-ScheduledTask -TaskName 'Claude Remote bridge' -ErrorAction SilentlyContinue
# The scheduled task runs node with this folder as its working directory; stop that node too.
$listener = Get-NetTCPConnection -LocalPort 8000 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($listener) {
    $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)" -ErrorAction SilentlyContinue
    if ($proc -and $proc.Name -eq 'node.exe' -and $proc.CommandLine -like '*index.mjs*') { Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue }
}
New-Item -ItemType Directory $Dir -Force | Out-Null
if (Test-Path "$Dir\server") { Remove-Item "$Dir\server" -Recurse -Force -ErrorAction SilentlyContinue }
Copy-Item (Join-Path $src.FullName 'server') "$Dir\server" -Recurse -Force
Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue

Step 'Installing dependencies (this includes Claude Code itself)'
Push-Location "$Dir\server"
npm install --omit=dev --no-audit --no-fund --loglevel=error
if ($LASTEXITCODE -ne 0) { Pop-Location; Fail 'npm install failed. See the messages above.' }
Pop-Location

# --- Autostart + start now ---
Step 'Starting the bridge (and at every sign-in)'
& powershell -NoProfile -ExecutionPolicy Bypass -File "$Dir\server\scripts\install-windows.ps1"

# --- Wait for it and report ---
$ok = $false
for ($i = 0; $i -lt 20 -and -not $ok; $i++) {
    Start-Sleep 1
    try { $null = Invoke-RestMethod "http://${ip}:8000/" -TimeoutSec 2; $ok = $true } catch {}
}
Write-Host ''
if ($ok) {
    Write-Host '  Claude Remote is running.' -ForegroundColor Green
} else {
    Write-Host "  The bridge didn't answer yet. Check the log: $Dir\server\bridge.log" -ForegroundColor Yellow
}
Write-Host ''
Write-Host '  In the Claude Remote app, tap "Add device" and enter:' -ForegroundColor White
Write-Host ''
Write-Host "        $ip" -ForegroundColor Yellow
Write-Host ''
Write-Host "  Get the app: https://github.com/$Repo/releases/latest/download/claude-remote.apk"
Write-Host '  Not signed in to Claude yet? Sign in from the app (device screen > Sign in).'
Write-Host "  Logs: $Dir\server\bridge.log"
Write-Host ''
