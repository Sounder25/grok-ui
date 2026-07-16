package com.offlinetts.reader.ingestion

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/**
 * Extracts raw text from a local PDF using PDFBox-Android. Runs fully offline against a
 * content:// or file:// Uri already resolved by the caller (e.g. from a SEND intent).
 */
object PdfExtractor {

    fun extract(context: Context, uri: Uri, maxPages: Int = Int.MAX_VALUE): String {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open $uri" }
            PDDocument.load(input).use { document ->
                if (document.isEncrypted) {
                    throw SecurityException("Encrypted PDFs are not supported offline: $uri")
                }
                val stripper = PDFTextStripper().apply {
                    sortByPosition = true
                    startPage = 1
                    endPage = minOf(document.numberOfPages, maxPages)
                }
                return stripper.getText(document)
            }
        }
    }
}
