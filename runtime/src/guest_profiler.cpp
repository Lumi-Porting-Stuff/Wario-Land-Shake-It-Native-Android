// NSMBW_PROFILE=<start s>:<length s> (Windows): a sampling profiler for the game thread. From
// <start> seconds after the first presented frame, for <length> seconds, the thread that presents
// frames is suspended about a thousand times a second and its instruction pointer recorded. At the
// end, samples are mapped back to the translated guest function whose code they fall in (by each
// translated function's host entry point) and the top entries are logged with their guest address,
// so a slow scene can be traced to the game code (or the runtime and aurora code) it spends time in.
#include "guest_profiler.h"

#include "abi_bridge.h"
#include "runtime_log.h"

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#elif defined(__ANDROID__) || defined(__linux__)
#include <atomic>
#include <csignal>
#include <dlfcn.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>
#endif

extern "C" {
extern const uint32_t kGuestMapSymbolCount;
extern const uint32_t kGuestMapSymbolAddresses[];
extern const char* const kGuestMapSymbolNames[];
}

namespace GuestProfiler {
namespace {

struct Entry {
    uintptr_t host;
    uint32_t guest;
};

const char* GuestName(uint32_t address) {
    const uint32_t* begin = kGuestMapSymbolAddresses;
    const uint32_t* end = kGuestMapSymbolAddresses + kGuestMapSymbolCount;
    const uint32_t* it = std::lower_bound(begin, end, address);
    return it != end && *it == address ? kGuestMapSymbolNames[it - begin] : "";
}

// Every translated function's host entry point, sorted, to find which one a sample falls in.
std::vector<Entry> BuildEntries() {
    std::vector<Entry> entries;
    for (const auto& [lo, hi] : {std::pair{0x80004000u, 0x80319180u}, std::pair{0x80BF691Cu, 0x80C5A648u}}) {
        for (uint32_t address = lo; address < hi; address += 4) {
            if (const TranslatedFunctionInfo* info = TranslatedFunctionRegistry::FindByAddressPtr(address);
                info != nullptr && info->entryPoint != nullptr && info->address == address) {
                entries.push_back({reinterpret_cast<uintptr_t>(info->entryPoint), address});
            }
        }
    }
    std::sort(entries.begin(), entries.end(), [](const Entry& a, const Entry& b) { return a.host < b.host; });
    return entries;
}

#ifdef _WIN32
void Report(const std::unordered_map<uintptr_t, uint32_t>& samples, const std::vector<std::vector<uintptr_t>>& stacks,
            uint32_t total) {
    std::vector<Entry> entries = BuildEntries();
    const auto exeBase = reinterpret_cast<uintptr_t>(GetModuleHandleW(nullptr));
    const auto* dos = reinterpret_cast<const IMAGE_DOS_HEADER*>(exeBase);
    const auto* nt = reinterpret_cast<const IMAGE_NT_HEADERS*>(exeBase + dos->e_lfanew);
    const uintptr_t exeEnd = exeBase + nt->OptionalHeader.SizeOfImage;

    // A host address as a translated guest function, or exe+RVA (resolve with llvm-nm), or [dll].
    const auto describe = [&](uintptr_t rip) -> std::string {
        if (rip >= exeBase && rip < exeEnd) {
            auto it = std::upper_bound(entries.begin(), entries.end(), rip,
                                       [](uintptr_t value, const Entry& e) { return value < e.host; });
            // Translated code sits in its own range: past the last entry point it is runtime code.
            if (it != entries.begin() && it != entries.end()) {
                --it;
                char text[160];
                std::snprintf(text, sizeof(text), "%08X %s", it->guest, GuestName(it->guest));
                return text;
            }
            char text[40];
            std::snprintf(text, sizeof(text), "exe+%llX", static_cast<unsigned long long>(rip - exeBase));
            return text;
        }
        HMODULE module = nullptr;
        char path[MAX_PATH] = "?";
        if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                               reinterpret_cast<LPCSTR>(rip), &module)) {
            GetModuleFileNameA(module, path, MAX_PATH);
        }
        const char* slash = std::strrchr(path, '\\');
        return std::string("[") + (slash ? slash + 1 : path) + "]";
    };
    // Raw stacks (exe RVAs, "-" for other modules) for offline symbolization: nm the exe, see AGENTS.md.
    if (FILE* raw = std::fopen("profile_stacks.txt", "w")) {
        for (const auto& stack : stacks) {
            for (const uintptr_t rip : stack) {
                if (rip >= exeBase && rip < exeEnd) {
                    std::fprintf(raw, "%llX ", static_cast<unsigned long long>(rip - exeBase));
                } else {
                    std::fputs("- ", raw);
                }
            }
            std::fputc('\n', raw);
        }
        std::fclose(raw);
    }
    // Where each sample's stack enters system code from ours: the first frames inside the exe.
    std::unordered_map<std::string, uint32_t> chains;
    for (const auto& stack : stacks) {
        std::string chain;
        int exeFrames = 0;
        for (const uintptr_t rip : stack) {
            if (rip >= exeBase && rip < exeEnd) {
                chain += (chain.empty() ? "" : " < ") + describe(rip);
                if (++exeFrames == 5) break;
            } else if (exeFrames > 0) {
                break;
            }
        }
        if (stack.empty() || (stack[0] >= exeBase && stack[0] < exeEnd)) {
            continue;  // already in our code: the leaf table covers it
        }
        chains[describe(stack[0]) + " from " + (chain.empty() ? "?" : chain)]++;
    }
    std::vector<std::pair<std::string, uint32_t>> sortedChains(chains.begin(), chains.end());
    std::sort(sortedChains.begin(), sortedChains.end(), [](const auto& a, const auto& b) { return a.second > b.second; });
    for (size_t i = 0; i < sortedChains.size() && i < 25; ++i) {
        RT_LOGF(RT_TAG_RUNTIME, "profile chain: %5.1f%%  %s\n", 100.0 * sortedChains[i].second / total,
                sortedChains[i].first.c_str());
    }

