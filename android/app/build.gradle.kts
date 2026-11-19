plugins {
    id("com.android.application")
}

// One place for the version: defaultConfig below and the release file's name both read it.
val warioVersion = "0.4.0"

// SDL3's activity classes come from the SDL3 source tree the native build already fetched, rather
// than a copy checked in here (see gradle.properties). Without it the app has no SDLActivity to
// extend, so the build stops with a readable message instead of a missing-symbol error.
val sdl3JavaDir: String = (project.findProperty("wario.sdl3JavaDir") as String?)
    ?: error("wario.sdl3JavaDir is not set")
// Resolved against the android/ folder rather than app/, so the default in gradle.properties can
// point at the native build directory next to it.
val sdl3Java = rootProject.file(sdl3JavaDir)

android {
    namespace = "com.santiago109.wariorecomp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.santiago109.wariorecomp"
        // Android 10. The engine needs a 64-bit CPU and a working Vulkan 1.1 driver, which in
        // practice means Snapdragon 630/660 or newer (Adreno 5xx late / 6xx+) or an equivalent
        // Mali; older phones install but cannot create a device.
        //
        // The floor is 29 rather than 28 for the engine's sake, not the app's: below 29 the NDK
        // emulates thread-local storage, and this runtime reads thread-locals on essentially every
        // translated guest call. Keep this in step with -MinSdk in tools/build-wario-android.ps1,
        // which is what the engine itself is compiled against.
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = warioVersion

        ndk {
            // The runtime's CMake only accepts arm64-v8a (runtime/CMakeLists.txt).
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    // SDL3's activity classes join this module's own sources. The engine and the runtime assets sit
    // in the default jniLibs and assets folders, which tools/build-wario-android.ps1 fills.
    sourceSets.getByName("main").java.directories.add(sdl3Java.absolutePath)

    packaging {
        jniLibs {
            // libmain.so is hundreds of megabytes of translated code; leaving it uncompressed lets
            // Android map it from the APK instead of unpacking a second copy at install time.
            useLegacyPackaging = false
        }
    }

    androidResources {
        // Copied in verbatim: the pipeline cache and the bootstrap NAND must stay byte-exact.
        noCompress.addAll(listOf("bin", "db", "pem", "ctl", "mbx", "cbk", "cfg"))
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
}

// A clear failure beats a confusing one: the engine and SDL3's Java side both come from the native
// build, so check for them before javac does.
tasks.register("checkNativeBuild") {
    doFirst {
        val engine = file("src/main/jniLibs/arm64-v8a/libmain.so")
        if (!engine.exists()) {
            throw GradleException(
                "The engine is missing: ${engine.path}\n" +
                    "Build it first with tools\\build-wario-android.ps1 (it runs the translator and " +
                    "the NDK build, then copies libmain.so here)."
            )
        }
        if (!sdl3Java.isDirectory) {
            throw GradleException(
                "SDL3's Java sources are missing: ${sdl3Java.path}\n" +
                    "They come from the SDL3 checkout the native CMake build downloads. Run " +
                    "tools\\build-wario-android.ps1, or point wario.sdl3JavaDir at an SDL3 source tree."
            )
        }
    }
}

tasks.matching { it.name.startsWith("compile") && it.name.contains("JavaWithJavac") }.configureEach {
    dependsOn("checkNativeBuild")
}

// The release file is named for what it is. It leaves this folder and ends up in a download or a
// chat, where "app-release.apk" says nothing about which build somebody is holding and two of them
// are indistinguishable. AGP 8 removed the old applicationVariants hook, so this is the variant
// API that replaced it.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            (output as com.android.build.api.variant.impl.VariantOutputImpl).outputFileName.set(
                "WarioRecomp-$warioVersion-arm64-v8a-${variant.buildType}.apk")
        }
    }
}
