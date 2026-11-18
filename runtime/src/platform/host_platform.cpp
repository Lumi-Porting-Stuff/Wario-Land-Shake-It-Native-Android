#include "platform/host_platform.h"

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <algorithm>
#include <array>
#include <mutex>

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <psapi.h>
#include <shlobj.h>
#else
#include <cerrno>
#include <sched.h>
#include <sys/resource.h>
#include <unistd.h>
#endif

#if defined(__APPLE__)
#include <mach-o/dyld.h>
#include <pwd.h>
#endif

namespace RuntimePlatform {

std::optional<std::filesystem::path> ExecutableDirectory() noexcept {
#if defined(_WIN32)
    std::wstring buffer(MAX_PATH, L'\0');
    for (;;) {
        const DWORD length = GetModuleFileNameW(nullptr, buffer.data(), static_cast<DWORD>(buffer.size()));
        if (length == 0) {
            return std::nullopt;
        }
        if (length < buffer.size() - 1) {
            buffer.resize(length);
            return std::filesystem::path(buffer).parent_path();
        }
        buffer.resize(buffer.size() * 2);
    }
#elif defined(__APPLE__)
    uint32_t size = 0;
    if (_NSGetExecutablePath(nullptr, &size) != -1 || size == 0) {
        return std::nullopt;
    }
    std::string path(size, '\0');
    if (_NSGetExecutablePath(path.data(), &size) != 0) {
        return std::nullopt;
    }
    path.resize(std::char_traits<char>::length(path.c_str()));
    std::error_code ec;
    const auto resolved = std::filesystem::weakly_canonical(path, ec);
    return (ec ? std::filesystem::path(path) : resolved).parent_path();
#elif defined(__ANDROID__)
    // The game is a library inside an APK, so there is no executable directory. The app unpacks
    // the files a desktop build keeps beside its executable (DSP ROM, pipeline cache, Wii
    // bootstrap NAND, CA bundle) and names that directory before SDL starts the game.
    if (const char* directory = std::getenv("WIICOMPILED_RUNTIME_DIR"); directory && *directory) {
        return std::filesystem::path(directory);
    }
    return std::nullopt;
#else
    return std::nullopt;
#endif
}

std::filesystem::path ApplicationDataDirectory(std::string_view applicationName) {
#if defined(_WIN32)
    PWSTR rawPath = nullptr;
    if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_LocalAppData, KF_FLAG_CREATE, nullptr, &rawPath)) && rawPath) {
        const std::filesystem::path directory = std::filesystem::path(rawPath) / applicationName;
        CoTaskMemFree(rawPath);
        return directory;
    }
#elif defined(__APPLE__)
    if (const char* home = std::getenv("HOME"); home && *home) {
        return std::filesystem::path(home) / "Library" / "Application Support" / applicationName;
    }
    if (const passwd* user = getpwuid(getuid()); user && user->pw_dir && *user->pw_dir) {
        return std::filesystem::path(user->pw_dir) / "Library" / "Application Support" / applicationName;
    }
#elif defined(__ANDROID__)
    // The app's own storage (its external files directory, which a PC can reach over USB) already
    // belongs to this game alone, so it is used as-is rather than gaining an application subfolder.
    (void)applicationName;
    if (const char* directory = std::getenv("WIICOMPILED_DATA_DIR"); directory && *directory) {
        return std::filesystem::path(directory);
    }
#endif
    return std::filesystem::current_path() / applicationName;
}

std::filesystem::path LogDirectory(std::string_view applicationName) {
    return ApplicationDataDirectory(applicationName) / "Logs";
}

uint64_t CurrentProcessId() noexcept {
#if defined(_WIN32)
    return static_cast<uint64_t>(::GetCurrentProcessId());
#else
    return static_cast<uint64_t>(::getpid());
#endif
}

bool PreferPerformanceCores() noexcept {
#if defined(__ANDROID__) || defined(__linux__)
    // Each core advertises the top frequency it can reach. On a big.LITTLE part the values fall
    // into clusters, and the fastest cluster is the one a frame deadline can be met on.
    unsigned long long best = 0;
    std::array<unsigned long long, CPU_SETSIZE> maximum{};
    const long configured = ::sysconf(_SC_NPROCESSORS_CONF);
    const int cores = configured > 0 ? static_cast<int>(std::min<long>(configured, CPU_SETSIZE)) : 0;
    for (int core = 0; core < cores; ++core) {
        char path[128];
        std::snprintf(path, sizeof(path),
                      "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", core);
        if (std::FILE* file = std::fopen(path, "r")) {
            unsigned long long value = 0;
            if (std::fscanf(file, "%llu", &value) == 1) {
                maximum[static_cast<size_t>(core)] = value;
                best = std::max(best, value);
            }
            std::fclose(file);
        }
    }
    if (best == 0) {
        return false;  // no frequency information: leave the scheduler alone
    }

    // Within 15% of the fastest counts as a performance core, which keeps the "big" and "prime"
    // clusters of a three-cluster phone together rather than pinning everything to one core.
    const unsigned long long floor = best - best / 7;
    cpu_set_t set;
    CPU_ZERO(&set);
    int chosen = 0;
    for (int core = 0; core < cores; ++core) {
        if (maximum[static_cast<size_t>(core)] >= floor) {
            CPU_SET(core, &set);
            ++chosen;
        }
    }
    // All cores alike, or every core qualifying, means there is nothing to avoid.
    if (chosen == 0 || chosen == cores) {
        return false;
    }
    return ::sched_setaffinity(0, sizeof(set), &set) == 0;
#else
    return false;
#endif
}

