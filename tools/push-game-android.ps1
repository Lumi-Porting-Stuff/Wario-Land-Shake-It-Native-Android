# WarioRecomp: copies an extracted disc onto a phone, where the Android app reads it.
# Usage: powershell -ExecutionPolicy Bypass -File tools\push-game-android.ps1 -GameData <extracted DATA folder>
#
# The engine opens the disc with ordinary file calls, so it has to be a real path on the phone. By
# default that is a plain folder on shared storage, visible in any file manager:
#   /sdcard/WarioRecomp/game
# The app reads it with the all-files permission it asks for on first start. -AppPrivate instead
# uses the app's own folder, which needs no permission but is awkward to reach on Android 11+:
#   /sdcard/Android/data/com.santiago109.wariorecomp/files/game
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$GameData,
    # Where the disc goes on the phone. The app looks here first.
    [string]$Remote = '/sdcard/WarioRecomp/game',
    # Use the app's private folder instead of a plain shared one.
    [switch]$AppPrivate,
    [string]$Package = 'com.santiago109.wariorecomp',
    [string]$Adb = '',
    # Push to a device reached over Wi-Fi, or with several listed (adb -s <serial>).
    [string]$Serial = ''
)
$ErrorActionPreference = 'Stop'

# ── adb ─────────────────────────────────────────────────────────────────────────────────────────
if (-not $Adb) {
    # Built one at a time: Join-Path throws on an unset variable, and ANDROID_HOME often is one.
    $candidates = @()
    if ($env:LOCALAPPDATA) { $candidates += Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' }
    foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if ($sdk) { $candidates += Join-Path $sdk 'platform-tools\adb.exe' }
    }
    $onPath = (Get-Command adb -ErrorAction SilentlyContinue).Source
    if ($onPath) { $candidates += $onPath }
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) { $Adb = $candidate; break }
    }
}
if (-not $Adb) { throw 'adb not found: install the SDK Manager''s "Android SDK Platform-Tools", or pass -Adb <path>.' }
$target = if ($Serial) { @('-s', $Serial) } else { @() }

# ── The disc ────────────────────────────────────────────────────────────────────────────────────
# nodtool extracts into a DATA folder, so accept either it or its parent, as the engine does.
if (-not (Test-Path -LiteralPath (Join-Path $GameData 'sys\boot.bin')) -and
     (Test-Path -LiteralPath (Join-Path $GameData 'DATA\sys\boot.bin'))) {
    $GameData = Join-Path $GameData 'DATA'
}
foreach ($required in @('files', 'sys\fst.bin', 'sys\boot.bin')) {
    if (-not (Test-Path -LiteralPath (Join-Path $GameData $required))) {
        throw "Not an extracted disc: $GameData has no $required. Extract the image first (nodtool extract <image> <folder>\DATA)."
    }
}
# The same check the app and the PC build make, so a wrong disc fails here and not on the phone.
$header = [byte[]]::new(8)
$stream = [IO.File]::OpenRead((Join-Path $GameData 'sys\boot.bin'))
try { $null = $stream.Read($header, 0, 8) } finally { $stream.Dispose() }
$id = [Text.Encoding]::ASCII.GetString($header, 0, 6)
$revision = $header[7]
if ($id -ne 'RWLE01') { throw "That disc is $id, not Wario Land: Shake It! (RWLE01). This port runs only that one." }
if ($revision -ne 0) { throw "That is RWLE01 revision $revision. This port runs only revision 0." }
$sizeGB = [Math]::Round(((Get-ChildItem -LiteralPath $GameData -Recurse -File | Measure-Object -Property Length -Sum).Sum / 1GB), 2)
Write-Host "disc: $GameData (RWLE01 revision 0, $sizeGB GB)"

# ── The phone ───────────────────────────────────────────────────────────────────────────────────
$devices = (& $Adb @target devices) -split "`n" | Where-Object { $_ -match '\sdevice$' }
if (-not $devices) {
    throw 'No phone connected: plug it in, turn on USB debugging, and accept the prompt on the phone (adb devices should list it).'
}

$remote = if ($AppPrivate) { "/sdcard/Android/data/$Package/files/game" } else { $Remote }
# adb prints notices to stderr, and redirecting that in Windows PowerShell turns them into a
# terminating error; the probe below is what actually verifies the folder.
& $Adb @target shell mkdir -p "$remote" > $null
# Checked before the copy, not after: Android 11+ guards Android/data, and adb does not reach it on
# every ROM, so a doomed push should not run for minutes first.
$probe = (& $Adb @target shell "ls -d $remote 2>/dev/null || echo MISSING") -join ''
if ($probe -match 'MISSING') {
    throw ("Android would not let adb create $remote on this phone. A plain folder such as " +
           "/sdcard/WarioRecomp/game normally works; for the app-private one, install the app and " +
           "open it once so it creates the folder, or copy the disc with a file manager.")
}

Write-Host "pushing to $remote (this takes a while)"
foreach ($part in @('files', 'sys')) {
    & $Adb @target push (Join-Path $GameData $part) "$remote/"
    if ($LASTEXITCODE -ne 0) { throw "adb push of $part failed with exit code $LASTEXITCODE" }
}

$check = (& $Adb @target shell "ls $remote/sys/fst.bin 2>/dev/null || echo MISSING") -join ''
if ($check -match 'MISSING') { throw "The copy finished but $remote/sys/fst.bin is not there." }
Write-Host "done: the game is at $remote"
Write-Host 'Open WarioRecomp on the phone and press Play; it reads that folder as it is.'
