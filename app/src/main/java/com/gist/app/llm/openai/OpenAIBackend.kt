package com.gist.app.llm.openai

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
import io.ktor.http.HttpHeaders
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenAI backend using the Responses API (`POST /v1/responses`, `stream: true`) per §6.2.
 * Supports `tools: [{type: "web_search"}]` and extracts `url_citation` annotations.
 */
@Singleton
class OpenAIBackend @Inject constructor(
    private val httpClient: HttpClient,
    private val secureStore: SecureStore,
) : LlmBackend {

    override val id: BackendId = BackendId.OPENAI

    override val capabilities = Capabilities(
        streaming = true,
        webSearch = true,
        maxContextTokens = 128_000,
        onDevice = false,
        inAppChat = true,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun getApiKey(): String? = secureStore.getSecret(SECRET_KEY_OPENAI)?.takeIf { it.isNotBlank() }

    override suspend fun isAvailable(): Availability {
        return if (getApiKey() != null) {
            Availability.Available
        } else {
            Availability.Unavailable("OpenAI API key is not configured", ErrorKind.AUTH)
        }
    }

    override suspend fun fetchModels(): List<String> {
        val apiKey = getApiKey() ?: return emptyList()
        return try {
            val response = httpClient.get("https://api.openai.com/v1/models") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            if (response.status != HttpStatusCode.OK) return emptyList()
            val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
            root["data"]?.jsonArray?.mapNotNull { el ->
                val id = el.jsonObject["id"]?.jsonPrimitive?.content
                if (id != null && (id.startsWith("gpt-4") || id.startsWith("o1") || id.startsWith("o3"))) id else null
            }?.sorted() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        val apiKey = getApiKey()
        if (apiKey == null) {
            emit(ChatEvent.Error(ErrorKind.AUTH, "Missing OpenAI API key. Open Settings to configure your key."))
            return@flow
        }

        val payload = buildRequestPayload(request)

        try {
            httpClient.preparePost("https://api.openai.com/v1/responses") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
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
                                inputTokens = ev.inputTokens
                                outputTokens = ev.outputTokens
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
            emit(ChatEvent.Error(ErrorKind.NETWORK, "Network error connecting to OpenAI: ${e.message ?: "Unknown"}"))
        }
    }

    internal fun buildRequestPayload(request: ChatRequest): JsonObject = buildJsonObject {
        put("model", request.model.ifBlank { "gpt-4o-mini" })
        put("stream", true)
        if (request.system.isNotBlank()) {
            put("instructions", request.system)
        }
        putJsonArray("input") {
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
        if (request.webSearch) {
            putJsonArray("tools") {
                addJsonObject {
                    put("type", "web_search")
                }
            }
        }
        request.temperature?.let { put("temperature", it) }
        request.maxOutputTokens?.let { put("max_output_tokens", it) }
    }

    internal fun parseSseEvent(eventType: String?, data: String): List<ChatEvent> {
        val events = mutableListOf<ChatEvent>()
        try {
            val root = json.parseToJsonElement(data).jsonObject
            val type = eventType ?: root["type"]?.jsonPrimitive?.content ?: ""

            when (type) {
                "response.output_text.delta" -> {
                    val delta = root["delta"]?.jsonPrimitive?.content
                    if (!delta.isNullOrEmpty()) {
                        events.add(ChatEvent.Delta(delta))
                    }
                }
                "response.output_text.annotation.added" -> {
                    val ann = root["annotation"]?.jsonObject
                    if (ann?.get("type")?.jsonPrimitive?.content == "url_citation") {
                        val url = ann["url"]?.jsonPrimitive?.content
                        val title = ann["title"]?.jsonPrimitive?.content ?: url
                        if (!url.isNullOrBlank()) {
                            events.add(ChatEvent.Citations(listOf(Citation(title = title ?: url, url = url))))
                        }
                    }
                }
                "response.completed" -> {
                    val resp = root["response"]?.jsonObject ?: root
                    val usage = resp["usage"]?.jsonObject
                    if (usage != null) {
                        val inTok = usage["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                        val outTok = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                        events.add(ChatEvent.Usage(inTok, outTok))
                    }
                    // Also check output items for url_citation annotations
                    resp["output"]?.jsonArray?.forEach { outItem ->
                        outItem.jsonObject["content"]?.jsonArray?.forEach { contentItem ->
                            contentItem.jsonObject["annotations"]?.jsonArray?.forEach { annEl ->
                                val ann = annEl.jsonObject
                                if (ann["type"]?.jsonPrimitive?.content == "url_citation") {
                                    val url = ann["url"]?.jsonPrimitive?.content
                                    val title = ann["title"]?.jsonPrimitive?.content ?: url
                                    if (!url.isNullOrBlank()) {
                                        events.add(ChatEvent.Citations(listOf(Citation(title = title ?: url, url = url))))
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    // Fallback support if a standard chat.completion.chunk is received
                    val choices = root["choices"]?.jsonArray
                    val firstDelta = choices?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject
                    val content = firstDelta?.get("content")?.jsonPrimitive?.content
                    if (!content.isNullOrEmpty()) {
                        events.add(ChatEvent.Delta(content))
                    }
                }
            }
        } catch (_: Exception) {
        }
        return events
    }

    private fun mapHttpError(status: HttpStatusCode, body: String): ChatEvent.Error {
        return when (status.value) {
            401, 403 -> ChatEvent.Error(ErrorKind.AUTH, "Invalid OpenAI API key (${status.value}).")
            429 -> ChatEvent.Error(ErrorKind.RATE_LIMIT, "OpenAI rate limit exceeded.", retryAfterSeconds = 5)
            400 -> if (body.contains("context_length", ignoreCase = true)) {
                ChatEvent.Error(ErrorKind.CONTEXT_TOO_LONG, "Context length exceeded.")
            } else {
                ChatEvent.Error(ErrorKind.UNAVAILABLE, "OpenAI error 400: ${body.take(160)}")
            }
            else -> ChatEvent.Error(ErrorKind.NETWORK, "OpenAI HTTP ${status.value}: ${body.take(160)}")
        }
    }

    companion object {
        const val SECRET_KEY_OPENAI = "api_key_openai"
    }
}