    std::unordered_map<std::string, uint32_t> byName;
    for (const auto& [rip, count] : samples) {
        std::string key;
        if (rip < exeBase || rip >= exeEnd) {
            HMODULE module = nullptr;
            char path[MAX_PATH] = "?";
            if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                                   reinterpret_cast<LPCSTR>(rip), &module)) {
                GetModuleFileNameA(module, path, MAX_PATH);
            }
            const char* slash = std::strrchr(path, '\\');
            key = std::string("[") + (slash ? slash + 1 : path) + "]";
        } else {
            auto it = std::upper_bound(entries.begin(), entries.end(), rip,
                                       [](uintptr_t value, const Entry& e) { return value < e.host; });
            if (it == entries.begin()) {
                key = "[exe, before translated code]";
            } else {
                --it;
                char text[160];
                std::snprintf(text, sizeof(text), "%08X %s", it->guest, GuestName(it->guest));
                key = text;
            }
        }
        byName[key] += count;
    }
    std::vector<std::pair<std::string, uint32_t>> sorted(byName.begin(), byName.end());
    std::sort(sorted.begin(), sorted.end(), [](const auto& a, const auto& b) { return a.second > b.second; });
    RT_LOGF(RT_TAG_RUNTIME, "profile: %u samples, %zu translated functions\n", total, entries.size());
    for (size_t i = 0; i < sorted.size() && i < 60; ++i) {
        RT_LOGF(RT_TAG_RUNTIME, "profile: %5.1f%%  %s\n", 100.0 * sorted[i].second / total, sorted[i].first.c_str());
    }
}

void Run(HANDLE thread, double startSeconds, double lengthSeconds) {
    std::this_thread::sleep_for(std::chrono::duration<double>(startSeconds));
    std::unordered_map<uintptr_t, uint32_t> samples;
    std::vector<std::vector<uintptr_t>> stacks;
    uint32_t total = 0;
    auto next = std::chrono::steady_clock::now();
    const auto end = next + std::chrono::duration<double>(lengthSeconds);
    while (std::chrono::steady_clock::now() < end) {
        // About 1000 samples a second; Sleep() is too coarse for that, so wait by yielding.
        next += std::chrono::microseconds(1000);
        while (std::chrono::steady_clock::now() < next) {
            std::this_thread::yield();
        }
        if (SuspendThread(thread) != static_cast<DWORD>(-1)) {
            // No heap use while the game thread is suspended: it may hold the heap lock (deadlock).
            uintptr_t frames[24];
            int depth = 0;
            CONTEXT context{};
            context.ContextFlags = CONTEXT_FULL;
            if (GetThreadContext(thread, &context)) {
                // The call stack, by the unwind data every x64 function carries.
                for (; depth < 24 && context.Rip != 0; ++depth) {
                    frames[depth] = static_cast<uintptr_t>(context.Rip);
                    DWORD64 imageBase = 0;
                    PRUNTIME_FUNCTION function = RtlLookupFunctionEntry(context.Rip, &imageBase, nullptr);
                    if (function == nullptr) {
                        context.Rip = *reinterpret_cast<DWORD64*>(context.Rsp);  // a leaf: return address on top
                        context.Rsp += 8;
                        continue;
                    }
                    PVOID handlerData = nullptr;
                    DWORD64 establisher = 0;
                    RtlVirtualUnwind(UNW_FLAG_NHANDLER, imageBase, context.Rip, function, &context, &handlerData,
                                     &establisher, nullptr);
                }
            }
            ResumeThread(thread);
            if (depth > 0) {
                ++samples[frames[0]];
                ++total;
                stacks.emplace_back(frames, frames + depth);
            }
        }
    }
    Report(samples, stacks, total);
}
#elif defined(__ANDROID__) || defined(__linux__)

