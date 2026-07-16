package com.offlinetts.reader.ingestion

import android.content.Context
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * JNI front for the bundled espeak-ng shared library. Converts normalized text into an
 * IPA phoneme string so Kokoro never has to guess pronunciation of numbers, abbreviations,
 * or dates — that guesswork is exactly what produces hallucinated audio.
 *
 * espeak-ng keeps global mutable state in the C library (current voice, its own data
 * directory handle), so all native calls are serialized behind [lock]; the synthesis
 * worker (see [com.offlinetts.reader.pipeline.SynthesisWorker]) calls into this from a
 * single dedicated thread/dispatcher, but the lock makes that a documented invariant
 * rather than an assumption.
 */
class Phonemizer private constructor() {

    private val lock = ReentrantLock()
    @Volatile private var initialized = false

    fun initialize(context: Context, voice: String = "en-us"): Boolean = lock.withLock {
        if (initialized) return true
        val dataDir = ensureEspeakDataExtracted(context)
        initialized = nativeInit(dataDir.absolutePath) && nativeSetVoice(voice)
        initialized
    }

    /** Returns IPA phonemes, sentence-clause boundaries preserved as `|`. */
    fun toPhonemes(text: String): String = lock.withLock {
        check(initialized) { "Phonemizer.initialize() must succeed before use" }
        nativeTextToPhonemes(text)
    }

    fun setVoice(voice: String): Boolean = lock.withLock { nativeSetVoice(voice) }

    /**
     * espeak-ng expects its voice/language data files on disk under a real directory (it
     * mmaps them by path, not by file descriptor), so we unpack the assets bundled at
     * `assets/espeak-ng-data/` into app-private storage once and reuse it thereafter.
     */
    private fun ensureEspeakDataExtracted(context: Context): File {
        val target = File(context.filesDir, "espeak-ng-data")
        val marker = File(target, ".extracted")
        if (marker.exists()) return target

        target.deleteRecursively()
        target.mkdirs()
        val assetManager = context.assets
        fun copyDir(assetPath: String, outDir: File) {
            outDir.mkdirs()
            val entries = assetManager.list(assetPath) ?: return
            if (entries.isEmpty()) {
                assetManager.open(assetPath).use { input ->
                    File(outDir, assetPath.substringAfterLast('/')).outputStream().use { input.copyTo(it) }
                }
                return
            }
            for (entry in entries) {
                val childAssetPath = "$assetPath/$entry"
                val childOut = File(outDir, entry)
                val childEntries = assetManager.list(childAssetPath)
                if (childEntries.isNullOrEmpty()) {
                    assetManager.open(childAssetPath).use { input ->
                        childOut.outputStream().use { input.copyTo(it) }
                    }
                } else {
                    copyDir(childAssetPath, childOut)
                }
            }
        }
        copyDir("espeak-ng-data", target)
        marker.writeText("ok")
        return target
    }

    private external fun nativeInit(dataPath: String): Boolean
    private external fun nativeSetVoice(voice: String): Boolean
    private external fun nativeTextToPhonemes(text: String): String

    companion object {
        @Volatile private var instance: Phonemizer? = null

        fun getInstance(): Phonemizer = instance ?: synchronized(this) {
            instance ?: Phonemizer().also { instance = it }
        }

        init {
            System.loadLibrary("offlinetts_phonemizer")
        }
    }
}
