# espeak-ng prebuilt artifacts (not checked in)

Run `scripts/build_espeak_ng.sh <ndk-path>` on a machine with internet access and the
Android NDK to populate:

    third_party/espeak-ng/include/espeak-ng/speak_lib.h
    third_party/espeak-ng/lib/arm64-v8a/libespeak-ng.so
    third_party/espeak-ng/lib/armeabi-v7a/libespeak-ng.so

and `app/src/main/assets/espeak-ng-data/` with the voice/dictionary data.

This directory is intentionally left empty in version control (see docs/BUILD.md) — the
espeak-ng shared library is GPL-licensed and cross-compiled per ABI, so it doesn't belong
in source control as a binary blob.
