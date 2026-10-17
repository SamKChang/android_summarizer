package com.gist.app.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

enum class BackendId(val displayName: String, val isCloud: Boolean) {
    GEMINI("Gemini Flash", true),
    OPENAI("OpenAI", true),
    ANTHROPIC("Claude", true),
    OPENAI_COMPATIBLE("OpenRouter / Custom", true),
    GEMINI_NANO("Gemini Nano", false),
    HANDOFF("→ ChatGPT / Claude / Gemini App", false),
    DEMO("Gemini Flash (Demo)", false)
}

data class Capabilities(
    val streaming: Boolean,
    val webSearch: Boolean,
    val maxContextTokens: Int,
    val onDevice: Boolean,
    val inAppChat: Boolean,
)

sealed interface Availability {
    data object Available : Availability
    data class Unavailable(val reason: String, val kind: ErrorKind = ErrorKind.UNAVAILABLE) : Availability
}

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

@Serializable
data class Citation(
    val title: String,
    val url: String
)

data class ChatMessage(
    val role: MessageRole,
    val content: String,
    val cacheablePrefix: Boolean = false
)

data class ChatRequest(
    val system: String,
    val messages: List<ChatMessage>,
    val model: String,
    val webSearch: Boolean,
    val temperature: Float? = 0.3f,
    val maxOutputTokens: Int? = 2048
)

enum class ErrorKind {
    AUTH,
    RATE_LIMIT,
    CONTEXT_TOO_LONG,
    NETWORK,
    SAFETY_BLOCKED,
    UNAVAILABLE
}

sealed interface ChatEvent {
    data class Delta(val text: String) : ChatEvent
    data class Citations(val items: List<Citation>) : ChatEvent
    data class Usage(val inputTokens: Int, val outputTokens: Int) : ChatEvent
    data class Error(val kind: ErrorKind, val message: String, val retryAfterSeconds: Int? = null) : ChatEvent
    data object Done : ChatEvent
}

interface LlmBackend {
    val id: BackendId
    val capabilities: Capabilities
    suspend fun isAvailable(): Availability
    suspend fun fetchModels(): List<String> = emptyList()
    fun stream(request: ChatRequest): Flow<ChatEvent>
}
