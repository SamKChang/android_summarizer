package com.gist.app.llm.compat

import com.gist.app.data.SecureStore
import com.gist.app.llm.Availability
import com.gist.app.llm.BackendId
import com.gist.app.llm.Capabilities
import com.gist.app.llm.ChatEvent
import com.gist.app.llm.ChatRequest
import com.gist.app.llm.ErrorKind
import com.gist.app.llm.LlmBackend
import com.gist.app.llm.MessageRole
import com.gist.app.llm.sse.SseParser
import com.gist.app.settings.SettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenAI-compatible Chat Completions backend (`POST {baseUrl}/chat/completions`) for OpenRouter, Ollama, vLLM, etc. (§6.2).
 * Web search toggle is disabled (`capabilities.webSearch = false`).
 */
@Singleton
class OpenAICompatibleBackend @Inject constructor(
    private val httpClient: HttpClient,
    private val secureStore: SecureStore,
    private val settingsRepository: SettingsRepository,
) : LlmBackend {

    override val id: BackendId = BackendId.OPENAI_COMPATIBLE

    override val capabilities = Capabilities(
        streaming = true,
        webSearch = false,
        maxContextTokens = 128_000,
        onDevice = false,
        inAppChat = true,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun getApiKey(): String? = secureStore.getSecret(SECRET_KEY_COMPAT)?.takeIf { it.isNotBlank() }

    override suspend fun isAvailable(): Availability {
        val baseUrl = settingsRepository.settingsFlow.first().openAiCompatibleBaseUrl
        val isLocal = baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1") || baseUrl.contains("10.0.2.2")
        return if (isLocal || getApiKey() != null) {
            Availability.Available
        } else {
            Availability.Unavailable("OpenAI-compatible API key is not configured", ErrorKind.AUTH)
        }
    }

    override suspend fun fetchModels(): List<String> {
        val baseUrl = settingsRepository.settingsFlow.first().openAiCompatibleBaseUrl.trimEnd('/')
        val apiKey = getApiKey()
        return try {
            val response = httpClient.get("$baseUrl/models") {
                if (apiKey != null) header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            if (response.status != HttpStatusCode.OK) return emptyList()
            val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
            root["data"]?.jsonArray?.mapNotNull { el ->
                el.jsonObject["id"]?.jsonPrimitive?.content
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        val baseUrl = settingsRepository.settingsFlow.first().openAiCompatibleBaseUrl.trimEnd('/')
        val apiKey = getApiKey()
        val payload = buildRequestPayload(request)

        try {
            httpClient.preparePost("$baseUrl/chat/completions") {
                if (apiKey != null) {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                }
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }.execute { response ->
                if (response.status != HttpStatusCode.OK) {
                    val errText = runCatching { response.bodyAsText() }.getOrDefault("")
                    val kind = when (response.status.value) {
                        401, 403 -> ErrorKind.AUTH
                        429 -> ErrorKind.RATE_LIMIT
                        else -> ErrorKind.NETWORK
                    }
                    emit(ChatEvent.Error(kind, "HTTP ${response.status.value}: ${errText.take(160)}"))
                    return@execute
                }

                SseParser.parseChannel(response.bodyAsChannel()).collect { sse ->
                    for (ev in parseSseChunk(sse.data)) {
                        emit(ev)
                    }
                }
                emit(ChatEvent.Done)
            }
        } catch (e: Exception) {
            emit(ChatEvent.Error(ErrorKind.NETWORK, "Network error: ${e.message ?: "Unknown"}"))
        }
    }

    internal fun buildRequestPayload(request: ChatRequest): JsonObject = buildJsonObject {
        put("model", request.model.ifBlank { "openai/gpt-4o-mini" })
        put("stream", true)
        request.temperature?.let { put("temperature", it) }
        request.maxOutputTokens?.let { put("max_tokens", it) }
        putJsonArray("messages") {
            if (request.system.isNotBlank()) {
                addJsonObject {
                    put("role", "system")
                    put("content", request.system)
                }
            }
            for (msg in request.messages) {
                val role = when (msg.role) {
                    MessageRole.ASSISTANT -> "assistant"
                    MessageRole.SYSTEM -> "system"
                    MessageRole.USER -> "user"
                }
                addJsonObject {
                    put("role", role)
                    put("content", msg.content)
                }
            }
        }
    }

    internal fun parseSseChunk(data: String): List<ChatEvent> {
        val events = mutableListOf<ChatEvent>()
        try {
            val root = json.parseToJsonElement(data).jsonObject
            val choices = root["choices"]?.jsonArray
            val delta = choices?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject
            val content = delta?.get("content")?.jsonPrimitive?.content
            if (!content.isNullOrEmpty()) {
                events.add(ChatEvent.Delta(content))
            }
            val usage = root["usage"]?.jsonObject
            if (usage != null) {
                val inTok = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                val outTok = usage["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                events.add(ChatEvent.Usage(inTok, outTok))
            }
        } catch (_: Exception) {
        }
        return events
    }

    companion object {
        const val SECRET_KEY_COMPAT = "api_key_openai_compat"
    }
}
