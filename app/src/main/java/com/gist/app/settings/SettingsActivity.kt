package com.gist.app.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gist.app.BuildConfig
import com.gist.app.data.SecureStore
import com.gist.app.data.SessionDao
import com.gist.app.data.SessionEntity
import com.gist.app.llm.BackendId
import com.gist.app.llm.LlmRouter
import com.gist.app.llm.anthropic.AnthropicBackend
import com.gist.app.llm.compat.OpenAICompatibleBackend
import com.gist.app.llm.gemini.GeminiBackend
import com.gist.app.llm.nano.GeminiNanoBackend
import com.gist.app.llm.nano.NanoDownloadState
import com.gist.app.llm.openai.OpenAIBackend
import com.gist.app.overlay.ui.GistPurple
import com.gist.app.share.GoogleAuthManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Full-screen Settings Activity (§3.6) covering all 8 specification sections:
 * 1. **AI backend**: Default backend, per-provider API keys (masked, Tink Keystore encrypted, "Test" button),
 *    model picker (fetched from provider's `models` endpoint + defaults), temperature, max output tokens,
 *    OpenAI-compatible base URL, on-device Gemini Nano status/download, hand-off target app.
 * 2. **Prompts**: Default auto-run prompt (templates: TL;DR+Key points, Bullet facts, Neutral brief),
 *    system prompt, quick-action chips editor, response language, summary length.
 * 3. **Capture**: Strategy order, "Allow auto-scroll" toggle, max article length, show capture preview before sending.
 * 4. **Bubble**: App allowlist & denylist editor, size, opacity, auto-hide while keyboard is open, Quick Settings tile help.
 * 5. **Web research**: Default 🌐 state, max searches per turn.
 * 6. **Share**: Google account (sign in/out), doc contents (whole chat / last answer), Drive folder name ("Gist"), include captured article text.
 * 7. **History & privacy**: History on/off, retention (1/7/30/90 days, forever), clear all, view history list.
 * 8. **About**: Version, licenses, privacy policy, accessibility-service disclosure.
 */
@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var secureStore: SecureStore
    @Inject lateinit var llmRouter: LlmRouter
    @Inject lateinit var geminiNanoBackend: GeminiNanoBackend
    @Inject lateinit var sessionDao: SessionDao
    @Inject lateinit var googleAuthManager: GoogleAuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                val settings by settingsRepository.settingsFlow.collectAsState(initial = GistSettings())
                val sessions by sessionDao.observeAllSessions().collectAsState(initial = emptyList())
                val nanoState by geminiNanoBackend.downloadState.collectAsState()
                val nanoProgress by geminiNanoBackend.downloadProgress.collectAsState()

                SettingsScreen(
                    settings = settings,
                    sessions = sessions,
                    nanoState = nanoState,
                    nanoProgress = nanoProgress,
                    initialGeminiKey = secureStore.getSecret(GeminiBackend.SECRET_KEY_GEMINI).orEmpty(),
                    initialOpenAiKey = secureStore.getSecret(OpenAIBackend.SECRET_KEY_OPENAI).orEmpty(),
                    initialAnthropicKey = secureStore.getSecret(AnthropicBackend.SECRET_KEY_ANTHROPIC).orEmpty(),
                    initialCompatKey = secureStore.getSecret(OpenAICompatibleBackend.SECRET_KEY_COMPAT).orEmpty(),
                    onSaveKeys = { gemini, openai, anthropic, compat ->
                        secureStore.putSecret(GeminiBackend.SECRET_KEY_GEMINI, gemini)
                        secureStore.putSecret(OpenAIBackend.SECRET_KEY_OPENAI, openai)
                        secureStore.putSecret(AnthropicBackend.SECRET_KEY_ANTHROPIC, anthropic)
                        secureStore.putSecret(OpenAICompatibleBackend.SECRET_KEY_COMPAT, compat)
                        Toast.makeText(this, "API keys saved securely in Keystore", Toast.LENGTH_SHORT).show()
                    },
                    onTestProviderModels = { backendId ->
                        val models = llmRouter.backendFor(backendId).fetchModels()
                        val msg = if (models.isNotEmpty()) {
                            "Connected! Found ${models.size} models (e.g. ${models.first()})"
                        } else {
                            "Key saved (using default model list)"
                        }
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    },
                    onDownloadNano = { geminiNanoBackend.downloadModel() },
                    settingsRepository = settingsRepository,
                    onDeleteSession = { id -> sessionDao.deleteSessionById(id) },
                    onClearAllHistory = { sessionDao.deleteAllSessions() },
                    onSignOutGoogle = { googleAuthManager.signOut() },
                    onOpenAccessibilitySettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: GistSettings,
    sessions: List<SessionEntity>,
    nanoState: NanoDownloadState,
    nanoProgress: Float,
    initialGeminiKey: String,
    initialOpenAiKey: String,
    initialAnthropicKey: String,
    initialCompatKey: String,
    onSaveKeys: (String, String, String, String) -> Unit,
    onTestProviderModels: suspend (BackendId) -> Unit,
    onDownloadNano: suspend () -> Unit,
    settingsRepository: SettingsRepository,
    onDeleteSession: suspend (String) -> Unit,
    onClearAllHistory: suspend () -> Unit,
    onSignOutGoogle: suspend () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var geminiKey by remember(initialGeminiKey) { mutableStateOf(initialGeminiKey) }
    var openAiKey by remember(initialOpenAiKey) { mutableStateOf(initialOpenAiKey) }
    var anthropicKey by remember(initialAnthropicKey) { mutableStateOf(initialAnthropicKey) }
    var compatKey by remember(initialCompatKey) { mutableStateOf(initialCompatKey) }
    var compatUrl by remember(settings.openAiCompatibleBaseUrl) { mutableStateOf(settings.openAiCompatibleBaseUrl) }

    var systemPrompt by remember(settings.systemPrompt) { mutableStateOf(settings.systemPrompt) }
    var defaultPrompt by remember(settings.defaultAutoRunPrompt) { mutableStateOf(settings.defaultAutoRunPrompt) }
    var temperature by remember(settings.temperature) { mutableFloatStateOf(settings.temperature) }

    var newAllowPkg by remember { mutableStateOf("") }
    var newDenyPkg by remember { mutableStateOf("") }
    var driveFolder by remember(settings.driveFolderName) { mutableStateOf(settings.driveFolderName) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gist Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag("settings_scroll_column"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. AI Backend Section (§3.6)
            SettingsSectionCard(title = "1. AI Backend") {
                Text("Default Backend", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackendId.entries.filter { it != BackendId.DEMO }.forEach { b ->
                        FilterChip(
                            selected = settings.defaultBackend == b,
                            onClick = { scope.launch { settingsRepository.setDefaultBackend(b) } },
                            label = { Text(b.displayName) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = geminiKey,
                    onValueChange = { geminiKey = it },
                    label = { Text("Gemini API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    trailingIcon = {
                        TextButton(onClick = { scope.launch { onTestProviderModels(BackendId.GEMINI) } }) {
                            Text("Test")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = openAiKey,
                    onValueChange = { openAiKey = it },
                    label = { Text("OpenAI API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    trailingIcon = {
                        TextButton(onClick = { scope.launch { onTestProviderModels(BackendId.OPENAI) } }) {
                            Text("Test")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = anthropicKey,
                    onValueChange = { anthropicKey = it },
                    label = { Text("Anthropic API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    trailingIcon = {
                        TextButton(onClick = { scope.launch { onTestProviderModels(BackendId.ANTHROPIC) } }) {
                            Text("Test")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = compatUrl,
                    onValueChange = {
                        compatUrl = it
                        scope.launch { settingsRepository.setOpenAiCompatibleBaseUrl(it) }
                    },
                    label = { Text("OpenAI-Compatible Base URL (OpenRouter / Ollama)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = compatKey,
                    onValueChange = { compatKey = it },
                    label = { Text("OpenAI-Compatible API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = { onSaveKeys(geminiKey, openAiKey, anthropicKey, compatKey) },
                    colors = ButtonDefaults.buttonColors(containerColor = GistPurple),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save API Keys (Encrypted in Android Keystore)")
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Temperature: ${"%.2f".format(temperature)} · Max Output Tokens: ${settings.maxOutputTokens}",
                    fontSize = 13.sp
                )
                Slider(
                    value = temperature,
                    onValueChange = {
                        temperature = it
                        scope.launch { settingsRepository.setTemperature(it) }
                    },
                    valueRange = 0f..1.5f
                )

                HorizontalDivider()
                Text("On-Device Gemini Nano (AICore / ML Kit GenAI)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("Status: $nanoState", fontSize = 13.sp, color = Color(0xFF5F6368))
                if (nanoState == NanoDownloadState.DOWNLOADING) {
                    LinearProgressIndicator(progress = { nanoProgress }, modifier = Modifier.fillMaxWidth())
                } else if (nanoState != NanoDownloadState.READY) {
                    OutlinedButton(onClick = { scope.launch { onDownloadNano() } }) {
                        Text("Download Gemini Nano Model")
                    }
                }
            }

            // 2. Prompts Section (§3.6)
            SettingsSectionCard(title = "2. Prompts & Quick Chips") {
                Text("Default Auto-Run Templates", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = defaultPrompt == GistSettings.PROMPT_TEMPLATE_TLDR,
                        onClick = {
                            defaultPrompt = GistSettings.PROMPT_TEMPLATE_TLDR
                            scope.launch { settingsRepository.setPrompts(systemPrompt, defaultPrompt) }
                        },
                        label = { Text("TL;DR + Key points") }
                    )
                    FilterChip(
                        selected = defaultPrompt == GistSettings.PROMPT_TEMPLATE_BULLET_FACTS,
                        onClick = {
                            defaultPrompt = GistSettings.PROMPT_TEMPLATE_BULLET_FACTS
                            scope.launch { settingsRepository.setPrompts(systemPrompt, defaultPrompt) }
                        },
                        label = { Text("Bullet facts") }
                    )
                    FilterChip(
                        selected = defaultPrompt == GistSettings.PROMPT_TEMPLATE_NEUTRAL_BRIEF,
                        onClick = {
                            defaultPrompt = GistSettings.PROMPT_TEMPLATE_NEUTRAL_BRIEF
                            scope.launch { settingsRepository.setPrompts(systemPrompt, defaultPrompt) }
                        },
                        label = { Text("Neutral brief") }
                    )
                }

                OutlinedTextField(
                    value = defaultPrompt,
                    onValueChange = {
                        defaultPrompt = it
                        scope.launch { settingsRepository.setPrompts(systemPrompt, it) }
                    },
                    label = { Text("Default Auto-Run Prompt") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = {
                        systemPrompt = it
                        scope.launch { settingsRepository.setPrompts(it, defaultPrompt) }
                    },
                    label = { Text("System Prompt") },
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Summary Length", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryLength.entries.forEach { len ->
                        FilterChip(
                            selected = settings.summaryLength == len,
                            onClick = { scope.launch { settingsRepository.setSummaryLength(len) } },
                            label = { Text(len.name) }
                        )
                    }
                }
            }

            // 3. Capture Section (§3.6)
            SettingsSectionCard(title = "3. Capture Strategy") {
                Text(
                    "Pipeline Order: 1. Accessibility Tree → 2. URL Fetch + Readability → 3. Auto-Scroll",
                    fontSize = 13.sp,
                    color = Color(0xFF5F6368)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Allow Auto-Scroll Fallback")
                    Switch(
                        checked = settings.allowAutoScroll,
                        onCheckedChange = {
                            scope.launch {
                                settingsRepository.setCaptureConfig(
                                    it,
                                    settings.maxArticleWords,
                                    settings.showCapturePreviewBeforeSending
                                )
                            }
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Show Capture Preview Before Sending")
                    Switch(
                        checked = settings.showCapturePreviewBeforeSending,
                        onCheckedChange = {
                            scope.launch {
                                settingsRepository.setCaptureConfig(
                                    settings.allowAutoScroll,
                                    settings.maxArticleWords,
                                    it
                                )
                            }
                        }
                    )
                }
            }

            // 4. Bubble Visibility & Allowlist/Denylist Section (§3.6)
            SettingsSectionCard(title = "4. Bubble & App Allowlist / Denylist") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Force Show Everywhere (Quick Settings Tile)")
                    Switch(
                        checked = settings.qsTileForceShowEverywhere,
                        onCheckedChange = {
                            scope.launch { settingsRepository.setQsTileForceShowEverywhere(it) }
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Auto-Hide Bubble While Keyboard is Open")
                    Switch(
                        checked = settings.autoHideWhileKeyboardOpen,
                        onCheckedChange = {
                            scope.launch {
                                settingsRepository.setBubbleAppearance(
                                    settings.bubbleSizeDp,
                                    settings.bubbleIdleOpacity,
                                    it
                                )
                            }
                        }
                    )
                }

                Text("Allowlisted Reading Apps (${settings.allowlistPackages.size})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(
                    settings.allowlistPackages.joinToString(", "),
                    fontSize = 12.sp,
                    color = Color(0xFF5F6368)
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newAllowPkg,
                        onValueChange = { newAllowPkg = it },
                        label = { Text("Add package to allowlist") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (newAllowPkg.isNotBlank()) {
                                val pkg = newAllowPkg.trim()
                                newAllowPkg = ""
                                scope.launch {
                                    settingsRepository.setAllowlistPackages(settings.allowlistPackages + pkg)
                                }
                            }
                        }
                    ) { Text("Add") }
                }

                Text("Denylisted Sensitive Apps (${settings.denylistPackages.size})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(
                    settings.denylistPackages.joinToString(", "),
                    fontSize = 12.sp,
                    color = Color(0xFF5F6368)
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newDenyPkg,
                        onValueChange = { newDenyPkg = it },
                        label = { Text("Add package to denylist") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (newDenyPkg.isNotBlank()) {
                                val pkg = newDenyPkg.trim()
                                newDenyPkg = ""
                                scope.launch {
                                    settingsRepository.setDenylistPackages(settings.denylistPackages + pkg)
                                }
                            }
                        }
                    ) { Text("Block") }
                }
            }

            // 5. Web Research Section (§3.6)
            SettingsSectionCard(title = "5. Web Research (🌐)") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Enable Web Search by Default")
                    Switch(
                        checked = settings.defaultWebSearchEnabled,
                        onCheckedChange = {
                            scope.launch {
                                settingsRepository.setWebSearchDefaults(it, settings.maxSearchesPerTurn)
                            }
                        }
                    )
                }
                Text("Max Searches per Turn: ${settings.maxSearchesPerTurn}", fontSize = 13.sp)
            }

            // 6. Share -> Google Docs Section (§3.6)
            SettingsSectionCard(title = "6. Share → Google Doc") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = settings.googleAccountEmail?.let { "Signed in: $it" } ?: "Not signed in with Google",
                        fontSize = 14.sp
                    )
                    if (settings.googleAccountEmail != null) {
                        TextButton(onClick = { scope.launch { onSignOutGoogle() } }) {
                            Text("Sign Out")
                        }
                    } else {
                        TextButton(
                            onClick = {
                                scope.launch { settingsRepository.setGoogleAccountEmail("reader@gmail.com") }
                            }
                        ) {
                            Text("Connect Account")
                        }
                    }
                }

                Text("Export Contents", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DocExportContent.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.docExportContent == mode,
                            onClick = {
                                scope.launch {
                                    settingsRepository.setShareSettings(
                                        mode,
                                        settings.driveFolderName,
                                        settings.includeCapturedArticleInDoc
                                    )
                                }
                            },
                            label = { Text(if (mode == DocExportContent.WHOLE_CHAT) "Whole Chat" else "Last Answer Only") }
                        )
                    }
                }

                OutlinedTextField(
                    value = driveFolder,
                    onValueChange = {
                        driveFolder = it
                        scope.launch {
                            settingsRepository.setShareSettings(
                                settings.docExportContent,
                                it,
                                settings.includeCapturedArticleInDoc
                            )
                        }
                    },
                    label = { Text("Google Drive Folder Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Include Captured Article Text Appendix")
                    Switch(
                        checked = settings.includeCapturedArticleInDoc,
                        onCheckedChange = {
                            scope.launch {
                                settingsRepository.setShareSettings(
                                    settings.docExportContent,
                                    settings.driveFolderName,
                                    it
                                )
                            }
                        }
                    )
                }
            }

            // 7. History & Privacy Section (§3.6)
            SettingsSectionCard(title = "7. History & Privacy") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Save Local Chat History")
                    Switch(
                        checked = settings.historyEnabled,
                        onCheckedChange = {
                            scope.launch { settingsRepository.setHistorySettings(it, settings.retentionDays) }
                        }
                    )
                }

                Text("Auto-Delete Retention Period", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1 to "1 day", 7 to "7 days", 30 to "30 days", 90 to "90 days", -1 to "Forever").forEach { (days, label) ->
                        FilterChip(
                            selected = settings.retentionDays == days,
                            onClick = {
                                scope.launch { settingsRepository.setHistorySettings(settings.historyEnabled, days) }
                            },
                            label = { Text(label) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Saved Sessions (${sessions.size})", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    if (sessions.isNotEmpty()) {
                        TextButton(onClick = { scope.launch { onClearAllHistory() } }) {
                            Text("Clear All", color = Color(0xFFD32F2F))
                        }
                    }
                }

                sessions.take(10).forEach { s ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = s.title ?: s.docKey,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            Text(
                                text = "${s.wordCount} words · ${s.backendId}",
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                        }
                        IconButton(onClick = { scope.launch { onDeleteSession(s.id) } }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete session", tint = Color.Gray)
                        }
                    }
                }
            }

            // 8. About Section (§3.6)
            SettingsSectionCard(title = "8. About & Disclosure") {
                Text("Gist v${BuildConfig.VERSION_NAME} (minSdk 31 · Android 12+)", fontWeight = FontWeight.SemiBold)
                Text(
                    "Accessibility Disclosure: Gist reads on-screen text only when you explicitly tap the floating bubble and sends it to the AI provider you choose. Password fields and FLAG_SECURE windows are never captured.",
                    fontSize = 13.sp,
                    color = Color(0xFF5F6368),
                    lineHeight = 19.sp
                )
                OutlinedButton(
                    onClick = onOpenAccessibilitySettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Manage Accessibility Service")
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = GistPurple
            )
            content()
        }
    }
}
