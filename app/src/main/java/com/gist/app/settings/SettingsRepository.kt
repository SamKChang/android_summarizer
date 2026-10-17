package com.gist.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gist.app.llm.BackendId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "gist_settings")

@Serializable
data class QuickActionChipConfig(
    val label: String,
    val prompt: String
)

@Serializable
data class ModelDescriptor(
    val id: String,
    val displayName: String,
    val contextTokens: Int = 128000
)

@Serializable
data class DefaultsAsset(
    val defaultBackend: String = "GEMINI",
    val models: Map<String, List<ModelDescriptor>> = emptyMap(),
    val defaultAllowlist: List<String> = emptyList(),
    val defaultDenylist: List<String> = emptyList(),
    val quickActionChips: List<QuickActionChipConfig> = emptyList()
)

enum class SummaryLength { SHORT, MEDIUM, LONG }
enum class DocExportContent { WHOLE_CHAT, LAST_ANSWER }

data class GistSettings(
    val onboardingCompleted: Boolean = false,
    val defaultBackend: BackendId = BackendId.GEMINI,
    val selectedModels: Map<BackendId, String> = mapOf(
        BackendId.GEMINI to "gemini-2.5-flash",
        BackendId.OPENAI to "gpt-4o-mini",
        BackendId.ANTHROPIC to "claude-sonnet-4-20250514",
        BackendId.OPENAI_COMPATIBLE to "openai/gpt-4o-mini",
        BackendId.GEMINI_NANO to "gemini-nano",
        BackendId.HANDOFF to "chatgpt-app",
        BackendId.DEMO to "demo-flash",
    ),
    val temperature: Float = 0.3f,
    val maxOutputTokens: Int = 2048,
    val openAiCompatibleBaseUrl: String = "https://openrouter.ai/api/v1",
    val handoffTargetPackage: String = "com.openai.chatgpt",
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val defaultAutoRunPrompt: String = DEFAULT_AUTO_RUN_PROMPT,
    val quickActionChips: List<QuickActionChipConfig> = DEFAULT_CHIPS,
    val responseLanguage: String = "auto",
    val summaryLength: SummaryLength = SummaryLength.MEDIUM,
    val allowAutoScroll: Boolean = true,
    val maxArticleWords: Int = 60000,
    val showCapturePreviewBeforeSending: Boolean = false,
    val allowlistPackages: Set<String> = DEFAULT_ALLOWLIST,
    val denylistPackages: Set<String> = DEFAULT_DENYLIST,
    val bubbleSizeDp: Int = 56,
    val bubbleIdleOpacity: Float = 0.85f,
    val autoHideWhileKeyboardOpen: Boolean = false,
    val qsTileForceShowEverywhere: Boolean = false,
    val bubblePositionXPercent: Float = 1.0f, // 0.0 left, 1.0 right edge
    val bubblePositionYFraction: Float = 0.35f,
    val defaultWebSearchEnabled: Boolean = false,
    val maxSearchesPerTurn: Int = 3,
    val googleAccountEmail: String? = null,
    val docExportContent: DocExportContent = DocExportContent.WHOLE_CHAT,
    val driveFolderName: String = "Gist",
    val includeCapturedArticleInDoc: Boolean = false,
    val historyEnabled: Boolean = true,
    val retentionDays: Int = 30, // 1, 7, 30, 90, or -1 for forever
    val consentedCloudProviders: Set<String> = emptySet(),
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "You are a reading assistant. The user is reading the article below. Answer using the article first; say when something is not in the article. Be concise, use Markdown headings and bullets. If web search is enabled, cite sources."

        const val DEFAULT_AUTO_RUN_PROMPT =
            "Give a TL;DR (2 sentences), then 5–7 key points, then notable numbers/quotes, then what's missing or debatable."

        const val PROMPT_TEMPLATE_TLDR = DEFAULT_AUTO_RUN_PROMPT
        const val PROMPT_TEMPLATE_BULLET_FACTS =
            "List the core verifiable facts, figures, and entities mentioned in the article as terse bullet points."
        const val PROMPT_TEMPLATE_NEUTRAL_BRIEF =
            "Provide an executive neutral brief: 1) Core thesis, 2) Supporting evidence, 3) Stakeholders & perspectives, 4) Open questions."

        val DEFAULT_ALLOWLIST = setOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.google.android.apps.magazines",
            "com.nytimes.android",
            "com.guardian",
            "com.medium.reader",
            "com.substack.app",
            "com.reuters.jet",
            "com.ft.news",
            "com.bloomberg.android.plus"
        )

        val DEFAULT_DENYLIST = setOf(
            "com.gist.app",
            "com.android.settings",
            "com.google.android.apps.messaging",
            "com.google.android.apps.authenticator2",
            "com.agilebits.onepassword",
            "com.x8bit.bitwarden",
            "com.dashlane",
            "com.paypal.android.p2pmobile",
            "com.chase.sig.android",
            "com.wf.wellsfargomobile",
            "com.infonow.bofa"
        )

        val DEFAULT_CHIPS = listOf(
            QuickActionChipConfig(
                "Key facts",
                "Extract the key facts, statistics, and numbers from this article as concise bullet points."
            ),
            QuickActionChipConfig(
                "ELI5",
                "Explain like I'm 5 (ELI5)"
            ),
            QuickActionChipConfig(
                "Counter-arguments",
                "What are the strongest counter-arguments, missing perspectives, or limitations to the claims made in this article?"
            ),
            QuickActionChipConfig(
                "Timeline",
                "Construct a chronological timeline of the events described in this article."
            )
        )
    }
}

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }

    val defaultsAsset: DefaultsAsset by lazy {
        try {
            val raw = context.assets.open("defaults.json").bufferedReader().use { it.readText() }
            json.decodeFromString<DefaultsAsset>(raw)
        } catch (e: Exception) {
            DefaultsAsset()
        }
    }

    val settingsFlow: Flow<GistSettings> = context.dataStore.data.map { prefs ->
        val backendStr = prefs[Keys.DEFAULT_BACKEND] ?: defaultsAsset.defaultBackend
        val backend = runCatching { BackendId.valueOf(backendStr) }.getOrDefault(BackendId.GEMINI)

        val chipsJson = prefs[Keys.QUICK_CHIPS_JSON]
        val chips = if (chipsJson != null) {
            runCatching { json.decodeFromString<List<QuickActionChipConfig>>(chipsJson) }
                .getOrDefault(GistSettings.DEFAULT_CHIPS)
        } else {
            defaultsAsset.quickActionChips.ifEmpty { GistSettings.DEFAULT_CHIPS }
        }

        val modelsJson = prefs[Keys.SELECTED_MODELS_JSON]
        val defaultModels = GistSettings().selectedModels
        val models = if (modelsJson != null) {
            runCatching {
                val mapStr = json.decodeFromString<Map<String, String>>(modelsJson)
                defaultModels + mapStr.mapNotNull { (k, v) ->
                    runCatching { BackendId.valueOf(k) to v }.getOrNull()
                }.toMap()
            }.getOrDefault(defaultModels)
        } else {
            defaultModels
        }

        GistSettings(
            onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
            defaultBackend = backend,
            selectedModels = models,
            temperature = prefs[Keys.TEMPERATURE] ?: 0.3f,
            maxOutputTokens = prefs[Keys.MAX_OUTPUT_TOKENS] ?: 2048,
            openAiCompatibleBaseUrl = prefs[Keys.OPENAI_COMPAT_BASE_URL] ?: "https://openrouter.ai/api/v1",
            handoffTargetPackage = prefs[Keys.HANDOFF_TARGET_PACKAGE] ?: "com.openai.chatgpt",
            systemPrompt = prefs[Keys.SYSTEM_PROMPT] ?: GistSettings.DEFAULT_SYSTEM_PROMPT,
            defaultAutoRunPrompt = prefs[Keys.DEFAULT_AUTO_RUN_PROMPT] ?: GistSettings.DEFAULT_AUTO_RUN_PROMPT,
            quickActionChips = chips,
            responseLanguage = prefs[Keys.RESPONSE_LANGUAGE] ?: "auto",
            summaryLength = prefs[Keys.SUMMARY_LENGTH]?.let {
                runCatching { SummaryLength.valueOf(it) }.getOrNull()
            } ?: SummaryLength.MEDIUM,
            allowAutoScroll = prefs[Keys.ALLOW_AUTO_SCROLL] ?: true,
            maxArticleWords = prefs[Keys.MAX_ARTICLE_WORDS] ?: 60000,
            showCapturePreviewBeforeSending = prefs[Keys.SHOW_CAPTURE_PREVIEW] ?: false,
            allowlistPackages = prefs[Keys.ALLOWLIST_PACKAGES]
                ?: defaultsAsset.defaultAllowlist.toSet().ifEmpty { GistSettings.DEFAULT_ALLOWLIST },
            denylistPackages = prefs[Keys.DENYLIST_PACKAGES]
                ?: defaultsAsset.defaultDenylist.toSet().ifEmpty { GistSettings.DEFAULT_DENYLIST },
            bubbleSizeDp = prefs[Keys.BUBBLE_SIZE_DP] ?: 56,
            bubbleIdleOpacity = prefs[Keys.BUBBLE_OPACITY] ?: 0.85f,
            autoHideWhileKeyboardOpen = prefs[Keys.AUTO_HIDE_KEYBOARD] ?: false,
            qsTileForceShowEverywhere = prefs[Keys.QS_FORCE_SHOW] ?: false,
            bubblePositionXPercent = prefs[Keys.BUBBLE_POS_X] ?: 1.0f,
            bubblePositionYFraction = prefs[Keys.BUBBLE_POS_Y] ?: 0.35f,
            defaultWebSearchEnabled = prefs[Keys.DEFAULT_WEB_SEARCH] ?: false,
            maxSearchesPerTurn = prefs[Keys.MAX_SEARCHES_PER_TURN] ?: 3,
            googleAccountEmail = prefs[Keys.GOOGLE_ACCOUNT_EMAIL],
            docExportContent = prefs[Keys.DOC_EXPORT_CONTENT]?.let {
                runCatching { DocExportContent.valueOf(it) }.getOrNull()
            } ?: DocExportContent.WHOLE_CHAT,
            driveFolderName = prefs[Keys.DRIVE_FOLDER_NAME] ?: "Gist",
            includeCapturedArticleInDoc = prefs[Keys.INCLUDE_CAPTURED_ARTICLE_DOC] ?: false,
            historyEnabled = prefs[Keys.HISTORY_ENABLED] ?: true,
            retentionDays = prefs[Keys.RETENTION_DAYS] ?: 30,
            consentedCloudProviders = prefs[Keys.CONSENTED_PROVIDERS] ?: emptySet(),
        )
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
    }

    suspend fun setDefaultBackend(backendId: BackendId) {
        context.dataStore.edit { it[Keys.DEFAULT_BACKEND] = backendId.name }
    }

    suspend fun setSelectedModel(backendId: BackendId, model: String) {
        context.dataStore.edit { prefs ->
            val currentJson = prefs[Keys.SELECTED_MODELS_JSON]
            val currentMap = if (currentJson != null) {
                runCatching { json.decodeFromString<Map<String, String>>(currentJson) }.getOrDefault(emptyMap())
            } else emptyMap()
            val updated = currentMap + (backendId.name to model)
            prefs[Keys.SELECTED_MODELS_JSON] = json.encodeToString(updated)
        }
    }

    suspend fun setTemperature(temp: Float) {
        context.dataStore.edit { it[Keys.TEMPERATURE] = temp.coerceIn(0f, 2f) }
    }

    suspend fun setMaxOutputTokens(tokens: Int) {
        context.dataStore.edit { it[Keys.MAX_OUTPUT_TOKENS] = tokens.coerceIn(128, 32768) }
    }

    suspend fun setOpenAiCompatibleBaseUrl(url: String) {
        context.dataStore.edit { it[Keys.OPENAI_COMPAT_BASE_URL] = url.trim() }
    }

    suspend fun setHandoffTargetPackage(pkg: String) {
        context.dataStore.edit { it[Keys.HANDOFF_TARGET_PACKAGE] = pkg }
    }

    suspend fun setPrompts(systemPrompt: String, defaultAutoRunPrompt: String) {
        context.dataStore.edit {
            it[Keys.SYSTEM_PROMPT] = systemPrompt
            it[Keys.DEFAULT_AUTO_RUN_PROMPT] = defaultAutoRunPrompt
        }
    }

    suspend fun setQuickActionChips(chips: List<QuickActionChipConfig>) {
        context.dataStore.edit {
            it[Keys.QUICK_CHIPS_JSON] = json.encodeToString(chips)
        }
    }

    suspend fun setResponseLanguage(lang: String) {
        context.dataStore.edit { it[Keys.RESPONSE_LANGUAGE] = lang }
    }

    suspend fun setSummaryLength(length: SummaryLength) {
        context.dataStore.edit { it[Keys.SUMMARY_LENGTH] = length.name }
    }

    suspend fun setCaptureConfig(allowAutoScroll: Boolean, maxWords: Int, showPreview: Boolean) {
        context.dataStore.edit {
            it[Keys.ALLOW_AUTO_SCROLL] = allowAutoScroll
            it[Keys.MAX_ARTICLE_WORDS] = maxWords
            it[Keys.SHOW_CAPTURE_PREVIEW] = showPreview
        }
    }

    suspend fun setAllowlistPackages(packages: Set<String>) {
        context.dataStore.edit { it[Keys.ALLOWLIST_PACKAGES] = packages }
    }

    suspend fun setDenylistPackages(packages: Set<String>) {
        context.dataStore.edit { it[Keys.DENYLIST_PACKAGES] = packages }
    }

    suspend fun setBubbleAppearance(sizeDp: Int, opacity: Float, autoHideKeyboard: Boolean) {
        context.dataStore.edit {
            it[Keys.BUBBLE_SIZE_DP] = sizeDp.coerceIn(40, 80)
            it[Keys.BUBBLE_OPACITY] = opacity.coerceIn(0.3f, 1.0f)
            it[Keys.AUTO_HIDE_KEYBOARD] = autoHideKeyboard
        }
    }

    suspend fun setBubblePosition(xPercent: Float, yFraction: Float) {
        context.dataStore.edit {
            it[Keys.BUBBLE_POS_X] = xPercent.coerceIn(0f, 1f)
            it[Keys.BUBBLE_POS_Y] = yFraction.coerceIn(0.05f, 0.9f)
        }
    }

    suspend fun setQsTileForceShowEverywhere(enabled: Boolean) {
        context.dataStore.edit { it[Keys.QS_FORCE_SHOW] = enabled }
    }

    suspend fun setWebSearchDefaults(enabledByDefault: Boolean, maxSearches: Int) {
        context.dataStore.edit {
            it[Keys.DEFAULT_WEB_SEARCH] = enabledByDefault
            it[Keys.MAX_SEARCHES_PER_TURN] = maxSearches.coerceIn(1, 10)
        }
    }

    suspend fun setGoogleAccountEmail(email: String?) {
        context.dataStore.edit {
            if (email == null) it.remove(Keys.GOOGLE_ACCOUNT_EMAIL)
            else it[Keys.GOOGLE_ACCOUNT_EMAIL] = email
        }
    }

    suspend fun setShareSettings(
        content: DocExportContent,
        folderName: String,
        includeArticle: Boolean
    ) {
        context.dataStore.edit {
            it[Keys.DOC_EXPORT_CONTENT] = content.name
            it[Keys.DRIVE_FOLDER_NAME] = folderName.ifBlank { "Gist" }
            it[Keys.INCLUDE_CAPTURED_ARTICLE_DOC] = includeArticle
        }
    }

    suspend fun setHistorySettings(enabled: Boolean, retentionDays: Int) {
        context.dataStore.edit {
            it[Keys.HISTORY_ENABLED] = enabled
            it[Keys.RETENTION_DAYS] = retentionDays
        }
    }

    suspend fun recordCloudProviderConsent(backendId: BackendId) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.CONSENTED_PROVIDERS] ?: emptySet()
            prefs[Keys.CONSENTED_PROVIDERS] = current + backendId.name
        }
    }

    suspend fun clearCloudProviderConsents() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.CONSENTED_PROVIDERS)
        }
    }

    private object Keys {
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val DEFAULT_BACKEND = stringPreferencesKey("default_backend")
        val SELECTED_MODELS_JSON = stringPreferencesKey("selected_models_json")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val MAX_OUTPUT_TOKENS = intPreferencesKey("max_output_tokens")
        val OPENAI_COMPAT_BASE_URL = stringPreferencesKey("openai_compat_base_url")
        val HANDOFF_TARGET_PACKAGE = stringPreferencesKey("handoff_target_package")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val DEFAULT_AUTO_RUN_PROMPT = stringPreferencesKey("default_auto_run_prompt")
        val QUICK_CHIPS_JSON = stringPreferencesKey("quick_chips_json")
        val RESPONSE_LANGUAGE = stringPreferencesKey("response_language")
        val SUMMARY_LENGTH = stringPreferencesKey("summary_length")
        val ALLOW_AUTO_SCROLL = booleanPreferencesKey("allow_auto_scroll")
        val MAX_ARTICLE_WORDS = intPreferencesKey("max_article_words")
        val SHOW_CAPTURE_PREVIEW = booleanPreferencesKey("show_capture_preview")
        val ALLOWLIST_PACKAGES = stringSetPreferencesKey("allowlist_packages")
        val DENYLIST_PACKAGES = stringSetPreferencesKey("denylist_packages")
        val BUBBLE_SIZE_DP = intPreferencesKey("bubble_size_dp")
        val BUBBLE_OPACITY = floatPreferencesKey("bubble_opacity")
        val AUTO_HIDE_KEYBOARD = booleanPreferencesKey("auto_hide_keyboard")
        val QS_FORCE_SHOW = booleanPreferencesKey("qs_force_show")
        val BUBBLE_POS_X = floatPreferencesKey("bubble_pos_x")
        val BUBBLE_POS_Y = floatPreferencesKey("bubble_pos_y")
        val DEFAULT_WEB_SEARCH = booleanPreferencesKey("default_web_search")
        val MAX_SEARCHES_PER_TURN = intPreferencesKey("max_searches_per_turn")
        val GOOGLE_ACCOUNT_EMAIL = stringPreferencesKey("google_account_email")
        val DOC_EXPORT_CONTENT = stringPreferencesKey("doc_export_content")
        val DRIVE_FOLDER_NAME = stringPreferencesKey("drive_folder_name")
        val INCLUDE_CAPTURED_ARTICLE_DOC = booleanPreferencesKey("include_captured_article_doc")
        val HISTORY_ENABLED = booleanPreferencesKey("history_enabled")
        val RETENTION_DAYS = intPreferencesKey("retention_days")
        val CONSENTED_PROVIDERS = stringSetPreferencesKey("consented_providers")
    }
}
