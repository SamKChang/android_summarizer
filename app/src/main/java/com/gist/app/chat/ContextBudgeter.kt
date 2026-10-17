package com.gist.app.chat

import com.gist.app.llm.ChatEvent
import com.gist.app.llm.ChatMessage
import com.gist.app.llm.ChatRequest
import com.gist.app.llm.LlmBackend
import com.gist.app.llm.MessageRole
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Estimates token usage and budgets article + conversation context (§6.4):
 * - Estimates tokens as `chars / 4`.
 * - If article + history fit in 70% of the model's context (and not on-device Nano), sends as-is.
 * - Otherwise runs **map-reduce**: chunks by paragraphs (~3k tokens per chunk, or smaller for Nano),
 *   summarizes chunks in parallel (max 4 concurrent), then answers over combined chunk summaries.
 * - Long chats: keeps the article + last N turns + a rolling summary of older turns.
 */
object ContextBudgeter {

    fun estimateTokens(text: String): Int = (text.length + 3) / 4

    fun needsMapReduce(
        articleText: String,
        historyMessages: List<ChatMessage>,
        maxContextTokens: Int,
        isOnDeviceNano: Boolean
    ): Boolean {
        if (isOnDeviceNano && estimateTokens(articleText) > 1200) {
            return true
        }
        val totalTokens = estimateTokens(articleText) + historyMessages.sumOf { estimateTokens(it.content) }
        val budget = (maxContextTokens * 0.70).toInt()
        return totalTokens > budget
    }

    /**
     * Splits an article into paragraph-aligned chunks of at most [maxTokensPerChunk] tokens (~3k tokens default).
     */
    fun chunkByParagraphs(articleText: String, maxTokensPerChunk: Int = 3_000): List<String> {
        val maxChars = maxTokensPerChunk * 4
        val paragraphs = articleText.split(Regex("\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return emptyList()

        val chunks = mutableListOf<String>()
        val current = StringBuilder()

        for (para in paragraphs) {
            if (current.isNotEmpty() && current.length + para.length + 2 > maxChars) {
                chunks.add(current.toString().trim())
                current.clear()
            }
            if (para.length > maxChars) {
                // Split oversized single paragraph
                var start = 0
                while (start < para.length) {
                    val end = (start + maxChars).coerceAtMost(para.length)
                    chunks.add(para.substring(start, end))
                    start = end
                }
            } else {
                if (current.isNotEmpty()) current.append("\n\n")
                current.append(para)
            }
        }
        if (current.isNotEmpty()) {
            chunks.add(current.toString().trim())
        }
        return chunks
    }

    /**
     * Summarizes chunks in parallel (max 4 concurrent) using [backend].
     */
    suspend fun mapReduceArticle(
        articleText: String,
        backend: LlmBackend,
        model: String,
        maxTokensPerChunk: Int = 3_000
    ): String = coroutineScope {
        val chunks = chunkByParagraphs(articleText, maxTokensPerChunk)
        if (chunks.size <= 1) return@coroutineScope articleText

        val semaphore = Semaphore(4)
        val chunkSummaries = chunks.mapIndexed { idx, chunk ->
            async {
                semaphore.withPermit {
                    val req = ChatRequest(
                        system = "Summarize this section of an article concisely, preserving all key facts, numbers, and names.",
                        messages = listOf(
                            ChatMessage(
                                role = MessageRole.USER,
                                content = "Section ${idx + 1}/${chunks.size}:\n\n$chunk"
                            )
                        ),
                        model = model,
                        webSearch = false,
                        temperature = 0.2f,
                        maxOutputTokens = 512
                    )
                    val sb = StringBuilder()
                    backend.stream(req).collect { ev ->
                        if (ev is ChatEvent.Delta) sb.append(ev.text)
                    }
                    sb.toString().trim().ifEmpty { chunk.take(600) }
                }
            }
        }.awaitAll()

        chunkSummaries.mapIndexed { index, summary ->
            "[Part ${index + 1}]\n$summary"
        }.joinToString("\n\n")
    }

    /**
     * Keeps the last [keepRecentTurns] messages and condenses older turns into a rolling summary header (§6.4).
     */
    fun trimHistoryWithRollingSummary(
        turns: List<ChatMessage>,
        keepRecentTurns: Int = 6
    ): List<ChatMessage> {
        if (turns.size <= keepRecentTurns) return turns
        val older = turns.dropLast(keepRecentTurns)
        val recent = turns.takeLast(keepRecentTurns)
        val summaryText = older.joinToString("\n") { msg ->
            val prefix = if (msg.role == MessageRole.USER) "User" else "Assistant"
            val snippet = msg.content.replace(Regex("<article[\\s\\S]*?</article>"), "[Article]")
                .trim()
                .take(200)
            "- $prefix: $snippet"
        }
        val summaryTurn = ChatMessage(
            role = MessageRole.USER,
            content = "[Summary of earlier conversation turns]\n$summaryText"
        )
        return listOf(summaryTurn) + recent
    }
}
