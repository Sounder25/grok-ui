# Architecture

Offline Reader is a fully on-device text-to-speech app: no network permission is even
declared in the manifest. It follows the four-layer design below; each layer maps to one
package under `app/src/main/java/com/offlinetts/reader/`.

## 1. Ingestion & normalization — `ingestion/`

| Concern | File | Notes |
|---|---|---|
| HTML → plain text | `HtmlExtractor.kt` | Jsoup, strips scripts/nav/etc, preserves paragraph breaks |
| PDF → plain text | `PdfExtractor.kt` | PDFBox-Android, reads from a `content://`/`file://` Uri |
| Sanitization | `TextSanitizer.kt` | Regex pass: zero-width chars, emoji, control chars, smart-punctuation normalization |
| Phonemization | `Phonemizer.kt` + `cpp/phonemizer_jni.cpp` | JNI wrapper around espeak-ng, text → IPA phonemes |

Text flows `raw bytes → Html/PdfExtractor → TextSanitizer → SentenceChunker → Phonemizer`
before it ever reaches the model. This ordering matters: espeak-ng needs sanitized text so
zero-width/control characters don't confuse its clause detection, and the ONNX model never
sees raw text at all — only IPA phonemes.

## 2. Inference engine — `engine/`

| File | Role |
|---|---|
| `OnnxSessionFactory.kt` | Builds the single `OrtSession`, configured for the XNNPACK execution provider |
| `PhonemeTokenizer.kt` | IPA symbol → vocab id, using `assets/models/tokenizer.json` |
| `VoiceStyleRepository.kt` | Loads per-voice style tables (`assets/models/voices/*.bin`), sliced by token count |
| `KokoroTtsEngine.kt` | Ties the above together: phonemes in, `FloatArray` PCM out |

The model itself (`assets/models/kokoro-82m-int8.onnx`) is not checked into this repo —
see `docs/BUILD.md` for how to produce it with `scripts/export_kokoro_onnx.py`.

## 3. Concurrency pipeline — `pipeline/`

Producer/consumer, exactly as specified:

- **Worker A** (`SentenceChunker.kt`, in `:core` — see below) — splits sanitized text
  into sentence-ish chunks (~300 chars, abbreviation-aware), run up front since it's cheap
  pure-CPU string work.
- **Worker B** (`SynthesisWorker.kt`) — pulls `TextChunk`s off a `Channel`, phonemizes +
  synthesizes each one on a single dedicated dispatcher (espeak-ng and the ONNX session
  both have state that's only safe to touch serially), and pushes `AudioChunk`s into...
- **`PlaybackBuffer.kt`** — a bounded (`capacity = 2`) blocking queue. This is the actual
  backpressure point: `push()` blocks the synthesis worker once two chunks are queued, so
  synthesis never runs more than ~2 sentences ahead of playback. `TtsPipeline.kt` wires
  Worker A + B together and exposes a `StateFlow<PipelineState>` for the UI/notification.

Worker C (playback) lives in `playback/` since it's really part of the audio-integration
layer — see below.

## 4. OS & audio integration — `service/`, `playback/`

- **`TtsForegroundService.kt`** — a `LifecycleService` started with
  `startForegroundService()`, posts an ongoing notification immediately (`startForeground`
  within `onStartCommand`), and owns both the `TtsPipeline` and the `TtsPlayer` for the
  lifetime of a reading session. This is what keeps synthesis+playback alive when the
  screen locks — without it Android kills the process a few seconds into background.
- **`TtsPlayer.kt`** (Worker C) — drains `PlaybackBuffer`, wraps each `AudioChunk` as a
  tiny self-contained WAV (`WavPcmEncoder.kt`) and queues it as one `MediaItem` on a single
  `ExoPlayer`. Consecutive same-format items play back gaplessly, which delivers "no gap
  between paragraphs" without needing a custom streaming container.
- **`InMemoryDataSource.kt`/`InMemoryChunkRegistry.kt`** — the `DataSource` plumbing that
  lets ExoPlayer read those in-memory WAV byte arrays without ever touching disk.
- **`AudioFocusManager.kt`** — explicit `AudioFocusRequest`: ducks on transient loss (nav
  prompt), pauses on full loss (phone call / another app's playback), resumes on regain.

## Data flow, end to end

```
 shared HTML/PDF/pasted text
        │
        ▼
 HtmlExtractor / PdfExtractor   (ingestion/)
        │
        ▼
 TextSanitizer
        │
        ▼
 SentenceChunker            ─── Worker A
        │  Channel<TextChunk>
        ▼
 SynthesisWorker            ─── Worker B  (Phonemizer → KokoroTtsEngine)
        │  PlaybackBuffer (bounded, capacity 2)
        ▼
 TtsPlayer                  ─── Worker C  (WavPcmEncoder → ExoPlayer playlist)
        │
        ▼
 speakers, via AudioFocusManager-gated ExoPlayer
```

## `:core` — the pure-Kotlin/JVM slice

`SentenceChunker`, `TextSanitizer`, and `WavPcmEncoder` have no Android framework
dependency, so they live in a separate Gradle module (`core/`, plugin
`org.jetbrains.kotlin.jvm`, not an Android module) rather than `:app`. `:app` depends on
`:core`; the package names are unchanged, so nothing importing them had to change. This
split means the algorithmic core of the app — chunking, sanitization, and PCM encoding —
has real, executable unit tests (`core/src/test/kotlin/...`, 17 tests) that build and run
with just a JDK, no Android SDK/NDK required: `./gradlew :core:test`.

## Known simplifications (MVP scope)

- The activity does not currently bind to the service for live progress updates; the
  notification is the source of truth for pipeline state while backgrounded. Wiring a
  shared `StateFlow` (e.g. via a small repository singleton) into the activity is
  straightforward follow-up work, not an architectural gap.
- `SentenceChunker`'s abbreviation list is illustrative, not exhaustive.
- Only a single reading session is supported at a time (matches the "one book/article
  read aloud" use case this is designed for).
