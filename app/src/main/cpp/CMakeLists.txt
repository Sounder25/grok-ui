cmake_minimum_required(VERSION 3.22.1)
project(offlinetts_phonemizer)

# espeak-ng cannot be pulled in as a Gradle dependency; it's a C library that must be
# cross-compiled for each Android ABI with the NDK. `scripts/build_espeak_ng.sh` builds it
# and stages the result under third_party/espeak-ng/{include,lib/<abi>} — see docs/BUILD.md.
set(ESPEAK_NG_ROOT ${CMAKE_SOURCE_DIR}/../../../../third_party/espeak-ng)
set(ESPEAK_NG_LIB_DIR ${ESPEAK_NG_ROOT}/lib/${ANDROID_ABI})

if(NOT EXISTS ${ESPEAK_NG_LIB_DIR}/libespeak-ng.so)
    message(WARNING
        "Prebuilt libespeak-ng.so not found for ABI ${ANDROID_ABI} at ${ESPEAK_NG_LIB_DIR}. "
        "Run scripts/build_espeak_ng.sh <path-to-ndk> first (see docs/BUILD.md). "
        "The app module will still configure, but the native phonemizer target will fail to link.")
endif()

add_library(espeak-ng SHARED IMPORTED)
set_target_properties(espeak-ng PROPERTIES
    IMPORTED_LOCATION ${ESPEAK_NG_LIB_DIR}/libespeak-ng.so
)

add_library(offlinetts_phonemizer SHARED
    phonemizer_jni.cpp
)

target_include_directories(offlinetts_phonemizer PRIVATE
    ${ESPEAK_NG_ROOT}/include
)

find_library(log-lib log)

target_link_libraries(offlinetts_phonemizer
    espeak-ng
    ${log-lib}
)
