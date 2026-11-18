#pragma once

#include <cstdint>
#include <filesystem>
#include <optional>
#include <string_view>

// Small host-services boundary for functionality that must not leak Win32
// assumptions into runtime or game code. Guest execution, virtual memory, and
// cooperative contexts remain outside this layer until dedicated macOS
// prototypes establish a safe abstraction.
namespace RuntimePlatform {

std::optional<std::filesystem::path> ExecutableDirectory() noexcept;

// Returns the platform's conventional per-user application-data directory.
// It does not create the directory, leaving that policy to the caller.
std::filesystem::path ApplicationDataDirectory(std::string_view applicationName);

// The root for per-run diagnostics. Keeping this here ensures log placement
// follows the same host convention as configuration and other user data.
std::filesystem::path LogDirectory(std::string_view applicationName);

uint64_t CurrentProcessId() noexcept;

// What this process is costing the machine, for the on-screen counter.
struct ProcessUsage {
    // Share of one core, so a process using two cores fully reads 200.
    float cpuPercent = 0.0f;
    // Resident memory: what is actually in RAM, not what has been reserved. The engine reserves a
    // very large address space for guest memory, so the reserved figure would say nothing useful.
    float residentMegabytes = 0.0f;
    bool valid = false;
};

// Sampled between calls, so the CPU share covers the interval since the previous one. The first
// call has no interval to measure and reports memory only.
ProcessUsage CurrentProcessUsage() noexcept;

// Keeps the calling thread off a phone's small cores.
//
// A big.LITTLE part has fast and slow cores, and the slow ones run roughly half the speed. The
// game thread has 16.6 ms to finish a frame, so landing on one costs frames outright. This asks
// for the fast cluster only, and does nothing where the cores are all alike. Returns whether a
// restriction was applied.
//
// Call it on the thread that is about to run the game and no earlier: a thread inherits the
// affinity of whichever thread created it, so a restriction set before the render thread, the
// shader compilers and the audio mixer exist would be handed to all of them, and they would then
// be competing for exactly the cores the frame needs.
bool PreferPerformanceCores() noexcept;

// Asks the scheduler to run the calling thread ahead of ordinary application threads.
//
// A phone has no shortage of other work in the same app - the UI thread, the sensor listener, the
// asset loaders - and all of it is nominally equal to the thread that owes the screen a frame
// every 16.6 ms. Android lets an app lower the nice value of its own threads, so this says which
// of them matters. Returns whether the system allowed it.
bool RaiseThreadPriority() noexcept;

} // namespace RuntimePlatform
