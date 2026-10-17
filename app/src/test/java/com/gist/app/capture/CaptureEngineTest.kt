package com.gist.app.capture

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureEngineTest {

    @Test
    fun `normalizeUrl strips tracking parameters and normalizes scheme and host`() {
        val raw = "www.FT.com/markets/central-banks/fed-rate-cut/?utm_source=twitter&utm_medium=social&fbclid=12345&section=us"
        val normalized = raw.normalizeUrl()
        assertEquals("https://ft.com/markets/central-banks/fed-rate-cut?section=us", normalized)
    }

    @Test
    fun `TextCleaner removes boilerplate, collapses whitespace, and caps words`() {
        val dirty = """
            We use cookies to improve your experience.
            Accept all cookies
            
            Federal Reserve   Enacts   Aggressive 50 bps Cut.
            
            Advertisement
            
            Subscribe to our newsletter
            
            The move brings the federal funds target rate to a range between 4.75% and 5%.
            
            Share this article
        """.trimIndent()

        val cleaned = TextCleaner.clean(dirty, maxWords = 100)
        assertFalse(cleaned.contains("cookies", ignoreCase = true))
        assertFalse(cleaned.contains("Advertisement", ignoreCase = true))
        assertFalse(cleaned.contains("Subscribe", ignoreCase = true))
        assertTrue(cleaned.contains("Federal Reserve Enacts Aggressive 50 bps Cut."))
        assertTrue(cleaned.contains("4.75% and 5%."))

        val capped = TextCleaner.clean(dirty, maxWords = 5)
        assertEquals(5, TextCleaner.countWords(capped))
    }

    @Test
    fun `CompletenessEvaluator flags short scrollable or unpunctuated articles as LIKELY_TRUNCATED`() {
        val shortText = "This is a short teaser paragraph that has fewer than three hundred words."
        assertEquals(
            Completeness.LIKELY_TRUNCATED,
            CompletenessEvaluator.evaluate(
                cleanedText = shortText,
                wordCount = TextCleaner.countWords(shortText),
                visibleOnlyWordCount = TextCleaner.countWords(shortText),
                canScrollForward = true
            )
        )

        val cutOffSentence = ("Word ".repeat(350)) + "and then the sentence abruptly cuts off without punctuation"
        assertEquals(
            Completeness.LIKELY_TRUNCATED,
            CompletenessEvaluator.evaluate(
                cleanedText = cutOffSentence,
                wordCount = 359,
                visibleOnlyWordCount = 100,
                canScrollForward = false
            )
        )

        val completeArticle = (1..40).joinToString("\n\n") {
            "Paragraph $it discusses the monetary policy trajectory and inflation cooling toward the 2 percent target."
        }
        val completeWords = TextCleaner.countWords(completeArticle)
        assertEquals(
            Completeness.LIKELY_COMPLETE,
            CompletenessEvaluator.evaluate(
                cleanedText = completeArticle,
                wordCount = completeWords,
                visibleOnlyWordCount = 120,
                canScrollForward = false
            )
        )
    }

    @Test
    fun `A11yTreeExtractor discovers Chrome URL bar, skips passwords, and picks highest-scoring WebView`() {
        val navWebView = A11yNodeSnapshot(
            className = "android.webkit.WebView",
            children = listOf(
                A11yNodeSnapshot(text = "Home", isClickable = true),
                A11yNodeSnapshot(text = "Markets", isClickable = true),
                A11yNodeSnapshot(text = "Opinion", isClickable = true),
            )
        )

        val articleParagraph1 =
            "WASHINGTON — The Federal Reserve voted Wednesday to lower benchmark borrowing costs by a half percentage point, pivoting boldly toward economic preservation."
        val articleParagraph2 =
            "The move brings the federal funds target rate to a range between 4.75% and 5%, concluding a two-year campaign of monetary tightening."

        val mainArticleWebView = A11yNodeSnapshot(
            className = "android.webkit.WebView",
            children = listOf(
                A11yNodeSnapshot(
                    className = "android.widget.TextView",
                    text = "Federal Reserve Enacts Aggressive 50 bps Interest Rate Cut as Inflation Cools"
                ),
                A11yNodeSnapshot(
                    className = "android.widget.EditText",
                    text = "secret_password_123",
                    isPassword = true
                ),
                A11yNodeSnapshot(
                    className = "android.widget.TextView",
                    text = articleParagraph1
                ),
                A11yNodeSnapshot(
                    className = "android.widget.TextView",
                    text = articleParagraph2,
                    isVisibleToUser = false
                )
            )
        )

        val root = A11yNodeSnapshot(
            className = "android.widget.FrameLayout",
            packageName = "com.android.chrome",
            children = listOf(
                A11yNodeSnapshot(
                    className = "android.widget.EditText",
                    viewIdResourceName = "com.android.chrome:id/url_bar",
                    text = "ft.com/markets/central-banks/fed-rate-cut?utm_source=rss",
                    boundsTop = 40,
                    boundsBottom = 110
                ),
                navWebView,
                mainArticleWebView
            )
        )

        val extracted = A11yTreeExtractor.extract(root)
        assertEquals("https://ft.com/markets/central-banks/fed-rate-cut", extracted.url)
        assertEquals("Federal Reserve Enacts Aggressive 50 bps Interest Rate Cut as Inflation Cools", extracted.title)
        assertTrue(extracted.rawText.contains(articleParagraph1))
        assertTrue(extracted.rawText.contains(articleParagraph2))
        assertFalse(extracted.rawText.contains("secret_password_123"))
    }

    @Test
    fun `CaptureEngine falls back to Strategy 2 Readability when Strategy 1 is truncated`() = runTest {
        val paragraphs = (1..35).map { idx ->
            "Section $idx: The Federal Reserve lowered benchmark borrowing costs by 50 basis points as inflation cooled toward the two percent objective."
        }
        val fullArticleBody = paragraphs.joinToString("\n\n")
        val jsonBodyEscaped = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.serializer<String>(),
            fullArticleBody
        )
        val htmlResponse = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta property="og:title" content="Fed Enacts 50 bps Rate Cut" />
                <script type="application/ld+json">
                {
                  "@context": "https://schema.org",
                  "@type": "NewsArticle",
                  "headline": "Fed Enacts 50 bps Rate Cut",
                  "articleBody": $jsonBodyEscaped
                }
                </script>
            </head>
            <body><article><h1>Fed Enacts 50 bps Rate Cut</h1>${paragraphs.joinToString("\n") { "<p>$it</p>" }}</article></body>
            </html>
        """.trimIndent()

        val mockClient = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = htmlResponse,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8")
                    )
                }
            }
        }

        val readability = ReadabilityExtractor(mockClient)
        val engine = CaptureEngine(readability)

        val truncatedSnapshot = A11yNodeSnapshot(
            className = "android.widget.FrameLayout",
            packageName = "com.android.chrome",
            canScrollForward = true,
            children = listOf(
                A11yNodeSnapshot(
                    viewIdResourceName = "com.android.chrome:id/url_bar",
                    text = "https://ft.com/markets/central-banks/fed-rate-cut"
                ),
                A11yNodeSnapshot(
                    text = "Short visible teaser before paywall or offscreen scroll."
                )
            )
        )

        val doc = engine.captureFromSnapshot(truncatedSnapshot, "com.android.chrome")
        assertEquals(
            "Expected URL_FETCH but got ${doc.method} (url=${doc.url}, completeness=${doc.completeness}, words=${doc.wordCount}, text=${doc.text})",
            CaptureMethod.URL_FETCH,
            doc.method
        )
        assertEquals(Completeness.LIKELY_COMPLETE, doc.completeness)
        assertEquals("Fed Enacts 50 bps Rate Cut", doc.title)
        assertTrue(doc.wordCount > 300)
    }

    @Test
    fun `20-article corpus extraction achieves at least 95 percent word accuracy and LIKELY_COMPLETE`() {
        val sources = listOf(
            "nytimes.com" to "Federal Reserve Monetary Policy Shift",
            "ft.com" to "Central Banks Pivot Toward Growth",
            "theguardian.com" to "Global Inflation Cools Across G7 Economies",
            "substack.com" to "Macroeconomic Deep Dive on Treasury Yields",
            "medium.com" to "Understanding Quantitative Tightening and Rate Cuts",
            "reuters.com" to "US Labor Market Shows Gradual Rebalancing",
            "arstechnica.com" to "Semiconductor Supply Chains and Capital Expenditure",
            "bloomberg.com" to "Bond Traders Price In Additional Easing",
            "news.google.com" to "Weekly Economic Roundup and Policy Analysis",
            "economist.com" to "The New Era of Neutral Interest Rates",
            "wsj.com" to "Mortgage Rates Fall Following FOMC Decision",
            "bbc.com" to "Bank of England and ECB Weigh Similar Policy Paths",
            "cnbc.com" to "Equity Markets Rally on 50 Basis Point Cut",
            "apnews.com" to "Powell Signals Data-Dependent Approach Ahead",
            "theatlantic.com" to "How Post-Pandemic Inflation Was Tamed",
            "wired.com" to "AI Infrastructure Investment Under Lower Rates",
            "Axios.com" to "What the Rate Cut Means for Small Businesses",
            "politico.com" to "Fiscal and Monetary Policy Intersections in 2026",
            "nature.com" to "Quantitative Models of Macroeconomic Resilience",
            "stratechery.com" to "Tech Valuations and Discount Rate Sensitivity"
        )

        val readability = ReadabilityExtractor(HttpClient(MockEngine { respond("") }))
        var completeCount = 0

        for ((domain, headline) in sources) {
            val expectedParagraphs = (1..25).map { p ->
                "In paragraph $p of the $domain analysis titled '$headline', economists examine how empirical inflation indicators converged toward the target range while employment remained resilient across sectors."
            }
            val expectedBody = expectedParagraphs.joinToString("\n\n")
            val expectedWords = TextCleaner.countWords(expectedBody)

            val html = """
                <html>
                <head><title>$headline</title></head>
                <body>
                    <header><nav><a>Subscribe</a><a>Sign In</a></nav></header>
                    <main>
                        <article>
                            <h1>$headline</h1>
                            ${expectedParagraphs.joinToString("\n") { "<p>$it</p>" }}
                        </article>
                    </main>
                    <footer><p>Accept all cookies</p></footer>
                </body>
                </html>
            """.trimIndent()

            val extracted = readability.extractFromHtml("https://$domain/article", html)
            assertNotNull(extracted)
            val wordRatio = extracted!!.wordCount.toDouble() / expectedWords.toDouble()
            assertTrue("Expected >= 95% words for $domain, got $wordRatio", wordRatio >= 0.95)

            val completeness = CompletenessEvaluator.evaluate(
                cleanedText = extracted.text,
                wordCount = extracted.wordCount,
                visibleOnlyWordCount = 120,
                canScrollForward = false
            )
            if (completeness == Completeness.LIKELY_COMPLETE) {
                completeCount++
            }
        }

        assertEquals(20, completeCount)
    }

    @Test
    fun `Strategy 3 mergeScrolledPages deduplicates overlapping paragraphs across scrolled viewports`() {
        val engine = CaptureEngine(ReadabilityExtractor(HttpClient(MockEngine { respond("") })))
        val page1 = listOf(
            "Paragraph 1: The Federal Reserve lowered interest rates by 50 basis points.",
            "Paragraph 2: Chair Jerome Powell emphasized labor market resilience."
        )
        val page2 = listOf(
            "Paragraph 2: Chair Jerome Powell emphasized labor market resilience.",
            "Paragraph 3: Bond yields adjusted across the two-year and ten-year curve."
        )
        val page3 = listOf(
            "Paragraph 3: Bond yields adjusted across the two-year and ten-year curve.",
            "Paragraph 4: Markets now price in two additional quarter-point reductions."
        )

        val merged = engine.mergeScrolledPages(listOf(page1, page2, page3))
        val paragraphs = merged.split("\n\n")
        assertEquals(4, paragraphs.size)
        assertTrue(paragraphs[0].startsWith("Paragraph 1:"))
        assertTrue(paragraphs[3].startsWith("Paragraph 4:"))
    }

    @Test
    fun `captureSharedContent extracts article when URL is shared or cleans raw text when passage is shared`() = runTest {
        val bodyParagraphs = (1..10).joinToString("\n") {
            "<p>Paragraph $it of the shared article provides detailed analysis of central bank policy and inflation trends.</p>"
        }
        val mockClient = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = "<html><head><title>Shared URL Article</title></head><body><article>$bodyParagraphs</article></body></html>",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/html")
                    )
                }
            }
        }
        val engine = CaptureEngine(ReadabilityExtractor(mockClient))

        val urlDoc = engine.captureSharedContent("Check this out: https://reuters.com/markets/rates?utm_source=app")
        assertEquals(CaptureMethod.URL_FETCH, urlDoc.method)
        assertEquals("https://reuters.com/markets/rates", urlDoc.url)
        assertEquals("Shared URL Article", urlDoc.title)

        val longPassage = (1..40).joinToString(" ") { "Selected text passage from a PDF reader." }
        val textDoc = engine.captureSharedContent(longPassage)
        assertEquals(CaptureMethod.SHARED_TEXT, textDoc.method)
        assertTrue(textDoc.wordCount >= 200)
    }
}
