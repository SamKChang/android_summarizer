package com.gist.app.capture

import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates the 3-stage hybrid text capture pipeline (§5.2):
 * 1. Strategy 1: Accessibility tree walk + WebView article scoring + completeness check
 * 2. Strategy 2: If truncated and URL is known -> HTTP GET + Readability4J / JSON-LD extraction
 *    (accepted if extracted words > 1.2x a11y words and > 250 words)
 * 3. Strategy 3: Auto-scroll capture with rolling-window line deduplication and scroll-back restoration
 */
@Singleton
class CaptureEngine @Inject constructor(
    private val readabilityExtractor: ReadabilityExtractor,
) {
    /**
     * Runs Strategy 1 + Strategy 2 automatically.
     * If the result is still `LIKELY_TRUNCATED` and a scrollable node exists, the UI can prompt the user
     * to run [performAutoScrollCapture] (Strategy 3) per §5.2.
     */
    suspend fun captureFromNode(
        rootNode: AccessibilityNodeInfo?,
        sourcePackage: String,
        screenHeightPx: Int = 2400,
        maxWords: Int = 60_000,
    ): CapturedDocument {
        val snapshot = A11yTreeExtractor.snapshotFromNode(rootNode)
            ?: return CapturedDocument(
                sourcePackage = sourcePackage,
                title = null,
                url = null,
                text = "",
                wordCount = 0,
                method = CaptureMethod.A11Y_TREE,
                completeness = Completeness.LIKELY_TRUNCATED,
                capturedAt = Instant.now()
            )

        return captureFromSnapshot(snapshot, sourcePackage, screenHeightPx, maxWords)
    }

    suspend fun captureFromSnapshot(
        snapshot: A11yNodeSnapshot,
        sourcePackage: String,
        screenHeightPx: Int = 2400,
        maxWords: Int = 60_000,
    ): CapturedDocument {
        val a11yResult = A11yTreeExtractor.extract(snapshot, screenHeightPx)
        val cleanedA11yText = TextCleaner.clean(a11yResult.rawText, maxWords)
        val a11yWords = TextCleaner.countWords(cleanedA11yText)
        val visibleWords = TextCleaner.countWords(TextCleaner.clean(a11yResult.visibleOnlyText, maxWords))

        val completeness = CompletenessEvaluator.evaluate(
            cleanedText = cleanedA11yText,
            wordCount = a11yWords,
            visibleOnlyWordCount = visibleWords,
            canScrollForward = a11yResult.canScrollForward
        )

        val a11yDoc = CapturedDocument(
            sourcePackage = sourcePackage,
            title = a11yResult.title,
            url = a11yResult.url,
            text = cleanedA11yText,
            wordCount = a11yWords,
            method = CaptureMethod.A11Y_TREE,
            completeness = completeness,
            capturedAt = Instant.now()
        )

        if (completeness == Completeness.LIKELY_COMPLETE) {
            return a11yDoc
        }

        // Strategy 2: URL fetch + Readability if URL is known
        val url = a11yResult.url
        if (!url.isNullOrBlank()) {
            val fetched = readabilityExtractor.fetchAndExtract(url, maxWords)
            if (fetched != null && fetched.wordCount > 250 && fetched.wordCount > (a11yWords * 1.2).toInt()) {
                return CapturedDocument(
                    sourcePackage = sourcePackage,
                    title = fetched.title ?: a11yResult.title,
                    url = url,
                    text = fetched.text,
                    wordCount = fetched.wordCount,
                    method = CaptureMethod.URL_FETCH,
                    completeness = Completeness.LIKELY_COMPLETE,
                    capturedAt = Instant.now()
                )
            }
        }

        return a11yDoc
    }

    /**
     * Captures from shared text or URL (`Intent.ACTION_SEND` / `Intent.ACTION_PROCESS_TEXT` per §5.2).
     */
    suspend fun captureSharedContent(
        sharedText: String,
        sourcePackage: String = "android.intent.share",
        maxWords: Int = 60_000
    ): CapturedDocument {
        val trimmed = sharedText.trim()
        val urlCandidate = Regex("""https?://\S+""").find(trimmed)?.value

        if (urlCandidate != null && TextCleaner.countWords(trimmed) < 30) {
            val normalizedUrl = urlCandidate.normalizeUrl()
            val fetched = readabilityExtractor.fetchAndExtract(normalizedUrl, maxWords)
            if (fetched != null && fetched.wordCount > 50) {
                return CapturedDocument(
                    sourcePackage = sourcePackage,
                    title = fetched.title,
                    url = normalizedUrl,
                    text = fetched.text,
                    wordCount = fetched.wordCount,
                    method = CaptureMethod.URL_FETCH,
                    completeness = Completeness.LIKELY_COMPLETE,
                    capturedAt = Instant.now()
                )
            }
        }

        val cleaned = TextCleaner.clean(trimmed, maxWords)
        val words = TextCleaner.countWords(cleaned)
        return CapturedDocument(
            sourcePackage = sourcePackage,
            title = cleaned.lineSequence().firstOrNull()?.take(80) ?: "Shared text",
            url = urlCandidate?.normalizeUrl(),
            text = cleaned,
            wordCount = words,
            method = CaptureMethod.SHARED_TEXT,
            completeness = Completeness.LIKELY_COMPLETE,
            capturedAt = Instant.now()
        )
    }

    /**
     * Strategy 3: Auto-scroll capture (§5.2).
     * Scrolls forward up to 60 pages / 30 s, deduplicating lines with a rolling-window merge,
     * stopping when new text overlap is > 90%, then scrolling backward N times to restore position.
     */
    suspend fun performAutoScrollCapture(
        initialDoc: CapturedDocument,
        rootProvider: () -> AccessibilityNodeInfo?,
        swipeFallback: suspend () -> Boolean,
        onProgress: (page: Int, maxPages: Int) -> Unit = { _, _ -> },
        maxWords: Int = 60_000,
    ): CapturedDocument {
        val pages = mutableListOf<List<String>>()
        if (initialDoc.text.isNotBlank()) {
            pages.add(initialDoc.text.split("\n\n").filter { it.isNotBlank() })
        }

        var scrollsPerformed = 0
        val startTimeMillis = System.currentTimeMillis()
        val maxDurationMillis = 30_000L
        val maxPages = 60

        try {
            while (scrollsPerformed < maxPages && (System.currentTimeMillis() - startTimeMillis) < maxDurationMillis) {
                onProgress(scrollsPerformed + 1, maxPages)

                val root = rootProvider()
                val scrollableNode = root?.let { findMainScrollableNode(it) }
                val scrolled = if (scrollableNode != null) {
                    scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                } else {
                    swipeFallback()
                }

                if (!scrolled) break
                scrollsPerformed++
                delay(400L)

                val nextRoot = rootProvider() ?: break
                val snap = A11yTreeExtractor.snapshotFromNode(nextRoot) ?: break
                val extracted = A11yTreeExtractor.extract(snap)
                val pageParagraphs = TextCleaner.clean(extracted.rawText, maxWords)
                    .split("\n\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                if (pageParagraphs.isEmpty()) break

                val previousFlat = pages.flatten().toSet()
                val overlapCount = pageParagraphs.count { it in previousFlat }
                val overlapRatio = overlapCount.toDouble() / pageParagraphs.size.toDouble()

                pages.add(pageParagraphs)

                // Stop when new text overlap > 90% (§5.2)
                if (overlapRatio > 0.90) break
            }
        } catch (ce: CancellationException) {
            throw ce
        } finally {
            // Scroll back to restore original reading position (§5.2)
            val root = rootProvider()
            val scrollableNode = root?.let { findMainScrollableNode(it) }
            if (scrollableNode != null) {
                repeat(scrollsPerformed) {
                    scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    delay(50L)
                }
            }
        }

        val mergedText = mergeScrolledPages(pages, maxWords)
        val mergedWords = TextCleaner.countWords(mergedText)
        return initialDoc.copy(
            text = mergedText,
            wordCount = mergedWords,
            method = CaptureMethod.AUTO_SCROLL,
            completeness = Completeness.LIKELY_COMPLETE,
            capturedAt = Instant.now()
        )
    }

    /**
     * Line-level rolling-window deduplication across consecutively scrolled pages (§5.2).
     */
    internal fun mergeScrolledPages(pages: List<List<String>>, maxWords: Int = 60_000): String {
        val seenHashes = LinkedHashSet<Int>()
        val merged = mutableListOf<String>()
        for (page in pages) {
            for (para in page) {
                val normalized = para.trim()
                if (normalized.isEmpty()) continue
                val hash = normalized.lowercase().hashCode()
                if (seenHashes.add(hash)) {
                    merged.add(normalized)
                }
            }
        }
        return TextCleaner.clean(merged.joinToString("\n\n"), maxWords)
    }

    private fun findMainScrollableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var best: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val canForward = node.actionList.any {
                it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            if (node.isScrollable && canForward) {
                return node
            }
            if (node.isScrollable && best == null) {
                best = node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return best
    }
}
