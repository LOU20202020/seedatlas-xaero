#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "$script_dir/../.." && pwd)"
native_source="$project_dir/src/main/native"

configuration="${CONFIGURATION:-Release}"
run_tests="${RUN_TESTS:-ON}"
platform="${1:-}"

os="$(uname -s)"
architecture="$(uname -m)"
case "$os/$architecture" in
    Linux/x86_64) host_platform="linux-x86_64" ;;
    Darwin/x86_64) host_platform="macos-x86_64" ;;
    Darwin/arm64|Darwin/aarch64) host_platform="macos-aarch64" ;;
    MINGW*/x86_64|MSYS*/x86_64|CYGWIN*/x86_64)
        host_platform="windows-x86_64"
        ;;
    *) echo "Unsupported native host: $os / $architecture" >&2; exit 2 ;;
esac

if [[ -z "$platform" ]]; then
    platform="$host_platform"
elif [[ "$platform" != "$host_platform" ]]; then
    echo "Requested $platform on $os / $architecture ($host_platform)" >&2
    echo "Use a native runner for each resource platform" >&2
    exit 2
fi

case "$platform" in
    windows-x86_64|linux-x86_64|macos-x86_64|macos-aarch64) ;;
    *) echo "Unsupported resource platform: $platform" >&2; exit 2 ;;
esac

build_dir="$project_dir/build/native/$platform"
resource_root="$project_dir/build/generated/native-resources/natives/$platform"

cmake_args=(
    -DCMAKE_BUILD_TYPE="$configuration"
    -DCMAKE_INSTALL_PREFIX="$resource_root"
    -DBUILD_TESTING="$run_tests"
)
if [[ -n "${SEEDATLAS_ENGINE_DIR:-}" ]]; then
    cmake_args+=("-DSEEDATLAS_ENGINE_DIR=$SEEDATLAS_ENGINE_DIR")
fi
case "$platform" in
    macos-aarch64)
        cmake_args+=(
            -DCMAKE_OSX_ARCHITECTURES=arm64
            -DCMAKE_OSX_DEPLOYMENT_TARGET=13.0
        )
        ;;
    macos-x86_64)
        cmake_args+=(
            -DCMAKE_OSX_ARCHITECTURES=x86_64
            -DCMAKE_OSX_DEPLOYMENT_TARGET=13.0
        )
        ;;
esac

cmake -S "$native_source" -B "$build_dir" "${cmake_args[@]}"
cmake --build "$build_dir" --config "$configuration" --parallel

if [[ "$run_tests" == "ON" ]]; then
    ctest --test-dir "$build_dir" -C "$configuration" --output-on-failure
fi

cmake --install "$build_dir" --config "$configuration"

if [[ "$platform" == macos-* ]]; then
    if [[ "$(uname -s)" != "Darwin" ]]; then
        echo "macOS native resources must be finalized on macOS" >&2
        exit 2
    fi
    dylib="$resource_root/seedatlas_xaero.dylib"
    /usr/bin/install_name_tool -id @rpath/seedatlas_xaero.dylib "$dylib"
    /usr/bin/codesign --force --sign - \
        --identifier org.seedatlas.xaero.native "$dylib"
    /usr/bin/codesign --verify --strict --verbose=2 "$dylib"
    if [[ "$run_tests" == "ON" ]]; then
        SAX_DYLIB="$dylib" /usr/bin/python3 -c \
            'import ctypes, os; lib = ctypes.CDLL(os.environ["SAX_DYLIB"]); assert lib.sax_abi_version() == 6'
    fi
fi

echo "Native resource generated at $resource_root"
