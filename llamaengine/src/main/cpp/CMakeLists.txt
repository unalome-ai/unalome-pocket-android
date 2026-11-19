cmake_minimum_required(VERSION 3.22)

project(unalome_llama C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Optimised native code in every build (see build.gradle.kts). Set explicitly rather than relying on
# the NDK toolchain's Release defaults.
set(CMAKE_C_FLAGS_RELEASE   "-O3 -DNDEBUG" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG" CACHE STRING "" FORCE)

# 16 KB page size (required for Android 15+ devices with 16 KB pages). NDK r28 and later already
# align ELF segments to 16 KB; the flag keeps that true if the NDK default ever changes.
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(CMAKE_MODULE_LINKER_FLAGS "${CMAKE_MODULE_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

set(LLAMA_ROOT ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)
if(NOT EXISTS ${LLAMA_ROOT}/CMakeLists.txt)
    message(FATAL_ERROR "llama.cpp is missing. Run: git submodule update --init third_party/llama.cpp")
endif()

# GPU backends (OpenCL for Adreno, Vulkan) are a later milestone: they would be switched on here
# with GGML_OPENCL / GGML_VULKAN and selected at run time through LlamaConfig.backend.
# LLAMA_BUILD_MTMD (set in build.gradle.kts) adds libmtmd, llama.cpp's image reader, without the
# rest of its tools.
add_subdirectory(${LLAMA_ROOT} llama.cpp)

add_library(unalome_llama SHARED llama_jni.cpp)
target_include_directories(unalome_llama PRIVATE ${CMAKE_CURRENT_LIST_DIR})
target_link_libraries(unalome_llama PRIVATE llama llama-common mtmd ggml android log)
