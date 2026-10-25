cmake_minimum_required(VERSION 3.20)
project(seedatlas_xaero_native VERSION 1.0.0 LANGUAGES C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)

set(SEEDATLAS_ENGINE_DIR "" CACHE PATH "Optional local Seed Atlas engine checkout")
if(NOT SEEDATLAS_ENGINE_DIR)
    include(FetchContent)
    FetchContent_Declare(seedatlas_source
        URL https://codeload.github.com/DUzzL/Seed-Atlas/tar.gz/fc2c628693bc72130d7c16a21636f50d57553ddf
        URL_HASH SHA256=9b90ec564b23ee53053147068db82e0361cc79c74cb8632350a68a29acd64d10
        SOURCE_SUBDIR seedatlas-no-cmake-subdirectory
    )
    FetchContent_MakeAvailable(seedatlas_source)
    set(SEEDATLAS_ENGINE_DIR "${seedatlas_source_SOURCE_DIR}/seedatlas-engine")
endif()

set(SEEDATLAS_ENGINE_SOURCES
    "${SEEDATLAS_ENGINE_DIR}/finders.c"
    "${SEEDATLAS_ENGINE_DIR}/generator.c"
    "${SEEDATLAS_ENGINE_DIR}/layers.c"
    "${SEEDATLAS_ENGINE_DIR}/biomenoise.c"
    "${SEEDATLAS_ENGINE_DIR}/biomes.c"
    "${SEEDATLAS_ENGINE_DIR}/noise.c"
    "${SEEDATLAS_ENGINE_DIR}/util.c"
)

# The upstream engine contains C11 variable-length arrays. MSVC's C frontend
# does not implement them; Windows builds must use MinGW GCC or clang.
if(MSVC AND NOT CMAKE_C_COMPILER_ID MATCHES "Clang")
    message(FATAL_ERROR
        "seedatlas-engine requires C11 VLAs; use MinGW GCC or clang on Windows")
endif()

add_library(seedatlas_xaero SHARED
    seedatlas_xaero.c
    seedatlas_xaero.h
    ${SEEDATLAS_ENGINE_SOURCES}
)
target_include_directories(seedatlas_xaero PRIVATE "${SEEDATLAS_ENGINE_DIR}")
target_compile_definitions(seedatlas_xaero PRIVATE SEEDATLAS_XAERO_BUILD=1)
set_target_properties(seedatlas_xaero PROPERTIES
    PREFIX ""
    C_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
)
if(APPLE)
    set_target_properties(seedatlas_xaero PROPERTIES
        INSTALL_NAME_DIR "@rpath"
    )
endif()

if(MSVC)
    target_compile_options(seedatlas_xaero PRIVATE /W4 /O2)
    target_compile_definitions(seedatlas_xaero PRIVATE
        _CRT_SECURE_NO_WARNINGS=1 _USE_MATH_DEFINES=1)
else()
    target_compile_options(seedatlas_xaero PRIVATE
        -Wall -Wextra -Wpedantic -O3 -fno-strict-aliasing)
    if(NOT WIN32)
        target_link_libraries(seedatlas_xaero PRIVATE m)
    endif()
endif()

include(GNUInstallDirs)
install(TARGETS seedatlas_xaero
    RUNTIME DESTINATION .
    LIBRARY DESTINATION .
)

include(CTest)
if(BUILD_TESTING)
    add_executable(seedatlas_xaero_test tests/seedatlas_xaero_test.c)
    target_include_directories(seedatlas_xaero_test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}")
    target_link_libraries(seedatlas_xaero_test PRIVATE seedatlas_xaero)
    # Release configurations normally define NDEBUG, which would compile out
    # every assertion in this ABI smoke test and turn it into a no-op.
    if(MSVC)
        target_compile_options(seedatlas_xaero_test PRIVATE /UNDEBUG)
    else()
        target_compile_options(seedatlas_xaero_test PRIVATE -UNDEBUG)
    endif()
    add_test(NAME seedatlas_xaero_abi COMMAND seedatlas_xaero_test)

    add_executable(nether_reference_test tests/nether_reference_test.c ${SEEDATLAS_ENGINE_SOURCES})
    target_include_directories(nether_reference_test PRIVATE "${SEEDATLAS_ENGINE_DIR}")
    target_compile_definitions(nether_reference_test PRIVATE SEEDATLAS_XAERO_BUILD=1)
    if(MSVC)
        target_compile_options(nether_reference_test PRIVATE /UNDEBUG)
    else()
        target_compile_options(nether_reference_test PRIVATE -UNDEBUG)
        if(NOT WIN32)
            target_link_libraries(nether_reference_test PRIVATE m)
        endif()
    endif()
    add_test(NAME nether_vanilla_26_3 COMMAND nether_reference_test)

    add_executable(seedatlas_xaero_benchmark
        tests/seedatlas_xaero_benchmark.c)
    target_include_directories(seedatlas_xaero_benchmark PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}")
    target_link_libraries(seedatlas_xaero_benchmark PRIVATE seedatlas_xaero)
endif()
