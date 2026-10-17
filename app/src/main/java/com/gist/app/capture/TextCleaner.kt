package com.gist.app.capture

/**
 * Cleans extracted text per §5.3:
 * - Collapses horizontal whitespace
 * - Removes boilerplate lines (cookie banners, "Subscribe", "Share this", "Advertisement", etc.)
 * - Drops duplicate consecutive or near-duplicate boilerplate lines
 * - Preserves paragraph breaks
 * - Caps the final text at the configured maximum word count (default 60k words)
 */
object TextCleaner {

    private val BOILERPLATE_PATTERNS = listOf(
        Regex("^(accept|reject|manage)\\s+(all\\s+)?cookies.*$", RegexOption.IGNORE_CASE),
        Regex("^we use cookies.*$", RegexOption.IGNORE_CASE),
        Regex("^subscribe(\\s+now|\\s+to\\s+our\\s+newsletter)?[!.]?$", RegexOption.IGNORE_CASE),
        Regex("^sign\\s+up\\s+for\\s+our\\s+newsletter.*$", RegexOption.IGNORE_CASE),
        Regex("^share\\s+this(\\s+article|\\s+story)?.*$", RegexOption.IGNORE_CASE),
        Regex("^advertisement$", RegexOption.IGNORE_CASE),
        Regex("^sponsored\\s+content$", RegexOption.IGNORE_CASE),
        Regex("^read\\s+more:?$", RegexOption.IGNORE_CASE),
        Regex("^related\\s+(articles|stories):?$", RegexOption.IGNORE_CASE),
        Regex("^skip\\s+to\\s+(main\\s+)?content$", RegexOption.IGNORE_CASE),
        Regex("^follow\\s+us\\s+on\\s+(twitter|x|facebook|instagram|linkedin).*$", RegexOption.IGNORE_CASE),
    )

    fun clean(rawText: String, maxWords: Int = 60_000): String {
        if (rawText.isBlank()) return ""

        val rawParagraphs = rawText
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split(Regex("\n{1,}"))

        val cleanedParagraphs = mutableListOf<String>()
        val seenShortLines = mutableSetOf<String>()

        for (rawPara in rawParagraphs) {
            val normalized = rawPara
                .replace(Regex("[ \\t\\x0B\\f]+"), " ")
                .trim()

            if (normalized.isEmpty()) continue
            if (isBoilerplate(normalized)) continue

            // Deduplicate identical paragraphs or repeated short navigation chrome
            val lower = normalized.lowercase()
            if (cleanedParagraphs.lastOrNull()?.lowercase() == lower) continue
            if (!seenShortLines.add(lower)) continue

            cleanedParagraphs.add(normalized)
        }

        val joined = cleanedParagraphs.joinToString("\n\n")
        return capWords(joined, maxWords)
    }

    fun isBoilerplate(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.length <= 2 && !trimmed.any { it.isLetterOrDigit() }) return true
        return BOILERPLATE_PATTERNS.any { it.matches(trimmed) }
    }

    fun countWords(text: String): Int {
        if (text.isBlank()) return 0
        return text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
    }

    private fun capWords(text: String, maxWords: Int): String {
        if (maxWords <= 0) return ""
        val words = text.split(Regex("(?<=\\s)|(?=\\s+)"))
        var wordCounter = 0
        val sb = StringBuilder()
        for (token in words) {
            if (token.isNotBlank()) {
                wordCounter++
                if (wordCounter > maxWords) break
            }
            sb.append(token)
        }
        return sb.toString().trim()
    }
}
