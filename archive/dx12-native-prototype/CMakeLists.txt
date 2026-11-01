cmake_minimum_required(VERSION 3.24)
project(direct_renderer LANGUAGES CXX)

if(NOT WIN32)
    message(FATAL_ERROR "The Direct renderer native backend requires Windows.")
endif()

if(NOT JAVA_HOME)
    message(FATAL_ERROR "JAVA_HOME must point to the JDK used to run Gradle.")
endif()

add_library(direct_renderer SHARED direct_renderer.cpp dx12_gl_interop.cpp)
target_compile_features(direct_renderer PRIVATE cxx_std_17)
target_compile_definitions(direct_renderer PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX)
target_include_directories(direct_renderer PRIVATE
    "${JAVA_HOME}/include"
    "${JAVA_HOME}/include/win32")
target_link_libraries(direct_renderer PRIVATE d3d11 d3d12 dxgi d3dcompiler)
set_target_properties(direct_renderer PROPERTIES
    PREFIX ""
    OUTPUT_NAME "direct_renderer"
    RUNTIME_OUTPUT_DIRECTORY "${DIRECT_OUTPUT_DIR}"
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${DIRECT_OUTPUT_DIR}"
    RUNTIME_OUTPUT_DIRECTORY_DEBUG "${DIRECT_OUTPUT_DIR}")
