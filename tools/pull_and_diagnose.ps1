# Pull the app database off the phone and run the quantitative diagnosis.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools\pull_and_diagnose.ps1
#
# Prerequisites: phone connected over USB, "USB debugging" enabled,
# and the installed app is a debug build (run-as needs a debuggable app).
#
# NOTE: this file is intentionally ASCII-only. Windows PowerShell 5.1 reads
# .ps1 files as ANSI unless they carry a BOM, so any non-ASCII text here would
# be decoded wrong and break the parser. Chinese output lives in the Python
# part (diagnose_usage.py), which Python always reads as UTF-8.

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $project '.toolchain\android-sdk\platform-tools\adb.exe'
$python = 'python'
$pkg = 'com.timeledger.app'
$outDir = Join-Path $project 'build\diagnose'
$dbOut = Join-Path $outDir 'time_ledger.db'

if (-not (Test-Path $adb)) { throw "adb not found: $adb" }
if (-not (Test-Path $python)) { throw "python not found: $python" }

$devices = (& $adb devices) -split "`r?`n" | Where-Object { $_ -match '\tdevice$' }
if (-not $devices) {
    Write-Host "No device detected. Check:" -ForegroundColor Yellow
    Write-Host "  1) phone connected with a data cable"
    Write-Host "  2) Developer options -> USB debugging enabled"
    Write-Host "  3) tap Allow on the 'Allow USB debugging?' prompt"
    Write-Host ""
    Write-Host "Current adb devices output:"
    & $adb devices -l
    exit 1
}

New-Item -ItemType Directory -Path $outDir -Force | Out-Null

Write-Host "Pulling database..." -ForegroundColor Cyan
& $adb shell run-as $pkg ls databases | Out-Null

# WAL mode keeps the newest writes in -wal, so pull all three files and keep
# their original names next to each other (sqlite picks the WAL up by itself).
foreach ($name in @('time_ledger.db', 'time_ledger.db-wal', 'time_ledger.db-shm')) {
    $target = Join-Path $outDir $name
    if (Test-Path $target) { Remove-Item $target -Force }
    # Use cmd's ">" redirect: PowerShell's own ">" goes through the text
    # pipeline and would corrupt the binary.
    cmd /c "`"$adb`" exec-out run-as $pkg cat databases/$name > `"$target`" 2>nul"
}
if (-not (Test-Path $dbOut) -or (Get-Item $dbOut).Length -eq 0) {
    Write-Host "Pull failed. Possible reasons:" -ForegroundColor Red
    Write-Host "  - installed build is not debuggable (run-as unavailable)"
    Write-Host "  - the app has not created its database yet (open the app once)"
    exit 1
}
Write-Host ("database size: {0:N0} bytes" -f (Get-Item $dbOut).Length) -ForegroundColor DarkGray
Write-Host ""

& $python (Join-Path $project 'tools\diagnose_usage.py') $dbOut @args
