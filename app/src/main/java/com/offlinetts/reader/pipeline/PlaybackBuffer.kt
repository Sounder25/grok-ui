package com.offlinetts.reader.pipeline

import java.util.concurrent.ArrayBlockingQueue

/**
 * Bounded handoff between Worker B (synthesis) and Worker C (playback). Capacity is
 * intentionally small — a couple of chunks — so the synthesizer never runs more than
 * one or two sentences ahead of what's currently playing: that bounds peak memory to a
 * few hundred KB of PCM instead of buffering an entire chapter, and [push] naturally
 * blocks (backpressures) the synthesis coroutine when playback falls behind.
 *
 * [pop] is a *blocking* call by design, not suspend: it's read from ExoPlayer's loading
 * thread inside [com.offlinetts.reader.playback.PcmQueueDataSource], which is a plain
 * background thread, not a coroutine.
 */
class PlaybackBuffer(capacity: Int = 2) {

    private val queue = ArrayBlockingQueue<Envelope>(capacity)

    private sealed class Envelope {
        data class Chunk(val audio: AudioChunk) : Envelope()
        data class Error(val cause: Throwable) : Envelope()
        object EndOfStream : Envelope()
    }

    /** Suspends (via thread blocking on IO dispatcher) if the buffer is full — this is the backpressure point. */
    fun push(chunk: AudioChunk) = queue.put(Envelope.Chunk(chunk))

    fun pushError(cause: Throwable) = queue.put(Envelope.Error(cause))

    fun pushEndOfStream() = queue.put(Envelope.EndOfStream)

    /** Blocks until a chunk is available. Returns null at end of stream. Throws on upstream error. */
    fun pop(): AudioChunk? {
        return when (val envelope = queue.take()) {
            is Envelope.Chunk -> envelope.audio
            is Envelope.Error -> throw envelope.cause
            Envelope.EndOfStream -> null
        }
    }

    fun clear() = queue.clear()
}
