# Building Offline Reader

The Kotlin/JNI/Gradle source in this repo is complete and ready to open in Android
Studio, but two binary artifacts are deliberately **not** checked in — they're large,
per-ABI native/model binaries that must be produced on a machine with internet access and
the Android NDK. Neither step can run inside a network-isolated CI/agent sandbox.

## Prerequisites

- Android Studio Koala+ (or a standalone Android SDK with `compileSdk = 34`)
- Android NDK `26.3.11579264` (installed via Android Studio's SDK Manager, or standalone)
- Python 3.10+ with `pip` (for the Kokoro export step only — not needed to build the APK
  once assets are staged)

## Step 1 — build espeak-ng for Android

```sh
./scripts/build_espeak_ng.sh /path/to/Android/Sdk/ndk/26.3.11579264
```

This clones upstream `espeak-ng` (GPL-3.0 — see Licensing below), cross-compiles it for
`arm64-v8a` and `armeabi-v7a`, and stages:

```
third_party/espeak-ng/include/espeak-ng/speak_lib.h
third_party/espeak-ng/lib/arm64-v8a/libespeak-ng.so
third_party/espeak-ng/lib/armeabi-v7a/libespeak-ng.so
app/src/main/assets/espeak-ng-data/           # voice/dictionary data, extracted at first app run
```

## Step 2 — export Kokoro-82M to ONNX

```sh
pip install torch kokoro onnx onnxruntime huggingface_hub
python scripts/export_kokoro_onnx.py --voices af_heart,am_adam,bf_emma
```

This downloads Kokoro-82M's weights from Hugging Face (`hexgrad/Kokoro-82M`), traces and
exports it to ONNX, quantizes to int8 (~40-80MB depending on op coverage — down from the
~330MB fp32 checkpoint), and writes:

```
app/src/main/assets/models/kokoro-82m-int8.onnx
app/src/main/assets/models/tokenizer.json
app/src/main/assets/models/voices/af_heart.bin
app/src/main/assets/models/voices/am_adam.bin
app/src/main/assets/models/voices/bf_emma.bin
```

Pick whichever voice IDs you want bundled — each adds ~520KB (`510 × 256 × 4` bytes).

## Step 3 — build

```sh
./gradlew assembleDebug
```

or open the project root in Android Studio and hit Run. `CMakeLists.txt`
(`app/src/main/cpp/`) picks up the staged `third_party/espeak-ng` artifacts automatically;
if it can't find `libespeak-ng.so` for the target ABI it emits a clear CMake warning rather
than a cryptic linker error.

## Licensing note

`espeak-ng` is GPL-3.0. Statically or dynamically linking it into this app means the
combined APK is subject to GPL-3.0 obligations for the app's own source (not just
espeak-ng's) unless you replace it with a differently-licensed phonemizer. This is called
out here because it's a real decision, not a build detail — evaluate it before shipping to
a store.

Kokoro-82M is released under Apache-2.0.
