package com.gist.app.share

import com.gist.app.data.SecureStore
import com.gist.app.settings.GistSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownToDocsConverterTest {

    @Test
    fun `MarkdownToDocsConverter computes exact UTF-16 code unit spans with emoji and CJK characters`() {
        // 🚀 is a surrogate pair in UTF-16 (length = 2 UTF-16 code units).
        // 東京 is 2 BMP CJK characters (length = 2 UTF-16 code units).
        val markdown = """
            # Gist 🚀 東京
            
            Intro with **bold text** and a [Source Link](https://ft.com/fed).
            
            - First bullet item
            - Second *italic* item
        """.trimIndent()

        val result = MarkdownToDocsConverter.convert(markdown, baseIndex = 1)
        val plain = result.plainText

        // Verify every style operation's UTF-16 [startIndex, endIndex) matches the exact substring in plainText
        val headingOp = result.styleOperations.filterIsInstance<DocStyleOperation.HeadingStyle>().single()
        assertEquals(1, headingOp.level)
        val headingSlice = plain.substring(headingOp.range.startIndex - 1, headingOp.range.endIndex - 1).trim()
        assertEquals("Gist 🚀 東京", headingSlice)

        val boldOp = result.styleOperations.filterIsInstance<DocStyleOperation.TextStyle>().first { it.bold }
        val boldSlice = plain.substring(boldOp.range.startIndex - 1, boldOp.range.endIndex - 1)
        assertEquals("bold text", boldSlice)

        val linkOp = result.styleOperations.filterIsInstance<DocStyleOperation.TextStyle>().first { it.linkUrl != null }
        val linkSlice = plain.substring(linkOp.range.startIndex - 1, linkOp.range.endIndex - 1)
        assertEquals("Source Link", linkSlice)
        assertEquals("https://ft.com/fed", linkOp.linkUrl)

        val italicOp = result.styleOperations.filterIsInstance<DocStyleOperation.TextStyle>().first { it.italic }
        val italicSlice = plain.substring(italicOp.range.startIndex - 1, italicOp.range.endIndex - 1)
        assertEquals("italic", italicSlice)

        val bulletOps = result.styleOperations.filterIsInstance<DocStyleOperation.BulletListStyle>()
        assertEquals(2, bulletOps.size)
        assertTrue(result.requestsJson.size >= 6)
    }

    @Test
    fun `DocsExporter creates Drive folder, creates Doc, sends batchUpdate, and attaches folder parent`() = runTest {
        val requestsReceived = mutableListOf<String>()
        val mockClient = HttpClient(MockEngine) {
            engine {
                addHandler { req ->
                    requestsReceived.add("${req.method.value} ${req.url}")
                    when {
                        req.url.encodedPath.endsWith("/drive/v3/files") && req.method.value == "GET" -> {
                            respond(
                                content = """{"files": [{"id": "folder_gist_123", "name": "Gist"}]}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json")
                            )
                        }
                        req.url.encodedPath.endsWith("/v1/documents") && req.method.value == "POST" -> {
                            respond(
                                content = """{"documentId": "doc_abc_789", "title": "Gist: Fed Cut"}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json")
                            )
                        }
                        req.url.encodedPath.endsWith(":batchUpdate") && req.method.value == "POST" -> {
                            respond(
                                content = """{"documentId": "doc_abc_789"}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json")
                            )
                        }
                        req.url.encodedPath.contains("/drive/v3/files/doc_abc_789") && req.method.value == "PATCH" -> {
                            respond(
                                content = """{"id": "doc_abc_789"}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json")
                            )
                        }
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                }
            }
        }

        val exporter = DocsExporter(
            context = io.mockk.mockk(relaxed = true),
            httpClient = mockClient,
            googleAuthManager = io.mockk.mockk(relaxed = true)
        )

        val created = exporter.createFormattedGoogleDoc(
            accessToken = "ya29.mock_token",
            title = "Gist: Fed Cut",
            markdown = "### TL;DR Summary\n- Fed cut 50 bps.",
            folderName = "Gist"
        )

        assertNotNull(created)
        assertEquals("doc_abc_789", created!!.docId)
        assertEquals("https://docs.google.com/document/d/doc_abc_789/edit", created.docUrl)
        assertEquals(4, requestsReceived.size)
    }
}
