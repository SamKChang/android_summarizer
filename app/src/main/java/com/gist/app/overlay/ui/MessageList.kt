package com.gist.app.overlay.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gist.app.chat.UiChatMessage
import com.gist.app.llm.Citation
import com.gist.app.llm.ErrorKind
import com.gist.app.llm.MessageRole

private val SuperscriptNumbers = listOf("¹", "²", "³", "⁴", "⁵", "⁶", "⁷", "⁸", "⁹")

/**
 * Renders the streaming Markdown message list, inline citation pills (`Source: Federal Reserve PR [¹]`),
 * typed error bubbles with action buttons (§6.5), and long-press message actions (Copy / Share / Regenerate) (§3.3).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun MessageList(
    messages: List<UiChatMessage>,
    onCopyMessage: (UiChatMessage) -> Unit,
    onShareMessage: (UiChatMessage) -> Unit,
    onRegenerateMessage: () -> Unit,
    onOpenSettingsForAuth: () -> Unit,
    onRetryError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("message_list"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(messages, key = { it.id }) { msg ->
            var showContextMenu by remember { mutableStateOf(false) }

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = if (msg.role == MessageRole.USER) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                when {
                    msg.errorKind != null -> {
                        ErrorMessageBubble(
                            message = msg,
                            onOpenSettings = onOpenSettingsForAuth,
                            onRetry = onRetryError
                        )
                    }
                    msg.role == MessageRole.USER -> {
                        // Purple pill user message matching gist_demo_video.mp4 (00:04)
                        Box(
                            modifier = Modifier
                                .widthIn(max = 300.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(GistPurple)
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = { showContextMenu = true }
                                )
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = msg.content,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    else -> {
                        // Assistant message: initial summary directly on sheet or subsequent Q&A in soft card
                        val isFollowUpAnswer = messages.indexOf(msg) > 0
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (isFollowUpAnswer) {
                                        Modifier
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(Color(0xFFF3F4F8))
                                            .padding(14.dp)
                                    } else {
                                        Modifier.padding(vertical = 4.dp)
                                    }
                                )
                        ) {
                            StreamingMarkdownBlock(
                                markdown = msg.content,
                                isStreaming = msg.isStreaming,
                                modifier = Modifier.combinedClickable(
                                    onClick = {},
                                    onLongClick = { showContextMenu = true }
                                )
                            )

                            if (msg.citations.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    msg.citations.forEachIndexed { idx, citation ->
                                        CitationChip(
                                            index = idx + 1,
                                            citation = citation,
                                            onClick = {
                                                runCatching { uriHandler.openUri(citation.url) }
                                            }
                                        )
                                    }
                                }
                            }

                            if (msg.tokenUsage > 0 && !msg.isStreaming) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${msg.tokenUsage} tokens",
                                    fontSize = 11.sp,
                                    color = Color(0xFF8E9199)
                                )
                            }
                        }
                    }
                }

                DropdownMenu(
                    expanded = showContextMenu,
                    onDismissRequest = { showContextMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Copy") },
                        onClick = {
                            showContextMenu = false
                            onCopyMessage(msg)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Share") },
                        onClick = {
                            showContextMenu = false
                            onShareMessage(msg)
                        }
                    )
                    if (msg.role == MessageRole.ASSISTANT) {
                        DropdownMenuItem(
                            text = { Text("Regenerate") },
                            onClick = {
                                showContextMenu = false
                                onRegenerateMessage()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CitationChip(
    index: Int,
    citation: Citation,
    onClick: () -> Unit
) {
    val sup = SuperscriptNumbers.getOrElse(index - 1) { "$index" }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFFF1F3F9),
        modifier = Modifier.testTag("citation_chip_$index")
    ) {
        Text(
            text = "Source: ${citation.title} [$sup]",
            fontSize = 12.sp,
            color = Color(0xFF5A5D64),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun ErrorMessageBubble(
    message: UiChatMessage,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFFFEBEE),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("error_bubble")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = message.content,
                color = Color(0xFFB71C1C),
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                when (message.errorKind) {
                    ErrorKind.AUTH, ErrorKind.UNAVAILABLE -> {
                        TextButton(
                            onClick = onOpenSettings,
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFB71C1C)),
                            modifier = Modifier.testTag("error_action_open_settings")
                        ) {
                            Text("Open Settings")
                        }
                    }
                    else -> {
                        TextButton(
                            onClick = onRetry,
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFB71C1C)),
                            modifier = Modifier.testTag("error_action_retry")
                        ) {
                            Text("Retry")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renders Markdown headings, bullet lists, numbered lists, bold/italic/code spans, and links,
 * plus a purple streaming cursor block while tokens arrive (matching `gist_demo_video.mp4` 00:02–00:05).
 */
