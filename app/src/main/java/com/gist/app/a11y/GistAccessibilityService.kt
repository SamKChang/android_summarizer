package com.gist.app.a11y

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.gist.app.overlay.OverlayManager
import com.gist.app.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * Core AccessibilityService (§4.1, §5.2):
 * - Hosts `TYPE_ACCESSIBILITY_OVERLAY` windows via [OverlayManager] without requiring `SYSTEM_ALERT_WINDOW`.
 * - Tracks `TYPE_WINDOW_STATE_CHANGED` and `TYPE_WINDOWS_CHANGED` for bubble visibility rules.
 * - Never listens to continuous text events; reads the node tree **only on an explicit user tap**.
 * - Supports `dispatchGesture` swipe fallback for Strategy 3 auto-scroll capture.
 */
@AndroidEntryPoint
class GistAccessibilityService : AccessibilityService() {

    @Inject lateinit var overlayManager: OverlayManager
    @Inject lateinit var foregroundAppTracker: ForegroundAppTracker
    @Inject lateinit var secureWindowDetector: SecureWindowDetector
    @Inject lateinit var settingsRepository: SettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance.value = this
        overlayManager.attachService(this)

        // Register Accessibility Button shortcut callback so tapping the system accessibility shortcut toggles/triggers Gist
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                accessibilityButtonController.registerAccessibilityButtonCallback(
                    object : AccessibilityButtonController.AccessibilityButtonCallback() {
                        override fun onClicked(controller: AccessibilityButtonController?) {
                            overlayManager.onBubbleTapped()
                        }
                    }
                )
            }
        }

        // Seed initial foreground package if available
        rootInActiveWindow?.packageName?.toString()?.let { pkg ->
            foregroundAppTracker.onForegroundPackageChanged(pkg)
        }

        serviceScope.launch {
            combine(
                foregroundAppTracker.environment,
                settingsRepository.settingsFlow
            ) { env, settings ->
                foregroundAppTracker.shouldShowBubble(env, settings) to settings
            }.collect { (shouldShow, settings) ->
                overlayManager.updateBubbleVisibilityAndConfig(shouldShow, settings)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                val pkg = event.packageName?.toString()
                if (!pkg.isNullOrBlank() && pkg != "com.gist.app" && pkg != "com.android.systemui") {
                    foregroundAppTracker.onForegroundPackageChanged(pkg)
                }
                val root = runCatching { rootInActiveWindow }.getOrNull()
                val winList = runCatching { windows }.getOrNull()
                val sec = secureWindowDetector.inspect(winList, root)
                foregroundAppTracker.updateWindowStates(
                    isSecure = sec.isSecureOrBlocked,
                    isPasswordFocused = sec.isPasswordFieldFocused,
                    isKeyboardVisible = sec.isImeVisible,
                    isDeviceLocked = sec.isDeviceLocked
                )
            }
            else -> {}
        }
    }

    override fun onInterrupt() {
        // Required override
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        overlayManager.detachService()
        _instance.value = null
        serviceScope.cancel()
        return super.onUnbind(intent)
    }

    /**
     * Returns the root node of the underlying active application window (ignoring our own overlay windows).
     */
    fun getUnderlyingAppRootNode(): AccessibilityNodeInfo? {
        val winList = runCatching { windows }.getOrNull()
        if (winList != null) {
            for (win in winList) {
                if (win.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
                    val r = win.root
                    if (r != null && r.packageName?.toString() != "com.gist.app") {
                        return r
                    }
                }
            }
        }
        val active = runCatching { rootInActiveWindow }.getOrNull()
        if (active != null && active.packageName?.toString() != "com.gist.app") {
            return active
        }
        return null
    }

    /**
     * Fallback gesture scroll for Strategy 3 when `ACTION_SCROLL_FORWARD` is unsupported by a custom view.
     */
    suspend fun performSwipeUpGesture(): Boolean = suspendCancellableCoroutine { cont ->
        val metrics = resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val startY = metrics.heightPixels * 0.75f
        val endY = metrics.heightPixels * 0.25f

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 280L))
            .build()

        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            },
            null
        )
        if (!dispatched && cont.isActive) {
            cont.resume(false)
        }
    }

    companion object {
        private val _instance = MutableStateFlow<GistAccessibilityService?>(null)
        val instance: StateFlow<GistAccessibilityService?> = _instance.asStateFlow()

        val isServiceEnabled: Boolean
            get() = _instance.value != null
    }
}
