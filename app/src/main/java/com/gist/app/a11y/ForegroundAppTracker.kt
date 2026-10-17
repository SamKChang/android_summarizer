package com.gist.app.a11y

import com.gist.app.settings.GistSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class ForegroundEnvironment(
    val foregroundPackage: String = "",
    val isSecureWindow: Boolean = false,
    val isPasswordFieldFocused: Boolean = false,
    val isKeyboardOpen: Boolean = false,
    val isDeviceLocked: Boolean = false,
    val dismissedForCurrentApp: Boolean = false,
)

/**
 * Tracks the active foreground package and security/privacy signals from `GistAccessibilityService`,
 * and evaluates the bubble visibility rules defined in D11, §3.2, and §8.2:
 * - Shown if the foreground package is in the allowlist OR the QS tile is ON (`qsTileForceShowEverywhere`).
 * - Hidden if:
 *   - the package is denylisted (or matches banking/password/authenticator heuristics),
 *   - the window is `FLAG_SECURE`,
 *   - the keyboard is up over a password field (or `autoHideWhileKeyboardOpen` is enabled),
 *   - the device is locked,
 *   - the user dismissed the bubble for the current app session.
 */
@Singleton
class ForegroundAppTracker @Inject constructor() {

    private val _env = MutableStateFlow(ForegroundEnvironment())
    val environment: StateFlow<ForegroundEnvironment> = _env.asStateFlow()

    fun onForegroundPackageChanged(packageName: String) {
        if (packageName.isBlank() || packageName == "com.android.systemui") return
        val current = _env.value
        val changedApp = current.foregroundPackage != packageName
        _env.value = current.copy(
            foregroundPackage = packageName,
            // Reset per-app temporary dismiss when switching apps (§3.2)
            dismissedForCurrentApp = if (changedApp) false else current.dismissedForCurrentApp
        )
    }

    fun updateWindowStates(
        isSecure: Boolean,
        isPasswordFocused: Boolean,
        isKeyboardVisible: Boolean,
        isDeviceLocked: Boolean
    ) {
        _env.value = _env.value.copy(
            isSecureWindow = isSecure,
            isPasswordFieldFocused = isPasswordFocused,
            isKeyboardOpen = isKeyboardVisible,
            isDeviceLocked = isDeviceLocked
        )
    }

    fun dismissForCurrentApp() {
        _env.value = _env.value.copy(dismissedForCurrentApp = true)
    }

    fun shouldShowBubble(env: ForegroundEnvironment, settings: GistSettings): Boolean {
        return evaluateVisibility(env, settings)
    }

    companion object {
        private val SENSITIVE_PACKAGE_KEYWORDS = listOf(
            "bank", "wallet", "auth", "pass", "vault", "keychain", "crypto"
        )

        fun evaluateVisibility(env: ForegroundEnvironment, settings: GistSettings): Boolean {
            val pkg = env.foregroundPackage
            if (pkg.isBlank()) return settings.qsTileForceShowEverywhere

            // Hard privacy & security guards (§3.2, §8.2)
            if (env.isDeviceLocked) return false
            if (env.isSecureWindow) return false
            if (env.isPasswordFieldFocused) return false
            if (env.dismissedForCurrentApp) return false
            if (settings.autoHideWhileKeyboardOpen && env.isKeyboardOpen) return false

            // Denylist always wins over allowlist and QS tile (§3.2, §8.2)
            if (pkg in settings.denylistPackages) return false
            if (pkg !in settings.allowlistPackages && SENSITIVE_PACKAGE_KEYWORDS.any { pkg.contains(it, ignoreCase = true) }) {
                return false
            }

            // Shown if in allowlist OR QS tile is ON
            return (pkg in settings.allowlistPackages) || settings.qsTileForceShowEverywhere
        }
    }
}
