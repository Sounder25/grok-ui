package com.offlinetts.reader.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offlinetts.reader.engine.VoiceStyleRepository
import com.offlinetts.reader.ingestion.HtmlExtractor
import com.offlinetts.reader.ingestion.PdfExtractor
import com.offlinetts.reader.ingestion.TextSanitizer
import com.offlinetts.reader.pipeline.SentenceChunker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Resolves whatever the user handed the app (pasted text, a shared HTML snippet, a
 * shared PDF Uri) down into plain sanitized text, then hands sentence chunks off to the
 * foreground service. Extraction runs off the main thread since PDFBox parsing of a long
 * document is not instantaneous.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val voiceRepository = VoiceStyleRepository(application)

    private val _voices = MutableStateFlow<List<String>>(emptyList())
    val voices: StateFlow<List<String>> = _voices

    private val _extractionError = MutableStateFlow<String?>(null)
    val extractionError: StateFlow<String?> = _extractionError

    init {
        _voices.value = voiceRepository.availableVoices().ifEmpty { listOf("af_heart") }
    }

    fun prepareChunksFromText(text: String, onReady: (List<String>) -> Unit) {
        viewModelScope.launch {
            val chunks = withContext(Dispatchers.Default) {
                val sanitized = TextSanitizer.sanitize(text)
                SentenceChunker.chunk(sanitized).map { it.text }
            }
            onReady(chunks)
        }
    }

    fun prepareChunksFromHtml(html: String, onReady: (List<String>) -> Unit) {
        viewModelScope.launch {
            val chunks = withContext(Dispatchers.Default) {
                val extracted = HtmlExtractor.extract(html)
                val sanitized = TextSanitizer.sanitize(extracted)
                SentenceChunker.chunk(sanitized).map { it.text }
            }
            onReady(chunks)
        }
    }

    fun prepareChunksFromPdf(uri: Uri, onReady: (List<String>) -> Unit) {
        viewModelScope.launch {
            try {
                val chunks = withContext(Dispatchers.IO) {
                    val extracted = PdfExtractor.extract(getApplication(), uri)
                    val sanitized = TextSanitizer.sanitize(extracted)
                    SentenceChunker.chunk(sanitized).map { it.text }
                }
                onReady(chunks)
            } catch (e: Exception) {
                _extractionError.value = e.message ?: "Failed to read PDF"
            }
        }
    }
}
