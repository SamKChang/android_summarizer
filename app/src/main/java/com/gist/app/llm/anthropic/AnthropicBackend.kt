package com.gist.app.llm.anthropic

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
import kotlinx.serialization.json.addJsonObject
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

/**
 * Anthropic backend (`POST /v1/messages`, `stream: true`) per §6.2.
 * Uses prompt caching (`cache_control: {"type": "ephemeral"}`) on the article block and supports
 * the `web_search` server tool with `web_search_result_location` citations.
 */
@Singleton
class AnthropicBackend @Inject constructor(
    private val httpClient: HttpClient,
    private val secureStore: SecureStore,
) : LlmBackend {

    override val id: BackendId = BackendId.ANTHROPIC

    override val capabilities = Capabilities(
        streaming = true,
        webSearch = true,
        maxContextTokens = 200_000,
        onDevice = false,
        inAppChat = true,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun getApiKey(): String? = secureStore.getSecret(SECRET_KEY_ANTHROPIC)?.takeIf { it.isNotBlank() }

    override suspend fun isAvailable(): Availability {
        return if (getApiKey() != null) {
            Availability.Available
        } else {
            Availability.Unavailable("Anthropic API key is not configured", ErrorKind.AUTH)
        }
    }

    override suspend fun fetchModels(): List<String> {
        val apiKey = getApiKey() ?: return emptyList()
        return try {
            val response = httpClient.get("https://api.anthropic.com/v1/models") {
                header("x-api-key", apiKey)
                header("anthropic-version", ANTHROPIC_VERSION)
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
        val apiKey = getApiKey()
        if (apiKey == null) {
            emit(ChatEvent.Error(ErrorKind.AUTH, "Missing Anthropic API key. Open Settings to configure your key."))
            return@flow
        }

        val payload = buildRequestPayload(request)

        try {
            httpClient.preparePost("https://api.anthropic.com/v1/messages") {
                header("x-api-key", apiKey)
                header("anthropic-version", ANTHROPIC_VERSION)
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }.execute { response ->
                if (response.status != HttpStatusCode.OK) {
                    val errText = runCatching { response.bodyAsText() }.getOrDefault("")
                    emit(mapHttpError(response.status, errText))
                    return@execute
                }

                val collectedCitations = linkedMapOf<String, Citation>()
                var inputTokens = 0
                var outputTokens = 0

                SseParser.parseChannel(response.bodyAsChannel()).collect { sse ->
                    val events = parseSseEvent(sse.event, sse.data)
                    for (ev in events) {
                        when (ev) {
                            is ChatEvent.Citations -> ev.items.forEach { collectedCitations[it.url] = it }
                            is ChatEvent.Usage -> {
                                if (ev.inputTokens > 0) inputTokens = ev.inputTokens
                                if (ev.outputTokens > 0) outputTokens = ev.outputTokens
                            }
                            else -> emit(ev)
                        }
                    }
                }

                if (collectedCitations.isNotEmpty()) {
                    emit(ChatEvent.Citations(collectedCitations.values.toList()))
                }
                if (inputTokens > 0 || outputTokens > 0) {
                    emit(ChatEvent.Usage(inputTokens, outputTokens))
                }
                emit(ChatEvent.Done)
            }
        } catch (e: Exception) {
            emit(ChatEvent.Error(ErrorKind.NETWORK, "Network error connecting to Anthropic: ${e.message ?: "Unknown"}"))
        }
    }

    internal fun buildRequestPayload(request: ChatRequest): JsonObject = buildJsonObject {
        put("model", request.model.ifBlank { "claude-sonnet-4-20250514" })
        put("max_tokens", request.maxOutputTokens ?: 2048)
        put("stream", true)
        request.temperature?.let { put("temperature", it) }

        if (request.system.isNotBlank()) {
            put("system", request.system)
        }

        putJsonArray("messages") {
            request.messages.forEachIndexed { idx, msg ->
                val role = if (msg.role == MessageRole.ASSISTANT) "assistant" else "user"
                addJsonObject {
                    put("role", role)
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", msg.content)
                            // Enable ephemeral prompt caching on the initial article block (§6.2)
                            if (idx == 0 && (msg.cacheablePrefix || msg.content.contains("<article"))) {
                                putJsonObject("cache_control") {
                                    put("type", "ephemeral")
                                }
                            }
                        }
                    }
                }
            }
        }

        if (request.webSearch) {
            putJsonArray("tools") {
                addJsonObject {
                    put("type", "web_search_20250305")
                    put("name", "web_search")
                }
            }
        }
    }

    internal fun parseSseEvent(eventType: String?, data: String): List<ChatEvent> {
        val events = mutableListOf<ChatEvent>()
        try {
            val root = json.parseToJsonElement(data).jsonObject
            val type = eventType ?: root["type"]?.jsonPrimitive?.content ?: ""

            when (type) {
                "message_start" -> {
                    val usage = root["message"]?.jsonObject?.get("usage")?.jsonObject
                    val inTok = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0
                    val outTok = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: 0
                    if (inTok > 0 || outTok > 0) {
                        events.add(ChatEvent.Usage(inTok, outTok))
                    }
                }
                "content_block_delta" -> {
                    val delta = root["delta"]?.jsonObject
                    val deltaType = delta?.get("type")?.jsonPrimitive?.content
                    if (deltaType == "text_delta") {
                        val text = delta["text"]?.jsonPrimitive?.content
                        if (!text.isNullOrEmpty()) {
                            events.add(ChatEvent.Delta(text))
                        }
                    } else if (deltaType == "citations_delta") {
                        val cit = delta["citation"]?.jsonObject
                        if (cit?.get("type")?.jsonPrimitive?.content == "web_search_result_location") {
                            val url = cit["url"]?.jsonPrimitive?.content
                            val title = cit["title"]?.jsonPrimitive?.content ?: url
                            if (!url.isNullOrBlank()) {
                                events.add(ChatEvent.Citations(listOf(Citation(title = title ?: url, url = url))))
                            }
                        }
                    }
                }
                "message_delta" -> {
                    val usage = root["usage"]?.jsonObject
                    val outTok = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: 0
                    if (outTok > 0) {
                        events.add(ChatEvent.Usage(0, outTok))
                    }
                }
                "error" -> {
                    val msg = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "Anthropic error"
                    events.add(ChatEvent.Error(ErrorKind.UNAVAILABLE, msg))
                }
            }
        } catch (_: Exception) {
        }
        return events
    }

    private fun mapHttpError(status: HttpStatusCode, body: String): ChatEvent.Error {
        return when (status.value) {
            401, 403 -> ChatEvent.Error(ErrorKind.AUTH, "Invalid Anthropic API key (${status.value}).")
            429 -> ChatEvent.Error(ErrorKind.RATE_LIMIT, "Anthropic rate limit exceeded.", retryAfterSeconds = 5)
            400 -> if (body.contains("prompt is too long", ignoreCase = true)) {
                ChatEvent.Error(ErrorKind.CONTEXT_TOO_LONG, "Prompt exceeds Claude's context window.")
            } else {
                ChatEvent.Error(ErrorKind.UNAVAILABLE, "Anthropic error 400: ${body.take(160)}")
            }
            else -> ChatEvent.Error(ErrorKind.NETWORK, "Anthropic HTTP ${status.value}: ${body.take(160)}")
        }
    }

    companion object {
        const val SECRET_KEY_ANTHROPIC = "api_key_anthropic"
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
