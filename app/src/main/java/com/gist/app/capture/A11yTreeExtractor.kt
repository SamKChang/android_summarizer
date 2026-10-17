package com.gist.app.capture

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.Serializable

/**
 * Pure, serializable representation of an AccessibilityNodeInfo subtree so that Strategy 1's
 * DFS walker, WebView article-subtree Readability scoring, and URL discovery can be unit-tested
 * deterministically on fixtures without requiring a live Android Window (§5.2, §11).
 */
@Serializable
data class A11yNodeSnapshot(
    val className: String = "",
    val packageName: String = "",
    val viewIdResourceName: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val isClickable: Boolean = false,
    val isScrollable: Boolean = false,
    val canScrollForward: Boolean = false,
    val isPassword: Boolean = false,
    val isVisibleToUser: Boolean = true,
    val boundsTop: Int = 0,
    val boundsBottom: Int = 0,
    val children: List<A11yNodeSnapshot> = emptyList()
)

data class A11yExtractionResult(
    val title: String?,
    val url: String?,
    val rawText: String,
    val visibleOnlyText: String,
    val canScrollForward: Boolean,
)

object A11yTreeExtractor {

    private val BUTTON_CLASS_NAMES = setOf(
        "android.widget.Button",
        "android.widget.ImageButton",
        "androidx.appcompat.widget.AppCompatButton",
        "androidx.appcompat.widget.AppCompatImageButton",
        "android.widget.Switch",
        "android.widget.CheckBox",
        "android.widget.TabWidget"
    )

    private val KNOWN_URL_BAR_IDS = setOf(
        "com.android.chrome:id/url_bar",
        "com.android.chrome:id/search_box_text",
        "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
        "com.brave.browser:id/url_bar",
        "com.microsoft.emmx:id/url_bar"
    )

    private val URL_REGEX = Regex(
        """^(https?://)?([a-zA-Z0-9-]+\.)+[a-zA-Z]{2,}(/\S*)?$"""
    )

    fun snapshotFromNode(root: AccessibilityNodeInfo?, maxDepth: Int = 60): A11yNodeSnapshot? {
        if (root == null) return null
        // Skip our own overlay window if ever encountered
        if (root.packageName?.toString() == "com.gist.app") return null

        fun convert(node: AccessibilityNodeInfo, depth: Int): A11yNodeSnapshot {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val canForward = node.actionList.any {
                it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            val childSnapshots = mutableListOf<A11yNodeSnapshot>()
            if (depth < maxDepth) {
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    if (child.packageName?.toString() != "com.gist.app") {
                        childSnapshots.add(convert(child, depth + 1))
                    }
                }
            }
            return A11yNodeSnapshot(
                className = node.className?.toString().orEmpty(),
                packageName = node.packageName?.toString().orEmpty(),
                viewIdResourceName = node.viewIdResourceName,
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                isClickable = node.isClickable,
                isScrollable = node.isScrollable,
                canScrollForward = canForward,
                isPassword = node.isPassword,
                isVisibleToUser = node.isVisibleToUser,
                boundsTop = rect.top,
                boundsBottom = rect.bottom,
                children = childSnapshots
            )
        }

        return convert(root, 0)
    }

