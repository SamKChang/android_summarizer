package com.gist.app.overlay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Bottom composer bar (§3.3):
 * - Single-line field that grows up to 5 lines
 * - 🌐 web search toggle (D7, disabled with a tooltip on Gemini Nano / backends without web search)
 * - Send button turns into Stop while streaming
 * - Reports focus changes so the overlay window toggles `FLAG_NOT_FOCUSABLE` only while typing
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Composer(
    isStreaming: Boolean,
    webSearchEnabled: Boolean,
    webSearchSupported: Boolean,
    onToggleWebSearch: (Boolean) -> Unit,
    onSendPrompt: (String) -> Unit,
    onStopStreaming: () -> Unit,
    onInputFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 🌐 Web research toggle with tooltip when unsupported (§3.3, D7)
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = {
                PlainTooltip {
                    Text(
                        if (webSearchSupported) "Toggle web search with citations"
                        else "Web search is not supported on this backend"
                    )
                }
            },
            state = rememberTooltipState()
        ) {
            val tint = when {
                !webSearchSupported -> Color(0xFFBFC2CA)
                webSearchEnabled -> GistPurple
                else -> Color(0xFF6C7078)
            }
            IconButton(
                onClick = {
                    if (webSearchSupported) onToggleWebSearch(!webSearchEnabled)
                },
                enabled = webSearchSupported,
                modifier = Modifier
                    .size(38.dp)
                    .testTag("composer_web_search_toggle")
            ) {
                Icon(
                    imageVector = Icons.Default.Language,
                    contentDescription = "Web search toggle",
                    tint = tint
                )
            }
        }

        // Pill input field ("Ask follow-up question...") matching gist_demo_video.mp4
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFFF3F4F8),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = "Ask follow-up question...",
                        color = Color(0xFF8E9199),
                        fontSize = 14.sp
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    maxLines = 5,
                    textStyle = TextStyle(
                        color = Color(0xFF1A1C1E),
                        fontSize = 14.sp
                    ),
                    cursorBrush = SolidColor(GistPurple),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (text.isNotBlank()) {
                                val toSend = text.trim()
                                text = ""
                                onSendPrompt(toSend)
                            }
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("composer_input")
                        .onFocusChanged { focusState ->
                            onInputFocusChanged(focusState.isFocused)
                        }
                )
            }
        }

        // Send / Stop circular button matching gist_demo_video.mp4
        val canSend = text.isNotBlank() || isStreaming
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(if (canSend) GistPurple else GistPurple.copy(alpha = 0.7f))
                .clickable {
                    if (isStreaming) {
                        onStopStreaming()
                    } else if (text.isNotBlank()) {
                        val toSend = text.trim()
                        text = ""
                        onSendPrompt(toSend)
                    }
                }
                .testTag("composer_send_stop_button"),
            contentAlignment = Alignment.Center
        ) {
            if (isStreaming) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "Stop streaming",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send prompt",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
