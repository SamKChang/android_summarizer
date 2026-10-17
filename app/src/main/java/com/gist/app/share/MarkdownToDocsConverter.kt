package com.gist.app.share

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.parser.Parser

data class Utf16Span(
    val startIndex: Int,
    val endIndex: Int
)

sealed interface DocStyleOperation {
    data class HeadingStyle(val range: Utf16Span, val level: Int) : DocStyleOperation
    data class BulletListStyle(val range: Utf16Span, val ordered: Boolean) : DocStyleOperation
    data class TextStyle(
        val range: Utf16Span,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val linkUrl: String? = null
    ) : DocStyleOperation
}

data class DocsConversionResult(
    val plainText: String,
    val styleOperations: List<DocStyleOperation>,
    val requestsJson: JsonArray
)

/**
 * Parses Markdown with **commonmark-java** and emits Google Docs API `batchUpdate` requests,
 * computing exact UTF-16 code-unit indexes (`String.length` in Kotlin/JVM is UTF-16 code units,
 * accurately handling surrogate pairs like emojis and CJK characters) per §7.2 & §11.
 */
object MarkdownToDocsConverter {

    private val parser: Parser = Parser.builder().build()

    /**
     * Converts a Markdown string into a single `insertText` request at [baseIndex] (default 1, the start of a Google Doc body)
     * followed by style requests (`updateParagraphStyle`, `createParagraphBullets`, `updateTextStyle`).
     */
    fun convert(markdown: String, baseIndex: Int = 1): DocsConversionResult {
        val documentNode: Node = parser.parse(markdown)
        val buffer = StringBuilder()
        val operations = mutableListOf<DocStyleOperation>()

        fun currentUtf16Index(): Int = baseIndex + buffer.length

        val visitor = object : AbstractVisitor() {
            private var inListItem = false
            private var currentListOrdered = false

            override fun visit(heading: Heading) {
                val start = currentUtf16Index()
                visitChildren(heading)
                if (!buffer.endsWith("\n")) buffer.append("\n")
                val end = currentUtf16Index()
                if (end > start) {
                    operations.add(DocStyleOperation.HeadingStyle(Utf16Span(start, end), heading.level.coerceIn(1, 6)))
                }
            }

            override fun visit(paragraph: Paragraph) {
                val start = currentUtf16Index()
                visitChildren(paragraph)
                if (!buffer.endsWith("\n")) buffer.append("\n")
                val end = currentUtf16Index()
                if (inListItem && end > start) {
                    operations.add(DocStyleOperation.BulletListStyle(Utf16Span(start, end), currentListOrdered))
                } else if (!inListItem) {
                    buffer.append("\n")
                }
            }

            override fun visit(bulletList: BulletList) {
                val prevOrdered = currentListOrdered
                currentListOrdered = false
                visitChildren(bulletList)
                currentListOrdered = prevOrdered
                if (!buffer.endsWith("\n\n")) buffer.append("\n")
            }

            override fun visit(orderedList: OrderedList) {
                val prevOrdered = currentListOrdered
                currentListOrdered = true
                visitChildren(orderedList)
                currentListOrdered = prevOrdered
                if (!buffer.endsWith("\n\n")) buffer.append("\n")
            }

            override fun visit(listItem: ListItem) {
                val prevInList = inListItem
                inListItem = true
                visitChildren(listItem)
                inListItem = prevInList
            }

            override fun visit(strongEmphasis: StrongEmphasis) {
                val start = currentUtf16Index()
                visitChildren(strongEmphasis)
                val end = currentUtf16Index()
                if (end > start) {
                    operations.add(DocStyleOperation.TextStyle(Utf16Span(start, end), bold = true))
                }
            }

            override fun visit(emphasis: Emphasis) {
                val start = currentUtf16Index()
                visitChildren(emphasis)
                val end = currentUtf16Index()
                if (end > start) {
                    operations.add(DocStyleOperation.TextStyle(Utf16Span(start, end), italic = true))
                }
            }

            override fun visit(link: Link) {
                val start = currentUtf16Index()
                visitChildren(link)
                val end = currentUtf16Index()
                if (end > start && !link.destination.isNullOrBlank()) {
                    operations.add(DocStyleOperation.TextStyle(Utf16Span(start, end), linkUrl = link.destination))
                }
            }

            override fun visit(code: Code) {
                val start = currentUtf16Index()
                buffer.append(code.literal)
                val end = currentUtf16Index()
                if (end > start) {
                    operations.add(DocStyleOperation.TextStyle(Utf16Span(start, end), code = true))
                }
            }

            override fun visit(fencedCodeBlock: FencedCodeBlock) {
                val start = currentUtf16Index()
                val literal = fencedCodeBlock.literal.trimEnd('\n')
                buffer.append(literal).append("\n\n")
                val end = start + literal.length
                if (end > start) {
                    operations.add(DocStyleOperation.TextStyle(Utf16Span(start, end), code = true))
                }
            }

            override fun visit(text: Text) {
                buffer.append(text.literal)
            }

            override fun visit(softLineBreak: SoftLineBreak) {
                buffer.append(" ")
            }

            override fun visit(hardLineBreak: HardLineBreak) {
                buffer.append("\n")
            }
        }

        documentNode.accept(visitor)

        val fullText = buffer.toString()
        val requests = buildRequestsJson(fullText, baseIndex, operations)
        return DocsConversionResult(
            plainText = fullText,
            styleOperations = operations,
            requestsJson = requests
        )
    }

