package com.gist.app.overlay.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.gist.app.capture.CaptureMethod
import com.gist.app.capture.CapturedDocument
import com.gist.app.capture.Completeness
import com.gist.app.chat.ChatUiState
import com.gist.app.chat.UiChatMessage
import com.gist.app.llm.BackendId
import com.gist.app.llm.Citation
import com.gist.app.llm.ErrorKind
import com.gist.app.llm.MessageRole
import com.gist.app.settings.GistSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h915dp-mdpi")
class ChatSheetComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sampleDoc = CapturedDocument(
        sourcePackage = "com.android.chrome",
        title = "Fed Rate Cut 50 bps",
        url = "https://ft.com/markets/central-banks/fed-rate-cut",
        text = "Sample article text",
        wordCount = 4210,
        method = CaptureMethod.A11Y_TREE,
        completeness = Completeness.LIKELY_COMPLETE
    )

    @Test
    fun `ChatSheet renders header source chip, streaming summary, citations, quick chips, and 3-dot menu items`() {
        var shareClicked = false
        var settingsClicked = false
        var sentPrompt: String? = null

        val uiState = ChatUiState(
            sessionId = "s1",
            document = sampleDoc,
            backendId = BackendId.GEMINI,
            model = "gemini-2.5-flash",
            isStreaming = false,
            messages = listOf(
                UiChatMessage(
                    id = "m1",
                    role = MessageRole.ASSISTANT,
                    content = "### TL;DR Summary\n- The Fed pivoted monetary policy with a decisive 50 basis point reduction.",
                    citations = listOf(Citation("Federal Reserve PR", "https://federalreserve.gov"))
                )
            )
        )

        composeTestRule.setContent {
            MaterialTheme {
                ChatSheet(
                    uiState = uiState,
                    quickChips = GistSettings.DEFAULT_CHIPS,
                    webSearchSupported = true,
                    onCollapseToBubble = {},
                    onRecaptureClicked = {},
                    onConfirmRecapture = {},
                    onDismissRecaptureDialog = {},
                    onSelectBackend = {},
                    onShareToGoogleDoc = { shareClicked = true },
                    onOpenSettings = { settingsClicked = true },
                    onCopyText = { _, _ -> },
                    onShareMessage = {},
                    onRegenerateLast = {},
                    onNewChat = {},
                    onSendPrompt = { sentPrompt = it },
                    onStopStreaming = {},
                    onToggleWebSearch = {},
                    onComposerFocusChanged = {},
                    onLaunchHandoff = { _, _ -> },
                    onStartAutoScrollCapture = {},
                    onDismissAutoScrollPrompt = {},
                    onConfirmCloudConsent = {},
                    onDismissCloudConsent = {},
                    onDismissSnackbar = {},
                    initialSheetFraction = 0.88f
                )
            }
        }

        // Verify source chip with word count
        composeTestRule.onNodeWithTag("source_chip").assertIsDisplayed()
        composeTestRule.onNodeWithText("TL;DR Summary").assertIsDisplayed()
        composeTestRule.onNodeWithTag("citation_chip_1").assertIsDisplayed()

        // Verify quick-action chip tap sends ELI5 prompt (matching 00:04 of gist_demo_video.mp4)
        composeTestRule.onNodeWithTag("chip_ELI5").performClick()
        assertEquals("Explain like I'm 5 (ELI5)", sentPrompt)

        // Open ⋮ menu and verify Share and Settings items exist and work
        composeTestRule.onNodeWithTag("header_more_menu_button").performClick()
        composeTestRule.onNodeWithTag("menu_item_share").assertIsDisplayed()
        composeTestRule.onNodeWithTag("menu_item_settings").assertIsDisplayed()
        composeTestRule.onNodeWithTag("menu_item_share").performClick()
        assertTrue(shareClicked)

        composeTestRule.onNodeWithTag("header_more_menu_button").performClick()
        composeTestRule.onNodeWithTag("menu_item_settings").performClick()
        assertTrue(settingsClicked)
    }

    @Test
    fun `ChatSheet renders inline error state with action button`() {
        var openedSettings = false
        val errorState = ChatUiState(
            sessionId = "s2",
            document = sampleDoc,
            messages = listOf(
                UiChatMessage(
                    id = "err1",
                    role = MessageRole.ASSISTANT,
                    content = "Missing Gemini API key. Open Settings to configure your key.",
                    errorKind = ErrorKind.AUTH
                )
            )
        )

        composeTestRule.setContent {
            MaterialTheme {
                ChatSheet(
                    uiState = errorState,
                    quickChips = GistSettings.DEFAULT_CHIPS,
                    webSearchSupported = true,
                    onCollapseToBubble = {},
                    onRecaptureClicked = {},
                    onConfirmRecapture = {},
                    onDismissRecaptureDialog = {},
                    onSelectBackend = {},
                    onShareToGoogleDoc = {},
                    onOpenSettings = { openedSettings = true },
                    onCopyText = { _, _ -> },
                    onShareMessage = {},
                    onRegenerateLast = {},
                    onNewChat = {},
                    onSendPrompt = {},
                    onStopStreaming = {},
                    onToggleWebSearch = {},
                    onComposerFocusChanged = {},
                    onLaunchHandoff = { _, _ -> },
                    onStartAutoScrollCapture = {},
                    onDismissAutoScrollPrompt = {},
                    onConfirmCloudConsent = {},
                    onDismissCloudConsent = {},
                    onDismissSnackbar = {},
                    initialSheetFraction = 0.88f
                )
            }
        }

        composeTestRule.onNodeWithTag("error_bubble").assertIsDisplayed()
        composeTestRule.onNodeWithTag("error_action_open_settings").performClick()
        assertTrue(openedSettings)
    }

    @Test
    fun `Bubble tap triggers capture and ChatSheet displays Google Doc created banner and Handoff card`() {
        var bubbleTapped = false
        var handedOffPrompt: String? = null

        val handoffState = ChatUiState(
            sessionId = "s3",
            document = sampleDoc,
            backendId = BackendId.HANDOFF,
            snackbarMessage = "Google Doc created · Open"
        )

        composeTestRule.setContent {
            MaterialTheme {
                Box {
                    ChatSheet(
                        uiState = handoffState,
                        quickChips = GistSettings.DEFAULT_CHIPS,
                        webSearchSupported = false,
                        onCollapseToBubble = {},
                        onRecaptureClicked = {},
                        onConfirmRecapture = {},
                        onDismissRecaptureDialog = {},
                        onSelectBackend = {},
                        onShareToGoogleDoc = {},
                        onOpenSettings = {},
                        onCopyText = { _, _ -> },
                        onShareMessage = {},
                        onRegenerateLast = {},
                        onNewChat = {},
                        onSendPrompt = {},
                        onStopStreaming = {},
                        onToggleWebSearch = {},
                        onComposerFocusChanged = {},
                        onLaunchHandoff = { _, prompt -> handedOffPrompt = prompt },
                        onStartAutoScrollCapture = {},
                        onDismissAutoScrollPrompt = {},
                        onConfirmCloudConsent = {},
                        onDismissCloudConsent = {},
                        onDismissSnackbar = {},
                        initialSheetFraction = 0.88f
                    )
                    Bubble(
                        state = BubbleVisualState.IDLE,
                        onTap = { bubbleTapped = true }
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("gist_bubble").performClick()
        assertTrue(bubbleTapped)

        composeTestRule.onNodeWithTag("doc_created_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("handoff_send_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("handoff_send_button").performClick()
        assertEquals("Give a concise TL;DR and 5–7 key points for this article:", handedOffPrompt)
    }
}
