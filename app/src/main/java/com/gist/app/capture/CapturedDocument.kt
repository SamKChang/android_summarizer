package com.gist.app.capture

import java.net.URI
import java.time.Instant

enum class CaptureMethod {
    A11Y_TREE,
    URL_FETCH,
    AUTO_SCROLL,
    SHARED_TEXT
}

enum class Completeness {
    LIKELY_COMPLETE,
    LIKELY_TRUNCATED,
    UNKNOWN
}

data class CapturedDocument(
    val sourcePackage: String,
    val title: String?,
    val url: String?,
    val text: String, // cleaned, paragraph-separated
    val wordCount: Int,
    val method: CaptureMethod,
    val completeness: Completeness,
    val capturedAt: Instant = Instant.now(),
) {
    val docKey: String
        get() = url?.normalizeUrl() ?: "$sourcePackage:${title.orEmpty().hashCode()}"
}

private val TRACKING_PARAMS = setOf(
    "fbclid", "gclid", "dclid", "msclkid", "mc_cid", "mc_eid", "ref", "yclid", "igsh"
)

fun String.normalizeUrl(): String {
    val trimmed = this.trim()
    if (trimmed.isEmpty()) return ""
    val withScheme = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        "https://$trimmed"
    } else {
        trimmed
    }
    return try {
        val uri = URI(withScheme)
        val scheme = (uri.scheme ?: "https").lowercase()
        val host = (uri.host ?: return withScheme).lowercase().removePrefix("www.")
        val path = (uri.rawPath ?: "").trimEnd('/')
        val filteredQuery = uri.rawQuery
            ?.split("&")
            ?.filter { param ->
                val key = param.substringBefore("=").lowercase()
                key.isNotBlank() && !key.startsWith("utm_") && key !in TRACKING_PARAMS
            }
            ?.joinToString("&")
            ?.takeIf { it.isNotEmpty() }

        buildString {
            append(scheme).append("://").append(host)
            if (path.isNotEmpty()) append(path)
            if (filteredQuery != null) append("?").append(filteredQuery)
        }
    } catch (_: Exception) {
        withScheme
    }
}