    private fun buildRequestsJson(
        fullText: String,
        baseIndex: Int,
        operations: List<DocStyleOperation>
    ): JsonArray = buildJsonArray {
        if (fullText.isEmpty()) return@buildJsonArray

        // 1. Insert the entire plain text at baseIndex
        addJsonObject {
            putJsonObject("insertText") {
                putJsonObject("location") { put("index", baseIndex) }
                put("text", fullText)
            }
        }

        // 2. Apply paragraph styles, bullets, and character styles
        for (op in operations) {
            when (op) {
                is DocStyleOperation.HeadingStyle -> {
                    val namedStyle = "HEADING_${op.level.coerceIn(1, 6)}"
                    addJsonObject {
                        putJsonObject("updateParagraphStyle") {
                            putJsonObject("range") {
                                put("startIndex", op.range.startIndex)
                                put("endIndex", op.range.endIndex)
                            }
                            putJsonObject("paragraphStyle") {
                                put("namedStyleType", namedStyle)
                            }
                            put("fields", "namedStyleType")
                        }
                    }
                }
                is DocStyleOperation.BulletListStyle -> {
                    val preset = if (op.ordered) "NUMBERED_DECIMAL_ALPHA_ROMAN" else "BULLET_DISC_CIRCLE_SQUARE"
                    addJsonObject {
                        putJsonObject("createParagraphBullets") {
                            putJsonObject("range") {
                                put("startIndex", op.range.startIndex)
                                put("endIndex", op.range.endIndex)
                            }
                            put("bulletPreset", preset)
                        }
                    }
                }
                is DocStyleOperation.TextStyle -> {
                    val fields = mutableListOf<String>()
                    addJsonObject {
                        putJsonObject("updateTextStyle") {
                            putJsonObject("range") {
                                put("startIndex", op.range.startIndex)
                                put("endIndex", op.range.endIndex)
                            }
                            putJsonObject("textStyle") {
                                if (op.bold) {
                                    put("bold", true)
                                    fields.add("bold")
                                }
                                if (op.italic) {
                                    put("italic", true)
                                    fields.add("italic")
                                }
                                if (op.linkUrl != null) {
                                    putJsonObject("link") { put("url", op.linkUrl) }
                                    fields.add("link")
                                }
                            }
                            put("fields", fields.joinToString(","))
                        }
                    }
                }
            }
        }
    }

    fun buildBatchUpdateBody(requests: JsonArray): JsonObject = buildJsonObject {
        put("requests", requests)
    }
}