bool RaiseThreadPriority() noexcept {
#if defined(__ANDROID__) || defined(__linux__)
    // Nice is per-thread on Linux, and `who == 0` means this thread. -8 is what Android itself
    // calls "urgent display": ahead of the app's ordinary threads, behind audio, and well short of
    // the values that would let a runaway frame starve the system. An ordinary Linux desktop does
    // not let a process lower its own nice value and this simply fails there, which is fine - the
    // problem it solves is a phone's.
    errno = 0;
    if (::setpriority(PRIO_PROCESS, 0, -8) == 0) {
        return true;
    }
    // Some systems allow part of the way down; take whatever is granted rather than nothing.
    errno = 0;
    return ::setpriority(PRIO_PROCESS, 0, -4) == 0;
#else
    // Windows is left alone on purpose: its scheduler already favours the window with focus, and
    // raising a thread above normal there costs more in other applications than it buys here.
    return false;
#endif
}

ProcessUsage CurrentProcessUsage() noexcept {
    using Clock = std::chrono::steady_clock;
    // Remembered across calls so the CPU share describes the interval between them; a total since
    // process start would only ever drift towards an average and hide what is happening now.
    static std::mutex mutex;
    static Clock::time_point lastSample{};
    static double lastCpuSeconds = 0.0;
    static bool primed = false;

    ProcessUsage usage;
    double cpuSeconds = 0.0;

#if defined(_WIN32)
    FILETIME creation{}, exit{}, kernel{}, user{};
    if (::GetProcessTimes(::GetCurrentProcess(), &creation, &exit, &kernel, &user) == 0) {
        return usage;
    }
    const auto toSeconds = [](const FILETIME& time) {
        // FILETIME counts 100-nanosecond intervals, split across two 32-bit halves.
        const uint64_t ticks = (static_cast<uint64_t>(time.dwHighDateTime) << 32) | time.dwLowDateTime;
        return static_cast<double>(ticks) / 1e7;
    };
    cpuSeconds = toSeconds(kernel) + toSeconds(user);

    PROCESS_MEMORY_COUNTERS memory{};
    if (::GetProcessMemoryInfo(::GetCurrentProcess(), &memory, sizeof(memory)) != 0) {
        usage.residentMegabytes = static_cast<float>(memory.WorkingSetSize) / (1024.0f * 1024.0f);
    }
#elif defined(__linux__) || defined(__ANDROID__)
    // /proc/self/stat field 14 is user time and 15 system time, both in clock ticks.
    if (std::FILE* stat = std::fopen("/proc/self/stat", "r")) {
        char buffer[1024] = {};
        const size_t read = std::fread(buffer, 1, sizeof(buffer) - 1, stat);
        std::fclose(stat);
        // The executable name can contain spaces and brackets, so fields are counted from the last
        // ')' rather than from the start of the line.
        const char* cursor = read > 0 ? std::strrchr(buffer, ')') : nullptr;
        if (cursor != nullptr) {
            unsigned long long utime = 0;
            unsigned long long stime = 0;
            // After ") " comes field 3; utime and stime are the 12th and 13th values from there.
            if (std::sscanf(cursor + 1,
                            " %*c %*d %*d %*d %*d %*d %*u %*lu %*lu %*lu %*lu %llu %llu",
                            &utime, &stime) == 2) {
                const double ticksPerSecond = static_cast<double>(::sysconf(_SC_CLK_TCK));
                if (ticksPerSecond > 0.0) {
                    cpuSeconds = static_cast<double>(utime + stime) / ticksPerSecond;
                }
            }
        }
    }
    // /proc/self/statm reports sizes in pages; the second is the resident set.
    if (std::FILE* statm = std::fopen("/proc/self/statm", "r")) {
        unsigned long long total = 0;
        unsigned long long resident = 0;
        if (std::fscanf(statm, "%llu %llu", &total, &resident) == 2) {
            const double pageSize = static_cast<double>(::sysconf(_SC_PAGESIZE));
            usage.residentMegabytes = static_cast<float>(resident * pageSize / (1024.0 * 1024.0));
        }
        std::fclose(statm);
    }
#else
    // macOS and anything else: memory and CPU are left at zero rather than guessed at.
    return usage;
#endif

    const auto now = Clock::now();
    std::lock_guard<std::mutex> lock(mutex);
    if (primed) {
        const double elapsed = std::chrono::duration<double>(now - lastSample).count();
        if (elapsed > 0.0) {
            usage.cpuPercent = static_cast<float>((cpuSeconds - lastCpuSeconds) / elapsed * 100.0);
            if (usage.cpuPercent < 0.0f) {
                usage.cpuPercent = 0.0f;
            }
        }
    }
    lastSample = now;
    lastCpuSeconds = cpuSeconds;
    primed = true;
    usage.valid = true;
    return usage;
}

} // namespace RuntimePlatform
