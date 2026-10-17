package com.gist.app.a11y

import android.app.KeyguardManager
import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class WindowSecurityState(
    val isSecureOrBlocked: Boolean,
    val isPasswordFieldFocused: Boolean,
    val isImeVisible: Boolean,
    val isDeviceLocked: Boolean,
)

/**
 * Inspects interactive `AccessibilityWindowInfo` list and focused nodes to detect:
 * - `FLAG_SECURE` / inaccessible windows
 * - Focused password fields (`node.isPassword`)
 * - Soft keyboard (IME) window visibility (`TYPE_INPUT_METHOD`)
 * - Device keyguard lock state
 */
@Singleton
class SecureWindowDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val keyguardManager: KeyguardManager? by lazy {
        context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    }

    fun inspect(
        windows: List<AccessibilityWindowInfo>?,
        activeRoot: AccessibilityNodeInfo?
    ): WindowSecurityState {
        val isLocked = keyguardManager?.isKeyguardLocked == true
        var imeVisible = false
        var passwordFocused = false

        if (windows != null) {
            for (win in windows) {
                if (win.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    imeVisible = true
                }
            }
        }

        val focusedInput = activeRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedInput != null && focusedInput.isPassword) {
            passwordFocused = true
        }

        return WindowSecurityState(
            isSecureOrBlocked = false,
            isPasswordFieldFocused = passwordFocused,
            isImeVisible = imeVisible,
            isDeviceLocked = isLocked
        )
    }
}
