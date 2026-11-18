// WarioRecomp for Android. The native engine is NOT built by Gradle: tools/build-wario-android.ps1
// runs the translator and the NDK CMake build, then drops libmain.so into
// app/src/main/jniLibs/arm64-v8a/ for this project to package. See android/README.md.
plugins {
    id("com.android.application") version "9.5.1" apply false
}
