package com.gist.app.share

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.gist.app.data.SecureStore
import com.gist.app.settings.SettingsRepository
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles Google Sign-In and `AuthorizationClient` token acquisition for `drive.file` scope (§7.1).
 * Launches [AuthTrampolineActivity] when interactive consent is required from an overlay context.
 */
@Singleton
class GoogleAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val secureStore: SecureStore,
) {
    suspend fun getAccessToken(): String? {
        // Check if an explicit token was configured for testing or cached briefly
        secureStore.getSecret(KEY_CACHED_ACCESS_TOKEN)?.takeIf { it.isNotBlank() }?.let {
            return it
        }

        return try {
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
                .build()

            val authResult = Identity.getAuthorizationClient(context)
                .authorize(request)
                .await()

            if (authResult.hasResolution()) {
                val pendingIntent = authResult.pendingIntent ?: return null
                launchTrampolineForResolution(pendingIntent)
            } else {
                authResult.accessToken
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun launchTrampolineForResolution(pendingIntent: PendingIntent): String? {
        val deferred = CompletableDeferred<String?>()
        AuthTrampolineActivity.pendingResolutionDeferred = deferred
        val intent = Intent(context, AuthTrampolineActivity::class.java).apply {
            putExtra(AuthTrampolineActivity.EXTRA_PENDING_INTENT, pendingIntent)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return deferred.await()
    }

    suspend fun signOut() {
        secureStore.putSecret(KEY_CACHED_ACCESS_TOKEN, null)
        settingsRepository.setGoogleAccountEmail(null)
    }

    companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        const val KEY_CACHED_ACCESS_TOKEN = "google_drive_access_token"
    }
}

/**
 * Transparent trampoline Activity used to launch OAuth consent `PendingIntent` from an overlay (§7.1).
 */
class AuthTrampolineActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            try {
                val authResult = Identity.getAuthorizationClient(this)
                    .getAuthorizationResultFromIntent(result.data!!)
                pendingResolutionDeferred?.complete(authResult.accessToken)
            } catch (_: Exception) {
                pendingResolutionDeferred?.complete(null)
            }
        } else {
            pendingResolutionDeferred?.complete(null)
        }
        pendingResolutionDeferred = null
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val pendingIntent = intent.getParcelableExtra<PendingIntent>(EXTRA_PENDING_INTENT)
        if (pendingIntent == null) {
            pendingResolutionDeferred?.complete(null)
            pendingResolutionDeferred = null
            finish()
            return
        }
        launcher.launch(IntentSenderRequest.Builder(pendingIntent).build())
    }

    companion object {
        const val EXTRA_PENDING_INTENT = "extra_pending_intent"
        var pendingResolutionDeferred: CompletableDeferred<String?>? = null
    }
}
