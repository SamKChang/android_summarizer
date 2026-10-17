package com.gist.app.capture

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.gist.app.a11y.GistAccessibilityService
import com.gist.app.overlay.OverlayManager
import com.gist.app.settings.SettingsActivity
import com.gist.app.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles `Intent.ACTION_SEND` ("Share → Gist") and `Intent.ACTION_PROCESS_TEXT` ("Ask Gist") (§5.2).
 * Captures the shared URL/text and opens the chat sheet (or `SettingsActivity` if the accessibility overlay
 * is not yet active).
 */
@AndroidEntryPoint
class ShareIntakeActivity : ComponentActivity() {

    @Inject lateinit var captureEngine: CaptureEngine
    @Inject lateinit var overlayManager: OverlayManager
    @Inject lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedText = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }

        if (sharedText.isNullOrBlank()) {
            finish()
            return
        }

        val referrerPkg = referrer?.host ?: "android.intent.share"

        scope.launch {
            val maxWords = settingsRepository.settingsFlow.first().maxArticleWords
            val doc = captureEngine.captureSharedContent(
                sharedText = sharedText,
                sourcePackage = referrerPkg,
                maxWords = maxWords
            )
            overlayManager.openWithSharedDocument(doc)
            if (!GistAccessibilityService.isServiceEnabled) {
                startActivity(
                    Intent(this@ShareIntakeActivity, SettingsActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
            finish()
        }
    }
}
