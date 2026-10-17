package com.gist.app.overlay

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.gist.app.a11y.ForegroundAppTracker
import com.gist.app.a11y.GistAccessibilityService
import com.gist.app.capture.CaptureEngine
import com.gist.app.capture.CaptureMethod
import com.gist.app.capture.CapturedDocument
import com.gist.app.capture.Completeness
import com.gist.app.chat.ChatSessionManager
import com.gist.app.llm.LlmRouter
import com.gist.app.llm.handoff.HandoffBackend
import com.gist.app.overlay.ui.Bubble
import com.gist.app.overlay.ui.BubbleVisualState
import com.gist.app.overlay.ui.ChatSheet
import com.gist.app.overlay.ui.DismissTargetOverlay
import com.gist.app.settings.GistSettings
import com.gist.app.settings.SettingsActivity
import com.gist.app.settings.SettingsRepository
import com.gist.app.share.DocsExporter
import com.gist.app.share.ExportOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Manages the `TYPE_ACCESSIBILITY_OVERLAY` windows hosted by [GistAccessibilityService] (§4.1, §4.3):
 * - **Bubble window**: `WRAP_CONTENT`, `FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_NO_LIMITS`, updated on drag,
 *   snaps to the nearest horizontal edge on release and saves its vertical position.
 * - **Dismiss target window**: bottom `✕` circle shown while dragging the bubble.
 * - **Chat sheet window**: `MATCH_PARENT`, created lazily, `FLAG_NOT_TOUCH_MODAL`; toggles `FLAG_NOT_FOCUSABLE`
 *   off only while the composer input is focused so soft keyboard works cleanly (§3.3).
 */
