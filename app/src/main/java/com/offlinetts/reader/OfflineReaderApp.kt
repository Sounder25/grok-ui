package com.offlinetts.reader

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class OfflineReaderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // PDFBox-Android needs a Context to load its embedded font/glyph resources once per process.
        PDFBoxResourceLoader.init(applicationContext)
    }
}