    fun extract(root: A11yNodeSnapshot, screenHeightPx: Int = 2400): A11yExtractionResult {
        var discoveredUrl: String? = null
        var canScrollForward = false
        val top15PercentPx = (screenHeightPx * 0.15).toInt()

        // First pass: URL discovery and scrollability check
        val stack = ArrayDeque<A11yNodeSnapshot>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node.isPassword) continue
            if (node.canScrollForward || node.isScrollable) {
                if (node.canScrollForward) canScrollForward = true
            }
            val viewId = node.viewIdResourceName
            val nodeText = node.text?.trim()
            if (!nodeText.isNullOrEmpty()) {
                if (viewId != null && viewId in KNOWN_URL_BAR_IDS) {
                    discoveredUrl = nodeText.normalizeUrl()
                } else if (discoveredUrl == null && node.boundsTop in 0..top15PercentPx && URL_REGEX.matches(nodeText) && !nodeText.contains(" ")) {
                    discoveredUrl = nodeText.normalizeUrl()
                }
            }
            for (i in node.children.indices.reversed()) {
                stack.addLast(node.children[i])
            }
        }

        // Check if there are WebView subtrees; if so, score each WebView subtree and pick the highest scoring one (§5.2)
        val webViewSubtrees = mutableListOf<A11yNodeSnapshot>()
        findWebViews(root, webViewSubtrees)

        val targetSubtree = if (webViewSubtrees.isNotEmpty()) {
            webViewSubtrees.maxByOrNull { scoreSubtree(it) } ?: root
        } else {
            root
        }

        val allParagraphs = mutableListOf<String>()
        val visibleParagraphs = mutableListOf<String>()
        var candidateTitle: String? = null

        collectTextIterativeDfs(
            root = targetSubtree,
            onParagraph = { text, isVisible, isHeadingCandidate ->
                if (candidateTitle == null && isHeadingCandidate && text.length in 12..180) {
                    candidateTitle = text
                }
                allParagraphs.add(text)
                if (isVisible) {
                    visibleParagraphs.add(text)
                }
            }
        )

        if (candidateTitle == null) {
            candidateTitle = allParagraphs.firstOrNull { it.length in 15..160 }
        }

        return A11yExtractionResult(
            title = candidateTitle,
            url = discoveredUrl,
            rawText = allParagraphs.joinToString("\n\n"),
            visibleOnlyText = visibleParagraphs.joinToString("\n\n"),
            canScrollForward = canScrollForward
        )
    }

    private fun findWebViews(node: A11yNodeSnapshot, out: MutableList<A11yNodeSnapshot>) {
        if (node.className.contains("WebView", ignoreCase = true)) {
            out.add(node)
            return
        }
        for (child in node.children) {
            findWebViews(child, out)
        }
    }

    /**
     * Simple Readability-like subtree scoring (§5.2):
     * `textLength * paragraphCount / (1 + linkDensity * 10)`
     */
    internal fun scoreSubtree(subtree: A11yNodeSnapshot): Double {
        var totalChars = 0
        var paragraphCount = 0
        var clickableChars = 0

        val stack = ArrayDeque<A11yNodeSnapshot>()
        stack.addLast(subtree)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (n.isPassword) continue
            val t = n.text?.trim().orEmpty()
            if (t.isNotEmpty() && ! isChromeOrButton(n, t)) {
                totalChars += t.length
                if (t.length >= 40) paragraphCount++
                if (n.isClickable) clickableChars += t.length
            }
            for (c in n.children) stack.addLast(c)
        }

        if (totalChars == 0) return 0.0
        val linkDensity = clickableChars.toDouble() / totalChars.toDouble()
        return (totalChars.toDouble() * (paragraphCount + 1)) / (1.0 + linkDensity * 10.0)
    }

    private fun isChromeOrButton(node: A11yNodeSnapshot, text: String): Boolean {
        if (node.className in BUTTON_CLASS_NAMES) return true
        if (node.viewIdResourceName in KNOWN_URL_BAR_IDS) return true
        if (node.isClickable && text.length < 25) return true
        return false
    }

    private fun collectTextIterativeDfs(
        root: A11yNodeSnapshot,
        onParagraph: (text: String, isVisible: Boolean, isHeadingCandidate: Boolean) -> Unit
    ) {
        val stack = ArrayDeque<Pair<A11yNodeSnapshot, Boolean>>()
        stack.addLast(root to false)

        while (stack.isNotEmpty()) {
            val (node, parentRepeatedShort) = stack.removeLast()
            if (node.isPassword) continue
            if (node.packageName == "com.gist.app") continue

            val raw = (node.text?.takeIf { it.isNotBlank() } ?: node.contentDescription?.takeIf { it.isNotBlank() })?.trim()
            if (!raw.isNullOrEmpty() && !isChromeOrButton(node, raw) && !parentRepeatedShort) {
                // Skip if child has identical text to avoid double-counting container + leaf
                val childHasSameText = node.children.any {
                    it.text?.trim() == raw || it.contentDescription?.trim() == raw
                }
                if (!childHasSameText) {
                    val isHeading = node.className.contains("Heading", ignoreCase = true) ||
                        (raw.length in 15..140 && !raw.endsWith("."))
                    onParagraph(raw, node.isVisibleToUser, isHeading)
                }
            }

            // Detect very short texts repeated across siblings (nav bars / tab strips)
            val shortSiblingCount = node.children.count {
                val t = it.text?.trim().orEmpty()
                t.isNotEmpty() && t.length < 18
            }
            val siblingsLookLikeNavChrome = node.children.size >= 4 && shortSiblingCount >= node.children.size - 1

            for (i in node.children.indices.reversed()) {
                val child = node.children[i]
                val childText = child.text?.trim().orEmpty()
                val skipShortNav = siblingsLookLikeNavChrome && childText.length < 18
                stack.addLast(child to skipShortNav)
            }
        }
    }
}