// The same profiler for phones and Linux, by a different mechanism.
//
// Nothing here may suspend the game thread the way the Windows path does: a thread stopped inside
// malloc still holds its lock, and the sampler would deadlock the moment it allocated. Instead the
// game thread samples itself. A helper sends it a signal, and the handler - which runs on the game
// thread, between two of its own instructions - copies its own program counter out of the context
// the kernel saved. The handler touches one atomic and nothing else, so it is safe wherever it
// lands.
//
// Only the leaf address is taken, not the call stack: unwinding inside a signal handler is not
// safe, and the leaf is what attributes time to a translated function anyway.
std::atomic<uintptr_t> g_sampledPc{0};
std::atomic<bool> g_sampleTaken{false};

void SampleSignalHandler(int, siginfo_t*, void* contextRaw) {
    const auto* context = static_cast<const ucontext_t*>(contextRaw);
    uintptr_t pc = 0;
#if defined(__aarch64__)
    pc = static_cast<uintptr_t>(context->uc_mcontext.pc);
#elif defined(__arm__)
    pc = static_cast<uintptr_t>(context->uc_mcontext.arm_pc);
#elif defined(__x86_64__)
    pc = static_cast<uintptr_t>(context->uc_mcontext.gregs[REG_RIP]);
#elif defined(__i386__)
    pc = static_cast<uintptr_t>(context->uc_mcontext.gregs[REG_EIP]);
#else
    (void)context;
#endif
    g_sampledPc.store(pc, std::memory_order_relaxed);
    g_sampleTaken.store(true, std::memory_order_release);
}

// A host address as a translated guest function, or as the library it belongs to.
std::string DescribePc(uintptr_t pc, const std::vector<Entry>& entries) {
    if (!entries.empty() && pc >= entries.front().host && pc <= entries.back().host) {
        auto it = std::upper_bound(entries.begin(), entries.end(), pc,
                                   [](uintptr_t value, const Entry& e) { return value < e.host; });
        if (it != entries.begin()) {
            --it;
            char text[160];
            std::snprintf(text, sizeof(text), "%08X %s", it->guest, GuestName(it->guest));
            return text;
        }
    }
    // Outside the translated range: name the library, and the symbol when one is exported.
    Dl_info info{};
    if (dladdr(reinterpret_cast<void*>(pc), &info) != 0 && info.dli_fname != nullptr) {
        const char* slash = std::strrchr(info.dli_fname, '/');
        const std::string library = std::string("[") + (slash != nullptr ? slash + 1 : info.dli_fname) + "]";
        if (info.dli_sname != nullptr) {
            return library + " " + info.dli_sname;
        }
        if (info.dli_fbase != nullptr) {
            char text[48];
            std::snprintf(text, sizeof(text), "+%llX",
                          static_cast<unsigned long long>(pc - reinterpret_cast<uintptr_t>(info.dli_fbase)));
            return library + text;
        }
        return library;
    }
    return "[unknown]";
}

void ReportPosix(const std::unordered_map<uintptr_t, uint32_t>& samples, uint32_t total) {
    if (total == 0) {
        RT_LOG(RT_TAG_RUNTIME) << "profile: no samples taken" << std::endl;
        return;
    }
    const std::vector<Entry> entries = BuildEntries();
    std::unordered_map<std::string, uint32_t> byName;
    for (const auto& [pc, count] : samples) {
        byName[DescribePc(pc, entries)] += count;
    }
    std::vector<std::pair<std::string, uint32_t>> sorted(byName.begin(), byName.end());
    std::sort(sorted.begin(), sorted.end(), [](const auto& a, const auto& b) { return a.second > b.second; });
    RT_LOGF(RT_TAG_RUNTIME, "profile: %u samples, %zu translated functions\n", total, entries.size());
    for (size_t i = 0; i < sorted.size() && i < 60; ++i) {
        RT_LOGF(RT_TAG_RUNTIME, "profile: %5.1f%%  %s\n", 100.0 * sorted[i].second / total, sorted[i].first.c_str());
    }
}

