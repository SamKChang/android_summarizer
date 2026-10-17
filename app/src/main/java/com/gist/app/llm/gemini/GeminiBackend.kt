package com.gist.app.llm.gemini

import com.gist.app.data.SecureStore
import com.gist.app.llm.Availability
import com.gist.app.llm.BackendId
import com.gist.app.llm.Capabilities
import com.gist.app.llm.ChatEvent
import com.gist.app.llm.ChatRequest
import com.gist.app.llm.Citation
import com.gist.app.llm.ErrorKind
import com.gist.app.llm.LlmBackend
import com.gist.app.llm.MessageRole
import com.gist.app.llm.sse.SseParser
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeminiBackend @Inject constructor(
    private val httpClient: HttpClient,
    private val secureStore: SecureStore,
) : LlmBackend {

    override val id: BackendId = BackendId.GEMINI

    override val capabilities = Capabilities(
        streaming = true,
        webSearch = true,
        maxContextTokens = 1_000_000,
        onDevice = false,
        inAppChat = true,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun getApiKey(): String? = secureStore.getSecret(SECRET_KEY_GEMINI)?.takeIf { it.isNotBlank() }

    override suspend fun isAvailable(): Availability {
        return if (getApiKey() != null) {
            Availability.Available
        } else {
            Availability.Unavailable("Gemini API key is not configured", ErrorKind.AUTH)
        }
    }

    override suspend fun fetchModels(): List<String> {
        val apiKey = getApiKey() ?: return emptyList()
        return try {
            val response = httpClient.get("https://generativelanguage.googleapis.com/v1beta/models") {
                header("x-goog-api-key", apiKey)
            }
            if (response.status != HttpStatusCode.OK) return emptyList()
            val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
            root["models"]?.jsonArray?.mapNotNull { el ->
                val name = el.jsonObject["name"]?.jsonPrimitive?.content?.removePrefix("models/")
                if (name != null && name.startsWith("gemini")) name else null
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        val apiKey = getApiKey()
        if (apiKey == null) {
            emit(ChatEvent.Error(ErrorKind.AUTH, "Missing Gemini API key. Open Settings to configure your key."))
            return@flow
        }

        val modelId = request.model.ifBlank { "gemini-2.5-flash" }.removePrefix("models/")
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:streamGenerateContent?alt=sse"

        val payload = buildRequestPayload(request)

        try {
            httpClient.preparePost(url) {
                header("x-goog-api-key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }.execute { response ->
                if (response.status != HttpStatusCode.OK) {
                    val errText = runCatching { response.bodyAsText() }.getOrDefault("")
                    emit(mapHttpError(response.status, errText))
                    return@execute
                }

                val collectedCitations = linkedMapOf<String, Citation>()
                var lastInputTokens = 0
                var lastOutputTokens = 0

                SseParser.parseChannel(response.bodyAsChannel()).collect { sse ->
                    val parsed = parseSseChunk(sse.data)
                    for (ev in parsed) {
                        when (ev) {
                            is ChatEvent.Citations -> {
                                ev.items.forEach { collectedCitations[it.url] = it }
                            }
                            is ChatEvent.Usage -> {
                                lastInputTokens = ev.inputTokens
                                lastOutputTokens = ev.outputTokens
                            }
                            else -> emit(ev)
                        }
                    }
                }

                if (collectedCitations.isNotEmpty()) {
                    emit(ChatEvent.Citations(collectedCitations.values.toList()))
                }
                if (lastInputTokens > 0 || lastOutputTokens > 0) {
                    emit(ChatEvent.Usage(lastInputTokens, lastOutputTokens))
                }
                emit(ChatEvent.Done)
            }
        } catch (e: Exception) {
            emit(ChatEvent.Error(ErrorKind.NETWORK, "Network error connecting to Gemini: ${e.message ?: "Unknown"}"))
        }
    }

    internal fun buildRequestPayload(request: ChatRequest): JsonObject = buildJsonObject {
        if (request.system.isNotBlank()) {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") {
                    addJsonObject { put("text", request.system) }
                }
            }
        }
        putJsonArray("contents") {
            for (msg in request.messages) {
                val role = if (msg.role == MessageRole.ASSISTANT) "model" else "user"
                addJsonObject {
                    put("role", role)
                    putJsonArray("parts") {
                        addJsonObject { put("text", msg.content) }
                    }
                }
            }
        }
        if (request.webSearch) {
            putJsonArray("tools") {
                addJsonObject {
                    putJsonObject("google_search") {}
                }
            }
        }
        putJsonObject("generationConfig") {
            request.temperature?.let { put("temperature", it) }
            request.maxOutputTokens?.let { put("maxOutputTokens", it) }
        }
    }

    internal fun parseSseChunk(data: String): List<ChatEvent> {
        val events = mutableListOf<ChatEvent>()
        try {
            val root = json.parseToJsonElement(data).jsonObject
            val candidates = root["candidates"]?.jsonArray
            val firstCandidate = candidates?.firstOrNull()?.jsonObject
            if (firstCandidate != null) {
                val finishReason = firstCandidate["finishReason"]?.jsonPrimitive?.content
                if (finishReason == "SAFETY" || finishReason == "BLOCKLIST") {
                    events.add(ChatEvent.Error(ErrorKind.SAFETY_BLOCKED, "Response blocked by Gemini safety filters."))
                }
                val parts = firstCandidate["content"]?.jsonObject?.get("parts")?.jsonArray
                parts?.forEach { part ->
                    val text = part.jsonObject["text"]?.jsonPrimitive?.content
                    if (!text.isNullOrEmpty()) {
                        events.add(ChatEvent.Delta(text))
                    }
                }
                val grounding = firstCandidate["groundingMetadata"]?.jsonObject
                val chunks = grounding?.get("groundingChunks")?.jsonArray
                val citations = chunks?.mapNotNull { ch ->
                    val web = ch.jsonObject["web"]?.jsonObject ?: return@mapNotNull null
                    val uri = web["uri"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val title = web["title"]?.jsonPrimitive?.content ?: uri
                    Citation(title = title, url = uri)
                } ?: emptyList()
                if (citations.isNotEmpty()) {
                    events.add(ChatEvent.Citations(citations))
                }
            }

            val usage = root["usageMetadata"]?.jsonObject
            if (usage != null) {
                val promptTokens = usage["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
                val candidateTokens = usage["candidatesTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
                events.add(ChatEvent.Usage(promptTokens, candidateTokens))
            }
        } catch (_: Exception) {
        }
        return events
    }

    private fun mapHttpError(status: HttpStatusCode, body: String): ChatEvent.Error {
        return when (status.value) {
            401, 403 -> ChatEvent.Error(ErrorKind.AUTH, "Invalid Gemini API key (${status.value}). Check Settings.")
            429 -> ChatEvent.Error(ErrorKind.RATE_LIMIT, "Gemini rate limit reached. Retrying shortly...", retryAfterSeconds = 5)
            400 -> if (body.contains("token", ignoreCase = true)) {
                ChatEvent.Error(ErrorKind.CONTEXT_TOO_LONG, "Article exceeds model context window.")
            } else {
                ChatEvent.Error(ErrorKind.UNAVAILABLE, "Gemini error 400: ${body.take(160)}")
            }
            else -> ChatEvent.Error(ErrorKind.NETWORK, "Gemini HTTP ${status.value}: ${body.take(160)}")
        }
    }

    companion object {
        const val SECRET_KEY_GEMINI = "api_key_gemini"
    }
}
