#!/usr/bin/env bash
# Cross-compiles espeak-ng for Android and stages the result under third_party/espeak-ng/
# so the CMake build in app/src/main/cpp can link against it.
#
# This must be run on a machine with network access and the Android NDK installed — it
# cannot run inside a network-isolated CI sandbox because it clones upstream espeak-ng.
#
# Usage:
#   ./scripts/build_espeak_ng.sh /path/to/Android/Sdk/ndk/26.3.11579264
#
# Requires: git, autoconf, automake, libtool, pkg-config (host build tools used to run
# espeak-ng's data compiler as part of its own build).

set -euo pipefail

NDK_PATH="${1:?Usage: build_espeak_ng.sh <path-to-android-ndk>}"
ABIS=("arm64-v8a" "armeabi-v7a")
API_LEVEL=26
ESPEAK_NG_TAG="1.51.1"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="$ROOT_DIR/build/espeak-ng-src"
OUT_DIR="$ROOT_DIR/third_party/espeak-ng"

mkdir -p "$WORK_DIR" "$OUT_DIR/include"

if [ ! -d "$WORK_DIR/.git" ]; then
    git clone --branch "$ESPEAK_NG_TAG" --depth 1 \
        https://github.com/espeak-ng/espeak-ng.git "$WORK_DIR"
fi

for ABI in "${ABIS[@]}"; do
    echo "=== Building espeak-ng for $ABI ==="
    BUILD_DIR="$WORK_DIR/build-$ABI"
    rm -rf "$BUILD_DIR"
    mkdir -p "$BUILD_DIR"

    TOOLCHAIN_FILE="$NDK_PATH/build/cmake/android.toolchain.cmake"
    if [ ! -f "$TOOLCHAIN_FILE" ]; then
        echo "Could not find NDK CMake toolchain at $TOOLCHAIN_FILE" >&2
        exit 1
    fi

    cmake -S "$WORK_DIR" -B "$BUILD_DIR" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM="android-$API_LEVEL" \
        -DANDROID_STL=c++_shared \
        -DBUILD_SHARED_LIBS=ON \
        -DUSE_ASYNC=OFF \
        -DUSE_MBROLA=OFF \
        -DUSE_LIBSONIC=OFF \
        -DUSE_LIBPCAUDIO=OFF \
        -DUSE_KLATT=OFF \
        -DUSE_SPEECHPLAYER=OFF \
        -DCMAKE_BUILD_TYPE=Release

    cmake --build "$BUILD_DIR" --target espeak-ng -j"$(nproc)"

    mkdir -p "$OUT_DIR/lib/$ABI"
    find "$BUILD_DIR" -name "libespeak-ng.so" -exec cp {} "$OUT_DIR/lib/$ABI/" \;
done

cp -r "$WORK_DIR/src/libespeak-ng"/*.h "$OUT_DIR/include/" 2>/dev/null || true
mkdir -p "$OUT_DIR/include/espeak-ng"
cp "$WORK_DIR/src/include/espeak-ng/speak_lib.h" "$OUT_DIR/include/espeak-ng/"

# espeak-ng's runtime voice/language data (dictionaries, phoneme tables) — bundle it as an
# Android asset so it can be extracted to app-private storage at first run (see
# Phonemizer.ensureEspeakDataExtracted). This is *not* an .so, it's built as part of the
# host build and lives under build-*/espeak-ng-data by convention.
DATA_SRC=$(find "$WORK_DIR" -maxdepth 3 -type d -name "espeak-ng-data" | head -n1 || true)
if [ -n "$DATA_SRC" ]; then
    mkdir -p "$ROOT_DIR/app/src/main/assets/espeak-ng-data"
    cp -r "$DATA_SRC/." "$ROOT_DIR/app/src/main/assets/espeak-ng-data/"
else
    echo "WARNING: could not locate espeak-ng-data directory; run the host (non-Android) build once to generate it, then re-run this script." >&2
fi

echo "Done. Staged headers + $ABIS .so files under $OUT_DIR"
