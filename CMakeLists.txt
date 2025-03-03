cmake_minimum_required(VERSION 3.4.1)

project(whisper.cpp)

set(CMAKE_CXX_STANDARD 17)

#set(GGML_VULKAN ON)

add_subdirectory(whisper.cpp)

find_library(LOG_LIB log)

set(target_name whisper-jni)

add_library(
        whisper-jni
        SHARED
        app/jni.c
)

if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    # We assume architecture supports fp16
    set(GGML_CPU_ARM_ARCH armv8.2-a+fp16)
endif()

if (NOT ${CMAKE_BUILD_TYPE} STREQUAL "Debug")
    target_compile_options(${target_name} PRIVATE -O3)
    target_compile_options(${target_name} PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
    target_compile_options(${target_name} PRIVATE -ffunction-sections -fdata-sections)

    target_link_options(${target_name} PRIVATE -Wl,--gc-sections)
    target_link_options(${target_name} PRIVATE -Wl,--exclude-libs,ALL)
    target_link_options(${target_name} PRIVATE -flto)
endif ()

# TODO fp16 optimizations need to be added again in whisper library

target_link_libraries(whisper-jni ${LOG_LIB} android whisper)
include_directories(whisper.cpp/include whisper.cpp/ggml/include)