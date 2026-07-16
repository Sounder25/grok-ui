package com.offlinetts.reader.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlin.math.min

/**
 * Serves a single already-encoded WAV byte array as a [DataSource]. Each synthesized
 * chunk is registered against a unique `tts-chunk://<index>` [Uri] in
 * [InMemoryChunkRegistry] before its [androidx.media3.common.MediaItem] is queued;
 * ExoPlayer's loading thread resolves the bytes for that URI when it calls [open].
 */
class InMemoryDataSource : BaseDataSource(/* isNetwork= */ false) {

    private var uri: Uri? = null
    private var bytes: ByteArray = ByteArray(0)
    private var position = 0
    private var bytesRemaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        bytes = InMemoryChunkRegistry.take(dataSpec.uri)
            ?: throw java.io.IOException("No registered PCM chunk for ${dataSpec.uri}")

        position = dataSpec.position.toInt()
        bytesRemaining = bytes.size - dataSpec.position
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            bytesRemaining = min(bytesRemaining, dataSpec.length)
        }
        if (bytesRemaining < 0) {
            throw IndexOutOfBoundsException("position ${dataSpec.position} beyond chunk of size ${bytes.size}")
        }
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val toRead = min(length.toLong(), bytesRemaining).toInt()
        System.arraycopy(bytes, position, buffer, offset, toRead)
        position += toRead
        bytesRemaining -= toRead
        bytesTransferred(toRead)
        return toRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = InMemoryDataSource()
    }
}
