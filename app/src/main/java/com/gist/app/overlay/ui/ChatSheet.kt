package com.gist.app.overlay.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gist.app.capture.Completeness
import com.gist.app.chat.ChatUiState
import com.gist.app.chat.UiChatMessage
import com.gist.app.llm.BackendId
import com.gist.app.llm.handoff.HandoffApp
import com.gist.app.settings.QuickActionChipConfig
import java.text.NumberFormat
import java.util.Locale

/**
 * Bottom half-sheet Chat Box (§3.3, D10) matching `gist_demo_video.mp4` (00:02–00:05):
 * - Opens at ~55% of the screen, draggable up to 90% or down to dismiss.
 * - 20% black translucent scrim above the sheet so the underlying article remains visible; tapping scrim collapses.
 * - Header:
 *   - Source chip (`📄 Title · 4,210 w`), tap to preview captured text, long-press to copy
 *   - `↻` Re-capture button
 *   - Backend pill (`Gemini Flash ▾`) for quick backend switching
 *   - Share icon button + `⋮` DropdownMenu (Share [forked arrow], Settings [gear], Copy last answer, Open in external app, New chat)
 *   - `—` Minimize button
 * - Messages list with streaming Markdown + citations
 * - Quick-action chips row (`Key facts`, `ELI5`, `Counter-arguments`, `Timeline`)
 * - Composer with 🌐 toggle and Send/Stop button
 * - Hand-off compact card when `BackendId.HANDOFF` is active (§3.5)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatSheet(
    uiState: ChatUiState,
    quickChips: List<QuickActionChipConfig>,
    webSearchSupported: Boolean,
    onCollapseToBubble: () -> Unit,
    onRecaptureClicked: () -> Unit,
    onConfirmRecapture: (keepChat: Boolean) -> Unit,
    onDismissRecaptureDialog: () -> Unit,
    onSelectBackend: (BackendId) -> Unit,
    onShareToGoogleDoc: () -> Unit,
    onOpenSettings: () -> Unit,
    onCopyText: (String, String) -> Unit,
    onShareMessage: (UiChatMessage) -> Unit,
    onRegenerateLast: () -> Unit,
    onNewChat: () -> Unit,
    onSendPrompt: (String) -> Unit,
    onStopStreaming: () -> Unit,
    onToggleWebSearch: (Boolean) -> Unit,
    onComposerFocusChanged: (Boolean) -> Unit,
    onLaunchHandoff: (HandoffApp, String) -> Unit,
    onStartAutoScrollCapture: () -> Unit,
    onDismissAutoScrollPrompt: () -> Unit,
    onConfirmCloudConsent: () -> Unit,
    onDismissCloudConsent: () -> Unit,
    onDismissSnackbar: () -> Unit,
    modifier: Modifier = Modifier,
    initialSheetFraction: Float = 0.56f
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }.coerceAtLeast(1000f)

    // Starts at 55% of screen height, draggable up to 90% or down below 32% to dismiss (§3.3, D10)
    var sheetFraction by remember { mutableFloatStateOf(initialSheetFraction) }
    var backendMenuExpanded by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .testTag("chat_sheet_root")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 20% translucent black scrim above the sheet; tapping collapses to bubble (§3.3)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight((1f - sheetFraction.coerceIn(0.40f, 0.90f)).coerceAtLeast(0.10f))
                    .background(Color.Black.copy(alpha = 0.20f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onCollapseToBubble
                    )
                    .testTag("chat_sheet_scrim")
            )

            // Bottom half-sheet container
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.White,
                shadowElevation = 16.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(sheetFraction.coerceIn(0.40f, 0.90f))
                    .testTag("chat_sheet_surface")
            ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Drag handle pill
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    val deltaFraction = -dragAmount / screenHeightPx
                                    val next = sheetFraction + deltaFraction
                                    if (next < 0.32f) {
                                        onCollapseToBubble()
                                    } else {
                                        sheetFraction = next.coerceIn(0.45f, 0.90f)
                                    }
                                },
                                onDragEnd = {
                                    sheetFraction = if (sheetFraction > 0.72f) 0.90f else 0.56f
                                }
                            )
                        }
                        .padding(top = 10.dp, bottom = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFFD5D7DE))
                    )
                }

                // Header Row (§3.3)
                val doc = uiState.document
                val formattedWords = remember(doc?.wordCount) {
                    NumberFormat.getIntegerInstance(Locale.US).format(doc?.wordCount ?: 0)
                }
                val shortTitle = remember(doc?.title) {
                    val t = doc?.title?.trim().orEmpty()
                    if (t.isEmpty()) "Captured Article"
                    else if (t.length > 20) t.take(18) + "…"
                    else t
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Source chip: tap to preview captured text, long-press to copy (§3.3)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .combinedClickable(
                                onClick = {
                                    onConfirmRecapture(true) // opens capture preview via callback below
                                },
                                onLongClick = {
                                    doc?.text?.let { onCopyText("Captured Article", it) }
                                }
                            )
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                            .testTag("source_chip"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Description,
                            contentDescription = "Captured Document",
                            tint = GistPurple,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "$shortTitle · $formattedWords w",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF3C4043),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // ↻ Re-capture button (§3.3)
                    IconButton(
                        onClick = onRecaptureClicked,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("recapture_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Re-capture article",
                            tint = Color(0xFF5F6368),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Backend pill (`Gemini Flash ▾`) (§3.3)
                    Box {
                        Surface(
                            onClick = { backendMenuExpanded = true },
                            shape = RoundedCornerShape(16.dp),
                            color = GistPurpleLight,
                            modifier = Modifier.testTag("backend_pill")
                        ) {
                            Text(
                                text = "${uiState.backendId.displayName.removeSuffix(" (Demo)")} ▾",
                                color = GistPurple,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = backendMenuExpanded,
                            onDismissRequest = { backendMenuExpanded = false }
                        ) {
                            BackendId.entries.filter { it != BackendId.DEMO }.forEach { b ->
                                DropdownMenuItem(
                                    text = { Text(b.displayName) },
                                    onClick = {
                                        backendMenuExpanded = false
                                        onSelectBackend(b)
                                    }
                                )
                            }
                        }
                    }

                    // Direct Share button (matching 00:05 of gist_demo_video.mp4)
                    IconButton(
                        onClick = onShareToGoogleDoc,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("direct_share_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = "Share to Google Docs",
                            tint = Color(0xFF44474E),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // ⋮ Menu with Share (forked arrow), Settings (gear), Copy, Handoff, New chat (§3.3)
                    HeaderMenu(
                        onShareToGoogleDoc = onShareToGoogleDoc,
                        onOpenSettings = onOpenSettings,
                        onCopyLastAnswer = {
                            val last = uiState.messages.lastOrNull()?.content.orEmpty()
                            if (last.isNotBlank()) onCopyText("Gist Summary", last)
                        },
                        onOpenInExternalApp = {
                            onLaunchHandoff(HandoffApp.CHATGPT, "Summarize this article:")
                        },
                        onNewChat = onNewChat
                    )

                    // — Minimize button (§3.3)
                    IconButton(
                        onClick = onCollapseToBubble,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("minimize_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.HorizontalRule,
                            contentDescription = "Minimize to bubble",
                            tint = Color(0xFF5F6368),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Truncated completeness warning banner + Auto-scroll trigger (§5.2)
                if (doc?.completeness == Completeness.LIKELY_TRUNCATED || uiState.needsAutoScrollConsent) {
                    Surface(
                        color = Color(0xFFFFF8E1),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Only part of the article may be visible.",
                                fontSize = 12.sp,
                                color = Color(0xFF795548),
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onStartAutoScrollCapture) {
                                Text("Auto-scroll (≈10s)", fontSize = 12.sp, color = GistPurple)
                            }
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFFECEEF2))

                // Main content area: either Hand-off mode card (§3.5) or in-app MessageList + Quick Chips (§3.3)
                if (uiState.backendId == BackendId.HANDOFF) {
                    HandoffModeCard(
                        onSendToApp = onLaunchHandoff,
                        modifier = Modifier
                            .weight(1f)
                            .padding(16.dp)
                    )
                } else {
                    MessageList(
                        messages = uiState.messages,
                        onCopyMessage = { msg -> onCopyText("Gist Message", msg.content) },
                        onShareMessage = onShareMessage,
                        onRegenerateMessage = onRegenerateLast,
                        onOpenSettingsForAuth = onOpenSettings,
                        onRetryError = onRegenerateLast,
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 8.dp)
                    )

                    // Quick-action chips row (`Key facts`, `ELI5`, `Counter-arguments`, `Timeline`) (§3.3)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .testTag("quick_action_chips_row"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (chip in quickChips) {
                            Surface(
                                onClick = { onSendPrompt(chip.prompt) },
                                shape = RoundedCornerShape(18.dp),
                                color = Color(0xFFF1F3F9),
                                modifier = Modifier.testTag("chip_${chip.label}")
                            ) {
                                Text(
                                    text = chip.label,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF3C4043),
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = Color(0xFFECEEF2))

                    // Composer bar (§3.3)
                    Composer(
                        isStreaming = uiState.isStreaming,
                        webSearchEnabled = uiState.webSearchEnabled,
                        webSearchSupported = webSearchSupported,
                        onToggleWebSearch = onToggleWebSearch,
                        onSendPrompt = onSendPrompt,
                        onStopStreaming = onStopStreaming,
                        onInputFocusChanged = onComposerFocusChanged
                    )
                }
            }
            }
        }

        // Floating toast/banner when Google Doc is created (matching 00:05 of gist_demo_video.mp4)
        if (!uiState.snackbarMessage.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF1F242D),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 340.dp, start = 24.dp, end = 24.dp)
                    .clickable { onDismissSnackbar() }
                    .testTag("doc_created_banner")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF4CAF50)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Text(
                        text = uiState.snackbarMessage,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }

    // Dialog: Captured Text Preview (when tapping the source chip) (§3.3)
    if (uiState.showCapturePreviewDialog && uiState.document != null) {
        AlertDialog(
            onDismissRequest = onDismissRecaptureDialog,
            title = {
                Text(
                    text = uiState.document.title ?: "Captured Article Preview",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .height(300.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "Method: ${uiState.document.method} · ${uiState.document.wordCount} words · ${uiState.document.completeness}",
                        fontSize = 12.sp,
                        color = GistPurple,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (!uiState.document.url.isNullOrBlank()) {
                        Text(
                            text = uiState.document.url,
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiState.document.text,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCopyText("Captured Article", uiState.document.text)
                        onDismissRecaptureDialog()
                    }
                ) {
                    Text("Copy Text")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRecaptureDialog) {
                    Text("Close")
                }
            }
        )
    }

    // Dialog: Re-capture "Keep chat?" prompt (§3.3)
    if (uiState.showKeepChatDialog) {
        AlertDialog(
            onDismissRequest = onDismissRecaptureDialog,
            title = { Text("Re-capture Article") },
            text = { Text("Keep the current conversation history with the newly captured text, or start a fresh chat?") },
            confirmButton = {
                TextButton(onClick = { onConfirmRecapture(true) }) {
                    Text("Keep chat")
                }
            },
            dismissButton = {
                TextButton(onClick = { onConfirmRecapture(false) }) {
                    Text("Start new")
                }
            }
        )
    }

    // Dialog: One-time cloud provider consent (§8.2)
    val consentProvider = uiState.needsCloudConsentFor
    if (consentProvider != null) {
        AlertDialog(
            onDismissRequest = onDismissCloudConsent,
            title = { Text("Send article text to ${consentProvider.displayName}?") },
            text = {
                Text(
                    "Gist will send the captured article text from your screen to ${consentProvider.displayName} using your configured API key. This confirmation is shown once per provider."
                )
            },
            confirmButton = {
                Button(
                    onClick = onConfirmCloudConsent,
                    colors = ButtonDefaults.buttonColors(containerColor = GistPurple)
                ) {
                    Text("Allow & Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissCloudConsent) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Hand-off card shown when the user selects ChatGPT / Claude / Gemini subscription hand-off mode (§3.5).
 */
