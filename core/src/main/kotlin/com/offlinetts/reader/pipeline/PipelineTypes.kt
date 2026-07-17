package com.offlinetts.reader.pipeline

/** One sentence-ish unit of text, in original document order. */
data class TextChunk(val index: Int, val text: String)

/** One synthesized chunk of audio, ready to be handed to the player in order. */
data class AudioChunk(val index: Int, val pcm: FloatArray, val sampleRateHz: Int)
