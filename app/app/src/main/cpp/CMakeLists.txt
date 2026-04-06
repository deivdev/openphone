cmake_minimum_required(VERSION 3.22.1)
project("openphone-llm")

# Path to llama.cpp source (relative to this CMakeLists.txt)
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../llama.cpp)

# llama.cpp build options for Android
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

# JNI bridge library
add_library(llm_bridge SHARED llm_bridge.cpp)

target_include_directories(llm_bridge PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/common
    ${LLAMA_DIR}/ggml/include
)

target_link_libraries(llm_bridge
    llama
    common
    android
    log
)
