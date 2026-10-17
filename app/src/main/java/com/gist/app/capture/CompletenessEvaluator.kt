package com.gist.app.capture

import kotlin.math.abs

/**
 * Evaluates whether captured text is likely complete or truncated (§5.2):
 * `LIKELY_TRUNCATED` if:
 * 1. words < 300 while a scrollable container exists that `canScrollForward`; OR
 * 2. the last paragraph ends without terminal punctuation; OR
 * 3. total captured text is approximately equal to the visible-only text while a scrollable container can scroll forward.
 */
object CompletenessEvaluator {

    private val TERMINAL_PUNCTUATION = setOf('.', '!', '?', '"', '”', '\'', '’', ')', ']', '…')

    fun evaluate(
        cleanedText: String,
        wordCount: Int,
        visibleOnlyWordCount: Int,
        canScrollForward: Boolean
    ): Completeness {
        if (cleanedText.isBlank() || wordCount == 0) {
            return Completeness.LIKELY_TRUNCATED
        }

        // Condition 1: words < 300 while a scrollable container exists that canScrollForward
        if (wordCount < 300 && canScrollForward) {
            return Completeness.LIKELY_TRUNCATED
        }

        // Condition 2: last paragraph ends without terminal punctuation (for multi-sentence paragraphs)
        val lastParagraph = cleanedText
            .split("\n\n")
            .lastOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()

        if (lastParagraph.length > 25) {
            val lastChar = lastParagraph.last()
            if (lastChar !in TERMINAL_PUNCTUATION) {
                return Completeness.LIKELY_TRUNCATED
            }
        }

        // Condition 3: total text ≈ visible-only text while scrollable forward
        if (canScrollForward && visibleOnlyWordCount > 0) {
            val diffRatio = abs(wordCount - visibleOnlyWordCount).toDouble() / wordCount.toDouble()
            if (diffRatio < 0.10 && wordCount < 800) {
                return Completeness.LIKELY_TRUNCATED
            }
        }

        return Completeness.LIKELY_COMPLETE
    }
}
