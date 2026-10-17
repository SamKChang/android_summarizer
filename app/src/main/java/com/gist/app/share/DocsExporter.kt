package com.gist.app.share

import android.content.Context
import android.content.Intent
import com.gist.app.capture.CapturedDocument
import com.gist.app.chat.UiChatMessage
import com.gist.app.llm.MessageRole
import com.gist.app.settings.DocExportContent
import com.gist.app.settings.GistSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ExportOutcome {
    data class GoogleDocCreated(val docId: String, val docUrl: String, val folderName: String) : ExportOutcome
    data class FallbackMarkdownShared(val markdown: String) : ExportOutcome
}

/**
 * Exports a chat session to a formatted Google Doc inside the user's "Gist" Drive folder (§7.2):
 * 1. Ensures Drive folder "Gist" exists (`files.list` / `files.create`).
 * 2. Creates the document (`POST https://docs.googleapis.com/v1/documents`).
 * 3. Formats the content via `documents.batchUpdate` using [MarkdownToDocsConverter].
 * 4. Moves the document into the "Gist" folder (`PATCH https://www.googleapis.com/drive/v3/files/{id}?addParents=...`).
 * 5. Opens the Android Share sheet with the Doc URL (or falls back to Markdown share if offline/unauthenticated).
 */
@Singleton
class DocsExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: HttpClient,
    private val googleAuthManager: GoogleAuthManager,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun exportSession(
        doc: CapturedDocument,
        messages: List<UiChatMessage>,
        backendDisplayName: String,
        modelName: String,
        settings: GistSettings,
    ): ExportOutcome {
        val markdown = formatSessionAsMarkdown(doc, messages, backendDisplayName, modelName, settings)
        val token = googleAuthManager.getAccessToken()

        if (token != null) {
            val created = createFormattedGoogleDoc(
                accessToken = token,
                title = "Gist: ${doc.title ?: "Article Summary"}",
                markdown = markdown,
                folderName = settings.driveFolderName.ifBlank { "Gist" }
            )
            if (created != null) {
                return created
            }
        }

        return ExportOutcome.FallbackMarkdownShared(markdown)
    }

    internal suspend fun createFormattedGoogleDoc(
        accessToken: String,
        title: String,
        markdown: String,
        folderName: String = "Gist"
    ): ExportOutcome.GoogleDocCreated? {
        return try {
            // 1. Ensure folder exists in Drive
            val folderId = ensureDriveFolder(accessToken, folderName)

            // 2. Create Google Doc
            val createResp = httpClient.post("https://docs.googleapis.com/v1/documents") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("title", title) }.toString())
            }
            if (createResp.status != HttpStatusCode.OK) return null
            val docObj = json.parseToJsonElement(createResp.bodyAsText()).jsonObject
            val documentId = docObj["documentId"]?.jsonPrimitive?.content ?: return null

            // 3. Convert Markdown -> Docs batchUpdate requests
            val conversion = MarkdownToDocsConverter.convert(markdown, baseIndex = 1)
            if (conversion.requestsJson.isNotEmpty()) {
                val batchBody = MarkdownToDocsConverter.buildBatchUpdateBody(conversion.requestsJson)
                val updateResp = httpClient.post("https://docs.googleapis.com/v1/documents/$documentId:batchUpdate") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    contentType(ContentType.Application.Json)
                    setBody(batchBody.toString())
                }
                if (updateResp.status != HttpStatusCode.OK) return null
            }

            // 4. Add Doc to Drive folder if folderId was obtained
            if (folderId != null) {
                httpClient.patch("https://www.googleapis.com/drive/v3/files/$documentId") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    parameter("addParents", folderId)
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            }

            val docUrl = "https://docs.google.com/document/d/$documentId/edit"
            ExportOutcome.GoogleDocCreated(
                docId = documentId,
                docUrl = docUrl,
                folderName = folderName
            )
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun ensureDriveFolder(accessToken: String, folderName: String): String? {
        return try {
            val safeName = folderName.replace("'", "\\'")
            val listResp = httpClient.get("https://www.googleapis.com/drive/v3/files") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                parameter(
                    "q",
                    "mimeType='application/vnd.google-apps.folder' and name='$safeName' and trashed=false"
                )
                parameter("fields", "files(id,name)")
            }
            if (listResp.status == HttpStatusCode.OK) {
                val files = json.parseToJsonElement(listResp.bodyAsText()).jsonObject["files"]?.jsonArray
                val existingId = files?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
                if (!existingId.isNullOrBlank()) return existingId
            }

            val createFolderBody = buildJsonObject {
                put("name", folderName)
                put("mimeType", "application/vnd.google-apps.folder")
            }
            val createResp = httpClient.post("https://www.googleapis.com/drive/v3/files") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(createFolderBody.toString())
            }
            if (createResp.status == HttpStatusCode.OK) {
                json.parseToJsonElement(createResp.bodyAsText()).jsonObject["id"]?.jsonPrimitive?.content
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun formatSessionAsMarkdown(
        doc: CapturedDocument,
        messages: List<UiChatMessage>,
        backendDisplayName: String,
        modelName: String,
        settings: GistSettings
    ): String {
        val dateStr = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(doc.capturedAt)

        val selectedMessages = if (settings.docExportContent == DocExportContent.LAST_ANSWER) {
            messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.let { listOf(it) } ?: messages
        } else {
            messages
        }

        val allCitations = selectedMessages.flatMap { it.citations }.distinctBy { it.url }

        return buildString {
            appendLine("# Gist: ${doc.title ?: "Captured Article"}")
            appendLine()
            if (!doc.url.isNullOrBlank()) {
                appendLine("**Source:** [${doc.url}](${doc.url})")
            }
            appendLine("**Captured:** $dateStr · ${doc.wordCount} words")
            appendLine("**Model:** $backendDisplayName ($modelName)")
            appendLine()

            for (msg in selectedMessages) {
                if (msg.role == MessageRole.USER) {
                    appendLine("**You:** ${msg.content}")
                    appendLine()
                } else {
                    appendLine(msg.content.trim())
                    appendLine()
                }
            }

            if (allCitations.isNotEmpty()) {
                appendLine("## Sources")
                allCitations.forEachIndexed { idx, cit ->
                    appendLine("${idx + 1}. [${cit.title}](${cit.url})")
                }
                appendLine()
            }

            if (settings.includeCapturedArticleInDoc && doc.text.isNotBlank()) {
                appendLine("## Appendix: Captured Article Text")
                appendLine(doc.text)
            }
        }.trim()
    }

    fun launchAndroidShareSheet(textOrUrl: String, subject: String? = null) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, textOrUrl)
            if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        val chooser = Intent.createChooser(sendIntent, "Share Gist").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
