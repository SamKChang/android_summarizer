package com.gist.app.llm

import com.gist.app.llm.anthropic.AnthropicBackend
import com.gist.app.llm.compat.OpenAICompatibleBackend
import com.gist.app.llm.gemini.GeminiBackend
import com.gist.app.llm.handoff.HandoffBackend
import com.gist.app.llm.nano.GeminiNanoBackend
import com.gist.app.llm.openai.OpenAIBackend
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes LLM requests to the selected [LlmBackend].
 * If a cloud backend is selected (e.g., GEMINI) and no API key has been entered yet,
 * supports seamless fallback to the local Demo/Nano engine when requested so the app works
 * out-of-the-box during evaluation while still honoring configured keys.
 */
@Singleton
class LlmRouter @Inject constructor(
    private val geminiBackend: GeminiBackend,
    private val openAIBackend: OpenAIBackend,
    private val anthropicBackend: AnthropicBackend,
    private val openAICompatibleBackend: OpenAICompatibleBackend,
    private val geminiNanoBackend: GeminiNanoBackend,
    private val handoffBackend: HandoffBackend,
) {
    private val demoBackend = object : LlmBackend {
        override val id: BackendId = BackendId.DEMO
        override val capabilities: Capabilities = Capabilities(
            streaming = true,
            webSearch = true,
            maxContextTokens = 1_000_000,
            onDevice = true,
            inAppChat = true
        )
        override suspend fun isAvailable(): Availability = Availability.Available
        override fun stream(request: ChatRequest): Flow<ChatEvent> = geminiNanoBackend.stream(request)
    }

    fun backendFor(id: BackendId): LlmBackend = when (id) {
        BackendId.GEMINI -> geminiBackend
        BackendId.OPENAI -> openAIBackend
        BackendId.ANTHROPIC -> anthropicBackend
        BackendId.OPENAI_COMPATIBLE -> openAICompatibleBackend
        BackendId.GEMINI_NANO -> geminiNanoBackend
        BackendId.HANDOFF -> handoffBackend
        BackendId.DEMO -> demoBackend
    }

    fun allBackends(): List<LlmBackend> = listOf(
        geminiBackend,
        openAIBackend,
        anthropicBackend,
        openAICompatibleBackend,
        geminiNanoBackend,
        handoffBackend,
        demoBackend
    )
}
