# Public WiiCompiled product graph.
#
# The translator owns the translated build graph. Mario Kart's profile-neutral
# functions are compiled once into mkw_base_shared; only callers whose direct
# ABI differs between profiles receive small base/RR variants.

set(DATA_INIT_FILE "${MKW_RUNTIME_SOURCE_DIR}/../generated/data_sections_init.cpp")
set(DATA_INIT_BLOB_ASM "${MKW_RUNTIME_SOURCE_DIR}/../generated/data_sections_init_blobs.S")
if(EXISTS "${DATA_INIT_FILE}")
    list(APPEND SOURCES "${DATA_INIT_FILE}")
endif()
# Crash-report symbolization table emitted by generate-data-init. The stub
# (deliberately outside the globbed src/ tree so it is never picked up twice)
# keeps link succeeding when the generated table has not been produced yet.
set(GUEST_SYMBOL_TABLE_FILE "${MKW_RUNTIME_SOURCE_DIR}/../generated/guest_symbol_table.cpp")
if(EXISTS "${GUEST_SYMBOL_TABLE_FILE}")
    list(APPEND SOURCES "${GUEST_SYMBOL_TABLE_FILE}")
else()
    list(APPEND SOURCES "${MKW_RUNTIME_SOURCE_DIR}/cmake/guest_symbol_table_stub.cpp")
endif()
if(EXISTS "${DATA_INIT_BLOB_ASM}")
    enable_language(ASM)
    set_source_files_properties("${DATA_INIT_BLOB_ASM}" PROPERTIES LANGUAGE ASM SKIP_UNITY_BUILD_INCLUSION ON)
    list(APPEND SOURCES "${DATA_INIT_BLOB_ASM}")
endif()
list(REMOVE_DUPLICATES SOURCES)

if(MKW_PLATFORM_MACOS)
    find_library(MKW_IOKIT_FRAMEWORK IOKit REQUIRED)
    find_library(MKW_COREFOUNDATION_FRAMEWORK CoreFoundation REQUIRED)
    find_library(MKW_COREAUDIO_FRAMEWORK CoreAudio REQUIRED)
endif()

function(mkw_apply_common_compile_options target)
    target_compile_options(${target} PRIVATE -O3 -ffast-math -w -pipe)
endfunction()

function(mkw_apply_translated_compile_options target)
    # The translated game code is the bulk of a frame on a phone, and profiling it showed no single
    # hot function to fix: the time is spread across the whole game, which is exactly the shape that
    # a higher optimisation level helps. The floating-point behaviour does not change with it —
    # MKW_TRANSLATED_PPC_FP_OPTIONS pins that with -fno-fast-math and -ffp-contract=off, and -O3
    # enables neither. Desktops keep -O2, where the frame budget is not the problem and the build
    # time and memory of -O3 over this much code are not worth paying.
    set(optimisation -O2)
    if(MKW_PLATFORM_ANDROID)
        set(optimisation -O3)
    endif()
    target_compile_options(${target} PRIVATE
        ${optimisation} ${MKW_TRANSLATED_PPC_FP_OPTIONS} -fno-slp-vectorize -w -pipe)
endfunction()

