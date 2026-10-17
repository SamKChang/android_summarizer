package com.gist.app.llm.handoff

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.gist.app.llm.Availability
import com.gist.app.llm.BackendId
import com.gist.app.llm.Capabilities
import com.gist.app.llm.ChatEvent
import com.gist.app.llm.ChatRequest
import com.gist.app.llm.LlmBackend
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

enum class HandoffApp(val id: String, val displayName: String, val packageName: String) {
    CHATGPT("chatgpt-app", "ChatGPT", "com.openai.chatgpt"),
    CLAUDE("claude-app", "Claude", "com.anthropic.claude"),
    GEMINI("gemini-app", "Gemini", "com.google.android.apps.bard");

    companion object {
        fun fromModelOrPackage(value: String): HandoffApp {
            return entries.firstOrNull { it.id == value || it.packageName == value } ?: CHATGPT
        }
    }
}

/**
 * Hand-off backend (§3.5, §6.2) that sends the prompt + source URL + article text (trimmed to ~100 KB)
 * via `Intent.ACTION_SEND` (`text/plain`) targeted at the official ChatGPT / Claude / Gemini app,
 * falling back to the system share chooser if not installed.
 */
@Singleton
class HandoffBackend @Inject constructor(
    @ApplicationContext private val context: Context,
) : LlmBackend {

    override val id: BackendId = BackendId.HANDOFF

    override val capabilities = Capabilities(
        streaming = false,
        webSearch = false,
        maxContextTokens = 128_000,
        onDevice = false,
        inAppChat = false,
    )

    override suspend fun isAvailable(): Availability = Availability.Available

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        emit(ChatEvent.Delta("Handed off to external AI app."))
        emit(ChatEvent.Done)
    }

    fun isTargetInstalled(app: HandoffApp): Boolean {
        return try {
            context.packageManager.getPackageInfo(app.packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun buildHandoffPayload(
        prompt: String,
        title: String?,
        url: String?,
        articleText: String
    ): String {
        val header = buildString {
            appendLine(prompt.trim())
            appendLine()
            if (!title.isNullOrBlank()) appendLine("Title: $title")
            if (!url.isNullOrBlank()) appendLine("Source: $url")
            appendLine()
            appendLine("--- Article ---")
        }
        val maxArticleChars = (MAX_BINDER_SAFE_BYTES - header.toByteArray(Charsets.UTF_8).size).coerceAtLeast(1000)
        val trimmedArticle = if (articleText.length > maxArticleChars) {
            articleText.take(maxArticleChars) + "\n...[truncated]"
        } else {
            articleText
        }
        return header + trimmedArticle
    }

    fun launchHandoff(
        app: HandoffApp,
        prompt: String,
        title: String?,
        url: String?,
        articleText: String
    ) {
        val textPayload = buildHandoffPayload(prompt, title, url, articleText)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, textPayload)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (isTargetInstalled(app)) {
            sendIntent.setPackage(app.packageName)
            try {
                context.startActivity(sendIntent)
                return
            } catch (_: Exception) {
                // Fall through to chooser
            }
        }

        val chooser = Intent.createChooser(sendIntent, "Send article to ${app.displayName}").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    companion object {
        // Keep well under Android 1MB Binder transaction limit (~100 KB per §3.5)
        const val MAX_BINDER_SAFE_BYTES = 100 * 1024
    }
}
