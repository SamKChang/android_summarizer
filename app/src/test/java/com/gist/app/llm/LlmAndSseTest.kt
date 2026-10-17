package com.gist.app.llm

import com.gist.app.chat.ContextBudgeter
import com.gist.app.data.SecureStore
import com.gist.app.llm.anthropic.AnthropicBackend
import com.gist.app.llm.gemini.GeminiBackend
import com.gist.app.llm.openai.OpenAIBackend
import com.gist.app.llm.sse.SseParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeSecureStore(initial: Map<String, String> = emptyMap()) : SecureStore {
    private val map = initial.toMutableMap()
    override fun getSecret(key: String): String? = map[key]
    override fun putSecret(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
    override fun clearAll() = map.clear()
}

class LlmAndSseTest {

    @Test
    fun `SseParser handles arbitrary chunk boundaries, multi-line data, comments, and DONE`() {
        val rawChunks = listOf(
            ": keep-alive comment\n",
            "event: content_block_",
            "delta\ndata: {\"part\": 1,\n",
            "data: \"text\": \"Hello\"}\n\n",
            "data: {\"text\": \" world\"}\r\n\r\n",
            "data: [DONE]\n\n"
        )

        val events = SseParser.parseChunks(rawChunks)
        assertEquals(2, events.size)
        assertEquals("content_block_delta", events[0].event)
        assertEquals("{\"part\": 1,\n\"text\": \"Hello\"}", events[0].data)
        assertEquals(null, events[1].event)
        assertEquals("{\"text\": \" world\"}", events[1].data)
    }

    @Test
    fun `GeminiBackend streams SSE tokens and extracts google_search grounding citations`() = runTest {
        val ssePayload = """
            data: {"candidates":[{"content":{"parts":[{"text":"### TL;DR Summary\n- The Fed pivoted"}]}}]}

            data: {"candidates":[{"content":{"parts":[{"text":" with a 50 bps cut."}],"role":"model"},"groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://www.federalreserve.gov/pr","title":"Federal Reserve PR"}}]}}],"usageMetadata":{"promptTokenCount":120,"candidatesTokenCount":35}}

        """.trimIndent() + "\n\n"

        val mockClient = HttpClient(MockEngine) {
            engine {
                addHandler { req ->
                    assertEquals("test-gemini-key", req.headers["x-goog-api-key"])
                    respond(
                        content = ssePayload,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
                    )
                }
            }
        }

        val store = FakeSecureStore(mapOf(GeminiBackend.SECRET_KEY_GEMINI to "test-gemini-key"))
        val backend = GeminiBackend(mockClient, store)

        val request = ChatRequest(
            system = "System prompt",
            messages = listOf(ChatMessage(MessageRole.USER, "Summarize")),
            model = "gemini-2.5-flash",
            webSearch = true
        )

        val events = backend.stream(request).toList()
        val deltas = events.filterIsInstance<ChatEvent.Delta>().joinToString("") { it.text }
        assertEquals("### TL;DR Summary\n- The Fed pivoted with a 50 bps cut.", deltas)

        val citations = events.filterIsInstance<ChatEvent.Citations>().flatMap { it.items }
        assertEquals(1, citations.size)
        assertEquals("Federal Reserve PR", citations[0].title)
        assertEquals("https://www.federalreserve.gov/pr", citations[0].url)

        val usage = events.filterIsInstance<ChatEvent.Usage>().single()
        assertEquals(120, usage.inputTokens)
        assertEquals(35, usage.outputTokens)
        assertTrue(events.last() is ChatEvent.Done)
    }

    @Test
    fun `OpenAIBackend builds Responses API payload with web_search tool and parses url_citation`() = runTest {
        val ssePayload = """
            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":"Inflation cooled to 2%."}

            event: response.output_text.annotation.added
            data: {"type":"response.output_text.annotation.added","annotation":{"type":"url_citation","url":"https://bls.gov/cpi","title":"BLS CPI Report"}}

            event: response.completed
            data: {"type":"response.completed","response":{"usage":{"input_tokens":200,"output_tokens":40}}}

        """.trimIndent() + "\n\n"

        val mockClient = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = ssePayload,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
                    )
                }
            }
        }

        val store = FakeSecureStore(mapOf(OpenAIBackend.SECRET_KEY_OPENAI to "sk-test"))
        val backend = OpenAIBackend(mockClient, store)
        val req = ChatRequest(
            system = "You are a reading assistant.",
            messages = listOf(ChatMessage(MessageRole.USER, "Check inflation")),
            model = "gpt-4o-mini",
            webSearch = true
        )

        val payload = backend.buildRequestPayload(req)
        assertEquals("gpt-4o-mini", payload["model"]?.jsonPrimitive?.content)
        assertEquals("web_search", payload["tools"]?.jsonArray?.get(0)?.jsonObject?.get("type")?.jsonPrimitive?.content)

        val events = backend.stream(req).toList()
        val text = events.filterIsInstance<ChatEvent.Delta>().joinToString("") { it.text }
        assertEquals("Inflation cooled to 2%.", text)
        val citations = events.filterIsInstance<ChatEvent.Citations>().flatMap { it.items }
        assertEquals(1, citations.size)
        assertEquals("BLS CPI Report", citations[0].title)
    }

    @Test
    fun `AnthropicBackend attaches ephemeral cache_control on article block and parses citations`() = runTest {
        val ssePayload = """
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":500,"output_tokens":1}}}

            event: content_block_delta
            data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Claude summary."}}

            event: content_block_delta
            data: {"type":"content_block_delta","delta":{"type":"citations_delta","citation":{"type":"web_search_result_location","url":"https://ft.com/fed","title":"FT Fed Coverage"}}}

        """.trimIndent() + "\n\n"

        val mockClient = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = ssePayload,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
                    )
                }
            }
        }

        val store = FakeSecureStore(mapOf(AnthropicBackend.SECRET_KEY_ANTHROPIC to "sk-ant-test"))
        val backend = AnthropicBackend(mockClient, store)
        val req = ChatRequest(
            system = "System",
            messages = listOf(ChatMessage(MessageRole.USER, "<article>Text</article>\nSummarize", cacheablePrefix = true)),
            model = "claude-sonnet-4-20250514",
            webSearch = true
        )

        val payload = backend.buildRequestPayload(req)
        val firstContentBlock = payload["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("ephemeral", firstContentBlock["cache_control"]?.jsonObject?.get("type")?.jsonPrimitive?.content)

        val events = backend.stream(req).toList()
        val text = events.filterIsInstance<ChatEvent.Delta>().joinToString("") { it.text }
        assertEquals("Claude summary.", text)
        val citations = events.filterIsInstance<ChatEvent.Citations>().flatMap { it.items }
        assertEquals("FT Fed Coverage", citations.single().title)
    }

    @Test
    fun `ContextBudgeter triggers map-reduce when tokens exceed 70 percent budget or on Nano`() {
        val mediumArticle = "word ".repeat(1500) // ~7500 chars -> ~1875 tokens
        assertTrue(
            ContextBudgeter.needsMapReduce(
                articleText = mediumArticle,
                historyMessages = emptyList(),
                maxContextTokens = 4096,
                isOnDeviceNano = true
            )
        )
        assertFalse(
            ContextBudgeter.needsMapReduce(
                articleText = mediumArticle,
                historyMessages = emptyList(),
                maxContextTokens = 128_000,
                isOnDeviceNano = false
            )
        )

        val multiParagraphArticle = (1..20).joinToString("\n\n") { "Paragraph $it ".repeat(200) }
        val chunks = ContextBudgeter.chunkByParagraphs(multiParagraphArticle, maxTokensPerChunk = 1000)
        assertTrue(chunks.size >= 4)
    }
}
