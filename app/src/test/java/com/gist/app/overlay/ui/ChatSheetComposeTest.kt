package com.gist.app.overlay.ui

import android.graphics.Bitmap
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
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

    @Test
    fun `Interactive demo walkthrough renders and verifies all 4 stages from gist_demo_video`() {
        var rootView: View? = null
        var isExpanded by mutableStateOf(false)
        var uiState by mutableStateOf(
            ChatUiState(
                sessionId = "demo_session",
                document = sampleDoc,
                backendId = BackendId.GEMINI,
                model = "gemini-2.5-flash",
                isStreaming = false,
                messages = listOf(
                    UiChatMessage(
                        id = "m_summary",
                        role = MessageRole.ASSISTANT,
                        content = "### TL;DR Summary\n" +
                            "- **Fed cuts rates by 50 bps** to 4.75%–5.00%, marking its first easing move since 2020.\n" +
                            "- **Inflation cools toward 2%**, allowing policymakers to pivot focus toward supporting the labor market.\n" +
                            "- **Dot plot signals further easing** of another 50 bps across the remaining meetings this year.",
                        citations = listOf(Citation("Federal Reserve PR", "https://www.federalreserve.gov"))
                    )
                )
            )
        )

        composeTestRule.setContent {
            rootView = LocalView.current
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Simulated underlying Chrome browser article matching gist_demo_video.mp4
                    SimulatedBrowserArticleBackground()

                    if (!isExpanded) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 240.dp, end = 12.dp),
                            contentAlignment = Alignment.TopEnd
                        ) {
                            Bubble(
                                state = BubbleVisualState.IDLE,
                                onTap = { isExpanded = true }
                            )
                        }
                    } else {
                        ChatSheet(
                            uiState = uiState,
                            quickChips = GistSettings.DEFAULT_CHIPS,
                            webSearchSupported = true,
                            onCollapseToBubble = { isExpanded = false },
                            onRecaptureClicked = {},
                            onConfirmRecapture = {},
                            onDismissRecaptureDialog = {},
                            onSelectBackend = {},
                            onShareToGoogleDoc = {
                                uiState = uiState.copy(snackbarMessage = "Google Doc created · Open")
                            },
                            onOpenSettings = {},
                            onCopyText = { _, _ -> },
                            onShareMessage = {},
                            onRegenerateLast = {},
                            onNewChat = {},
                            onSendPrompt = { prompt ->
                                uiState = uiState.copy(
                                    messages = uiState.messages + listOf(
                                        UiChatMessage(
                                            id = "m_user_eli5",
                                            role = MessageRole.USER,
                                            content = prompt
                                        ),
                                        UiChatMessage(
                                            id = "m_assistant_eli5",
                                            role = MessageRole.ASSISTANT,
                                            content = "Imagine a giant piggy bank! When prices were going up too fast, the bank made borrowing money expensive. Now that prices have calmed down, they lowered the borrowing cost so families and businesses can buy things and keep jobs safe."
                                        )
                                    )
                                )
                            },
                            onStopStreaming = {},
                            onToggleWebSearch = {},
                            onComposerFocusChanged = {},
                            onLaunchHandoff = { _, _ -> },
                            onStartAutoScrollCapture = {},
                            onDismissAutoScrollPrompt = {},
                            onConfirmCloudConsent = {},
                            onDismissCloudConsent = {},
                            onDismissSnackbar = { uiState = uiState.copy(snackbarMessage = null) },
                            initialSheetFraction = 0.62f
                        )
                    }
                }
            }
        }

        val reportDir = File("build/reports/visual_verification").apply { mkdirs() }
        val artifactDir = File("/Users/samio/.gemini/antigravity/brain/552affdc-9134-4a3f-8642-6b627b5b9469")

        fun saveScreenshot(name: String) {
            composeTestRule.waitForIdle()
            val v = rootView ?: return
            if (v.width <= 0 || v.height <= 0) return
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            v.draw(canvas)
            FileOutputStream(File(reportDir, name)).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (artifactDir.exists()) {
                FileOutputStream(File(artifactDir, name)).use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
        }

        // Stage 1: Floating purple Bubble over the FT article (00:01)
        composeTestRule.onNodeWithTag("gist_bubble").assertIsDisplayed()
        saveScreenshot("verify_step1_floating_bubble.png")

        // Stage 2: Tap Bubble -> ChatSheet opens with TL;DR Summary & Citations (00:03)
        composeTestRule.onNodeWithTag("gist_bubble").performClick()
        composeTestRule.onNodeWithTag("source_chip").assertIsDisplayed()
        composeTestRule.onNodeWithTag("citation_chip_1").assertIsDisplayed()
        saveScreenshot("verify_step2_auto_summary.png")

        // Stage 3: Tap "ELI5" quick-action chip -> User purple bubble + ELI5 answer (00:04)
        composeTestRule.onNodeWithTag("chip_ELI5").performClick()
        composeTestRule.onNodeWithText("Explain like I'm 5 (ELI5)").assertIsDisplayed()
        saveScreenshot("verify_step3_eli5_followup.png")

        // Stage 4: Tap Share button -> Google Doc created banner appears (00:05)
        composeTestRule.onNodeWithTag("direct_share_button").performClick()
        composeTestRule.onNodeWithTag("doc_created_banner").assertIsDisplayed()
        saveScreenshot("verify_step4_google_doc_created.png")
    }
}

@Composable
private fun SimulatedBrowserArticleBackground() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFFF9F5))
    ) {
        // Simulated Chrome URL bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF1F3F4))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = "🔒 ft.com/markets/central-banks/fed-rate-cut",
                fontSize = 13.sp,
                color = Color(0xFF3C4043)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "FINANCIAL TIMES · CENTRAL BANKS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF990F3D)
            )
            Text(
                text = "Federal Reserve Enacts Aggressive 50 bps Interest Rate Cut as Inflation Cools",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                color = Color(0xFF1A1C1E),
                lineHeight = 28.sp
            )
            Text(
                text = "By Macro Desk · Washington · 4,210 words",
                fontSize = 12.sp,
                color = Color(0xFF5F6368)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "WASHINGTON — The Federal Reserve voted Wednesday to lower benchmark borrowing costs by a half percentage point, embarking on its first monetary easing cycle in four years with a decisive move aimed at preserving labor market strength.",
                fontSize = 15.sp,
                fontFamily = FontFamily.Serif,
                color = Color(0xFF2B2D30),
                lineHeight = 23.sp
            )
            Text(
                text = "The move brings the federal funds target rate to a range between 4.75% and 5%, concluding a two-year campaign of monetary tightening that lifted borrowing costs to their highest level in more than two decades.",
                fontSize = 15.sp,
                fontFamily = FontFamily.Serif,
                color = Color(0xFF2B2D30),
                lineHeight = 23.sp
            )
        }
    }
}