void RunPosix(pid_t threadId, double startSeconds, double lengthSeconds) {
    std::this_thread::sleep_for(std::chrono::duration<double>(startSeconds));

    struct sigaction action {};
    action.sa_sigaction = &SampleSignalHandler;
    action.sa_flags = SA_SIGINFO | SA_RESTART;
    sigemptyset(&action.sa_mask);
    // SIGPROF is the profiling signal and is not used by the runtime for anything else.
    if (sigaction(SIGPROF, &action, nullptr) != 0) {
        RT_LOG(RT_TAG_RUNTIME) << "profile: could not install the sampling signal handler" << std::endl;
        return;
    }

    std::unordered_map<uintptr_t, uint32_t> samples;
    uint32_t total = 0;
    const pid_t processId = getpid();
    auto next = std::chrono::steady_clock::now();
    const auto end = next + std::chrono::duration<double>(lengthSeconds);
    while (std::chrono::steady_clock::now() < end) {
        // About 1000 samples a second. Sleeping is too coarse, so the wait is a yield loop, as on
        // the Windows path.
        next += std::chrono::microseconds(1000);
        while (std::chrono::steady_clock::now() < next) {
            std::this_thread::yield();
        }
        g_sampleTaken.store(false, std::memory_order_relaxed);
        if (syscall(SYS_tgkill, processId, threadId, SIGPROF) != 0) {
            break;  // the game thread is gone
        }
        // The handler runs on the game thread, so this waits for it rather than for a lock. A
        // bounded wait keeps a blocked thread from stopping the profiler altogether.
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(2);
        while (!g_sampleTaken.load(std::memory_order_acquire) &&
               std::chrono::steady_clock::now() < deadline) {
            std::this_thread::yield();
        }
        if (g_sampleTaken.load(std::memory_order_acquire)) {
            if (const uintptr_t pc = g_sampledPc.load(std::memory_order_relaxed); pc != 0) {
                ++samples[pc];
                ++total;
            }
        }
    }
    ReportPosix(samples, total);
}
#endif

} // namespace

void OnFramePresented() {
#ifdef _WIN32
    static bool started = false;
    if (started) {
        return;
    }
    started = true;
    const char* value = std::getenv("NSMBW_PROFILE");
    static char fileValue[64] = {};
    if (value == nullptr) {
        char exePath[MAX_PATH] = {};
        GetModuleFileNameA(nullptr, exePath, MAX_PATH);
        std::string path(exePath);
        path = path.substr(0, path.find_last_of("\\/") + 1) + "profile.txt";
        if (FILE* f = std::fopen(path.c_str(), "r")) {
            if (std::fgets(fileValue, sizeof(fileValue), f)) value = fileValue;
            std::fclose(f);
        }
    }
    double start = 0, length = 0;
    if (value == nullptr || std::sscanf(value, "%lf:%lf", &start, &length) != 2 || length <= 0) {
        return;
    }
    HANDLE thread = nullptr;
    DuplicateHandle(GetCurrentProcess(), GetCurrentThread(), GetCurrentProcess(), &thread,
                    THREAD_SUSPEND_RESUME | THREAD_GET_CONTEXT | THREAD_QUERY_INFORMATION, FALSE, 0);
    std::thread(Run, thread, start, length).detach();
#elif defined(__ANDROID__) || defined(__linux__)
    static bool started = false;
    if (started) {
        return;
    }
    started = true;
    // On a phone this arrives from `adb shell setprop debug.nsmbw.profile <start>:<length>`, which
    // main.cpp turns into this variable before the game starts.
    const char* value = std::getenv("NSMBW_PROFILE");
    double start = 0;
    double length = 0;
    if (value == nullptr || std::sscanf(value, "%lf:%lf", &start, &length) != 2 || length <= 0) {
        return;
    }
    // The thread that presents frames is the one worth sampling, and this runs on it.
    const auto threadId = static_cast<pid_t>(syscall(SYS_gettid));
    RT_LOGF(RT_TAG_RUNTIME, "profile: sampling the game thread for %.1f s, starting in %.1f s\n", length, start);
    std::thread(RunPosix, threadId, start, length).detach();
#endif
}

} // namespace GuestProfiler
