# Starts the Claude Remote bridge automatically when you sign in to Windows.
#   powershell -ExecutionPolicy Bypass -File scripts\install-windows.ps1            install (and start now)
#   powershell -ExecutionPolicy Bypass -File scripts\install-windows.ps1 -Remove    uninstall
# Optional: -WorkDir "D:\code" (default folder for Claude Code), -Port 8000.
# Logs go to server\bridge.log.
param([switch]$Remove, [string]$WorkDir = '', [int]$Port = 8000)

$ErrorActionPreference = 'Stop'
$task = 'Claude Remote bridge'
$server = Split-Path -Parent $PSScriptRoot

if ($Remove) {
    Stop-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $task -Confirm:$false -ErrorAction SilentlyContinue
    Write-Host "Removed '$task'."
    return
}

$node = (Get-Command node -ErrorAction SilentlyContinue).Source
if (-not $node) { throw 'Node.js was not found on PATH. Install Node.js 20 or newer first.' }
if (-not (Test-Path (Join-Path $server 'node_modules'))) {
    Write-Host 'Installing dependencies...'
    Push-Location $server; npm install --omit=dev; Pop-Location
}

$envs = "`$env:PORT='$Port';"
if ($WorkDir) { $envs += " `$env:WORK_DIR='$WorkDir';" }
$cmd = "$envs Set-Location '$server'; & '$node' index.mjs *>> '$server\bridge.log'"
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -WindowStyle Hidden -Command `"$cmd`""
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
# Restart it if it ever stops, and don't stop it on battery.
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit 0 `
    -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName $task -Action $action -Trigger $trigger -Settings $settings -Force | Out-Null
Start-ScheduledTask -TaskName $task
Write-Host "Installed '$task'. It starts at sign-in; it is running now. Logs: $server\bridge.log"
