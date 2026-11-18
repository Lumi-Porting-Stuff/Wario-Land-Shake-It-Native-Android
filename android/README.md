# WarioRecomp for Android

The same engine the PC build runs, cross-compiled for ARM64 phones. The game's PowerPC code is
statically recompiled to C++, so this is **not an emulator**: there is no PowerPC interpreter and no
JIT, and the Wii system libraries are replaced with native code. That is why a phone can run it at
all.

This is an **early port**, but a working one: the game boots, plays its opening and fills the
screen. It has been run on a Dimensity 8400 (Mali-G720 MC7, Android 16). The native engine already
carried Android support — it shares that with the NSMBW recomp it grew from — and what is new here
is the Android app around it, along with six fixes the port needed on the way
(see [Fixes this port needed](#fixes-this-port-needed)). What has and has not been tried is in
[What is left](#what-is-left).

## Will my phone run it?

The floor is **ARM64 with a working Vulkan 1.1 driver**, which in practice means roughly a
Snapdragon 630/660 or newer (Adreno 5xx late, 6xx and up) or an equivalent Mali, so about 2017
onwards. Anything from 2019 or later should be comfortable.

**A Snapdragon 600 will not work**, and cannot be made to:

- its Adreno 320 has no Vulkan driver at all, only OpenGL ES 3.0, and the renderer (Aurora on Dawn)
  is Vulkan-only;
- it is a 32-bit ARMv7 CPU, while the engine is built for `arm64-v8a` and the runtime's CMake
  accepts nothing else;
- the engine is compiled for ARMv8.2-A (LSE atomics, RCpc loads), which is the floor of every
  Android phone since Cortex-A55/A75.

Supporting it would mean writing a complete OpenGL ES backend to replace Dawn — a larger project
than this port.

The CPU is rarely the limit; the Vulkan driver is. Cheap or old devices often have incomplete
drivers, and Dawn is strict about conformance. `uses-feature` in the manifest keeps the app off
phones that would only fail at device creation.

## Building

You need the Android SDK with an NDK (r27 or newer), a JDK, and **your own copy of the game** (USA,
`RWLE01`, revision 0) extracted with Dolphin. Nothing from the game is in this repository.

```powershell
powershell -ExecutionPolicy Bypass -File tools\build-wario-android.ps1 -GameData <extracted DATA folder>
```

That script does the whole job:

1. checks `main.dol` against the known `RWLE01` hash;
2. runs the translator, producing the same C++ the Windows build compiles (reused from
   `generated\build_shards` if a desktop build already made it);
3. configures and builds `runtime\` with the NDK for `arm64-v8a`, producing `libmain.so`. SDL3 and
   Dawn are linked **into** it — Aurora defaults to vendored static dependencies when
   cross-compiling, and Dawn comes as the hash-pinned `android/aarch64` prebuilt rather than being
   built from source, which would add hours;
4. strips it and stages it in `app/src/main/jniLibs/arm64-v8a/`, and assembles the runtime files
   into `app/src/main/assets/runtime/`;
5. builds the APK with Gradle.

The first build downloads SDL3, Dawn and mbed TLS, and compiling the translated code takes a long
time and a lot of RAM (about 1.5 GB per parallel shard; the script sizes the job count to fit).

Useful switches: `-SkipTranslate` to reuse `generated\`, `-SkipApk` to stop after `libmain.so`,
`-Variant debug`, and `-Ndk <path>` to pick an NDK.

### Building the APK by itself

Gradle only packages what step 3 produced, so once `libmain.so` is staged you can open the
`android` folder in Android Studio, or run Gradle directly:

```powershell
gradle assembleRelease "-Pwario.sdl3JavaDir=<build-android>/_deps/sdl-src/android-project/app/src/main/java"
```

There is no Gradle wrapper in the repository (committing the wrapper jar is not worth it here), so
use Android Studio's Gradle or your own. The project is built with AGP 9.5.1 on Gradle 9.6.

**SDL3's Java half is not vendored.** `org.libsdl.app.*` is compiled straight out of the SDL3
checkout the native build downloaded, so the Java side can never be a different version from the
SDL linked into the engine. That is what `wario.sdl3JavaDir` points at.

## Putting the game on the phone

The recompiled game code opens the disc with ordinary file calls, exactly as the Windows build
does, so the folder has to be a real path: a folder picked through Android's document picker is a
`content://` URI that no such call can open. The app therefore reads the disc from a plain folder on
shared storage, visible in any file manager:

```
/sdcard/WarioRecomp/game
```

Reading a folder outside its own sandbox needs all-files access (`MANAGE_EXTERNAL_STORAGE`), which
the launcher's **Game data** page asks for. The app uses it for nothing else: the disc is only ever
read, and the app's own settings, saves, caches and logs stay in its private folder.

There are two ways to fill that folder, and the launcher offers both:

- **Copy it onto the phone** (no extra space). There is a script, which checks the disc before
  spending minutes copying it:

  ```powershell
  powershell -ExecutionPolicy Bypass -File tools\push-game-android.ps1 -GameData <extracted DATA folder>
  ```

  It finds `adb` itself, refuses anything that is not `RWLE01` revision 0, and pushes `files` and
  `sys` separately — `adb push` of a whole folder renames its destination differently between
  versions, and the app looks for exactly `game/files` and `game/sys`. Pass `-Remote` for another
  path, or `-AppPrivate` for the app's own folder.

- **Import it in the launcher**: pick the folder and it is copied in, which needs room for a second
  copy. An extracted Wario disc is about 3.5 GB.

The app's own folder, `Android/data/com.santiago109.wariorecomp/files/game`, is still accepted and
needs no permission at all; it is just awkward to reach on Android 11 and later, where even `adb`
is blocked from it on some builds.

Either way the app checks `sys/boot.bin` for `RWLE01` revision 0 and refuses anything else, with
the same explanations the PC build gives, and writes the folder into `Config.toml` as `dvd_root`.

## Controls

The game only ever reads a Wii Remote held sideways, so that is what the app presents: a D-pad, 1, 2
and A, `+` and `−`, and a **SHAKE** button for Wario's shake. Tilting the phone tilts the remote
(the axis out of the screen, mapped through the display's rotation so reverse landscape is not
inverted) and shaking the phone shakes it; both work at the same time as the on-screen controls, the
way the PC build lets a DualSense's sensors and sticks drive one remote. A Bluetooth gamepad works
through SDL as it does on PC.

Touches that miss every control are passed on to SDL *and* reported to the engine's own tap-driven
menus, which hide the overlay while they are up.

## How the app and the engine meet

Everything crosses in `runtime/src/android/android_bridge.cpp`:

| Call | What it is for |
| --- | --- |
| `nativeSetPaths` | Where the unpacked runtime files and this game's own state live. There is no executable directory inside an APK, so `host_platform.cpp` reads both from the environment. |
| `nativeSetTouchState` | The on-screen remote and the phone's motion, as `VirtualRemote::TouchButton` bits. |
| `nativeApplyDisplaySettings` / `nativeTakeSettings` | Aspect, render scale and the FPS counter, pushed in at start and read back when the player changes them in the game's own settings window, so they survive a restart. |
| `nativeTap` / `nativeTapScreen` | Menus the engine draws itself. |
| `nativeSetDeviceName` | What this phone is called in LAN room lists. |
| `restartForSession` | LAN play needs the engine's state untouched, and it cannot unwind itself, so the process is replaced. |

The bridge also exposes `nativeGraphicsInfo`, `nativeSessionActive` and `nativePrepareToLeave`,
which this app does not use yet; they are for a settings screen and a "leave LAN game" menu.

`WIICOMPILED_RUNTIME_DIR` must hold `dsp_coef.bin`, `cacert.pem` and
`initial_pipeline_cache.db` loose, plus the Wii bootstrap NAND under `wii_bootstrap/shared2/wc24/`.
That is the layout the engine looks for beside an executable, and the build script assembles it from
`runtime/assets`.

## Fixes this port needed

None of these were in the Android app: they were in code the PC build already used, which happened
to work only because of how a desktop builds or starts. They are all in the repository.

| Where | What was wrong |
| --- | --- |
| `aurora/cmake/AuroraDawnProvider.cmake` | Dawn's prebuilt Android package records the absolute paths of the Linux machine that built it, so the link asked for a `liblog.so` under a path that exists nowhere else. Its bare name is used instead. |
| `aurora/extern/CMakeLists.txt` | fmt 11.1 calls `malloc`/`free` without including `<cstdlib>`; the existing workaround covered only MinGW, and the NDK needs it too. |
| `aurora/extern/CMakeLists.txt` | Dependencies defaulted to shared, so libpng became a `libpng16.so` that an APK has nowhere to put. On Android everything links into `libmain.so`. |
| `runtime/src/guest_flat_memory.cpp` | The NDK only declares `memfd_create` from API 30. The system call is far older, so it is called directly and the floor stays at Android 9. |
| `android/.../GameActivity.java` | The engine reads `Config.toml` from a static initialiser, inside `System.loadLibrary`, and keeps that first answer. Its folders must therefore be in the environment *before* SDL loads it, which a JNI call cannot do. |
| `runtime/src/android/android_bridge.cpp` | aurora will not present a frame until it is told a surface exists, and upstream expects a patched `SDLSurface.java` to say so. This port builds SDL from its own sources, so the app watches the surface through `SurfaceHolder` and reports it. Without this the engine runs, draws, and shows a black screen. |

## What is left

Working: the game boots, reads the disc, plays its opening video with sound, and fills the screen.
The launcher's pages, the disc import and the log viewer work.

Not tried yet:

- **The controls.** The on-screen remote, the shake button and the phone's motion are written and
  wired, but none of them has been used in a real session.
- **Saves, and playing past the opening.**
- **16 KiB page sizes.** The link passes `-z max-page-size=16384`; the one phone it has run on does
  not use them.

Not done:

- **A settings screen for everything.** The launcher covers display, graphics, audio, game and
  controls; frame interpolation, texture packs and save backups from the PC launcher are not there.
- **Startup time.** `libmain.so` is about 165 MB of translated code, and loading it is slow enough
  to notice.
- **Signing.** The release build is signed with the debug key, so it is for testing, not release.
