# WarioRecomp: Android build (translation -> NDK compilation -> APK).
# Usage: powershell -ExecutionPolicy Bypass -File tools\build-wario-android.ps1 [-GameData <DATA folder>]
#
# The engine is cross-compiled for arm64-v8a as libmain.so, with SDL3 and Dawn linked into it, and
# the Gradle project in android\ only packages it. Needs the Android SDK with an NDK (r27+) and a
# JDK; everything else (SDL3, Dawn, mbed TLS) is downloaded by the CMake build the first time.
# Logs: build-logs\status.txt and build-logs\<step>.log.
[CmdletBinding()]
param(
    [int]$Jobs = 0,
    # Extracted game folder (contains sys\main.dol). Empty = paths.dvd_root from Play\UserData\Config.toml.
    [string]$GameData = '',
    # The translated C++ is host-independent, so a desktop build's generated\ folder is reused as is.
    [switch]$SkipTranslate,
    # Stop after libmain.so, without building the APK.
    [switch]$SkipApk,
    [string]$AndroidSdk = '',
    [string]$Ndk = '',
    # Android 10, and the reason is performance rather than any API this port calls.
    #
    # Below API 29 the NDK cannot use the ELF thread-local storage the hardware supports, and falls
    # back to emulating it: every read of a `thread_local` becomes a call to __emutls_get_address
    # instead of an offset from TPIDR_EL0. That would be a detail, except that the hottest path in
    # this runtime is thread-local - the current translated execution address is written on every
    # translated function, and the indirect dispatcher's memo tables are read on every indirect
    # call - so the emulation lands on nearly every guest instruction stream.
    #
    # Android 9 is what this costs. (Below API 28 the NDK's own C++ runtime and 16 KiB page support
    # also get awkward, which is why it was there in the first place.)
    [int]$MinSdk = 29,
    [ValidateSet('debug', 'release')][string]$Variant = 'release'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$fw = Join-Path $root 'fw'
$toolchain = Join-Path $root 'toolchain'
$logs = Join-Path $root 'build-logs'
$project = 'projects/wario/recomp.yml'
$build = Join-Path $root 'build-android'
$app = Join-Path $root 'android'
if ($Jobs -le 0) { $Jobs = [Math]::Max(1, [Environment]::ProcessorCount - 1) }
# Each translated-code shard needs ~1.5 GB of RAM to compile: limit the jobs by the PC's memory.
$ramGB = [Math]::Floor((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB)
$shardJobs = [Math]::Max(1, [Math]::Min($Jobs, [Math]::Floor(($ramGB - 6) / 2)))
[IO.Directory]::CreateDirectory($logs) | Out-Null

function Set-Status([string]$Text) {
    $line = "{0:yyyy-MM-dd HH:mm:ss} {1}" -f (Get-Date), $Text
    Write-Host $line
    Add-Content -LiteralPath (Join-Path $logs 'status.txt') -Value $line
}

function Invoke-Step([string]$Name, [string]$Exe, [string[]]$Arguments, [string]$WorkDir) {
    Set-Status "${Name}: started"
    $log = Join-Path $logs "$Name.log"
    # In Windows PowerShell, redirecting a native tool's stderr turns each line into an error
    # record, so with ErrorActionPreference = Stop a mere CMake deprecation warning would end the
    # build. Only the exit code decides whether a step failed.
    $previous = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    Push-Location $WorkDir
    try { & $Exe @Arguments 2>&1 | ForEach-Object { "$_" } | Out-File -LiteralPath $log -Encoding utf8 }
    finally { Pop-Location; $ErrorActionPreference = $previous }
    if ($LASTEXITCODE -ne 0) {
        Set-Status "${Name}: FAILED (code $LASTEXITCODE), see build-logs\$Name.log"
        Get-Content -LiteralPath $log -Tail 40
        throw "$Name failed with exit code $LASTEXITCODE (see $log)"
    }
    Set-Status "${Name}: ok"
}

# ── 1. The Android toolchain ────────────────────────────────────────────────────────────────────
if (-not $AndroidSdk) {
    foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT,
            (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) { $AndroidSdk = $candidate; break }
    }
}
if (-not $AndroidSdk -or -not (Test-Path -LiteralPath $AndroidSdk)) {
    throw 'Android SDK not found: install it, or pass -AndroidSdk <path> (or set ANDROID_HOME).'
}
if (-not $Ndk) {
    $ndkRoot = Join-Path $AndroidSdk 'ndk'
    if (-not (Test-Path -LiteralPath $ndkRoot)) {
        throw "No NDK in ${ndkRoot}: install one from the SDK Manager (r27 or newer), or pass -Ndk <path>."
    }
    # Newest first: the version names sort as text, so 30 would otherwise lose to 9.
    $Ndk = (Get-ChildItem -LiteralPath $ndkRoot -Directory |
        Sort-Object { try { [version]$_.Name } catch { [version]'0.0.0' } } -Descending |
        Select-Object -First 1).FullName
}
$ndkToolchainFile = Join-Path $Ndk 'build\cmake\android.toolchain.cmake'
if (-not (Test-Path -LiteralPath $ndkToolchainFile)) {
    throw "Not an NDK: $Ndk (no build\cmake\android.toolchain.cmake)"
}

# The SDK's own CMake and Ninja are the ones the NDK is tested against.
$sdkCMakeRoot = Join-Path $AndroidSdk 'cmake'
$cmake = $null
$ninja = $null
if (Test-Path -LiteralPath $sdkCMakeRoot) {
    $newest = Get-ChildItem -LiteralPath $sdkCMakeRoot -Directory |
        Sort-Object { try { [version]$_.Name } catch { [version]'0.0.0' } } -Descending |
        Select-Object -First 1
    if ($newest) {
        $cmake = Join-Path $newest.FullName 'bin\cmake.exe'
        $ninja = Join-Path $newest.FullName 'bin\ninja.exe'
    }
}
if (-not $cmake -or -not (Test-Path -LiteralPath $cmake)) {
    $cmake = (Get-Command cmake -ErrorAction SilentlyContinue).Source
    $ninja = (Get-Command ninja -ErrorAction SilentlyContinue).Source
}
if (-not $cmake) { throw 'cmake not found: install the SDK Manager''s "CMake" package.' }
if (-not $ninja -or -not (Test-Path -LiteralPath $ninja)) {
    throw 'ninja not found: install the SDK Manager''s "CMake" package (it ships one).'
}
$strip = Join-Path $Ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe'
Set-Status "NDK: $Ndk"

# ── 2. The game ─────────────────────────────────────────────────────────────────────────────────
$cfgPath = Join-Path $root 'Play\UserData\Config.toml'
if (-not $GameData -and (Test-Path -LiteralPath $cfgPath)) {
    $m = Select-String -LiteralPath $cfgPath -Pattern '^\s*dvd_root\s*=\s*"(.*)"' | Select-Object -First 1
    if ($m) { $GameData = $m.Matches[0].Groups[1].Value -replace '\\\\', '\' }
}
$dol = Join-Path $root 'game\main.dol'
if (-not (Test-Path -LiteralPath $dol)) {
    if (-not $GameData -or -not (Test-Path -LiteralPath (Join-Path $GameData 'sys\main.dol'))) {
        throw 'Game not found: pass -GameData <DATA folder> (the folder with sys\main.dol).'
    }
    New-Item -ItemType Directory -Force -Path (Join-Path $root 'game') | Out-Null
    Copy-Item -LiteralPath (Join-Path $GameData 'sys\main.dol') -Destination $dol
}
if ((Get-FileHash -LiteralPath $dol -Algorithm SHA256).Hash -ne '053BE60DDE84C8042F45ED2F4BA80C17EFB2D20FEDF95A414C2C7E37D336912A') {
    throw 'main.dol is not RWLE01 (SHA-256 mismatch)'
}

# ── 3. Translation (the same C++ the Windows build compiles) ────────────────────────────────────
$shards = Join-Path $root 'generated\build_shards'
if (-not $SkipTranslate -and -not (Test-Path -LiteralPath $shards)) {
    $translatorExe = Join-Path $toolchain 'translator\Translator.Cli.exe'
    $env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'; $env:DOTNET_ROLL_FORWARD = 'Major'
    if (Test-Path -LiteralPath $translatorExe) {
        $trExe = $translatorExe; $trPre = @()
    } else {
        $dotnetExe = $null
        $sys = Get-Command dotnet -ErrorAction SilentlyContinue
        if ($sys) { $sdks = & $sys.Source --list-sdks 2>$null; if ($sdks -match '^8\.') { $dotnetExe = $sys.Source } }
        if (-not $dotnetExe) {
            $dotnetExe = Join-Path $toolchain 'dotnet\dotnet.exe'
            if (-not (Test-Path -LiteralPath $dotnetExe)) {
                Set-Status '.NET 8 SDK: downloading (portable, to toolchain\dotnet)'
                $installer = Join-Path $toolchain 'dotnet-install.ps1'
                New-Item -ItemType Directory -Force -Path $toolchain | Out-Null
                Invoke-WebRequest -UseBasicParsing -Uri 'https://dot.net/v1/dotnet-install.ps1' -OutFile $installer
                & $installer -Channel 8.0 -InstallDir (Join-Path $toolchain 'dotnet') -NoPath *>&1 |
                    Out-File -LiteralPath (Join-Path $logs 'dotnet.log') -Encoding utf8
            }
        }
        $translator = Join-Path $fw 'translator\src\Translator.Cli\bin\Release\net8.0\Translator.Cli.dll'
        if (-not (Test-Path -LiteralPath $translator)) {
            Invoke-Step 'translator-build' $dotnetExe @('build',
                (Join-Path $fw 'translator\src\Translator.Cli\Translator.Cli.csproj'), '-c', 'Release') $root
        }
        $trExe = $dotnetExe; $trPre = @($translator)
    }

    Invoke-Step 'translate' $trExe @($trPre + @('translate-recursive', '0x80006124', '--project', $project,
        '--outdir', 'generated/functions', '--output-metadata', 'generated/base_translation_output.json',
        '--production-source-bundle', 'generated/base_translation_sources.bin',
        '--no-function-files', '--prune-stale', '--threads', "$Jobs")) $root
    Invoke-Step 'data-init' $trExe @($trPre + @('generate-data-init', '--project', $project)) $root
    Invoke-Step 'shards' $trExe @($trPre + @('emit-build-shards', '--project', $project,
        '--base-metadata', (Join-Path $root 'generated\base_translation_output.json'),
        '--base-functions-dir', (Join-Path $root 'generated\functions'),
        '--native-source-dir', (Join-Path $root 'runtime\src'),
        '--out', $shards)) $root
} else {
    Set-Status 'translation: reusing generated\build_shards'
}
if (-not (Test-Path -LiteralPath $shards)) {
    throw "No translated code in ${shards}: run without -SkipTranslate."
}

# ── 4. Cross-compile the engine ─────────────────────────────────────────────────────────────────
# aurora defaults to vendor + static when cross-compiling, which is what the Android app wants:
# SDL3 and Dawn end up inside libmain.so, so the APK carries one library. Dawn is taken as the
# hash-pinned prebuilt for android/aarch64 rather than built from source, which would add hours.
$configure = @(
    '-S', (Join-Path $root 'runtime'),
    '-B', $build,
    '-G', 'Ninja',
    "-DCMAKE_MAKE_PROGRAM=$($ninja -replace '\\', '/')",
    "-DCMAKE_TOOLCHAIN_FILE=$($ndkToolchainFile -replace '\\', '/')",
    '-DANDROID_ABI=arm64-v8a',
    "-DANDROID_PLATFORM=android-$MinSdk",
    '-DCMAKE_BUILD_TYPE=Release',
    '-DAURORA_DAWN_PROVIDER=package',
    '-DAURORA_SDL3_PROVIDER=vendor',
    '-DCMAKE_POLICY_DEFAULT_CMP0168=NEW',
    "-DMKW_TRANSLATED_COMPILE_JOBS=$shardJobs"
)
Invoke-Step 'configure-android' $cmake $configure $root
Invoke-Step 'compile-android' $cmake @('--build', $build, '--target', 'WiiCompiled', '--parallel', "$Jobs") $root

$engine = Join-Path $build 'libmain.so'
if (-not (Test-Path -LiteralPath $engine)) { throw "The engine was not produced: $engine" }

# ── 5. Stage it for Gradle ──────────────────────────────────────────────────────────────────────
$jniLibs = Join-Path $app 'app\src\main\jniLibs\arm64-v8a'
New-Item -ItemType Directory -Force -Path $jniLibs | Out-Null
$staged = Join-Path $jniLibs 'libmain.so'
Copy-Item -LiteralPath $engine -Destination $staged -Force
if (Test-Path -LiteralPath $strip) {
    # Translated code carries a great deal of debug information; unstripped it does not fit an APK.
    & $strip --strip-unneeded $staged
    if ($LASTEXITCODE -ne 0) { throw "llvm-strip failed with exit code $LASTEXITCODE" }
}
$megabytes = [Math]::Round((Get-Item -LiteralPath $staged).Length / 1MB, 1)
Set-Status "engine: $staged ($megabytes MB)"

# The files a desktop build keeps beside its executable, in the layout the engine looks for them in
# (runtime/src/platform/host_platform.cpp and RuntimeFiles.java).
$assets = Join-Path $app 'app\src\main\assets\runtime'
if (Test-Path -LiteralPath $assets) { Remove-Item -LiteralPath $assets -Recurse -Force }
New-Item -ItemType Directory -Force -Path $assets | Out-Null
Copy-Item -LiteralPath (Join-Path $root 'runtime\assets\dsp\dsp_coef.bin') -Destination $assets
Copy-Item -LiteralPath (Join-Path $root 'runtime\assets\certs\cacert.pem') -Destination $assets
Copy-Item -LiteralPath (Join-Path $root 'runtime\assets\pipeline\initial_pipeline_cache.db') -Destination $assets
Copy-Item -LiteralPath (Join-Path $root 'runtime\assets\wii') -Destination (Join-Path $assets 'wii_bootstrap') -Recurse
Set-Status "runtime files: $assets"

# The launcher's banner art (fan art by Dreaminerryday), kept in local-assets so the PC launcher and
# this app show the same picture.
$bannerSource = Join-Path $root 'local-assets\launcher_banner.jpg'
if (Test-Path -LiteralPath $bannerSource) {
    Copy-Item -LiteralPath $bannerSource -Destination (Join-Path $app 'app\src\main\assets') -Force
    Set-Status 'banner: local-assets\launcher_banner.jpg'
}

# The app icon is the PC launcher's own (local-assets\wario.ico): its 256x256 entry, which is
# stored inside the .ico as a PNG, so it only has to be cut out rather than converted.
$icoSource = Join-Path $root 'local-assets\wario.ico'
$iconTarget = Join-Path $app 'app\src\main\res\mipmap-xxxhdpi\ic_launcher_art.png'
if (Test-Path -LiteralPath $icoSource) {
    $ico = [IO.File]::ReadAllBytes($icoSource)
    $count = [BitConverter]::ToUInt16($ico, 4)
    $best = $null
    for ($i = 0; $i -lt $count; $i++) {
        $entry = 6 + $i * 16
        # A width byte of 0 means 256, which is the largest an .ico can hold.
        $width = if ($ico[$entry] -eq 0) { 256 } else { [int]$ico[$entry] }
        $size = [BitConverter]::ToInt32($ico, $entry + 8)
        $offset = [BitConverter]::ToInt32($ico, $entry + 12)
        if ($null -eq $best -or $width -gt $best.Width) {
            $best = [pscustomobject]@{ Width = $width; Size = $size; Offset = $offset }
        }
    }
    # Only the PNG-compressed entries can be used as they are; a BMP one would need converting.
    if ($best -and $ico[$best.Offset] -eq 0x89 -and $ico[$best.Offset + 1] -eq 0x50) {
        $png = New-Object byte[] $best.Size
        [Array]::Copy($ico, $best.Offset, $png, 0, $best.Size)
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $iconTarget) | Out-Null
        [IO.File]::WriteAllBytes($iconTarget, $png)
        Set-Status "icon: local-assets\wario.ico ($($best.Width)x$($best.Width))"
    } else {
        Set-Status 'icon: wario.ico has no PNG entry; keeping the one in the project'
    }
}

if ($SkipApk) {
    Set-Status 'done (the APK was not built: -SkipApk)'
    return
}

# ── 6. The APK ──────────────────────────────────────────────────────────────────────────────────
# SDL's Java half is compiled straight out of the SDL3 checkout the CMake build just downloaded, so
# it can never be a different version from the SDL inside the engine.
$sdlJava = Join-Path $build '_deps\sdl-src\android-project\app\src\main\java'
if (-not (Test-Path -LiteralPath $sdlJava)) {
    throw "SDL3's Java sources are missing: $sdlJava (expected from the vendored SDL3 build)"
}
# Written without a byte-order mark: a .properties file is read as text from the first byte, and a
# BOM would turn the key into one Gradle does not recognise. (Set-Content -Encoding utf8 adds one
# on Windows PowerShell.) Backslashes are doubled because they escape in a properties file.
[IO.File]::WriteAllText(
    (Join-Path $app 'local.properties'),
    "sdk.dir=$($AndroidSdk -replace '\\', '\\')`n",
    (New-Object System.Text.UTF8Encoding $false))

$gradle = (Get-Command gradle -ErrorAction SilentlyContinue).Source
if (-not $gradle) {
    # Gradle itself is not part of this repository (there is no wrapper jar to commit), so a
    # distribution a wrapper has already downloaded is reused when one is there.
    $dists = Join-Path $env:USERPROFILE '.gradle\wrapper\dists'
    if (Test-Path -LiteralPath $dists) {
        $gradle = (Get-ChildItem -LiteralPath $dists -Recurse -Filter 'gradle.bat' -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending | Select-Object -First 1).FullName
    }
}
if (-not $gradle) {
    Set-Status 'Gradle not found: open the android\ folder in Android Studio to build the APK.'
    return
}

$task = if ($Variant -eq 'debug') { 'assembleDebug' } else { 'assembleRelease' }
Invoke-Step 'gradle' $gradle @($task, "-Pwario.sdl3JavaDir=$($sdlJava -replace '\\', '/')") $app

$apk = Get-ChildItem -LiteralPath (Join-Path $app 'app\build\outputs\apk') -Recurse -Filter '*.apk' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($apk) {
    Set-Status "APK: $($apk.FullName)"
    Set-Status "install it with: adb install -r `"$($apk.FullName)`""
} else {
    Set-Status 'Gradle finished but no APK was found under android\app\build\outputs\apk.'
}