function(mkw_configure_object_target target)
    target_include_directories(${target} PRIVATE
        "${MKW_RUNTIME_SOURCE_DIR}/include"
        "${MKW_RUNTIME_SOURCE_DIR}/src"
        # Workspace root, so translator output is spelled "generated/<x>.h"
        # instead of a ../ chain whose depth depends on the includer.
        "${MKW_RUNTIME_SOURCE_DIR}/.."
        "${MKW_RUNTIME_SOURCE_DIR}/../aurora/include")
    target_compile_definitions(${target} PRIVATE
        TARGET_PC)
    set_target_properties(${target} PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
endfunction()

# Translated shard TUs are the memory-hungry compiles; everything else in the build is
# comparatively small. A dedicated Ninja job pool caps how many of them run at once so the
# global parallelism can use every core for the cheap TUs without the memory ceiling being a
# guess. The pool depth is per-machine (derived from installed RAM by LocalBuild.ps1) and is
# deliberately not part of the canonical flag set: it changes scheduling, never output bytes.
if(MKW_TRANSLATED_COMPILE_JOBS GREATER 0)
    set_property(GLOBAL APPEND PROPERTY JOB_POOLS "mkw_translated=${MKW_TRANSLATED_COMPILE_JOBS}")
endif()

function(mkw_bound_translated_compiles target)
    if(MKW_TRANSLATED_COMPILE_JOBS GREATER 0)
        set_property(TARGET ${target} PROPERTY JOB_POOL_COMPILE mkw_translated)
    endif()
endfunction()

function(mkw_configure_translated_target target)
    mkw_configure_object_target(${target})
    mkw_apply_translated_compile_options(${target})
    mkw_bound_translated_compiles(${target})
endfunction()

add_library(mkw_runtime_common OBJECT ${SOURCES})
mkw_configure_object_target(mkw_runtime_common)
target_compile_features(mkw_runtime_common PRIVATE cxx_std_20)
target_compile_definitions(mkw_runtime_common PRIVATE
    SDL_MAIN_HANDLED
    _DISABLE_STRING_ANNOTATION _DISABLE_VECTOR_ANNOTATION)
target_link_libraries(mkw_runtime_common PRIVATE
    aurora::gx aurora::pad aurora::si aurora::vi aurora::mtx)
target_link_libraries(mkw_runtime_common PRIVATE mkw_platform mkw::pugixml mkw::toml11 mkw::cryptopp mkw::mbedtls)
if(MKW_PLATFORM_WINDOWS)
    # ole32/uuid: the first-start game folder picker (game_folder.cpp).
    target_link_libraries(mkw_runtime_common PRIVATE shell32 windowsapp ole32 uuid)
elseif(MKW_PLATFORM_LINUX)
    # ${CMAKE_DL_LIBS} for music_attenuation.cpp's dlopen of libdbus-1 (MPRIS
    # media monitoring).
    target_link_libraries(mkw_runtime_common PRIVATE mkw::libco ${CMAKE_DL_LIBS})
elseif(MKW_PLATFORM_ANDROID)
    target_link_libraries(mkw_runtime_common PRIVATE android log ${CMAKE_DL_LIBS})
endif()

if(MKW_PLATFORM_MACOS)
    # CoreAudio framework is required for automatic music muting on macOS.
    target_link_libraries(mkw_runtime_common PRIVATE "${MKW_COREAUDIO_FRAMEWORK}")
    if(MKW_PLATFORM_MACOS_X86_64)
        # libco is used by Intel macOS. Apple Silicon uses the local
        # x18-safe assembly backend and therefore does not define mkw::libco.
        target_link_libraries(mkw_runtime_common PRIVATE mkw::libco)
    endif()
endif()

if(MKW_CPPWINRT_INCLUDE_DIR)
    if(NOT EXISTS "${MKW_CPPWINRT_INCLUDE_DIR}/winrt/base.h")
        message(FATAL_ERROR
            "MKW_CPPWINRT_INCLUDE_DIR does not contain winrt/base.h: ${MKW_CPPWINRT_INCLUDE_DIR}")
    endif()
    target_include_directories(mkw_runtime_common PRIVATE "${MKW_CPPWINRT_INCLUDE_DIR}")
endif()

# Keep runtime unity units small and semantically related. The old generated-TU
# batch size put all 57 native runtime sources into one memory-heavy compiler job.
foreach(source IN LISTS SOURCES)
    get_filename_component(source_name "${source}" NAME_WE)
    string(REPLACE "\\" "/" source_normalized "${source}")
    if(source_normalized MATCHES "/hle/gx/")
        set(runtime_group "gx_bridge")
    elseif(source_name MATCHES "network|socket|dns|dwc|ios")
        set(runtime_group "network_ios")
    elseif(source_name MATCHES "os_|system|memory|fiber|scheduler")
        set(runtime_group "guest_system")
    elseif(source_name MATCHES "debug|trace|prof")
        set(runtime_group "diagnostics")
    else()
        string(SHA256 source_hash "${source_name}")
        string(SUBSTRING "${source_hash}" 0 4 source_hash_prefix)
        math(EXPR runtime_bucket "0x${source_hash_prefix} % 8")
        set(runtime_group "runtime_${runtime_bucket}")
    endif()
    set_source_files_properties("${source}" PROPERTIES UNITY_GROUP "${runtime_group}")
endforeach()
# PPC semantic sources are excluded from unity/PCH and configured in CMakeLists.txt.
set_target_properties(mkw_runtime_common PROPERTIES UNITY_BUILD ON UNITY_BUILD_MODE GROUP)
target_precompile_headers(mkw_runtime_common PRIVATE "${MKW_RUNTIME_SOURCE_DIR}/include/mkw_pch.h")
mkw_apply_common_compile_options(mkw_runtime_common)

# Host ISA guard. Windows and Linux x86_64 product targets use x86-64-v3, so
# this object deliberately keeps the plain baseline ISA and checks the CPU
# before any AVX2/FMA code can execute. AArch64 has no equivalent optional ISA
# floor to probe: NEON/FMA are architectural requirements.
if(CMAKE_SYSTEM_PROCESSOR MATCHES "^(AMD64|amd64|x86_64|X86_64)$")
    add_library(mkw_cpu_baseline OBJECT "${MKW_CPU_BASELINE_SOURCE}")
    target_compile_features(mkw_cpu_baseline PRIVATE cxx_std_17)
    set_target_properties(mkw_cpu_baseline PROPERTIES UNITY_BUILD OFF)
    target_compile_options(mkw_cpu_baseline PRIVATE -w)
endif()

if(NOT MKW_BASE_COMMON_SHARDS)
    message(FATAL_ERROR "Translator build graph contains no shared base shards")
endif()

add_library(mkw_base_shared STATIC ${MKW_BASE_COMMON_SHARDS})
mkw_configure_translated_target(mkw_base_shared)
target_precompile_headers(mkw_base_shared PRIVATE "${MKW_RUNTIME_SOURCE_DIR}/include/mkw_pch.h")

if(MKW_BASE_PORTABLE_SENSITIVE_SHARDS)
    add_library(mkw_base_sensitive OBJECT ${MKW_BASE_PORTABLE_SENSITIVE_SHARDS})
    mkw_configure_translated_target(mkw_base_sensitive)
    target_precompile_headers(mkw_base_sensitive REUSE_FROM mkw_base_shared)
endif()

if(MKW_HAVE_RETRO_REWIND)
    if(MKW_RETRO_PORTABLE_SENSITIVE_SHARDS)
        add_library(mkw_retro_sensitive OBJECT ${MKW_RETRO_PORTABLE_SENSITIVE_SHARDS})
        mkw_configure_translated_target(mkw_retro_sensitive)
        target_precompile_headers(mkw_retro_sensitive REUSE_FROM mkw_base_shared)
    endif()

    set(MKW_RETRO_TRANSLATED_SOURCES ${MKW_RETRO_MOD_SHARDS} ${MKW_RETRO_EXTRA_SOURCES})
    set(MKW_RETRO_BLOB_OBJECTS)
    foreach(source IN LISTS MKW_RETRO_EXTRA_SOURCES)
        if(source MATCHES "\\.S$")
            enable_language(ASM)
            set_source_files_properties("${source}" PROPERTIES LANGUAGE ASM SKIP_PRECOMPILE_HEADERS ON)
        endif()
    endforeach()
    add_library(mkw_retro_rewind_functions OBJECT ${MKW_RETRO_TRANSLATED_SOURCES})
    mkw_configure_translated_target(mkw_retro_rewind_functions)
    target_precompile_headers(mkw_retro_rewind_functions REUSE_FROM mkw_base_shared)
endif()

function(mkw_configure_product target)
    target_sources(${target} PRIVATE $<TARGET_OBJECTS:mkw_runtime_common>)
    # Startup CPU check. Must stay a separate object library so it keeps the
    # plain baseline ISA while everything around it is built for x86-64-v3.
    if(CMAKE_SYSTEM_PROCESSOR MATCHES "^(AMD64|amd64|x86_64|X86_64)$")
        target_sources(${target} PRIVATE $<TARGET_OBJECTS:mkw_cpu_baseline>)
    endif()
    target_include_directories(${target} PRIVATE
        "${MKW_RUNTIME_SOURCE_DIR}/include"
        "${MKW_RUNTIME_SOURCE_DIR}/src"
        # Workspace root, so translator output is spelled "generated/<x>.h"
        # instead of a ../ chain whose depth depends on the includer.
        "${MKW_RUNTIME_SOURCE_DIR}/.."
        "${MKW_RUNTIME_SOURCE_DIR}/../aurora/include")
    target_compile_definitions(${target} PRIVATE
        SDL_MAIN_HANDLED _DISABLE_STRING_ANNOTATION _DISABLE_VECTOR_ANNOTATION TARGET_PC)
    target_compile_features(${target} PRIVATE cxx_std_20)
    mkw_apply_common_compile_options(${target})
    # The dispatch-table and registration shards compile inside the product target itself and
    # include the same fat translated headers; bound them by the same pool.
    mkw_bound_translated_compiles(${target})
    target_link_libraries(${target} PRIVATE
        mkw_platform mkw_base_shared mkw::pugixml mkw::toml11 mkw::cryptopp mkw::mbedtls)

    target_link_libraries(${target} PRIVATE
        aurora::gx aurora::pad aurora::si aurora::vi aurora::mtx)
    if(MKW_PLATFORM_MACOS)
        target_link_libraries(${target} PRIVATE
            "${MKW_IOKIT_FRAMEWORK}" "${MKW_COREFOUNDATION_FRAMEWORK}" "${MKW_COREAUDIO_FRAMEWORK}")
        target_link_options(${target} PRIVATE
            "LINKER:-U,_OBJC_CLASS_$_MTLLogStateDescriptor")
    endif()
    if(EXISTS "${MKW_AURORA_DIR}/cmake/AuroraCopyRuntimeDLLs.cmake")
        include("${MKW_AURORA_DIR}/cmake/AuroraCopyRuntimeDLLs.cmake")
        aurora_copy_runtime_dlls(${target})
    endif()
    if(TARGET sqlite3)
        get_target_property(MKW_SQLITE_TARGET_TYPE sqlite3 TYPE)
    endif()
    if(TARGET sqlite3 AND
       (MKW_SQLITE_TARGET_TYPE STREQUAL "SHARED_LIBRARY" OR
        MKW_SQLITE_TARGET_TYPE STREQUAL "MODULE_LIBRARY"))
        add_custom_command(TARGET ${target} POST_BUILD COMMAND ${CMAKE_COMMAND} -E copy_if_different
            $<TARGET_FILE:sqlite3> $<TARGET_FILE_DIR:${target}>)
    endif()

    if(MKW_PLATFORM_WINDOWS)
        target_link_libraries(${target} PRIVATE
            dbghelp user32 winmm ws2_32 iphlpapi secur32 crypt32 windowsapp)

        set_target_properties(${target} PROPERTIES WIN32_EXECUTABLE TRUE)
    elseif(MKW_PLATFORM_LINUX OR MKW_PLATFORM_MACOS_X86_64)
        # mkw_runtime_common is an OBJECT library: WiiCompiled/RetroRewind only pull in its .o
        # files via $<TARGET_OBJECTS:>, which does not propagate mkw_runtime_common's own
        # target_link_libraries (object libraries don't carry usage requirements to a consumer
        # that isn't itself linked against as a target). fiber_manager.cpp's co_* calls live in
        # those objects, so the actual executable link needs mkw::libco directly, same as it
        # needs it independently of that first `if(WIN32)` branch above. ${CMAKE_DL_LIBS} is
        # here for the same reason: music_attenuation.cpp's dlopen(libdbus-1) lives in those
        # objects (empty string on glibc >= 2.34, where dl* is in libc).
        target_link_libraries(${target} PRIVATE mkw::libco ${CMAKE_DL_LIBS})
    elseif(MKW_PLATFORM_ANDROID)
        # The same object-library gap as above: the product link needs the libraries the runtime
        # objects call into. SDL's Android activity loads the product as libmain.so and runs its
        # exported SDL_main (runtime/src/main.cpp). 16 KiB pages keep it loadable on Android 15+
        # devices that use them.
        target_link_libraries(${target} PRIVATE android log ${CMAKE_DL_LIBS})
        target_link_options(${target} PRIVATE "LINKER:-z,max-page-size=16384" "LINKER:--no-undefined")
        set_target_properties(${target} PROPERTIES OUTPUT_NAME main)
    endif()
    if(MKW_PLATFORM_WINDOWS)
        foreach(runtime_dll libc++.dll libunwind.dll)
            execute_process(
                COMMAND "${CMAKE_CXX_COMPILER}" "--print-file-name=${runtime_dll}"
                OUTPUT_VARIABLE runtime_dll_path
                OUTPUT_STRIP_TRAILING_WHITESPACE)
            if(NOT EXISTS "${runtime_dll_path}")
                get_filename_component(mkw_compiler_bin "${CMAKE_CXX_COMPILER}" DIRECTORY)
                set(runtime_dll_path "${mkw_compiler_bin}/${runtime_dll}")
            endif()
            if(NOT EXISTS "${runtime_dll_path}")
                message(FATAL_ERROR "llvm-mingw runtime DLL not found: ${runtime_dll}")
            endif()
            add_custom_command(TARGET ${target} POST_BUILD
                COMMAND ${CMAKE_COMMAND} -E copy_if_different
                    "${runtime_dll_path}" $<TARGET_FILE_DIR:${target}>)
        endforeach()
    endif()

    set(MKW_WII_BOOTSTRAP_SOURCE_DIR "${MKW_RUNTIME_SOURCE_DIR}/assets/wii")
    if(NOT EXISTS "${MKW_WII_BOOTSTRAP_SOURCE_DIR}/shared2/wc24")
        message(FATAL_ERROR "Missing Wii first-run bootstrap payload: ${MKW_WII_BOOTSTRAP_SOURCE_DIR}")
    endif()
    add_custom_command(TARGET ${target} POST_BUILD COMMAND ${CMAKE_COMMAND} -E copy_directory
        "${MKW_WII_BOOTSTRAP_SOURCE_DIR}" "$<TARGET_FILE_DIR:${target}>/wii_bootstrap")

    set(MKW_DSP_COEFFICIENT_ROM "${MKW_RUNTIME_SOURCE_DIR}/assets/dsp/dsp_coef.bin")
    if(NOT EXISTS "${MKW_DSP_COEFFICIENT_ROM}")
        message(FATAL_ERROR "Missing Wii DSP coefficient ROM: ${MKW_DSP_COEFFICIENT_ROM}")
    endif()
    file(SHA256 "${MKW_DSP_COEFFICIENT_ROM}" MKW_DSP_COEFFICIENT_ROM_SHA256)
    if(NOT MKW_DSP_COEFFICIENT_ROM_SHA256 STREQUAL
       "d7741279c2e8ec5c5fb318f8fbdd6de6bf583520d288e836a5383233a4238179")
        message(FATAL_ERROR "Wii DSP coefficient ROM hash mismatch: ${MKW_DSP_COEFFICIENT_ROM_SHA256}")
    endif()
    add_custom_command(TARGET ${target} POST_BUILD COMMAND ${CMAKE_COMMAND} -E copy_if_different
        "${MKW_DSP_COEFFICIENT_ROM}" "$<TARGET_FILE_DIR:${target}>/dsp_coef.bin")

    # Aurora imports this portable recipe database into each user's writable
    # pipeline cache. Keep the upstream filename so its default resourcesPath
    # lookup works without application-specific configuration.
    set(MKW_INITIAL_PIPELINE_CACHE
        "${MKW_RUNTIME_SOURCE_DIR}/assets/pipeline/initial_pipeline_cache.db")
    if(NOT EXISTS "${MKW_INITIAL_PIPELINE_CACHE}")
        message(FATAL_ERROR "Missing transferable Aurora pipeline cache: ${MKW_INITIAL_PIPELINE_CACHE}")
    endif()
    add_custom_command(TARGET ${target} POST_BUILD COMMAND ${CMAKE_COMMAND} -E copy_if_different
        "${MKW_INITIAL_PIPELINE_CACHE}"
        "$<TARGET_FILE_DIR:${target}>/initial_pipeline_cache.db")

    # Non-Windows TLS (runtime/src/hle/net/network_ssl.cpp's mbed TLS path) needs a trusted root
    # CA bundle to verify server certificates against - Windows gets this for free from the OS via
    # Schannel, mbed TLS does not ship one itself. Not SHA256-pinned like the DSP ROM above: unlike
    # a fixed hardware ROM, this bundle is expected to be refreshed periodically as CAs rotate.
    # Windows gets its trust store from Schannel, so only the platforms that actually build the
    # mbed TLS path need the bundle beside the executable.
    if(NOT MKW_PLATFORM_WINDOWS)
        set(MKW_CA_CERTIFICATE_BUNDLE "${MKW_RUNTIME_SOURCE_DIR}/assets/certs/cacert.pem")
        if(NOT EXISTS "${MKW_CA_CERTIFICATE_BUNDLE}")
            message(FATAL_ERROR "Missing TLS root CA bundle: ${MKW_CA_CERTIFICATE_BUNDLE}")
        endif()
        add_custom_command(TARGET ${target} POST_BUILD COMMAND ${CMAKE_COMMAND} -E copy_if_different
            "${MKW_CA_CERTIFICATE_BUNDLE}" "$<TARGET_FILE_DIR:${target}>/cacert.pem")
    endif()
endfunction()

# MKW_ENGINE_MODULE (Windows): the engine is a DLL named MKW_ENGINE_MODULE_NAME exporting SDL_main, like the Android
# libmain.so, and WiiCompiled.exe is the small loader in src/product/engine_loader.cpp that picks the region's module.
option(MKW_ENGINE_MODULE "Build the engine as a region module DLL plus the loader executable" OFF)
set(MKW_ENGINE_MODULE_NAME "engine_usa" CACHE STRING "Output name of the engine module DLL")
if(MKW_PLATFORM_ANDROID OR (MKW_PLATFORM_WINDOWS AND MKW_ENGINE_MODULE))
    add_library(WiiCompiled SHARED "${MKW_BASE_PRODUCT_SOURCE}" ${MKW_BASE_REGISTRATION_SOURCES})
else()
    add_executable(WiiCompiled "${MKW_BASE_PRODUCT_SOURCE}" ${MKW_BASE_REGISTRATION_SOURCES})
endif()
mkw_configure_product(WiiCompiled)
if(MKW_PLATFORM_ANDROID)
    # Bind calls inside libmain.so straight to their definitions instead of routing them through
    # the PLT. The whole engine - runtime, aurora, SDL, Dawn - lives in this one library, so nearly
    # every call it makes is to itself, and each one otherwise costs an indirect jump through a
    # stub whose target the dynamic linker could have resolved at load time. A profile of the game
    # thread put 1.9% of its time in those stubs alone.
    #
    # What this gives up is symbol interposition: another library can no longer replace a function
    # this one defines and have this one's internal calls see the replacement. Nothing here wants
    # that - the library is the program - and it stays exported for anyone calling in, which is
    # what JNI and SDL_main need.
    target_link_options(WiiCompiled PRIVATE "-Wl,-Bsymbolic-functions")

    # Keeping the engine's thread-local block small is a performance measure, and the mechanism is
    # worth stating because it is not the obvious one.
    #
    # The runtime reads thread-locals on essentially every translated guest call - the indirect
    # dispatcher's memo tables, the current execution address - so how a thread-local is addressed
    # is paid across the whole instruction stream. A shared library reaches its own through TLSDESC,
    # and bionic resolves those two ways: tlsdesc_resolver_static returns a precomputed offset from
    # the thread pointer, while tlsdesc_resolver_dynamic walks the thread's descriptor vector and
    # may allocate. Which one a library gets depends on whether the loader could give it an offset
    # in the static TLS area it reserves for libraries opened with dlopen - which the engine is,
    # since the app loads it with System.loadLibrary. Fit that reserve and every thread-local read
    # takes the cheap path; overrun it and they all take the expensive one, silently.
    #
    # Note for anyone tempted: -ftls-model=initial-exec does NOT work here, whatever the size.
    # Bionic refuses IE relocations from a dlopen'ed library outright - "TLS symbol ... using IE
    # access model" - and the library fails to load. Fitting the reserve is the whole lever.
    #
    # Android's reserve is 1664 bytes (bionic's SOLIB_TLS_SURPLUS), shared with anything else the
    # process dlopens, so the budget below is deliberately under it. At the time of writing the
    # engine uses about 680 bytes; the headroom is for a thread-local or two, not for another
    # table. Anything larger belongs on the heap behind a thread-local pointer - see abi_bridge.h.
    set(MKW_TLS_BUDGET_BYTES 1280)
    find_program(MKW_READELF NAMES llvm-readelf readelf
        HINTS "${ANDROID_TOOLCHAIN_ROOT}/bin" "${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/${ANDROID_HOST_TAG}/bin")
    if(MKW_READELF)
        add_custom_command(TARGET WiiCompiled POST_BUILD
            COMMAND "${CMAKE_COMMAND}"
                    "-DREADELF=${MKW_READELF}"
                    "-DLIBRARY=$<TARGET_FILE:WiiCompiled>"
                    "-DBUDGET=${MKW_TLS_BUDGET_BYTES}"
                    -P "${CMAKE_CURRENT_LIST_DIR}/CheckTlsSize.cmake"
            VERBATIM)
    else()
        message(WARNING
            "llvm-readelf was not found, so the thread-local storage budget is unchecked. "
            "If the app fails to load libmain.so at startup, that is the first thing to measure.")
    endif()
endif()
if(MKW_PLATFORM_WINDOWS AND MKW_ENGINE_MODULE)
    set_target_properties(WiiCompiled PROPERTIES OUTPUT_NAME "${MKW_ENGINE_MODULE_NAME}" PREFIX "")
    target_compile_definitions(mkw_runtime_common PRIVATE MKW_ENGINE_MODULE)  # main.cpp exports SDL_main instead of main
    add_executable(WiiCompiledLoader "${MKW_RUNTIME_SOURCE_DIR}/src/product/engine_loader.cpp")
    target_compile_features(WiiCompiledLoader PRIVATE cxx_std_20)
    set_target_properties(WiiCompiledLoader PROPERTIES OUTPUT_NAME WiiCompiled WIN32_EXECUTABLE TRUE)
    target_link_libraries(WiiCompiledLoader PRIVATE user32)
endif()
target_precompile_headers(WiiCompiled PRIVATE
    "${MKW_RUNTIME_SOURCE_DIR}/include/mkw_pch.h")
if(MKW_PLATFORM_WINDOWS)
    # The app icon (projects/nsmbw/tools/make_icons.ps1): Explorer, the taskbar and, through SDL's
    # default of the executable's first icon, the game window.
    enable_language(RC)
    file(TO_CMAKE_PATH "${MKW_RUNTIME_SOURCE_DIR}/assets/icon/nsmbw.ico" MKW_APP_ICON)
    file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/app_icon.rc" "1 ICON \"${MKW_APP_ICON}\"\n")
    target_sources(WiiCompiled PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/app_icon.rc")
    if(TARGET WiiCompiledLoader)
        target_sources(WiiCompiledLoader PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/app_icon.rc")
    endif()
endif()
if(TARGET mkw_base_sensitive)
    target_sources(WiiCompiled PRIVATE $<TARGET_OBJECTS:mkw_base_sensitive>)
endif()

if(MKW_HAVE_RETRO_REWIND)
    add_executable(RetroRewind "${MKW_RETRO_REWIND_PRODUCT_SOURCE}" ${MKW_RETRO_REGISTRATION_SOURCES})
    mkw_configure_product(RetroRewind)
    target_precompile_headers(RetroRewind REUSE_FROM WiiCompiled)
    if(TARGET mkw_retro_sensitive)
        target_sources(RetroRewind PRIVATE $<TARGET_OBJECTS:mkw_retro_sensitive>)
    endif()
    target_sources(RetroRewind PRIVATE $<TARGET_OBJECTS:mkw_retro_rewind_functions>)
    if(MKW_RETRO_BLOB_OBJECTS)
        target_sources(RetroRewind PRIVATE ${MKW_RETRO_BLOB_OBJECTS})
    endif()
    add_custom_target(mkw_release DEPENDS WiiCompiled RetroRewind)
else()
    add_custom_target(mkw_release DEPENDS WiiCompiled)
    message(STATUS "RetroRewind target disabled (run translate-mod and emit-build-shards)")
endif()

# Windows and Linux x86_64 share the x86-64-v3 floor that the CPU baseline
# object above checks. AArch64 builds are compiled locally for the host that
# will run them, so both Linux and Apple Silicon use the compiler's native CPU
# tuning rather than leaving target-specific performance on the table.
if(CMAKE_SYSTEM_PROCESSOR MATCHES "^(AMD64|amd64|x86_64|X86_64)$")
    set(MKW_BASELINE_ARCH_FLAG -march=x86-64-v3)
elseif(MKW_PLATFORM_ANDROID)
    # Cross-compiled, so -mcpu=native would describe the build machine. ARMv8.2-A (LSE atomics,
    # RCpc loads) is the floor of every Android phone since Cortex-A55/A75.
    set(MKW_BASELINE_ARCH_FLAG -march=armv8.2-a)
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "^(aarch64|arm64|ARM64)$")
    set(MKW_BASELINE_ARCH_FLAG -mcpu=native)
else()
    set(MKW_BASELINE_ARCH_FLAG "")
endif()

set(MKW_ALL_BUILD_TARGETS
    mkw_runtime_common mkw_base_shared mkw_base_sensitive mkw_retro_sensitive
    mkw_retro_rewind_functions WiiCompiled RetroRewind)
foreach(target IN LISTS MKW_ALL_BUILD_TARGETS)
    if(TARGET ${target} AND MKW_BASELINE_ARCH_FLAG)
        target_compile_options(${target} PRIVATE ${MKW_BASELINE_ARCH_FLAG})
    endif()
endforeach()
