package com.gist.app.chat

import com.gist.app.capture.CapturedDocument
import com.gist.app.llm.ChatMessage
import com.gist.app.llm.MessageRole
import com.gist.app.settings.SummaryLength
import java.util.Locale

/**
 * Formats system instructions and injects the captured article once as a delimited `<article ...>` block (§6.3).
 */
object PromptBuilder {

    fun buildSystemPrompt(
        baseSystemPrompt: String,
        responseLanguage: String = "auto",
        summaryLength: SummaryLength = SummaryLength.MEDIUM,
    ): String = buildString {
        append(baseSystemPrompt.trim())
        append(
            "\nIMPORTANT CONVERSATIONAL RULES:\n" +
                "1. Always prioritize the user's latest message in the chat.\n" +
                "2. If the user asks to summarize or respond in a specific language (e.g. Chinese, Spanish, French, Japanese, German) or writes their message in that language, you MUST respond in that language.\n" +
                "3. If the user asks a follow-up question or asks to deep-dive into a topic, provide a focused, conversational deep-dive on that specific topic using details from the article (and your broader knowledge when helpful), rather than repeating the general article summary."
        )
        if (responseLanguage != "auto" && responseLanguage.isNotBlank()) {
            append("\nDefault response language (unless the user requests another language in chat): ")
                .append(responseLanguage)
                .append(".")
        }
        when (summaryLength) {
            SummaryLength.SHORT -> append(" Keep answers concise and punchy unless the user asks for a deep dive.")
            SummaryLength.MEDIUM -> {}
            SummaryLength.LONG -> append(" Provide thorough, detailed analysis.")
        }
    }

    fun formatArticleBlock(doc: CapturedDocument, overrideText: String = doc.text): String {
        val safeTitle = (doc.title ?: "Untitled").replace("\"", "'")
        val safeUrl = (doc.url ?: "").replace("\"", "'")
        return buildString {
            append("<article title=\"").append(safeTitle)
            append("\" url=\"").append(safeUrl)
            append("\" captured_via=\"").append(doc.method.name).append("\">\n")
            append(overrideText.trim())
            append("\n</article>")
        }
    }

    fun buildMessages(
        doc: CapturedDocument,
        articleTextToUse: String,
        priorTurns: List<ChatMessage>,
        newUserPrompt: String,
        initialAutoPrompt: String = "Summarize this article."
    ): List<ChatMessage> {
        val articleBlock = formatArticleBlock(doc, articleTextToUse)
        val result = mutableListOf<ChatMessage>()
        if (priorTurns.isEmpty()) {
            result.add(
                ChatMessage(
                    role = MessageRole.USER,
                    content = "$articleBlock\n\n$newUserPrompt",
                    cacheablePrefix = true
                )
            )
        } else {
            val firstTurn = priorTurns.first()
            if (firstTurn.role == MessageRole.USER) {
                val firstWithArticle = if (firstTurn.content.contains("<article")) {
                    firstTurn.copy(cacheablePrefix = true)
                } else {
                    firstTurn.copy(
                        content = "$articleBlock\n\n${firstTurn.content}",
                        cacheablePrefix = true
                    )
                }
                result.add(firstWithArticle)
                result.addAll(priorTurns.drop(1))
            } else {
                // When the first stored message is the auto-generated ASSISTANT summary
                // (created with displayAsUserBubble = false), prepend the initial USER turn
                // with the <article> block so the conversation strictly alternates USER -> ASSISTANT -> USER.
                result.add(
                    ChatMessage(
                        role = MessageRole.USER,
                        content = "$articleBlock\n\n$initialAutoPrompt",
                        cacheablePrefix = true
                    )
                )
                result.addAll(priorTurns)
            }
            result.add(ChatMessage(role = MessageRole.USER, content = newUserPrompt))
        }
        return result
    }
}
