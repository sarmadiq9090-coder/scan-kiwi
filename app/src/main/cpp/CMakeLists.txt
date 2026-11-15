# SPDX-FileCopyrightText: 2026 FastKiwi
# SPDX-License-Identifier: Apache-2.0
#
# JNI glue for the "Accurate" OCR mode. Links against MNN 3.6.2's official Android build
# (libMNN.so, CPU backend; see tools/mnn/README.md for the source URL and sha256 of every file).
cmake_minimum_required(VERSION 3.22.1)
project(kiwi_mnn CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(mnn SHARED IMPORTED)
set_target_properties(mnn PROPERTIES
        IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libMNN.so
        INTERFACE_INCLUDE_DIRECTORIES ${CMAKE_CURRENT_SOURCE_DIR}/mnn-include)

add_library(kiwi_mnn SHARED kiwi_mnn.cpp)
target_compile_options(kiwi_mnn PRIVATE -Wall -Wextra -O2 -fvisibility=hidden -fno-exceptions)
# 16 KB pages (Android 15+): every 64-bit ELF segment aligned to 16 KB.
target_link_options(kiwi_mnn PRIVATE -Wl,-z,max-page-size=16384 -Wl,--gc-sections -Wl,--build-id=none)
target_link_libraries(kiwi_mnn PRIVATE mnn)
