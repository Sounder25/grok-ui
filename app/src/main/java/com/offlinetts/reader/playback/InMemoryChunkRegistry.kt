package com.offlinetts.reader.playback

import android.net.Uri

/**
 * Maps synthetic `tts-chunk://<index>` URIs to the WAV bytes ExoPlayer should read for
 * them. Entries are *not* removed on first read: WAV extraction/probing and internal
 * ExoPlayer retries can reopen a [InMemoryDataSource] for the same URI more than once, so
 * this instead keeps a small LRU-style cap and evicts the oldest entries, which is more
 * than enough since only a couple of chunks are ever in flight at once (see
 * [com.offlinetts.reader.pipeline.PlaybackBuffer]'s capacity).
 */
object InMemoryChunkRegistry {

    private const val MAX_ENTRIES = 8
    private const val SCHEME = "tts-chunk"

    private val entries = object : LinkedHashMap<Uri, ByteArray>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Uri, ByteArray>): Boolean =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun register(index: Int, bytes: ByteArray): Uri {
        val uri = Uri.parse("$SCHEME://$index")
        entries[uri] = bytes
        return uri
    }

    @Synchronized
    fun take(uri: Uri): ByteArray? = entries[uri]

    @Synchronized
    fun clear() = entries.clear()
}
