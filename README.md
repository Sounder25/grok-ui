# Offline Reader

A native Android app that reads text, HTML, and PDFs aloud entirely on-device — no
network permission, no cloud TTS API, no telemetry. Text is normalized and phonemized
with `espeak-ng`, synthesized with a quantized **Kokoro-82M** ONNX model running on
**ONNX Runtime**'s XNNPACK execution provider, and played back gaplessly with
**Media3/ExoPlayer** from a **Foreground Service** so playback survives screen-off and
backgrounding.

See `docs/ARCHITECTURE.md` for how the four layers (ingestion, inference, concurrency
pipeline, OS/audio integration) fit together, file by file.

## Status

All Kotlin/Java application code, the JNI phonemizer shim, the Gradle/CMake build
config, and the ONNX export tooling are implemented and committed. The pure-logic slice
(`:core` — sentence chunking, text sanitization, WAV encoding) has real unit tests that
build and run cleanly (`./gradlew :core:test`, 17/17 passing) with nothing but a JDK —
that's actually been executed, not just written. The rest of the app (`:app` — the
Android module: JNI phonemizer, ONNX engine, foreground service, ExoPlayer wiring, UI)
has been reviewed carefully but **could not be compiled or run on-device**, because two
things are intentionally **not** in this repo — they're large per-ABI binaries that must
be produced on a machine with internet access and the Android NDK, neither of which was
available where this was built:

1. A compiled `libespeak-ng.so` per ABI (`scripts/build_espeak_ng.sh`)
2. The quantized Kokoro-82M `.onnx` file + voice style tables
   (`scripts/export_kokoro_onnx.py`)

Follow `docs/BUILD.md` to produce both and build a runnable APK. Until then, the project
configures and the Kotlin/JVM code compiles, but `assembleDebug` will fail at the native
link step (with a clear message, not a silent one — see `CMakeLists.txt`) until step 1 is
done, and the app will crash on first "Read Aloud" until step 2 is done.

## Project layout

```
core/                          # plain Kotlin/JVM module, no Android dependency, real unit tests
├── src/main/kotlin/com/offlinetts/reader/
│   ├── ingestion/TextSanitizer.kt
│   ├── pipeline/{SentenceChunker,PipelineTypes}.kt
│   └── playback/WavPcmEncoder.kt
└── src/test/kotlin/...         # 17 tests, runnable with `./gradlew :core:test` — no SDK needed
app/src/main/
├── cpp/                      # espeak-ng JNI shim + CMake build
├── java/com/offlinetts/reader/
│   ├── ingestion/             # HTML/PDF extraction, phonemizer JNI wrapper (+ TextSanitizer from :core)
│   ├── engine/                # ONNX Runtime session, tokenizer, voice styles, Kokoro engine
│   ├── pipeline/               # synthesis worker, bounded playback buffer (+ chunker from :core)
│   ├── playback/               # ExoPlayer wiring, audio focus (+ WAV encoder from :core)
│   ├── service/                 # foreground service tying it all together
│   └── ui/                      # minimal activity: paste text / share HTML / share PDF
├── assets/models/              # NOT checked in — see docs/BUILD.md
docs/
├── ARCHITECTURE.md
└── BUILD.md
scripts/
├── build_espeak_ng.sh          # cross-compiles espeak-ng for Android
└── export_kokoro_onnx.py       # exports + quantizes Kokoro-82M, writes voice tables
third_party/espeak-ng/          # populated by build_espeak_ng.sh, not checked in
```

## Quick start

The Gradle wrapper jar (`gradle/wrapper/gradle-wrapper.jar`) isn't checked in — it's a
binary that has to be fetched from `services.gradle.org`, which wasn't reachable from the
sandbox this project was scaffolded in. Generate it once, from a machine with normal
internet access, before your first build:

```sh
gradle wrapper --gradle-version 8.7   # writes gradlew, gradlew.bat, gradle-wrapper.jar
```

Then:

```sh
./scripts/build_espeak_ng.sh $ANDROID_HOME/ndk/26.3.11579264
pip install torch kokoro onnx onnxruntime huggingface_hub
python scripts/export_kokoro_onnx.py --voices af_heart
./gradlew assembleDebug
```

Full details, including the GPL-3.0 licensing implication of bundling espeak-ng, are in
`docs/BUILD.md`.
