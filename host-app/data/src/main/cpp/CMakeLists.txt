cmake_minimum_required(VERSION 3.22.1)
project(opus_jni C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ==============================================================
# libopus (vendored, built as static library)
# ==============================================================

# Disable Opus components we don't need
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)

add_subdirectory(third_party/opus-1.5.2)

# ==============================================================
# JNI bridge shared library
# ==============================================================

add_library(opus_jni SHARED opus_jni.cpp)

target_include_directories(opus_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/opus-1.5.2/include
)

target_compile_options(opus_jni PRIVATE
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
    -O3
)
target_link_options(opus_jni PRIVATE
    -Wl,--gc-sections
    -Wl,--strip-all
    -Wl,--exclude-libs,ALL
)

# ==============================================================
# Project Sentinel: Low-Level Rust Security Core
# ==============================================================
set(RUST_TARGET "")
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(RUST_TARGET "aarch64-linux-android")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(RUST_TARGET "x86_64-linux-android")
endif()

set(RUST_LIB_PATH "${CMAKE_CURRENT_SOURCE_DIR}/../rust/sentinel_rust_core/target/${RUST_TARGET}/release/libsentinel_rust_core.a")

if(EXISTS "${RUST_LIB_PATH}")
    message(STATUS "Found Sentinel Rust Core: ${RUST_LIB_PATH}")
    add_library(sentinel_rust_core STATIC IMPORTED)
    set_target_properties(sentinel_rust_core PROPERTIES IMPORTED_LOCATION "${RUST_LIB_PATH}")
    target_link_libraries(opus_jni PRIVATE
        opus
        sentinel_rust_core
        log
        dl
        m
    )
    target_compile_definitions(opus_jni PRIVATE HAS_SENTINEL_RUST_CORE=1)
else()
    message(WARNING "Sentinel Rust Core not found at ${RUST_LIB_PATH}, using C++ fallback engines")
    target_link_libraries(opus_jni PRIVATE
        opus
        log
        dl
        m
    )
endif()

# Strip symbols in release for smaller .so
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    target_link_options(opus_jni PRIVATE -s)
endif()
