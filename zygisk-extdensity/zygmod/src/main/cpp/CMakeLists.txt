cmake_minimum_required(VERSION 3.28)
project(zygextdensity)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(DEPS ${CMAKE_CURRENT_SOURCE_DIR}/../../../../deps)

# --- Dobby (thread-safe / PAC-aware inline hooker for LSPlant), via the vvb2060 Android prefab ---
find_package(dobby REQUIRED CONFIG)

# --- LSPlant (static; pulls in dex_builder + parallel_hashmap). Static only, self-contained. ---
set(LSPLANT_BUILD_SHARED OFF CACHE BOOL "" FORCE)
add_subdirectory(${DEPS}/LSPlant/lsplant/src/main/jni ${CMAKE_BINARY_DIR}/lsplant)

# --- xDL (static; resolves libart symbols from .dynsym and .symtab) ---
add_library(xdl STATIC
    ${DEPS}/xDL/xdl/src/main/cpp/xdl.c
    ${DEPS}/xDL/xdl/src/main/cpp/xdl_iterate.c
    ${DEPS}/xDL/xdl/src/main/cpp/xdl_linker.c
    ${DEPS}/xDL/xdl/src/main/cpp/xdl_lzma.c
    ${DEPS}/xDL/xdl/src/main/cpp/xdl_util.c)
target_include_directories(xdl PUBLIC ${DEPS}/xDL/xdl/src/main/cpp/include)

# --- our Zygisk module (self-contained: c++_static via ANDROID_STL) ---
add_library(zygisk_extdensity SHARED module.cpp)
set_target_properties(zygisk_extdensity PROPERTIES CXX_SCAN_FOR_MODULES ON)
target_link_libraries(zygisk_extdensity log dobby::dobby lsplant_static xdl)
