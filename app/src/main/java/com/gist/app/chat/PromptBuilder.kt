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
        val lang = if (responseLanguage == "auto" || responseLanguage.isBlank()) {
            Locale.getDefault().displayLanguage.ifBlank { "English" }
        } else {
            responseLanguage
        }
        append("\nRespond in ").append(lang).append(".")
        when (summaryLength) {
            SummaryLength.SHORT -> append(" Keep answers very brief and punchy.")
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
        newUserPrompt: String
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
            // Inject article once in the first user turn
            val firstTurn = priorTurns.first()
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
            result.add(ChatMessage(role = MessageRole.USER, content = newUserPrompt))
        }
        return result
    }
}