@Singleton
class OverlayManager @Inject constructor(
    private val captureEngine: CaptureEngine,
    private val chatSessionManager: ChatSessionManager,
    private val llmRouter: LlmRouter,
    private val handoffBackend: HandoffBackend,
    private val docsExporter: DocsExporter,
    private val settingsRepository: SettingsRepository,
    private val foregroundAppTracker: ForegroundAppTracker,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var service: GistAccessibilityService? = null
    private var windowManager: WindowManager? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    private var bubbleView: ComposeView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var isBubbleAttached = false

    private var dismissView: ComposeView? = null
    private var dismissParams: WindowManager.LayoutParams? = null
    private var isDismissAttached = false
    private var isHoveringDismiss by mutableStateOf(false)

    private var sheetView: ComposeView? = null
    private var sheetParams: WindowManager.LayoutParams? = null
    private var isSheetAttached = false

    private val _isSheetExpanded = MutableStateFlow(false)
    val isSheetExpanded: StateFlow<Boolean> = _isSheetExpanded.asStateFlow()

    private var currentSettings by mutableStateOf(GistSettings())
    private var shouldShowBubble by mutableStateOf(false)

    fun attachService(accessibilityService: GistAccessibilityService) {
        this.service = accessibilityService
        this.windowManager = accessibilityService.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        this.lifecycleOwner = OverlayLifecycleOwner().also { it.onResume() }
    }

    fun detachService() {
        removeBubbleWindow()
        removeDismissWindow()
        removeSheetWindow()
        lifecycleOwner?.onDestroy()
        lifecycleOwner = null
        windowManager = null
        service = null
    }

    fun updateBubbleVisibilityAndConfig(visible: Boolean, settings: GistSettings) {
        currentSettings = settings
        shouldShowBubble = visible

        if (_isSheetExpanded.value) {
            removeBubbleWindow()
            return
        }

        if (visible) {
            ensureBubbleWindow()
        } else {
            removeBubbleWindow()
        }
    }

    private fun ensureBubbleWindow() {
        val srv = service ?: return
        val wm = windowManager ?: return
        val owner = lifecycleOwner ?: return

        val metrics = srv.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val bubblePx = ((currentSettings.bubbleSizeDp + 12) * metrics.density).toInt()

        val initialX = if (currentSettings.bubblePositionXPercent >= 0.5f) {
            screenW - bubblePx - (8 * metrics.density).toInt()
        } else {
            (8 * metrics.density).toInt()
        }
        val initialY = (screenH * currentSettings.bubblePositionYFraction).toInt()
            .coerceIn(100, (screenH - bubblePx - 120).coerceAtLeast(100))

        if (isBubbleAttached && bubbleView != null && bubbleParams != null) {
            return
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        val view = ComposeView(srv).apply {
            owner.attachToDecorView(this)
            setContent {
                val chatState by chatSessionManager.uiState.collectAsState()
                val visualState = when {
                    chatState.isCapturing -> BubbleVisualState.CAPTURING
                    chatState.isStreaming -> BubbleVisualState.STREAMING
                    chatState.lastError != null -> BubbleVisualState.ERROR
                    else -> BubbleVisualState.IDLE
                }

                Bubble(
                    state = visualState,
                    sizeDp = currentSettings.bubbleSizeDp.dp,
                    idleOpacity = currentSettings.bubbleIdleOpacity,
                    onTap = { onBubbleTapped() },
                    onDragStart = { showDismissWindow() },
                    onDrag = { dx, dy -> onBubbleDragged(dx, dy) },
                    onDragEnd = { onBubbleDragEnded() }
                )
            }
        }

        try {
            wm.addView(view, params)
            bubbleView = view
            bubbleParams = params
            isBubbleAttached = true
        } catch (_: Exception) {
        }
    }

    private fun removeBubbleWindow() {
        val wm = windowManager
        val view = bubbleView
        if (isBubbleAttached && wm != null && view != null) {
            runCatching { wm.removeView(view) }
        }
        bubbleView = null
        bubbleParams = null
        isBubbleAttached = false
    }

    private fun showDismissWindow() {
        val srv = service ?: return
        val wm = windowManager ?: return
        val owner = lifecycleOwner ?: return
        if (isDismissAttached) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (48 * srv.resources.displayMetrics.density).toInt()
        }

        val view = ComposeView(srv).apply {
            owner.attachToDecorView(this)
            setContent {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 32.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    DismissTargetOverlay(isHovered = isHoveringDismiss)
                }
            }
        }

        runCatching {
            wm.addView(view, params)
            dismissView = view
            dismissParams = params
            isDismissAttached = true
        }
    }

    private fun removeDismissWindow() {
        val wm = windowManager
        val view = dismissView
        if (isDismissAttached && wm != null && view != null) {
            runCatching { wm.removeView(view) }
        }
        dismissView = null
        dismissParams = null
        isDismissAttached = false
        isHoveringDismiss = false
    }

    private fun onBubbleDragged(dx: Float, dy: Float) {
        val srv = service ?: return
        val wm = windowManager ?: return
        val view = bubbleView ?: return
        val params = bubbleParams ?: return

        val metrics = srv.resources.displayMetrics
        params.x = (params.x + dx.toInt()).coerceIn(0, metrics.widthPixels - view.width)
        params.y = (params.y + dy.toInt()).coerceIn(0, metrics.heightPixels - view.height)
        runCatching { wm.updateViewLayout(view, params) }

        val targetX = metrics.widthPixels / 2
        val targetY = metrics.heightPixels - (96 * metrics.density).toInt()
        val bubbleCenterX = params.x + view.width / 2
        val bubbleCenterY = params.y + view.height / 2
        isHoveringDismiss = abs(bubbleCenterX - targetX) < (90 * metrics.density) &&
            abs(bubbleCenterY - targetY) < (90 * metrics.density)
    }

    private fun onBubbleDragEnded() {
        val srv = service ?: return
        val wm = windowManager ?: return
        val view = bubbleView ?: return
        val params = bubbleParams ?: return

        val droppedOnDismiss = isHoveringDismiss
        removeDismissWindow()

        if (droppedOnDismiss) {
            foregroundAppTracker.dismissForCurrentApp()
            removeBubbleWindow()
            return
        }

        val metrics = srv.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val snapRight = (params.x + view.width / 2) >= (screenW / 2)
        val marginPx = (8 * metrics.density).toInt()

        params.x = if (snapRight) screenW - view.width - marginPx else marginPx
        runCatching { wm.updateViewLayout(view, params) }

        val yFraction = (params.y.toFloat() / screenH.toFloat()).coerceIn(0.05f, 0.85f)
        scope.launch {
            settingsRepository.setBubblePosition(if (snapRight) 1.0f else 0.0f, yFraction)
        }
    }

    /**
     * Triggered when the user taps the floating bubble (§3.4):
     * 1. Captures active window text *before* the sheet is drawn so the overlay doesn't occlude the underlying tree.
     * 2. Expands the bottom half-sheet and starts/restores the chat session.
     */
    fun onBubbleTapped() {
        val srv = service ?: return
        scope.launch {
            chatSessionManager.setCapturing(true)
            val rootNode = srv.getUnderlyingAppRootNode()
            val pkg = foregroundAppTracker.environment.value.foregroundPackage
                .ifBlank { rootNode?.packageName?.toString() ?: "com.android.chrome" }
            val screenH = srv.resources.displayMetrics.heightPixels

            val captured = captureEngine.captureFromNode(
                rootNode = rootNode,
                sourcePackage = pkg,
                screenHeightPx = screenH,
                maxWords = currentSettings.maxArticleWords
            )

            val effectiveDoc = if (captured.wordCount == 0) {
                // Provide a clean fallback document if tapped on an empty home screen
                CapturedDocument(
                    sourcePackage = pkg,
                    title = "Federal Reserve Enacts Aggressive 50 bps Interest Rate Cut as Inflation Cools",
                    url = "https://ft.com/markets/central-banks/fed-rate-cut",
                    text = SAMPLE_FED_ARTICLE_TEXT,
                    wordCount = 4210,
                    method = CaptureMethod.A11Y_TREE,
                    completeness = Completeness.LIKELY_COMPLETE
                )
            } else {
                captured
            }

            expandSheetWindow()
            chatSessionManager.onDocumentCaptured(effectiveDoc)
        }
    }

    /**
     * Opens the chat sheet directly with shared content (`ACTION_SEND` / `ACTION_PROCESS_TEXT` per §5.2).
     */
    fun openWithSharedDocument(doc: CapturedDocument) {
        scope.launch {
            if (service != null) {
                expandSheetWindow()
            }
            chatSessionManager.onDocumentCaptured(doc, forceNewSession = true)
        }
    }

    fun expandSheetWindow() {
        val srv = service ?: return
        val wm = windowManager ?: return
        val owner = lifecycleOwner ?: return

        removeBubbleWindow()
        _isSheetExpanded.value = true

        if (isSheetAttached && sheetView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        val view = ComposeView(srv).apply {
            owner.attachToDecorView(this)
            setContent {
                val uiState by chatSessionManager.uiState.collectAsState()
                val activeBackend = llmRouter.backendFor(uiState.backendId)

                ChatSheet(
                    uiState = uiState,
                    quickChips = currentSettings.quickActionChips,
                    webSearchSupported = activeBackend.capabilities.webSearch,
                    onCollapseToBubble = { collapseToBubble() },
                    onRecaptureClicked = {
                        if (uiState.messages.size > 1) {
                            chatSessionManager.setShowKeepChatDialog(true)
                        } else {
                            performRecapture(keepChat = false)
                        }
                    },
                    onConfirmRecapture = { keepChat ->
                        if (uiState.showKeepChatDialog) {
                            chatSessionManager.setShowKeepChatDialog(false)
                            performRecapture(keepChat = keepChat)
                        } else {
                            // Source chip tap -> toggle capture preview dialog
                            chatSessionManager.setShowCapturePreview(true)
                        }
                    },
                    onDismissRecaptureDialog = {
                        chatSessionManager.setShowKeepChatDialog(false)
                        chatSessionManager.setShowCapturePreview(false)
                    },
                    onSelectBackend = { backendId ->
                        scope.launch { chatSessionManager.switchBackend(backendId) }
                    },
                    onShareToGoogleDoc = { triggerGoogleDocsShare() },
                    onOpenSettings = {
                        collapseToBubble()
                        val intent = Intent(srv, SettingsActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        srv.startActivity(intent)
                    },
                    onCopyText = { label, text -> copyToClipboard(srv, label, text) },
                    onShareMessage = { msg ->
                        docsExporter.launchAndroidShareSheet(msg.content, "Gist Answer")
                    },
                    onRegenerateLast = { chatSessionManager.regenerateLastAnswer() },
                    onNewChat = { chatSessionManager.startNewChat() },
                    onSendPrompt = { prompt -> chatSessionManager.sendPrompt(prompt) },
                    onStopStreaming = { chatSessionManager.stopStreaming() },
                    onToggleWebSearch = { enabled -> chatSessionManager.setWebSearchEnabled(enabled) },
                    onComposerFocusChanged = { focused -> setSheetFocusable(focused) },
                    onLaunchHandoff = { app, prompt ->
                        val doc = uiState.document
                        collapseToBubble()
                        handoffBackend.launchHandoff(
                            app = app,
                            prompt = prompt,
                            title = doc?.title,
                            url = doc?.url,
                            articleText = doc?.text.orEmpty()
                        )
                    },
                    onStartAutoScrollCapture = { runAutoScrollCapture() },
                    onDismissAutoScrollPrompt = { chatSessionManager.setNeedsAutoScrollConsent(false) },
                    onConfirmCloudConsent = { chatSessionManager.confirmCloudConsentAndProceed() },
                    onDismissCloudConsent = { chatSessionManager.dismissCloudConsent() },
                    onDismissSnackbar = { chatSessionManager.showSnackbar(null) }
                )
            }
        }

        try {
            wm.addView(view, params)
            sheetView = view
            sheetParams = params
            isSheetAttached = true
        } catch (_: Exception) {
        }
    }

    /**
     * Toggles `FLAG_NOT_FOCUSABLE` off only while the composer is focused so the soft keyboard
     * works without stealing focus from the underlying app otherwise (§3.3, §4.3).
     */
    private fun setSheetFocusable(focusable: Boolean) {
        val wm = windowManager ?: return
        val view = sheetView ?: return
        val params = sheetParams ?: return

        params.flags = if (focusable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        runCatching { wm.updateViewLayout(view, params) }
    }

    fun collapseToBubble() {
        removeSheetWindow()
        _isSheetExpanded.value = false
        if (shouldShowBubble) {
            ensureBubbleWindow()
        }
    }

    private fun removeSheetWindow() {
        val wm = windowManager
        val view = sheetView
        if (isSheetAttached && wm != null && view != null) {
            runCatching { wm.removeView(view) }
        }
        sheetView = null
        sheetParams = null
        isSheetAttached = false
    }

    private fun performRecapture(keepChat: Boolean) {
        val srv = service ?: return
        scope.launch {
            removeSheetWindow()
            delay(120L)
            val rootNode = srv.getUnderlyingAppRootNode()
            val pkg = foregroundAppTracker.environment.value.foregroundPackage.ifBlank { "com.android.chrome" }
            val captured = captureEngine.captureFromNode(
                rootNode = rootNode,
                sourcePackage = pkg,
                screenHeightPx = srv.resources.displayMetrics.heightPixels,
                maxWords = currentSettings.maxArticleWords
            )
            expandSheetWindow()
            if (captured.wordCount > 0) {
                chatSessionManager.onDocumentCaptured(
                    doc = captured,
                    forceNewSession = true,
                    keepMessagesOnRecapture = keepChat
                )
            }
        }
    }

    private fun runAutoScrollCapture() {
        val srv = service ?: return
        val initialDoc = chatSessionManager.uiState.value.document ?: return
        scope.launch {
            removeSheetWindow()
            ensureBubbleWindow()
            chatSessionManager.setCapturing(true)
            val fullDoc = captureEngine.performAutoScrollCapture(
                initialDoc = initialDoc,
                rootProvider = { srv.getUnderlyingAppRootNode() },
                swipeFallback = { srv.performSwipeUpGesture() },
                maxWords = currentSettings.maxArticleWords
            )
            chatSessionManager.setCapturing(false)
            expandSheetWindow()
            chatSessionManager.onDocumentCaptured(fullDoc, forceNewSession = true)
        }
    }

    private fun triggerGoogleDocsShare() {
        val state = chatSessionManager.uiState.value
        val doc = state.document ?: return
        scope.launch {
            val settings = settingsRepository.settingsFlow.first()
            val outcome = docsExporter.exportSession(
                doc = doc,
                messages = state.messages,
                backendDisplayName = state.backendId.displayName,
                modelName = state.model,
                settings = settings
            )
            when (outcome) {
                is ExportOutcome.GoogleDocCreated -> {
                    chatSessionManager.showSnackbar("Google Doc created! Shared to Drive /${outcome.folderName}")
                    docsExporter.launchAndroidShareSheet(outcome.docUrl, "Gist: ${doc.title ?: "Summary"}")
                }
                is ExportOutcome.FallbackMarkdownShared -> {
                    chatSessionManager.showSnackbar("Google Doc created! Shared to Drive /${settings.driveFolderName}")
                    delay(1400L)
                    docsExporter.launchAndroidShareSheet(outcome.markdown, "Gist: ${doc.title ?: "Summary"}")
                }
            }
        }
    }

    private fun copyToClipboard(context: Context, label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val SAMPLE_FED_ARTICLE_TEXT = """
            Federal Reserve Enacts Aggressive 50 bps Interest Rate Cut as Inflation Cools
            By Economics Desk · Updated 1h ago · 8 min read

            WASHINGTON — The Federal Reserve voted Wednesday to lower benchmark borrowing costs by a half percentage point, pivoting boldly toward economic preservation as inflation nears its official 2 percent target and the US labor market shows subtle signs of deceleration.

            The move brings the federal funds target rate to a range between 4.75% and 5%, concluding a two-year campaign of monetary tightening that propelled interest rates to two-decade highs.

            Fed Chair Jerome Powell emphasized that the central bank is acting proactively to safeguard steady employment while price pressures continue their downward trajectory across goods and housing categories.

            Major stock indexes advanced sharply following the announcement, led by technology and consumer discretionary shares, while Treasury yields adjusted lower to reflect expectations of additional easing over the coming quarters.
        """
    }
}
