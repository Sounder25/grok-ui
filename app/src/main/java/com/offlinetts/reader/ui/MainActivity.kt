package com.offlinetts.reader.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.offlinetts.reader.R
import com.offlinetts.reader.databinding.ActivityMainBinding
import com.offlinetts.reader.service.TtsForegroundService
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermissionIfNeeded()
        observeVoices()
        wireButtons()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun observeVoices() {
        viewModel.voices.onEach { voices ->
            binding.voiceSpinner.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item, voices,
            )
        }.launchIn(lifecycleScope)
    }

    private fun wireButtons() {
        binding.readButton.setOnClickListener {
            val text = binding.inputText.text?.toString().orEmpty()
            if (text.isBlank()) return@setOnClickListener
            viewModel.prepareChunksFromText(text) { chunks -> startReading(chunks) }
        }
        binding.stopButton.setOnClickListener {
            TtsForegroundService.stop(this)
            binding.statusText.setText(R.string.status_idle)
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        when (intent.type) {
            "text/plain" -> {
                val shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                binding.inputText.setText(shared)
                if (looksLikeHtml(shared)) {
                    viewModel.prepareChunksFromHtml(shared) { startReading(it) }
                } else {
                    viewModel.prepareChunksFromText(shared) { startReading(it) }
                }
            }
            "application/pdf" -> {
                val uri = intent.getParcelableExtraCompat(Intent.EXTRA_STREAM, Uri::class.java)
                if (uri != null) viewModel.prepareChunksFromPdf(uri) { startReading(it) }
            }
        }
    }

    private fun looksLikeHtml(text: String) =
        text.contains("<html", ignoreCase = true) || text.contains("</p>", ignoreCase = true)

    private fun startReading(chunks: List<String>) {
        if (chunks.isEmpty()) return
        val voice = binding.voiceSpinner.selectedItem as? String ?: "af_heart"
        TtsForegroundService.start(this, chunks, voice)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ActivityCompat.checkSelfPermission(
            this, android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

private fun <T> Intent.getParcelableExtraCompat(name: String, clazz: Class<T>): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, clazz)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name)
    }
