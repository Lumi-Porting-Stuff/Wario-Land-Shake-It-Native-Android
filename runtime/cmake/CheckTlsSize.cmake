# Fails the build when the engine's thread-local block outgrows the area the dynamic loader
# reserves for a library opened with dlopen.
#
# Why this is checked at build time: overrunning that reserve breaks nothing and reports nothing.
# The library still loads and the game still runs - every thread-local read just moves from
# bionic's cheap TLSDESC resolver to the one that walks the thread's descriptor vector, across an
# engine that reads thread-locals on nearly every translated guest call. It would show up as the
# frame rate being worse than it was, with nothing pointing at the cause, which is the kind of
# regression that survives for years. So it is a build error instead.
#
# Run as: cmake -DREADELF=... -DLIBRARY=... -DBUDGET=... -P CheckTlsSize.cmake

execute_process(COMMAND "${READELF}" --program-headers "${LIBRARY}"
                OUTPUT_VARIABLE headers RESULT_VARIABLE status ERROR_QUIET)
if(NOT status EQUAL 0)
    message(WARNING "Could not read ${LIBRARY}; the thread-local storage budget is unchecked.")
    return()
endif()

# The TLS program header's MemSiz is the fifth hexadecimal field on its line, after Offset,
# VirtAddr, PhysAddr and FileSiz:
#   TLS  0x0a2e8da0 0x000000000a2ecda0 0x000000000a2ecda0 0x000da0 0x005da0 R  0x8
#
# Spelled out rather than with a repetition count: CMake's regex flavour has no {n}, and a pattern
# using one does not fail - it simply never matches, which would make this check pass silently and
# always.
set(hex "0x[0-9a-fA-F]+[ \t]+")
if(NOT headers MATCHES "TLS[ \t]+${hex}${hex}${hex}${hex}0x([0-9a-fA-F]+)")
    # No TLS segment at all is a legitimate outcome, not a failure.
    message(STATUS "Thread-local storage: no TLS segment")
    return()
endif()
math(EXPR used "0x${CMAKE_MATCH_1}")

if(used GREATER BUDGET)
    message(FATAL_ERROR
        "The engine's thread-local storage is ${used} bytes, over its ${BUDGET}-byte budget.\n"
        "\n"
        "The loader can only give a dlopen'ed library an offset in its static TLS reserve while\n"
        "the library's whole block fits there (bionic: 1664 bytes, shared with everything else\n"
        "the process opens). Past that, every thread-local read in the engine takes bionic's slow\n"
        "resolver instead of the fast one - no error, just a worse frame rate.\n"
        "\n"
        "Something was made thread_local, or an existing one grew. Put the storage on the heap and\n"
        "keep only a pointer in thread-local storage, the way abi_bridge.h does with the indirect\n"
        "dispatch memo tables. To find what grew:\n"
        "  llvm-readelf --symbols <libmain.so> | awk '$4==\"TLS\" {print $3, $8}' | sort -rn | head\n")
endif()
message(STATUS "Thread-local storage: ${used} of ${BUDGET} bytes")
