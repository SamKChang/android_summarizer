package com.gist.app.chat

import com.gist.app.capture.CaptureMethod
import com.gist.app.capture.CapturedDocument
import com.gist.app.capture.Completeness
import com.gist.app.llm.ChatMessage
import com.gist.app.llm.ChatRequest
import com.gist.app.llm.MessageRole
import com.gist.app.llm.nano.GeminiNanoBackend
import com.gist.app.settings.SummaryLength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderAndConversationalTest {

    private val sampleDoc = CapturedDocument(
        sourcePackage = "com.android.chrome",
        title = "Next-Gen Semiconductor Packaging & HBM3e Yields",
        url = "https://arstechnica.com/semiconductors/packaging",
        text = """
            Global semiconductor foundries are rapidly expanding advanced packaging capacity to meet surging artificial intelligence accelerator demand.
            
            CoWoS (Chip-on-Wafer-on-Substrate) packaging bottlenecks have been the primary constraint on GPU shipments throughout the past four quarters, prompting major capital expenditure increases across Taiwan and Arizona.
            
            Meanwhile, high-bandwidth memory (HBM3e) yields have improved by 18 percent as memory manufacturers transition to next-generation thermal compression bonding equipment.
            
            Analysts warn that power delivery networks and liquid cooling infrastructure in hyperscale data centers could become the next major deployment bottleneck by late 2026.
        """.trimIndent(),
        wordCount = 92,
        method = CaptureMethod.A11Y_TREE,
        completeness = Completeness.LIKELY_COMPLETE
    )

    @Test
    fun `buildSystemPrompt does not hardcode Respond in English on auto and includes conversational rules`() {
        val sysAuto = PromptBuilder.buildSystemPrompt(
            baseSystemPrompt = "You are Gist, a concise reading assistant.",
            responseLanguage = "auto",
            summaryLength = SummaryLength.MEDIUM
        )
        assertFalse("Should not force English when language is auto", sysAuto.contains("Respond in English."))
        assertTrue(sysAuto.contains("prioritize the user's latest message"))
        assertTrue(sysAuto.contains("respond in that language"))
        assertTrue(sysAuto.contains("conversational deep-dive"))

        val sysSpanish = PromptBuilder.buildSystemPrompt(
            baseSystemPrompt = "You are Gist.",
            responseLanguage = "Spanish",
            summaryLength = SummaryLength.SHORT
        )
        assertTrue(sysSpanish.contains("Default response language (unless the user requests another language in chat): Spanish."))
        assertTrue(sysSpanish.contains("Keep answers concise and punchy"))

        val sysLong = PromptBuilder.buildSystemPrompt(
            baseSystemPrompt = "You are Gist.",
            responseLanguage = "auto",
            summaryLength = SummaryLength.LONG
        )
        assertTrue(sysLong.contains("Provide thorough, detailed analysis."))
    }

    @Test
    fun `buildMessages prepends synthetic USER turn when priorTurns starts with auto-summary ASSISTANT message`() {
        val autoSummaryAssistantMsg = ChatMessage(
            role = MessageRole.ASSISTANT,
            content = "### TL;DR Summary\n- Foundries are expanding CoWoS capacity."
        )

        val messages = PromptBuilder.buildMessages(
            doc = sampleDoc,
            articleTextToUse = sampleDoc.text,
            priorTurns = listOf(autoSummaryAssistantMsg),
            newUserPrompt = "Deep dive into HBM3e thermal compression bonding",
            initialAutoPrompt = "Summarize this article."
        )

        // Must strictly alternate USER -> ASSISTANT -> USER
        assertEquals(3, messages.size)
        assertEquals(MessageRole.USER, messages[0].role)
        assertTrue(messages[0].cacheablePrefix)
        assertTrue(messages[0].content.contains("<article title=\"Next-Gen Semiconductor Packaging & HBM3e Yields\""))
        assertTrue(messages[0].content.endsWith("Summarize this article."))

        assertEquals(MessageRole.ASSISTANT, messages[1].role)
        assertEquals(autoSummaryAssistantMsg.content, messages[1].content)

        assertEquals(MessageRole.USER, messages[2].role)
        assertEquals("Deep dive into HBM3e thermal compression bonding", messages[2].content)
    }

    @Test
    fun `buildMessages preserves existing USER first turn and injects article block exactly once`() {
        val prior = listOf(
            ChatMessage(MessageRole.USER, "Initial custom question"),
            ChatMessage(MessageRole.ASSISTANT, "Initial answer")
        )

        val messages = PromptBuilder.buildMessages(
            doc = sampleDoc,
            articleTextToUse = sampleDoc.text,
            priorTurns = prior,
            newUserPrompt = "Follow-up question"
        )

        assertEquals(3, messages.size)
        assertEquals(MessageRole.USER, messages[0].role)
        assertTrue(messages[0].cacheablePrefix)
        assertTrue(messages[0].content.contains("<article"))
        assertTrue(messages[0].content.endsWith("Initial custom question"))
        assertEquals(MessageRole.ASSISTANT, messages[1].role)
        assertEquals(MessageRole.USER, messages[2].role)
        assertFalse(messages[2].content.contains("<article"))
        assertEquals("Follow-up question", messages[2].content)
    }

    @Test
    fun `GeminiNanoBackend strips article block so article keywords do not falsely trigger chip presets`() {
        // Article mentions "Timeline" and "Counter-arguments" inside body text,
        // while the user asks a specific deep-dive question about liquid cooling.
        val trickyDoc = sampleDoc.copy(
            text = sampleDoc.text + "\n\nThe project timeline and counter-arguments were also discussed."
        )
        val messages = PromptBuilder.buildMessages(
            doc = trickyDoc,
            articleTextToUse = trickyDoc.text,
            priorTurns = listOf(ChatMessage(MessageRole.ASSISTANT, "### TL;DR Summary\n- Summary.")),
            newUserPrompt = "Why is liquid cooling infrastructure a bottleneck in hyperscale data centers?"
        )

        val (reply, _) = GeminiNanoBackend.generateResponseForRequest(
            ChatRequest(
                system = PromptBuilder.buildSystemPrompt("Base"),
                messages = messages,
                model = "gemini-nano",
                webSearch = false
            )
        )

        assertFalse("Should not trigger Chronological Timeline preset", reply.contains("### Chronological"))
        assertFalse("Should not trigger Counter-Arguments preset", reply.contains("### Counter-Arguments"))
        assertFalse("Should not repeat TL;DR Summary", reply.contains("### TL;DR Summary"))
        assertTrue("Should deep-dive into liquid cooling", reply.contains("Deep Dive:") && reply.contains("liquid cooling"))
    }

    @Test
    fun `GeminiNanoBackend supports multi-language requests across all 7 languages and default language setting`() {
        val prior = listOf(ChatMessage(MessageRole.ASSISTANT, "### TL;DR Summary\n- Summary."))

        fun queryWithPrompt(prompt: String, responseLanguageSetting: String = "auto"): String {
            val msgs = PromptBuilder.buildMessages(sampleDoc, sampleDoc.text, prior, prompt)
            val sys = PromptBuilder.buildSystemPrompt("You are Gist.", responseLanguage = responseLanguageSetting)
            return GeminiNanoBackend.generateResponseForRequest(
                ChatRequest(system = sys, messages = msgs, model = "gemini-nano", webSearch = false)
            ).first
        }

        // 1. Traditional Chinese
        val zhTw = queryWithPrompt("請用繁體中文總結這篇文章 (Traditional Chinese)")
        assertTrue(zhTw.contains("重點摘要"))

        // 2. Simplified Chinese (both English name & Chinese characters)
        val zhCn = queryWithPrompt("请用中文总结这篇文章")
        assertTrue(zhCn.contains("重点摘要"))

        // 3. Spanish
        val es = queryWithPrompt("Summarize this article in Spanish (Español)")
        assertTrue(es.contains("Resumen"))

        // 4. French
        val fr = queryWithPrompt("Peux-tu résumer en Français ?")
        assertTrue(fr.contains("Résumé"))

        // 5. Japanese
        val ja = queryWithPrompt("日本語で要約してください")
        assertTrue(ja.contains("要約"))

        // 6. German
        val de = queryWithPrompt("Bitte auf Deutsch zusammenfassen")
        assertTrue(de.contains("Zusammenfassung"))

        // 7. Korean
        val ko = queryWithPrompt("한국어로 요약해줘")
        assertTrue(ko.contains("요약"))

        // 8. System prompt default language fallback when user prompt does not specify a language
        val defaultSpanish = queryWithPrompt("Summarize", responseLanguageSetting = "Spanish")
        assertTrue(defaultSpanish.contains("Resumen"))
    }

    @Test
    fun `GeminiNanoBackend deep dive synthesizes matching evidence or gracefully explains when topic is absent`() {
        val prior = listOf(ChatMessage(MessageRole.ASSISTANT, "### TL;DR Summary\n- Summary."))

        // Topic present in article: HBM3e & thermal compression bonding
        val presentMsgs = PromptBuilder.buildMessages(
            doc = sampleDoc,
            articleTextToUse = sampleDoc.text,
            priorTurns = prior,
            newUserPrompt = "Tell me more about HBM3e yields and thermal compression bonding"
        )
        val (presentReply, _) = GeminiNanoBackend.generateResponseForRequest(
            ChatRequest(system = "System", messages = presentMsgs, model = "gemini-nano", webSearch = false)
        )
        assertTrue(presentReply.contains("### Deep Dive:"))
        assertTrue(presentReply.contains("18 percent"))
        assertTrue(presentReply.contains("thermal compression bonding"))
        assertTrue(presentReply.contains("### Takeaway"))

        // Topic absent from article: quantum entanglement satellites
        val absentMsgs = PromptBuilder.buildMessages(
            doc = sampleDoc,
            articleTextToUse = sampleDoc.text,
            priorTurns = prior,
            newUserPrompt = "What does the author say about quantum entanglement satellites?"
        )
        val (absentReply, _) = GeminiNanoBackend.generateResponseForRequest(
            ChatRequest(system = "System", messages = absentMsgs, model = "gemini-nano", webSearch = false)
        )
        assertTrue(absentReply.contains("### Exploring:"))
        assertTrue(absentReply.contains("does not directly mention"))
        assertFalse(absentReply.contains("### TL;DR Summary"))
    }
}
