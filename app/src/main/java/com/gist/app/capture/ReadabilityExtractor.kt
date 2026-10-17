package com.gist.app.capture

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.dankito.readability4j.Readability4J
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

data class HtmlExtractedArticle(
    val title: String?,
    val text: String,
    val wordCount: Int
)

/**
 * Strategy 2: URL fetch + Readability extraction (§5.2).
 * - Ktor GET with mobile Chrome UA, 10s timeout, 5MB cap.
 * - Parses HTML with jsoup & Readability4J, plus checks JSON-LD `articleBody` and `<meta property="og:title">`.
 */
@Singleton
class ReadabilityExtractor @Inject constructor(
    private val httpClient: HttpClient
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetchAndExtract(url: String, maxWords: Int = 60_000): HtmlExtractedArticle? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(10_000L) {
                try {
                    val response = httpClient.get(url) {
                        header(HttpHeaders.UserAgent, MOBILE_CHROME_UA)
                        header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    }
                    if (response.status != HttpStatusCode.OK) return@withTimeoutOrNull null

                    val html = response.bodyAsText().take(MAX_BYTES.toInt())
                    extractFromHtml(url, html, maxWords)
                } catch (_: Exception) {
                    null
                }
            }
        }

    fun extractFromHtml(url: String, html: String, maxWords: Int = 60_000): HtmlExtractedArticle? {
        if (html.isBlank()) return null

        val doc = Jsoup.parse(html, url)
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
        val docTitle = ogTitle ?: doc.title().takeIf { it.isNotBlank() }

        // 1. Check JSON-LD for `articleBody`
        val jsonLdBody = extractJsonLdArticleBody(doc)

        // 2. Run Readability4J
        val readability = Readability4J(url, html)
        val article = readability.parse()
        val readabilityTitle = article.title?.takeIf { it.isNotBlank() }
        val readabilityContentHtml = article.contentWithUtf8Encoding ?: article.content
        val readabilityText = if (!readabilityContentHtml.isNullOrBlank()) {
            val contentDoc = Jsoup.parse(readabilityContentHtml)
            val blocks = contentDoc.select("p, h1, h2, h3, h4, li, blockquote")
            if (blocks.isNotEmpty()) {
                blocks.mapNotNull { el ->
                    // Avoid duplicate nested paragraphs
                    if (el.tagName() == "li" && el.select("p").isNotEmpty()) null
                    else el.text().trim().takeIf { it.isNotEmpty() }
                }.joinToString("\n\n")
            } else {
                article.textContent?.trim().orEmpty()
            }
        } else {
            article.textContent?.trim().orEmpty()
        }

        // Pick whichever gave richer article content
        val bestRaw = if (TextCleaner.countWords(jsonLdBody) > TextCleaner.countWords(readabilityText)) {
            jsonLdBody
        } else {
            readabilityText
        }

        val cleaned = TextCleaner.clean(bestRaw, maxWords)
        val words = TextCleaner.countWords(cleaned)
        if (words == 0) return null

        return HtmlExtractedArticle(
            title = readabilityTitle ?: docTitle,
            text = cleaned,
            wordCount = words
        )
    }

    private fun extractJsonLdArticleBody(doc: org.jsoup.nodes.Document): String {
        val scripts = doc.select("script[type=application/ld+json]")
        for (script in scripts) {
            val rawJson = script.data().trim()
            if (rawJson.isEmpty()) continue
            try {
                val element = json.parseToJsonElement(rawJson)
                val body = findArticleBodyRecursive(element)
                if (!body.isNullOrBlank()) return body
            } catch (_: Exception) {
            }
        }
        return ""
    }

    private fun findArticleBodyRecursive(element: JsonElement): String? {
        when (element) {
            is JsonObject -> {
                val bodyEl = element["articleBody"]
                if (bodyEl is kotlinx.serialization.json.JsonPrimitive) {
                    val body = bodyEl.content
                    if (body.isNotBlank()) return body
                }
                for ((_, value) in element) {
                    val found = findArticleBodyRecursive(value)
                    if (!found.isNullOrBlank()) return found
                }
            }
            is JsonArray -> {
                for (item in element) {
                    val found = findArticleBodyRecursive(item)
                    if (!found.isNullOrBlank()) return found
                }
            }
            else -> {}
        }
        return null
    }

    companion object {
        private const val MAX_BYTES = 5L * 1024L * 1024L // 5 MB cap per §5.2
        const val MOBILE_CHROME_UA =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
