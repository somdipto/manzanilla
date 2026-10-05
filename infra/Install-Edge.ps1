param([Parameter(Mandatory=$true)][string]$Root, [Parameter(Mandatory=$true)][string]$EnvFile)
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path -LiteralPath $Root).Path
$EnvFile = (Resolve-Path -LiteralPath $EnvFile).Path
if (!(Test-Path -LiteralPath "$Root\.venv\Scripts\python.exe")) { throw 'Create the virtual environment and install requirements first.' }
# Run elevated. SYSTEM requires a system-readable install, not a private user profile.
$script = "$Root\infra\Start-Edge.ps1"
$args = '-NoProfile -File "' + $script + '" -Root "' + $Root + '" -EnvFile "' + $EnvFile + '"'
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $args -WorkingDirectory $Root
$trigger = New-ScheduledTaskTrigger -AtStartup
$settings = New-ScheduledTaskSettingsSet -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero) -StartWhenAvailable
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount
Register-ScheduledTask -TaskName 'OrangeEdge' -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force
Start-ScheduledTask -TaskName 'OrangeEdge'
