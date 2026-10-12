cmake_minimum_required(VERSION 3.22.1)
project(opus_decoder_jni C CXX)

# ==============================================================
# libopus (vendored, built as static library)
# Same version as Host encoder (opus-1.5.2)
# ==============================================================

set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)

add_subdirectory(third_party/opus-1.5.2)

# ==============================================================
# JNI bridge shared library (decoder)
# ==============================================================

add_library(opus_decoder_jni SHARED opus_decoder_jni.cpp)

target_include_directories(opus_decoder_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/opus-1.5.2/include
)

target_link_libraries(opus_decoder_jni PRIVATE
    opus
    log
)

# Ensure 16 KB page size ELF and GNU_RELRO alignment (Android 15+ compatible)
target_link_options(opus_decoder_jni PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

# Strip symbols in release for smaller .so
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    target_link_options(opus_decoder_jni PRIVATE -s)
endif()