@Composable
private fun HandoffModeCard(
    onSendToApp: (HandoffApp, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedApp by remember { mutableStateOf(HandoffApp.CHATGPT) }
    var selectedPromptLabel by remember { mutableStateOf("Summarize") }
    val prompts = mapOf(
        "Summarize" to "Give a concise TL;DR and 5–7 key points for this article:",
        "ELI5" to "Explain this article like I'm 5:",
        "Key facts" to "Extract all key facts, figures, and quotes from this article:"
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Hand-off to Official AI App",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1A1C1E)
        )
        Text(
            text = "Uses your existing ChatGPT, Claude, or Gemini consumer subscription by sending the captured article text and prompt directly to the app.",
            fontSize = 13.sp,
            color = Color(0xFF5F6368)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HandoffApp.entries.forEach { app ->
                val isSelected = selectedApp == app
                OutlinedButton(
                    onClick = { selectedApp = app },
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isSelected) GistPurpleLight else Color.Transparent,
                        contentColor = if (isSelected) GistPurple else Color(0xFF3C4043)
                    )
                ) {
                    Text(app.displayName)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            prompts.keys.forEach { label ->
                val isSelected = selectedPromptLabel == label
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (isSelected) GistPurpleLight else Color(0xFFF1F3F9),
                    modifier = Modifier.clickable { selectedPromptLabel = label }
                ) {
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        color = if (isSelected) GistPurple else Color(0xFF3C4043),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Button(
            onClick = {
                onSendToApp(selectedApp, prompts[selectedPromptLabel] ?: "Summarize:")
            },
            colors = ButtonDefaults.buttonColors(containerColor = GistPurple),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("handoff_send_button")
        ) {
            Text("Send to ${selectedApp.displayName} with prompt: [$selectedPromptLabel]")
        }
    }
}
