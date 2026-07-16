package com.offlinetts.reader.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.LongBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Loads Kokoro-82M (int8-quantized, exported to ONNX — see docs/BUILD.md) and runs
 * synthesis for one phoneme chunk at a time. One [OrtSession] is shared across all calls;
 * ONNX Runtime sessions are safe for concurrent `run()` calls, but this project's
 * concurrency pipeline (see [com.offlinetts.reader.pipeline.SynthesisWorker]) always
 * drives it from a single dedicated worker to keep memory/CPU bounded, so the lock here
 * exists to make that invariant hold even if that changes.
 */
class KokoroTtsEngine(context: Context) : AutoCloseable {

    private val appContext = context.applicationContext
    private val lock = ReentrantLock()

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession by lazy { OnnxSessionFactory.create(appContext, env) }
    private val tokenizer: PhonemeTokenizer by lazy { PhonemeTokenizer.load(appContext) }
    private val styles = VoiceStyleRepository(appContext)

    val sampleRateHz = 24_000

    data class Result(val pcm: FloatArray, val sampleRateHz: Int)

    fun availableVoices(): List<String> = styles.availableVoices()

    /**
     * Synthesizes one already-phonemized sentence/clause chunk. Callers are expected to
     * keep chunks short (a sentence or so) both because Kokoro was trained on
     * sentence-length inputs and because it bounds worst-case latency-to-first-audio in
     * the playback pipeline.
     */
    fun synthesize(phonemes: String, voiceId: String, speed: Float = 1.0f): Result = lock.withLock {
        val tokenIds = tokenizer.tokenize(phonemes)
        val style = styles.styleFor(voiceId, tokenIds.size)

        OnnxTensor.createTensor(env, LongBuffer.wrap(tokenIds), longArrayOf(1, tokenIds.size.toLong())).use { tokensTensor ->
            OnnxTensor.createTensor(env, arrayOf(style)).use { styleTensor ->
                OnnxTensor.createTensor(env, floatArrayOf(speed)).use { speedTensor ->
                    val inputs = mapOf(
                        "tokens" to tokensTensor,
                        "style" to styleTensor,
                        "speed" to speedTensor,
                    )
                    session.run(inputs).use { outputs ->
                        @Suppress("UNCHECKED_CAST")
                        val audioTensor = outputs[0].value
                        val pcm: FloatArray = when (audioTensor) {
                            is FloatArray -> audioTensor
                            is Array<*> -> (audioTensor as Array<FloatArray>)[0]
                            else -> error("Unexpected Kokoro output type: ${audioTensor?.javaClass}")
                        }
                        return@withLock Result(pcm, sampleRateHz)
                    }
                }
            }
        }
    }

    override fun close() {
        lock.withLock {
            session.close()
            // Do not close `env` here: OrtEnvironment is a process-wide singleton
            // (OrtEnvironment.getEnvironment() returns the same instance app-wide) and
            // closing it would break any other session sharing the process.
        }
    }
}
