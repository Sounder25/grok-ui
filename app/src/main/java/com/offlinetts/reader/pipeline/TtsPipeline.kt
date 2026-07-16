package com.offlinetts.reader.pipeline

import android.content.Context
import com.offlinetts.reader.engine.KokoroTtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PipelineState {
    data object Idle : PipelineState
    data class Synthesizing(val completed: Int, val total: Int) : PipelineState
    data object Draining : PipelineState // synthesis done, playback still catching up
    data object Done : PipelineState
    data class Error(val message: String) : PipelineState
}

/**
 * Wires Worker A (sentence chunking), Worker B ([SynthesisWorker]) and the handoff to
 * Worker C (playback, outside this class — see [com.offlinetts.reader.playback.TtsPlayer])
 * into one pipeline the service can start/stop as a unit.
 */
class TtsPipeline(context: Context, private val scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val engine = KokoroTtsEngine(appContext)
    private val synthesisWorker = SynthesisWorker(appContext, engine)

    val playbackBuffer = PlaybackBuffer(capacity = 2)

    private val _state = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    private var job: Job? = null

    fun start(chunks: List<TextChunk>, voiceId: String, speed: Float = 1.0f) {
        stop()
        playbackBuffer.clear()
        _state.value = PipelineState.Synthesizing(0, chunks.size)

        // Unbounded here on purpose: chunking already happened up front (cheap, CPU-only
        // string work), so the *real* backpressure point is PlaybackBuffer.push() inside
        // SynthesisWorker, which blocks once two chunks of audio are queued.
        val textChannel = Channel<TextChunk>(Channel.UNLIMITED)

        job = scope.launch {
            launch {
                for (chunk in chunks) textChannel.send(chunk)
                textChannel.close()
            }
            synthesisWorker.run(
                input = textChannel,
                output = playbackBuffer,
                voiceId = voiceId,
                speed = speed,
                onProgress = { completed, _ ->
                    _state.value = PipelineState.Synthesizing(completed, chunks.size)
                },
            )
            if (_state.value is PipelineState.Synthesizing) {
                _state.value = PipelineState.Done
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        playbackBuffer.clear()
        _state.value = PipelineState.Idle
    }

    fun availableVoices(): List<String> = engine.availableVoices()

    fun release() {
        stop()
        engine.close()
    }
}
