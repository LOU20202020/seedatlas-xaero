#!/usr/bin/env bash
# Rebuilds the two ABI wrappers and drops them into the mod's resources.
#
#   export CUBIOMES_DIR=/path/to/xpcubiomes   # https://github.com/xpple/cubiomes
#   bash native/build_natives.sh
#
# The engine dll (seedatlas_xaero.dll) is built by upstream Seed Atlas, not here.
set -euo pipefail

: "${CUBIOMES_DIR:?set CUBIOMES_DIR to an xpple/cubiomes checkout}"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/../common/src/main/resources/natives/windows-x86_64"
CC="${CC:-gcc}"
mkdir -p "$OUT"

build() {
  local src="$1" dll="$2"
  echo "== $src -> $dll"
  "$CC" -O2 -shared -static-libgcc -o "$OUT/$dll" "$HERE/$src" \
    -I"$CUBIOMES_DIR" -I"$HERE" \
    "$CUBIOMES_DIR"/*.c
}

build sax_detail.c sax_detail.dll

echo "done:"
ls -l "$OUT"/*.dll
