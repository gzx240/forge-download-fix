[CmdletBinding()]
param(
    [string]$ForgeDir = "D:\forge",
    [string]$JarName = "forge-gui-desktop-2.0.15-jar-with-dependencies.jar",
    [string]$BackupPath = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$jarPath = Join-Path $ForgeDir $JarName
$backupDir = Join-Path $repoRoot "backups"

if (-not (Test-Path -LiteralPath $jarPath)) {
    throw "Forge jar not found: $jarPath"
}

if (-not $BackupPath) {
    $BackupPath = Get-ChildItem -LiteralPath $backupDir -Filter "$JarName.bak-*" -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}

if (-not $BackupPath -or -not (Test-Path -LiteralPath $BackupPath)) {
    throw "No backup found. Pass -BackupPath explicitly."
}

Copy-Item -LiteralPath $BackupPath -Destination $jarPath -Force
Write-Host "Restored: $BackupPath -> $jarPath"
