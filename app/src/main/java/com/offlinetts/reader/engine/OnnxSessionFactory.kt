package com.offlinetts.reader.engine

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context

/**
 * Builds the single [OrtSession] the app uses for Kokoro inference, configured to run on
 * the XNNPACK execution provider so ARM CPU kernels are used instead of the generic CPU
 * EP. XNNPACK keeps inference fast enough to stay ahead of real-time playback without
 * pushing the SoC into thermal throttling the way a naive CPU EP config can.
 */
object OnnxSessionFactory {

    private const val MODEL_ASSET_PATH = "models/kokoro-82m-int8.onnx"

    fun create(context: Context, env: OrtEnvironment): OrtSession {
        val modelBytes = context.assets.open(MODEL_ASSET_PATH).use { it.readBytes() }

        val options = OrtSession.SessionOptions().apply {
            // Physical performance cores only; leave efficiency cores free for the UI/OS
            // so synthesis never causes jank or gets deprioritized by the scheduler.
            setIntraOpNumThreads(recommendedThreadCount())
            setMemoryPatternOptimization(true)
            setCPUArenaAllocator(true)

            // XNNPACK EP: falls back to the default CPU EP automatically for any op it
            // doesn't implement, so this is safe even if the exported graph has gaps.
            addXnnpack(mapOf("intra_op_num_threads" to recommendedThreadCount().toString()))
        }

        return env.createSession(modelBytes, options)
    }

    private fun recommendedThreadCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        // Leave at least one core free; cap at 4 since Kokoro-82M int8 doesn't scale much
        // past that and more threads just means more thermal headroom burned for no gain.
        return cores.coerceAtLeast(2).let { (it - 1).coerceIn(1, 4) }
    }
}
