package com.offlinetts.reader.pipeline

import android.content.Context
import com.offlinetts.reader.engine.KokoroTtsEngine
import com.offlinetts.reader.ingestion.Phonemizer
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException

/**
 * Worker B: pulls [TextChunk]s off the producer's channel, phonemizes and synthesizes
 * each one through Kokoro, and pushes the resulting [AudioChunk]s into the
 * [PlaybackBuffer] for Worker C to consume. Runs on a single dedicated worker so espeak-ng
 * (which keeps global C state) and the shared ONNX session are only ever touched from one
 * thread at a time, and so at most one chunk's worth of PCM is being computed at once.
 */
class SynthesisWorker(
    context: Context,
    private val engine: KokoroTtsEngine,
) {
    private val appContext = context.applicationContext
    private val phonemizer = Phonemizer.getInstance()

    // A dedicated single thread rather than the shared IO pool: espeak-ng's C state and
    // the ONNX session are called back-to-back per chunk here, and we want a predictable
    // one-chunk-at-a-time cadence instead of competing with other IO-dispatcher work.
    private val synthesisDispatcher = Dispatchers.IO.limitedParallelism(1)

    suspend fun run(
        input: ReceiveChannel<TextChunk>,
        output: PlaybackBuffer,
        voiceId: String,
        speed: Float,
        onProgress: (completed: Int, chunk: TextChunk) -> Unit,
    ) = withContext(synthesisDispatcher) {
        if (!phonemizer.initialize(appContext)) {
            output.pushError(IllegalStateException("espeak-ng phonemizer failed to initialize"))
            return@withContext
        }

        var completed = 0
        try {
            for (chunk in input) {
                val phonemes = phonemizer.toPhonemes(chunk.text)
                if (phonemes.isBlank()) {
                    completed++
                    continue // e.g. a chunk that was pure punctuation/whitespace
                }
                val result = engine.synthesize(phonemes, voiceId, speed)
                output.push(AudioChunk(chunk.index, result.pcm, result.sampleRateHz))
                completed++
                onProgress(completed, chunk)
            }
            output.pushEndOfStream()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            output.pushError(e)
        }
    }
}
