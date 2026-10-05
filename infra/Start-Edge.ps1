param([Parameter(Mandatory=$true)][string]$Root, [Parameter(Mandatory=$true)][string]$EnvFile)
$ErrorActionPreference = 'Stop'
# Fixed administrator-owned environment file. No model input is executed.
Get-Content -LiteralPath $EnvFile | ForEach-Object {
    if ($_ -match '^(ORANGE_EDGE_URL|ORANGE_EDGE_TOKEN|ORANGE_RESERVATION_FILE)=(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
    }
}
Set-Location -LiteralPath $Root
& "$Root\.venv\Scripts\python.exe" -m edge.executor
exit $LASTEXITCODE
