cmake_minimum_required(VERSION 3.22)
project(sloptype_llama LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Everything from llama/ggml is linked statically into one JNI shared library.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Only the core library: no tests/examples/tools/server/common.
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS   OFF CACHE BOOL "" FORCE)

# CPU backend only, compiled in (no dynamic backend loading), no OpenMP (ggml's own thread pool).
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL      OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI    OFF CACHE BOOL "" FORCE)
set(GGML_ALL_WARNINGS    OFF CACHE BOOL "" FORCE)
if (NOT DEFINED GGML_LLAMAFILE)
    set(GGML_LLAMAFILE   OFF CACHE BOOL "" FORCE)
endif()

# arm64-v8a only. Every arm64 phone from ~2018 on (Snapdragon 845 / Exynos 9810 and later) has
# armv8.2-a with dotprod; i8mm (int8 matmul) arrived with armv8.6 (Snapdragon 888 / Tensor G1,
# i.e. Android 11-era flagships). Both are used by ggml's Q8_0 kernels and matter a lot for speed.
if (ANDROID_ABI STREQUAL "arm64-v8a" OR CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+i8mm+fp16" CACHE STRING "" FORCE)
endif()

# Keep the .so small: hide everything but the JNI entry points, drop unused sections.
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
add_compile_options(-ffunction-sections -fdata-sections)

add_subdirectory(llama.cpp EXCLUDE_FROM_ALL)

add_library(sloptype_llama SHARED sloptype_llama.cpp)
target_include_directories(sloptype_llama PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml/include)
target_link_libraries(sloptype_llama PRIVATE llama ggml log android)
target_link_options(sloptype_llama PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,-z,max-page-size=16384)
