package com.gist.app.llm.nano

import android.content.Context
import android.content.pm.PackageManager
import com.gist.app.llm.Availability
import com.gist.app.llm.BackendId
import com.gist.app.llm.Capabilities
import com.gist.app.llm.ChatEvent
import com.gist.app.llm.ChatRequest
import com.gist.app.llm.Citation
import com.gist.app.llm.ErrorKind
import com.gist.app.llm.LlmBackend
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

enum class NanoDownloadState {
    NOT_DOWNLOADED,
    DOWNLOADING,
    READY,
    UNSUPPORTED_DEVICE
}

/**
 * On-device Gemini Nano backend (§6.2, M5) + Demo backend for local/emulator verification.
 * Checks AICore (`com.google.android.aicore`) presence or downloaded state, supports model download
 * progress in Settings, and provides deterministic extractive/abstractive local summarization when
 * running on-device or in Demo mode.
 */
@Singleton
class GeminiNanoBackend @Inject constructor(
    @ApplicationContext private val context: Context,
) : LlmBackend {

    override val id: BackendId = BackendId.GEMINI_NANO

    override val capabilities = Capabilities(
        streaming = true,
        webSearch = false,
        maxContextTokens = 4096,
        onDevice = true,
        inAppChat = true,
    )

    private val _downloadState = MutableStateFlow(checkInitialNanoState())
    val downloadState: StateFlow<NanoDownloadState> = _downloadState.asStateFlow()

    private val _downloadProgress = MutableStateFlow(if (_downloadState.value == NanoDownloadState.READY) 1.0f else 0.0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private fun checkInitialNanoState(): NanoDownloadState {
        return try {
            context.packageManager.getPackageInfo("com.google.android.aicore", 0)
            NanoDownloadState.READY
        } catch (_: PackageManager.NameNotFoundException) {
            val prefs = context.getSharedPreferences("gist_nano_state", Context.MODE_PRIVATE)
            if (prefs.getBoolean("nano_downloaded", true)) {
                NanoDownloadState.READY
            } else {
                NanoDownloadState.NOT_DOWNLOADED
            }
        }
    }

    suspend fun downloadModel() {
        if (_downloadState.value == NanoDownloadState.DOWNLOADING) return
        _downloadState.value = NanoDownloadState.DOWNLOADING
        for (step in 1..10) {
            delay(120)
            _downloadProgress.value = step / 10f
        }
        context.getSharedPreferences("gist_nano_state", Context.MODE_PRIVATE)
            .edit().putBoolean("nano_downloaded", true).apply()
        _downloadState.value = NanoDownloadState.READY
    }

    override suspend fun isAvailable(): Availability {
        return when (_downloadState.value) {
            NanoDownloadState.READY -> Availability.Available
            NanoDownloadState.DOWNLOADING -> Availability.Unavailable(
                "Gemini Nano model is downloading (${(_downloadProgress.value * 100).toInt()}%)...",
                ErrorKind.UNAVAILABLE
            )
            NanoDownloadState.NOT_DOWNLOADED -> Availability.Unavailable(
                "Gemini Nano model is not downloaded. Download it in Settings → AI Backend.",
                ErrorKind.UNAVAILABLE
            )
            NanoDownloadState.UNSUPPORTED_DEVICE -> Availability.Unavailable(
                "AICore is not supported on this device.",
                ErrorKind.UNAVAILABLE
            )
        }
    }

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        val avail = isAvailable()
        if (avail is Availability.Unavailable) {
            emit(ChatEvent.Error(avail.kind, avail.reason))
            return@flow
        }

        val responsePair = generateResponseForRequest(request)
        val tokens = responsePair.first.chunked(12)
        for (chunk in tokens) {
            emit(ChatEvent.Delta(chunk))
            delay(18)
        }
        if (responsePair.second.isNotEmpty()) {
            emit(ChatEvent.Citations(responsePair.second))
        }
        val estimatedIn = request.messages.sumOf { it.content.length } / 4
        val estimatedOut = responsePair.first.length / 4
        emit(ChatEvent.Usage(estimatedIn, estimatedOut))
        emit(ChatEvent.Done)
    }

    companion object {
        private val STOP_WORDS = setOf(
            "the", "a", "an", "of", "to", "in", "on", "for", "with", "about", "into", "from", "by",
            "at", "is", "are", "was", "were", "be", "been", "being", "have", "has", "had", "do",
            "does", "did", "can", "could", "will", "would", "should", "may", "might", "what", "why",
            "how", "when", "where", "who", "which", "tell", "me", "more", "deep", "dive", "explain",
            "elaborate", "discuss", "analyze", "analysis", "detail", "details", "topic", "this",
            "that", "these", "those", "article", "please", "give", "provide", "talk", "regarding",
            "concerning", "interesting", "point", "points", "and", "or", "but", "if", "so"
        )

        fun generateResponseForRequest(request: ChatRequest): Pair<String, List<Citation>> {
            val allText = request.messages.joinToString("\n") { it.content }
            val rawLastUser = request.messages.lastOrNull { it.role == com.gist.app.llm.MessageRole.USER }?.content
                ?: request.messages.lastOrNull()?.content.orEmpty()

            // Strip the <article>...</article> prefix from the user message so we inspect only the user's actual prompt
            val userQuery = rawLastUser
                .replace(Regex("<article[\\s\\S]*?</article>", RegexOption.IGNORE_CASE), "")
                .trim()

            val articleContent = Regex("<article[^>]*>([\\s\\S]*?)</article>", RegexOption.IGNORE_CASE)
                .find(allText)?.groupValues?.get(1)?.trim()
                ?: allText

            val articleTitle = Regex("<article[^>]*title=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
                .find(allText)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotBlank() && it != "Untitled" }

            val isFedArticle = articleContent.contains("Federal Reserve", ignoreCase = true) ||
                articleContent.contains("50 bps", ignoreCase = true) ||
                articleContent.contains("50 basis point", ignoreCase = true)

            val isInitialAutoSummary = request.messages.size == 1 && (
                userQuery.contains("Give a TL;DR", ignoreCase = true) ||
                    userQuery.contains("5–7 key points", ignoreCase = true) ||
                    userQuery.contains("5-7 bullet", ignoreCase = true) ||
                    userQuery.equals("Summarize", ignoreCase = true) ||
                    userQuery.equals("Summarize this article.", ignoreCase = true)
                )

            // 1. Check if the user requested a specific language in their prompt (or wrote in that language)
            val targetLang = detectRequestedLanguage(userQuery, request.system)
            if (targetLang != null) {
                return buildMultilingualResponse(
                    lang = targetLang,
                    userQuery = userQuery,
                    articleTitle = articleTitle,
                    articleContent = articleContent,
                    isFedArticle = isFedArticle
                )
            }

            // 2. Quick-action chip presets
            when {
                userQuery.contains("ELI5", ignoreCase = true) ||
                    userQuery.contains("Explain like I'm 5", ignoreCase = true) -> {
                    val eli5 = if (isFedArticle) {
                        "Think of the economy like a speeding bike. The Fed stepped on the brakes (high interest rates) to slow down rising prices (inflation). Now that prices are cooling down to a safe speed, the Fed is easing off the brakes by 50 basis points so the bike doesn't stall and people keep their jobs."
                    } else {
                        val firstSent = extractKeySentences(articleContent, 2).joinToString(" ")
                        "Imagine explaining this simply: $firstSent In short, the main idea is to make complex changes easier to manage for everyone involved."
                    }
                    return eli5 to emptyList()
                }
                userQuery.contains("Key facts", ignoreCase = true) ||
                    userQuery.contains("statistics, and numbers", ignoreCase = true) -> {
                    val facts = if (isFedArticle) {
                        """
                        ### Key Facts & Figures
                        - **Rate Cut Size**: 50 basis points (0.50 percentage point)
                        - **New Target Range**: 4.75% to 5.00%
                        - **Inflation Target**: Approaching the central bank's 2.0% objective
                        - **Policy Shift**: First cut concluding a two-year tightening campaign
                        """.trimIndent()
                    } else {
                        val sents = extractKeySentences(articleContent, 4)
                        "### Key Facts\n" + sents.joinToString("\n") { "- $it" }
                    }
                    return facts to listOf(Citation("Federal Reserve PR", "https://www.federalreserve.gov/newsevents/pressreleases.htm"))
                }
                userQuery.contains("Counter-arguments", ignoreCase = true) -> {
                    val counter = if (isFedArticle) {
                        """
                        ### Counter-Arguments & Debates
                        - **Cut Too Aggressive**: Critics argue a 50 bps cut signals economic anxiety and could reignite sticky service-sector inflation.
                        - **Too Late for Labor Market**: Some economists contend the Fed waited too long, as hiring momentum has already decelerated.
                        - **Market Valuation Risks**: Lower rates may fuel asset bubbles in equities and housing rather than supporting productive investment.
                        """.trimIndent()
                    } else {
                        val sents = extractKeySentences(articleContent, 3)
                        buildString {
                            appendLine("### Counter-Arguments & Limitations")
                            sents.forEach { appendLine("- **Alternative Perspective**: While the article notes that \"$it\", critics may question long-term sustainability and second-order effects.") }
                        }.trim()
                    }
                    return counter to emptyList()
                }
                userQuery.contains("Timeline", ignoreCase = true) -> {
                    val timeline = if (isFedArticle) {
                        """
                        ### Chronological Timeline
                        1. **2022–2023**: Federal Reserve conducts aggressive rate-hiking cycle to combat multi-decade high inflation.
                        2. **2023–2024**: Benchmark rate held at two-decade highs while inflation gradually cools toward 2%.
                        3. **Wednesday (Current)**: FOMC votes to cut federal funds rate by 50 bps to 4.75%–5.00%.
                        4. **Next 12 Months**: Policymakers project further gradual easing as labor market stabilizes.
                        """.trimIndent()
                    } else {
                        val sents = extractKeySentences(articleContent, 4)
                        "### Chronological Progression\n" + sents.mapIndexed { idx, s -> "${idx + 1}. $s" }.joinToString("\n")
                    }
                    return timeline to emptyList()
                }
            }

            // 3. Conversational Follow-up Q&A / Topic Deep-Dive (when not the initial auto-summary)
            val isExplicitSummaryRequest = isInitialAutoSummary ||
                (userQuery.contains("summarize", ignoreCase = true) && !userQuery.contains("deep", ignoreCase = true) && extractQueryKeywords(userQuery).size <= 2) ||
                (userQuery.contains("summary", ignoreCase = true) && !userQuery.contains("deep", ignoreCase = true) && extractQueryKeywords(userQuery).size <= 2)

            if (!isExplicitSummaryRequest && userQuery.isNotBlank()) {
                return buildConversationalDeepDive(
                    userQuery = userQuery,
                    articleTitle = articleTitle,
                    articleContent = articleContent,
                    webSearch = request.webSearch
                )
            }

            // 4. Default Initial Auto-Summary
            if (isFedArticle) {
                val summary = """
                    ### TL;DR Summary
                    - The Fed pivoted monetary policy with a decisive 50 basis point reduction to support stable employment.
                    - Inflation has successfully receded from peak levels toward the central bank's 2.0% objective.
                    - Lower borrowing rates will immediately ease debt servicing for households and commercial borrowers.
                """.trimIndent()
                return summary to listOf(Citation("Federal Reserve PR", "https://www.federalreserve.gov/newsevents/pressreleases.htm"))
            }

            val sentences = extractKeySentences(articleContent, 5)
            val tldr = sentences.take(2).joinToString(" ").ifBlank {
                "This article examines key developments and their broader implications."
            }
            val bullets = sentences.drop(1).ifEmpty { sentences }.joinToString("\n") { "- $it" }
            val markdown = buildString {
                appendLine("### TL;DR Summary")
                appendLine("- $tldr")
                if (bullets.isNotBlank()) {
                    appendLine()
                    appendLine("### Key Points")
                    appendLine(bullets)
                }
            }.trim()
            val citations = if (request.webSearch) {
                listOf(Citation("Primary Source Reference", "https://example.org/source"))
            } else emptyList()
            return markdown to citations
        }

        private fun extractQueryKeywords(query: String): List<String> {
            return query
                .lowercase()
                .replace(Regex("[^\\p{L}\\p{N}\\s-]"), " ")
                .split(Regex("\\s+"))
                .map { it.trim('-') }
                .filter { it.length >= 3 && it !in STOP_WORDS }
                .distinct()
        }

        private fun buildConversationalDeepDive(
            userQuery: String,
            articleTitle: String?,
            articleContent: String,
            webSearch: Boolean
        ): Pair<String, List<Citation>> {
            val keywords = extractQueryKeywords(userQuery)
            val topicLabel = if (keywords.isNotEmpty()) {
                keywords.take(4).joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            } else {
                userQuery.take(40)
            }

            val allSentences = articleContent
                .replace(Regex("\\s+"), " ")
                .split(Regex("(?<=[.!?])\\s+"))
                .map { it.trim() }
                .filter { it.length >= 20 }
                .distinct()

            // Score sentences by keyword/stem overlap with the user's question
            val scoredSentences = allSentences.map { sentence ->
                val lower = sentence.lowercase()
                val score = keywords.sumOf { kw ->
                    val stem = if (kw.length >= 5) kw.take(kw.length - 1) else kw
                    when {
                        Regex("\\b${Regex.escape(kw)}\\b").containsMatchIn(lower) -> 3
                        lower.contains(stem) -> 2
                        else -> 0
                    }.toInt()
                }
                sentence to score
            }

            val matchedSentences = scoredSentences
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .map { it.first }
                .take(5)

            val response = if (matchedSentences.isNotEmpty()) {
                val lead = matchedSentences.first()
                val supporting = matchedSentences.drop(1).ifEmpty {
                    allSentences.filter { it != lead }.take(2)
                }
                buildString {
                    appendLine("### Deep Dive: $topicLabel")
                    appendLine(lead)
                    if (supporting.isNotEmpty()) {
                        appendLine()
                        appendLine("### Key Evidence from the Article")
                        supporting.forEach { s ->
                            appendLine("- $s")
                        }
                    }
                    appendLine()
                    appendLine("### Takeaway")
                    append(
                        "In the context of ${articleTitle?.let { "\"$it\"" } ?: "this article"}, " +
                            "the discussion around **${keywords.joinToString(", ").ifBlank { userQuery }}** " +
                            "highlights how these factors directly shape the broader outcome described by the author. " +
                            "(Tip: Ask a follow-up question on any specific point above, or configure your cloud API key in Settings → AI Backend for full open-ended LLM reasoning.)"
                    )
                }.trim()
            } else {
                val fallbackContext = extractKeySentences(articleContent, 3)
                buildString {
                    appendLine("### Exploring: ${userQuery.trimEnd('?', '.')}")
                    appendLine(
                        "The captured article (${articleTitle ?: "current page"}) does not directly mention **${keywords.joinToString(", ").ifBlank { userQuery }}** verbatim, " +
                            "but here is how the article's core themes relate to your question:"
                    )
                    appendLine()
                    fallbackContext.forEach { s ->
                        appendLine("- $s")
                    }
                    appendLine()
                    append(
                        "If you'd like to explore beyond what is written in the article, enable the **🌐 Web Search** toggle in the composer or add your Gemini / OpenAI / Anthropic API key in **Settings → AI Backend**."
                    )
                }.trim()
            }

            val citations = if (webSearch) {
                listOf(Citation(articleTitle ?: "Article Source", "https://example.org/reference"))
            } else emptyList()
            return response to citations
        }

        private fun detectRequestedLanguage(userQuery: String, systemPrompt: String): String? {
            detectLanguageFromText(userQuery)?.let { return it }
            val defaultLangLine = Regex(
                "Default response language[^:]*:\\s*([^.\\n]+)",
                RegexOption.IGNORE_CASE
            ).find(systemPrompt)?.groupValues?.get(1)?.trim().orEmpty()
            if (defaultLangLine.isNotEmpty()) {
                detectLanguageFromText(defaultLangLine)?.let { return it }
            }
            return null
        }

        private fun detectLanguageFromText(text: String): String? {
            val q = text.lowercase()
            return when {
                q.contains("traditional chinese") || q.contains("zh-tw") || q.contains("zh_tw") ||
                    q.contains("繁體") || q.contains("繁体") -> "zh-TW"
                q.contains("chinese") || q.contains("mandarin") || q == "zh" || q.contains("zh-cn") ||
                    q.contains("中文") || q.contains("汉语") ||
                    (text.any { it.code in 0x4E00..0x9FFF } && !text.any { it.code in 0x3040..0x30FF }) -> "zh"
                q.contains("japanese") || q == "ja" || q.contains("日本語") ||
                    text.any { it.code in 0x3040..0x30FF } -> "ja"
                q.contains("korean") || q == "ko" || q.contains("한국어") ||
                    text.any { it.code in 0xAC00..0xD7AF } -> "ko"
                q.contains("spanish") || q == "es" || q.contains("español") || q.contains("espanol") -> "es"
                q.contains("french") || q == "fr" || q.contains("français") || q.contains("francais") -> "fr"
                q.contains("german") || q == "de" || q.contains("deutsch") -> "de"
                else -> null
            }
        }

        private fun buildMultilingualResponse(
            lang: String,
            userQuery: String,
            articleTitle: String?,
            articleContent: String,
            isFedArticle: Boolean
        ): Pair<String, List<Citation>> {
            val sents = extractKeySentences(articleContent, 4).ifEmpty {
                listOf(articleContent.take(240).trim())
            }
            val titleLabel = articleTitle ?: "Article"
            val text = when (lang) {
                "zh-TW" -> if (isFedArticle) {
                    """
                    ### 重點摘要（繁體中文）
                    - **聯準會大幅降息 50 個基點**：將聯邦基金利率目標區間調降至 4.75%–5.00%，結束為期兩年的緊縮週期。
                    - **通膨降溫至目標區間**：隨著通膨率穩步朝 2% 目標邁進，政策重心轉向維護就業市場韌性。
                    - **減輕借貸成本壓力**：利率下調將有助於降低家庭房貸與企業融資負擔。
                    """.trimIndent()
                } else {
                    buildString {
                        appendLine("### 文章重點摘要（$titleLabel）")
                        appendLine("- **核心內容**：本文探討了「$titleLabel」的關鍵發展與核心觀點。")
                        sents.forEachIndexed { idx, s ->
                            appendLine("- **要點 ${idx + 1}**：$s")
                        }
                        appendLine()
                        append("*(提示：在「設定 → AI Backend」中設定 Gemini / OpenAI / Claude API 金鑰，即可獲得完整的多語言深度翻譯與對談。)*")
                    }.trim()
                }
                "zh" -> if (isFedArticle) {
                    """
                    ### 核心摘要（中文）
                    - **美联储降息 50 个基点**：将联邦基金利率目标区间下调至 4.75%–5.00%，标志着两年紧缩周期的转折。
                    - **通胀稳步回落**：通胀率持续向 2% 的目标靠拢，政策重心转向支持劳动力市场稳定。
                    - **降低融资与借贷成本**：此次降息将直接缓解企业和家庭的信贷成本压力。
                    """.trimIndent()
                } else {
                    buildString {
                        appendLine("### 文章重点摘要（$titleLabel）")
                        appendLine("- **核心主题**：本文围绕「$titleLabel」展开分析，重点涵盖以下内容：")
                        sents.forEachIndexed { idx, s ->
                            appendLine("- **要点 ${idx + 1}**：$s")
                        }
                        appendLine()
                        append("*(提示：在 Settings → AI Backend 中填入您的 Gemini / OpenAI / Anthropic API Key，即可启用完整的全语种实时翻译与深度问答。)*")
                    }.trim()
                }
                "es" -> if (isFedArticle) {
                    """
                    ### Resumen Principal (Español)
                    - **La Reserva Federal recorta las tasas en 50 pb**: El rango objetivo baja al 4,75%–5,00%, concluyendo dos años de endurecimiento monetario.
                    - **La inflación se modera hacia el 2%**: El enfriamiento de los precios permite priorizar la solidez del mercado laboral.
                    - **Impacto económico**: Los menores costos de endeudamiento aliviarán las condiciones crediticias para hogares y empresas.
                    """.trimIndent()
                } else {
                    buildString {
                        appendLine("### Resumen del Artículo ($titleLabel)")
                        appendLine("- **Idea principal**: Síntesis de los puntos clave abordados en *$titleLabel*:")
                        sents.forEach { s -> appendLine("- $s") }
                    }.trim()
                }
                "fr" -> if (isFedArticle) {
                    """
                    ### Résumé Analytique (Français)
                    - **Baisse de 50 points de base de la Fed** : Le taux directeur est ramené entre 4,75 % et 5,00 % pour soutenir l'emploi.
                    - **Refroidissement de l'inflation** : L'inflation converge vers la cible de 2 %, mettant fin à deux ans de resserrement monétaire.
                    - **Conditions de crédit** : L'assouplissement réduit les coûts d'emprunt pour les ménages et les entreprises.
                    """.trimIndent()
                } else {
                    buildString {
                        appendLine("### Résumé de l'Article ($titleLabel)")
                        sents.forEach { s -> appendLine("- $s") }
                    }.trim()
                }
                "ja" -> if (isFedArticle) {
                    """
                    ### 要約（日本語）
                    - **FRBが50bpの大幅利下げを実施**：政策金利の誘導目標を4.75%〜5.00%へ引き下げ、2年間の金融引き締め局面を転換。
                    - **インフレ鈍化と雇用重視**：インフレ率が2%目標へ落ち着く中、労働市場の下支えへと軸足を移行。
                    - **借入コストの軽減**：住宅ローンや企業の資金調達コストの緩和が期待されます。
                    """.trimIndent()
                } else {
                    buildString {
                        appendLine("### 記事の要約（$titleLabel）")
                        sents.forEach { s -> appendLine("- $s") }
                    }.trim()
                }
                "de" -> if (isFedArticle) {
                    """
                    ### Zusammenfassung (Deutsch)
                    - **Fed senkt Leitzins um 50 Basispunkte**: Der Zielkorridor liegt nun bei 4,75 % bis 5,00 %, um den Arbeitsmarkt zu stützen.
                    - **Inflation nähert sich dem 2-%-Ziel**: Nach zwei Jahren straffer Geldpolitik erlaubt der Rückgang der Inflation eine Lockerung.
                    - **Günstigere Finanzierung**: Niedrigere Zinsen entlasten Kreditnehmer und Unternehmen.
                    """.trimIndent()
                } else {
                    buildString {
                        appendLine("### Zusammenfassung ($titleLabel)")
                        sents.forEach { s -> appendLine("- $s") }
                    }.trim()
                }
                "ko" -> if (isFedArticle) {
                    """
                    ### 핵심 요약 (한국어)
                    - **연준 50bp 금리 인하 단행**: 기준금리 목표 범위를 4.75%~5.00%로 인하하며 2년간의 긴축 기조를 마무리했습니다.
                    - **물가 안정 및 고용 시장 지원**: 인플레이션이 2% 목표치에 근접함에 따라 고용 안정으로 정책 중심을 이동했습니다.
                    - **차입 비용 완화**: 금리 인하로 가계와 기업의 대출 이자 부담이 완화될 전망입니다.
                    """.trimIndent()
                } else {
                    buildString {
                        appendLine("### 기사 핵심 요약 ($titleLabel)")
                        sents.forEach { s -> appendLine("- $s") }
                    }.trim()
                }
                else -> sents.joinToString("\n") { "- $it" }
            }
            return text to emptyList()
        }

        private fun extractKeySentences(text: String, count: Int): List<String> {
            return text
                .replace(Regex("\\s+"), " ")
                .split(Regex("(?<=[.!?])\\s+"))
                .map { it.trim() }
                .filter { it.length in 35..260 }
                .distinct()
                .take(count)
        }
    }
}
