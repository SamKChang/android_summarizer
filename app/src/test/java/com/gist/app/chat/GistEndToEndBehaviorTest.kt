package com.gist.app.chat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gist.app.capture.A11yNodeSnapshot
import com.gist.app.capture.CaptureEngine
import com.gist.app.capture.CaptureMethod
import com.gist.app.capture.CapturedDocument
import com.gist.app.capture.Completeness
import com.gist.app.capture.ReadabilityExtractor
import com.gist.app.data.GistDatabase
import com.gist.app.data.MessageDao
import com.gist.app.data.SessionDao
import com.gist.app.llm.BackendId
import com.gist.app.llm.FakeSecureStore
import com.gist.app.llm.LlmRouter
import com.gist.app.llm.MessageRole
import com.gist.app.llm.anthropic.AnthropicBackend
import com.gist.app.llm.compat.OpenAICompatibleBackend
import com.gist.app.llm.gemini.GeminiBackend
import com.gist.app.llm.handoff.HandoffApp
import com.gist.app.llm.handoff.HandoffBackend
import com.gist.app.llm.nano.GeminiNanoBackend
import com.gist.app.llm.openai.OpenAIBackend
import com.gist.app.settings.SettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GistEndToEndBehaviorTest {

    private lateinit var context: Context
    private lateinit var db: GistDatabase
    private lateinit var sessionDao: SessionDao
    private lateinit var messageDao: MessageDao
    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, GistDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sessionDao = db.sessionDao()
        messageDao = db.messageDao()
        settingsRepository = SettingsRepository(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun waitForStreamDone(manager: ChatSessionManager, timeoutMs: Long = 5_000L) {
        val start = System.currentTimeMillis()
        shadowOf(Looper.getMainLooper()).idle()
        delay(30L)
        shadowOf(Looper.getMainLooper()).idle()
        while ((manager.uiState.value.isStreaming || manager.uiState.value.messages.isEmpty()) &&
            manager.uiState.value.needsCloudConsentFor == null &&
            (System.currentTimeMillis() - start) < timeoutMs
        ) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
            delay(25L)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
        }
    }

    private fun buildRouter(
        httpClient: HttpClient,
        secureStore: FakeSecureStore
    ): LlmRouter {
        return LlmRouter(
            geminiBackend = GeminiBackend(httpClient, secureStore),
            openAIBackend = OpenAIBackend(httpClient, secureStore),
            anthropicBackend = AnthropicBackend(httpClient, secureStore),
            openAICompatibleBackend = OpenAICompatibleBackend(httpClient, secureStore, settingsRepository),
            geminiNanoBackend = GeminiNanoBackend(context),
            handoffBackend = HandoffBackend(context)
        )
    }

    @Test
    fun `End-to-end flow - capture screen, stream auto-summary, ask ELI5 follow-up, persist and resume by docKey`() = runBlocking {
        // Pre-consent Gemini and configure mock Gemini SSE server
        settingsRepository.recordCloudProviderConsent(BackendId.GEMINI)
        settingsRepository.setDefaultBackend(BackendId.GEMINI)

        var callCount = 0
        val mockHttp = HttpClient(MockEngine) {
            engine {
                addHandler {
                    callCount++
                    val sse = if (callCount == 1) {
                        """
                        data: {"candidates":[{"content":{"parts":[{"text":"### TL;DR Summary\n- The Federal Reserve enacted a 50 bps rate cut to 4.75%–5.00%."}]},"groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://www.federalreserve.gov/newsevents","title":"Federal Reserve PR"}}]}}],"usageMetadata":{"promptTokenCount":310,"candidatesTokenCount":42}}

                        """.trimIndent() + "\n\n"
                    } else {
                        """
                        data: {"candidates":[{"content":{"parts":[{"text":"Imagine a giant piggy bank: the Fed made it cheaper to borrow money so businesses can keep growing!"}]}}],"usageMetadata":{"promptTokenCount":380,"candidatesTokenCount":28}}

                        """.trimIndent() + "\n\n"
                    }
                    respond(
                        content = sse,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
                    )
                }
            }
        }

        val secureStore = FakeSecureStore(mapOf(GeminiBackend.SECRET_KEY_GEMINI to "AIzaSyMockKey"))
        val router = buildRouter(mockHttp, secureStore)
        val manager = ChatSessionManager(sessionDao, messageDao, router, settingsRepository)
        val captureEngine = CaptureEngine(ReadabilityExtractor(mockHttp))

        // 1. Capture article from Accessibility snapshot
        val paragraphs = (1..25).map { idx ->
            A11yNodeSnapshot(
                className = "android.widget.TextView",
                text = "Paragraph $idx explains how the Federal Reserve lowered the federal funds rate by 50 basis points as inflation cooled toward the two percent target."
            )
        }
        val snapshot = A11yNodeSnapshot(
            className = "android.widget.FrameLayout",
            packageName = "com.android.chrome",
            children = listOf(
                A11yNodeSnapshot(
                    viewIdResourceName = "com.android.chrome:id/url_bar",
                    text = "https://www.ft.com/markets/central-banks/fed-rate-cut?utm_source=twitter"
                ),
                A11yNodeSnapshot(
                    className = "android.webkit.WebView",
                    children = listOf(
                        A11yNodeSnapshot(
                            className = "android.widget.TextView",
                            text = "Federal Reserve Enacts Aggressive 50 bps Interest Rate Cut"
                        )
                    ) + paragraphs
                )
            )
        )

        val capturedDoc = captureEngine.captureFromSnapshot(snapshot, "com.android.chrome")
        assertEquals("https://ft.com/markets/central-banks/fed-rate-cut", capturedDoc.url)
        assertEquals(Completeness.LIKELY_COMPLETE, capturedDoc.completeness)

        // 2. Trigger onDocumentCaptured -> auto-runs default summary prompt without user bubble
        manager.onDocumentCaptured(capturedDoc)
        waitForStreamDone(manager)

        val stateAfterSummary = manager.uiState.value
        assertFalse(stateAfterSummary.isStreaming)
        assertEquals(1, stateAfterSummary.messages.size)
        val summaryMsg = stateAfterSummary.messages.single()
        assertEquals(MessageRole.ASSISTANT, summaryMsg.role)
        assertTrue(summaryMsg.content.contains("50 bps rate cut"))
        assertEquals(1, summaryMsg.citations.size)
        assertEquals("Federal Reserve PR", summaryMsg.citations.first().title)

        // 3. Tap quick-action chip "ELI5" -> sends user bubble + streams assistant follow-up
        manager.sendPrompt("Explain like I'm 5 (ELI5)", displayAsUserBubble = true)
        val followUpStart = System.currentTimeMillis()
        while ((manager.uiState.value.messages.size < 3 || manager.uiState.value.isStreaming) &&
            (System.currentTimeMillis() - followUpStart) < 5_000L
        ) {
            shadowOf(Looper.getMainLooper()).idle()
            delay(25L)
            shadowOf(Looper.getMainLooper()).idle()
        }

        val stateAfterFollowUp = manager.uiState.value
        assertEquals(3, stateAfterFollowUp.messages.size)
        assertEquals(MessageRole.USER, stateAfterFollowUp.messages[1].role)
        assertEquals("Explain like I'm 5 (ELI5)", stateAfterFollowUp.messages[1].content)
        assertEquals(MessageRole.ASSISTANT, stateAfterFollowUp.messages[2].role)
        assertTrue(stateAfterFollowUp.messages[2].content.contains("giant piggy bank"))
        assertEquals(2, callCount)

        // 4. Re-open bubble on same article (even with different utm parameters) -> restores existing session without new LLM call
        val sameArticleDifferentTracking = capturedDoc.copy(
            url = "https://ft.com/markets/central-banks/fed-rate-cut?utm_campaign=daily&fbclid=999"
        )
        assertEquals(capturedDoc.docKey, sameArticleDifferentTracking.docKey)

        manager.onDocumentCaptured(sameArticleDifferentTracking, forceNewSession = false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, callCount) // No extra LLM call!
        assertEquals(3, manager.uiState.value.messages.size)

        // 5. Re-capture with "Start new" (forceNewSession = true, keepMessagesOnRecapture = false)
        manager.onDocumentCaptured(capturedDoc, forceNewSession = true, keepMessagesOnRecapture = false)
        waitForStreamDone(manager)
        assertEquals(3, callCount)
        assertEquals(1, manager.uiState.value.messages.size)
    }

    @Test
    fun `One-time cloud provider consent gate pauses before sending and resumes upon confirmation`() = runBlocking {
        // Fresh settings: no cloud providers consented yet
        settingsRepository.clearCloudProviderConsents()
        settingsRepository.setDefaultBackend(BackendId.GEMINI)

        val mockHttp = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Consented summary.\"}]}}]}\n\n",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
                    )
                }
            }
        }

        val secureStore = FakeSecureStore(mapOf(GeminiBackend.SECRET_KEY_GEMINI to "AIzaSyKey"))
        val router = buildRouter(mockHttp, secureStore)
        val manager = ChatSessionManager(sessionDao, messageDao, router, settingsRepository)

        val doc = CapturedDocument(
            sourcePackage = "com.android.chrome",
            title = "Consent Test Article",
            url = "https://example.com/consent-test",
            text = "Article body text for testing cloud provider consent.",
            wordCount = 350,
            method = CaptureMethod.A11Y_TREE,
            completeness = Completeness.LIKELY_COMPLETE
        )

        manager.onDocumentCaptured(doc)
        waitForStreamDone(manager)

        // Must pause for consent on first use of GEMINI
        assertEquals(BackendId.GEMINI, manager.uiState.value.needsCloudConsentFor)
        assertTrue(manager.uiState.value.messages.isEmpty())

        // User confirms cloud consent
        manager.confirmCloudConsentAndProceed()
        waitForStreamDone(manager)

        assertNull(manager.uiState.value.needsCloudConsentFor)
        assertEquals(1, manager.uiState.value.messages.size)
        assertEquals("Consented summary.", manager.uiState.value.messages.single().content)
        assertTrue("GEMINI" in settingsRepository.settingsFlow.first().consentedCloudProviders)
    }

    @Test
    fun `HandoffBackend formats article and prompt within size limit and Handoff mode skips in-app streaming`() = runBlocking {
        settingsRepository.setDefaultBackend(BackendId.HANDOFF)
        val mockHttp = HttpClient(MockEngine { respond("") })
        val router = buildRouter(mockHttp, FakeSecureStore())
        val manager = ChatSessionManager(sessionDao, messageDao, router, settingsRepository)

        val doc = CapturedDocument(
            sourcePackage = "com.android.chrome",
            title = "Macroeconomic Outlook",
            url = "https://economist.com/outlook",
            text = "Key economic indicators point toward sustained disinflation.",
            wordCount = 400,
            method = CaptureMethod.A11Y_TREE,
            completeness = Completeness.LIKELY_COMPLETE
        )

        manager.onDocumentCaptured(doc)
        assertEquals(BackendId.HANDOFF, manager.uiState.value.backendId)
        assertTrue(manager.uiState.value.messages.isEmpty())
        assertFalse(manager.uiState.value.isStreaming)

        val handoffBackend = HandoffBackend(context)
        val payload = handoffBackend.buildHandoffPayload(
            prompt = "Summarize in 3 bullets:",
            title = doc.title,
            url = doc.url,
            articleText = doc.text
        )
        assertTrue(payload.contains("Title: Macroeconomic Outlook"))
        assertTrue(payload.contains("Source: https://economist.com/outlook"))
        assertTrue(payload.contains("Key economic indicators point toward sustained disinflation."))
        assertTrue(payload.startsWith("Summarize in 3 bullets:"))
        assertEquals("com.openai.chatgpt", HandoffApp.CHATGPT.packageName)
        assertEquals("com.anthropic.claude", HandoffApp.CLAUDE.packageName)
        assertEquals("com.google.android.apps.bard", HandoffApp.GEMINI.packageName)
    }

    @Test
    fun `ChatSessionManager automatically retries on HTTP 429 rate limit and completes summary`() = runBlocking {
        settingsRepository.recordCloudProviderConsent(BackendId.GEMINI)
        settingsRepository.setDefaultBackend(BackendId.GEMINI)

        var attempts = 0
        val mockHttp = HttpClient(MockEngine) {
            engine {
                addHandler {
                    attempts++
                    if (attempts == 1) {
                        respond(
                            content = """{"error":{"code":429,"message":"Rate limit exceeded"}}""",
                            status = HttpStatusCode.TooManyRequests,
                            headers = headersOf(HttpHeaders.RetryAfter, "1")
                        )
                    } else {
                        respond(
                            content = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Summary after retry.\"}]}}]}\n\n",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
                        )
                    }
                }
            }
        }

        val secureStore = FakeSecureStore(mapOf(GeminiBackend.SECRET_KEY_GEMINI to "AIzaSyRetryKey"))
        val router = buildRouter(mockHttp, secureStore)
        val manager = ChatSessionManager(sessionDao, messageDao, router, settingsRepository)

        val doc = CapturedDocument(
            sourcePackage = "com.android.chrome",
            title = "Retry Test Article",
            url = "https://example.com/retry-test",
            text = "Article body text for rate limit retry verification.",
            wordCount = 350,
            method = CaptureMethod.A11Y_TREE,
            completeness = Completeness.LIKELY_COMPLETE
        )

        manager.onDocumentCaptured(doc)
        waitForStreamDone(manager, timeoutMs = 8_000L)

        assertEquals(2, attempts)
        assertFalse(manager.uiState.value.isStreaming)
        assertEquals("Summary after retry.", manager.uiState.value.messages.single().content)
    }
}