@Composable
fun StreamingMarkdownBlock(
    markdown: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier
) {
    val lines = markdown.split("\n")
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEachIndexed { idx, rawLine ->
            val line = rawLine.trimEnd()
            val isLastLine = idx == lines.lastIndex
            val cursorSuffix = if (isStreaming && isLastLine) " ▋" else ""

            when {
                line.isBlank() -> {
                    Spacer(modifier = Modifier.height(2.dp))
                }
                line.startsWith("### ") || line.startsWith("## ") || line.startsWith("# ") -> {
                    val headingText = line.replaceFirst(Regex("^#{1,3}\\s+"), "")
                    Text(
                        text = headingText + cursorSuffix,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1A1C1E)
                    )
                }
                line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ") -> {
                    val itemText = line.replaceFirst(Regex("^[-*•]\\s+"), "")
                    Text(
                        text = formatInlineMarkdown("– $itemText$cursorSuffix", isStreaming && isLastLine),
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = Color(0xFF2B2D30)
                    )
                }
                else -> {
                    Text(
                        text = formatInlineMarkdown(line + cursorSuffix, isStreaming && isLastLine),
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = Color(0xFF2B2D30)
                    )
                }
            }
        }
    }
}

private fun formatInlineMarkdown(text: String, highlightStreamingCursor: Boolean): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        val len = text.length
        while (i < len) {
            when {
                highlightStreamingCursor && i == len - 2 && text.endsWith(" ▋") -> {
                    append(" ")
                    withStyle(SpanStyle(color = GistPurple, fontWeight = FontWeight.Bold)) {
                        append("▋")
                    }
                    break
                }
                text.startsWith("**", i) -> {
                    val close = text.indexOf("**", i + 2)
                    if (close != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, close))
                        }
                        i = close + 2
                    } else {
                        append(text[i])
                        i++
                    }
                }
                text.startsWith("`", i) -> {
                    val close = text.indexOf("`", i + 1)
                    if (close != -1) {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = Color(0xFFE8EAF0)
                            )
                        ) {
                            append(text.substring(i + 1, close))
                        }
                        i = close + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                text.startsWith("[", i) -> {
                    val closeBracket = text.indexOf("]", i + 1)
                    val openParen = if (closeBracket != -1 && closeBracket + 1 < len && text[closeBracket + 1] == '(') closeBracket + 1 else -1
                    val closeParen = if (openParen != -1) text.indexOf(")", openParen + 1) else -1
                    if (closeBracket != -1 && openParen != -1 && closeParen != -1) {
                        val label = text.substring(i + 1, closeBracket)
                        val url = text.substring(openParen + 1, closeParen)
                        pushStringAnnotation(tag = "URL", annotation = url)
                        withStyle(SpanStyle(color = GistPurple, textDecoration = TextDecoration.Underline)) {
                            append(label)
                        }
                        pop()
                        i = closeParen + 1
                    } else if (closeBracket != -1 && closeBracket - i <= 3) {
                        // Citation superscript like [¹]
                        val supLabel = text.substring(i, closeBracket + 1)
                        withStyle(
                            SpanStyle(
                                color = GistPurple,
                                fontSize = 12.sp,
                                baselineShift = BaselineShift.Superscript
                            )
                        ) {
                            append(supLabel)
                        }
                        i = closeBracket + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }
}
