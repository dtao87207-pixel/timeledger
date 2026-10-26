# One-off repair: pull the app database, drop the stale user edits, push it back,
# restart the app so it re-analyses and rewrites the records.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools\drop_stale_edits.ps1          # dry run
#   powershell -ExecutionPolicy Bypass -File tools\drop_stale_edits.ps1 -Apply
#
# NOTE: ASCII only. Windows PowerShell 5.1 reads .ps1 as ANSI unless it has a BOM,
# so any non-ASCII text here would break the parser. Chinese output lives in the
# Python parts, which are always read as UTF-8.

param([switch]$Apply)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $project '.toolchain\android-sdk\platform-tools\adb.exe'
$python = 'python'
$pkg = 'com.timeledger.app'
$outDir = Join-Path $project 'build\diagnose'
$db = Join-Path $outDir 'time_ledger.db'

if (-not (Test-Path $adb)) { throw "adb not found: $adb" }
if (-not (Test-Path $python)) { throw "python not found: $python" }
New-Item -ItemType Directory -Path $outDir -Force | Out-Null

Write-Host "Stopping the app..." -ForegroundColor Cyan
& $adb shell am force-stop $pkg
Start-Sleep -Milliseconds 800

Write-Host "Pulling the database..." -ForegroundColor Cyan
foreach ($name in @('time_ledger.db', 'time_ledger.db-wal', 'time_ledger.db-shm')) {
    $target = Join-Path $outDir $name
    if (Test-Path $target) { Remove-Item $target -Force }
    cmd /c "`"$adb`" exec-out run-as $pkg cat databases/$name > `"$target`" 2>nul"
}

$pyArgs = @((Join-Path $project 'tools\drop_edits.py'), $db)
if ($Apply) { $pyArgs += '--apply' }
& $python @pyArgs
if (-not $Apply) { exit 0 }

Write-Host "Pushing the patched database back..." -ForegroundColor Cyan
# The WAL must go first: otherwise SQLite would replay the old pages over our change.
& $adb shell run-as $pkg rm -f databases/time_ledger.db-wal databases/time_ledger.db-shm
cmd /c "`"$adb`" shell run-as $pkg sh -c ""cat > databases/time_ledger.db"" < `"$db`""

Write-Host "Restarting the app (it re-analyses today and yesterday)..." -ForegroundColor Cyan
& $adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 20
Write-Host "Done. Pull the database again to verify." -ForegroundColor Green
