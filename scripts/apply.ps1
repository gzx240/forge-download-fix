[CmdletBinding()]
param(
    [string]$ForgeDir = "D:\forge",
    [string]$JarName = "forge-gui-desktop-2.0.15-jar-with-dependencies.jar",
    [string]$JavaHome = ""
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$jarPath = Join-Path $ForgeDir $JarName
$srcPath = Join-Path $repoRoot "src\main\java\forge\gui\download\GuiDownloadFilteredCardImages.java"
$buildDir = Join-Path $repoRoot "build\classes"
$backupDir = Join-Path $repoRoot "backups"

if (-not (Test-Path -LiteralPath $jarPath)) {
    throw "Forge jar not found: $jarPath"
}
if (-not (Test-Path -LiteralPath $srcPath)) {
    throw "Patch source not found: $srcPath"
}

function Find-JavaTool([string]$name) {
    if ($JavaHome) {
        $candidate = Join-Path $JavaHome "bin\$name.exe"
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    $cmd = Get-Command "$name.exe" -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    foreach ($jdkHome in @("C:\Program Files\Java\jdk-17", "C:\Program Files\Java\jdk-22")) {
        $candidate = Join-Path $jdkHome "bin\$name.exe"
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    throw "Cannot find $name.exe. Install JDK 17+ or pass -JavaHome."
}

$javac = Find-JavaTool "javac"
$jarTool = Find-JavaTool "jar"
$javap = Find-JavaTool "javap"

New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backupPath = Join-Path $backupDir "$JarName.bak-$stamp"
Copy-Item -LiteralPath $jarPath -Destination $backupPath -Force
Write-Host "Backup: $backupPath"

if (Test-Path -LiteralPath $buildDir) {
    Remove-Item -LiteralPath $buildDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $buildDir | Out-Null

Write-Host "Compiling..."
& $javac --release 17 -encoding UTF-8 -cp $jarPath -d $buildDir $srcPath
if ($LASTEXITCODE -ne 0) {
    throw "Compilation failed. Forge jar and patch source may be incompatible."
}

Write-Host "Updating jar..."
Push-Location $ForgeDir
try {
    & $jarTool uf $jarPath -C $buildDir "forge/gui/download/GuiDownloadFilteredCardImages.class"
    $jarExit = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($jarExit -ne 0) {
    throw "jar update failed. Close Forge completely (including javaw.exe) and try again."
}

Write-Host "Verifying..."
& $javap -p -classpath $jarPath forge.gui.download.GuiDownloadFilteredCardImages
if ($LASTEXITCODE -ne 0) {
    throw "Verification failed. Restore from: $backupPath"
}

Write-Host ""
Write-Host "Patch applied successfully."
Write-Host "Restart Forge, then use Download Card Images."
Write-Host "To restore: .\scripts\restore.ps1 -ForgeDir $ForgeDir -BackupPath $backupPath"
