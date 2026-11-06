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
# Task Scheduler's restart settings only cover a task that fails to start, so the task itself
# starts the bridge again whenever it exits.
$cmd = "$envs Set-Location '$server'; while (`$true) { & '$node' index.mjs *>> '$server\bridge.log'; Start-Sleep -Seconds 5 }"
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -WindowStyle Hidden -Command `"$cmd`""
# The full account name (DOMAIN\user, or the PC name for local and Microsoft accounts); a bare
# user name is rejected on many machines.
$who = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $who
$principal = New-ScheduledTaskPrincipal -UserId $who -LogonType Interactive -RunLevel Limited
# Restart it if it ever stops, and don't stop it on battery.
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit 0 `
    -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew
try {
    Register-ScheduledTask -TaskName $task -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force -ErrorAction Stop | Out-Null
    Start-ScheduledTask -TaskName $task
    Write-Host "Installed '$task'. It starts at sign-in; it is running now. Logs: $server\bridge.log"
} catch {
    # No Task Scheduler access (policy, unusual account): start it now and say how to keep it.
    Write-Host "Couldn't register a startup task ($($_.Exception.Message.Trim())). Starting the bridge now instead." -ForegroundColor Yellow
    Start-Process powershell.exe -WindowStyle Hidden -ArgumentList '-NoProfile', '-Command', $cmd
    Write-Host "It won't start by itself after a restart; run this script again then. Logs: $server\bridge.log"
}
