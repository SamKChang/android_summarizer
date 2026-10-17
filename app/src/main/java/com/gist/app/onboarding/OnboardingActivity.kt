package com.gist.app.onboarding

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.gist.app.a11y.ForegroundAppTracker
import com.gist.app.a11y.GistAccessibilityService
import com.gist.app.data.SecureStore
import com.gist.app.llm.BackendId
import com.gist.app.llm.anthropic.AnthropicBackend
import com.gist.app.llm.gemini.GeminiBackend
import com.gist.app.llm.openai.OpenAIBackend
import com.gist.app.overlay.OverlayManager
import com.gist.app.overlay.ui.GistPurple
import com.gist.app.overlay.ui.GistPurpleLight
import com.gist.app.settings.GistSettings
import com.gist.app.settings.SettingsActivity
import com.gist.app.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * First-run Onboarding Activity (§3.1) + Built-in Demo Article Reader:
 * 1. Welcome & Prominent Disclosure ("Gist reads on-screen text only when you tap the bubble and sends it to the AI provider you choose").
 * 2. Enable Accessibility Service (`Settings.ACTION_ACCESSIBILITY_SETTINGS`) + Android 13+ "Allow restricted settings" deep-link to App Info.
 * 3. Choose a Backend (On-device Gemini Nano / Paste an API key / Hand-off to ChatGPT, Claude, Gemini app).
 * 4. Optional Google Sign-In for Share → Google Doc.
 * 5. Done + "Try it" button which opens the sample long-form article ("Federal Reserve Enacts Aggressive 50 bps Interest Rate Cut as Inflation Cools") in Chrome or the interactive reader view.
 */
@AndroidEntryPoint
class OnboardingActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var secureStore: SecureStore
    @Inject lateinit var overlayManager: OverlayManager
    @Inject lateinit var foregroundAppTracker: ForegroundAppTracker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val startInReaderMode = intent?.getBooleanExtra(EXTRA_OPEN_SAMPLE_ARTICLE, false) == true

        setContent {
            MaterialTheme {
                val settings by settingsRepository.settingsFlow.collectAsState(initial = GistSettings())
                val a11yInstance by GistAccessibilityService.instance.collectAsState()
                var showSampleReader by remember { mutableStateOf(startInReaderMode) }

                if (showSampleReader) {
                    SampleArticleReaderScreen(
                        onBack = { showSampleReader = false },
                        onOpenSettings = {
                            startActivity(Intent(this, SettingsActivity::class.java))
                        },
                        onTriggerBubbleTap = {
                            overlayManager.onBubbleTapped()
                        }
                    )
                } else {
                    OnboardingFlowScreen(
                        settings = settings,
                        isAccessibilityEnabled = a11yInstance != null,
                        onOpenAccessibilitySettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onOpenAppInfoRestrictedSettings = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                            }
                            startActivity(intent)
                        },
                        onSaveBackendAndKey = { backend, apiKey ->
                            when (backend) {
                                BackendId.GEMINI -> if (apiKey.isNotBlank()) secureStore.putSecret(GeminiBackend.SECRET_KEY_GEMINI, apiKey)
                                BackendId.OPENAI -> if (apiKey.isNotBlank()) secureStore.putSecret(OpenAIBackend.SECRET_KEY_OPENAI, apiKey)
                                BackendId.ANTHROPIC -> if (apiKey.isNotBlank()) secureStore.putSecret(AnthropicBackend.SECRET_KEY_ANTHROPIC, apiKey)
                                else -> {}
                            }
                        },
                        onSelectDefaultBackend = { backend ->
                            settingsRepository.setDefaultBackend(backend)
                        },
                        onSignInGoogle = { email ->
                            settingsRepository.setGoogleAccountEmail(email)
                        },
                        onCompleteAndTrySample = { openInChrome ->
                            settingsRepository.setOnboardingCompleted(true)
                            // Ensure our reader view or Chrome shows the bubble immediately
                            foregroundAppTracker.onForegroundPackageChanged("com.android.chrome")
                            if (openInChrome) {
                                try {
                                    val chromeIntent = Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://en.wikipedia.org/wiki/Federal_Reserve")
                                    ).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    startActivity(chromeIntent)
                                } catch (_: Exception) {
                                    showSampleReader = true
                                }
                            } else {
                                showSampleReader = true
                            }
                        },
                        onOpenFullSettings = {
                            startActivity(Intent(this, SettingsActivity::class.java))
                        }
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_SAMPLE_ARTICLE = "extra_open_sample_article"
    }
}

@Composable
fun OnboardingFlowScreen(
    settings: GistSettings,
    isAccessibilityEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppInfoRestrictedSettings: () -> Unit,
    onSaveBackendAndKey: (BackendId, String) -> Unit,
    onSelectDefaultBackend: suspend (BackendId) -> Unit,
    onSignInGoogle: suspend (String) -> Unit,
    onCompleteAndTrySample: suspend (openInChrome: Boolean) -> Unit,
    onOpenFullSettings: () -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    var selectedBackend by remember(settings.defaultBackend) { mutableStateOf(settings.defaultBackend) }
    var apiKeyInput by remember { mutableStateOf("") }
    var googleSignedInEmail by remember(settings.googleAccountEmail) { mutableStateOf(settings.googleAccountEmail) }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = if (step == 0) Color(0xFF1E194D) else Color(0xFFF8F9FC)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (step == 0) {
                // Step 1: Welcome + Prominent Disclosure (matching 00:06–00:07 splash in gist_demo_video.mp4 + §3.1)
                Spacer(modifier = Modifier.height(36.dp))
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(GistPurple),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(42.dp)) {
                        val w = size.width
                        val h = size.height
                        val cx = w / 2f
                        val cy = h / 2f
                        val starPath = Path().apply {
                            moveTo(cx, 0f)
                            cubicTo(cx + w * 0.06f, cy - h * 0.06f, cx + w * 0.06f, cy - h * 0.06f, w, cy)
                            cubicTo(cx + w * 0.06f, cy + h * 0.06f, cx + w * 0.06f, cy + h * 0.06f, cx, h)
                            cubicTo(cx - w * 0.06f, cy + h * 0.06f, cx - w * 0.06f, cy + h * 0.06f, 0f, cy)
                            cubicTo(cx - w * 0.06f, cy - h * 0.06f, cx - w * 0.06f, cy - h * 0.06f, cx, 0f)
                            close()
                        }
                        drawPath(starPath, Color.White)
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Gist",
                    color = Color.White,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "On-Screen AI Reading Assistant",
                    color = Color(0xFFD0C9FF),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Capture. Summarize. Chat. Share.",
                    color = Color(0xFFB5AEED),
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(28.dp))

                // Prominent Accessibility & Privacy Disclosure (§3.1, D3)
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2466)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("prominent_disclosure_card")
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Lock,
                                contentDescription = null,
                                tint = Color(0xFFB5AEED)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Prominent Privacy Disclosure",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Gist reads on-screen text only when you tap the bubble and sends it to the AI provider you choose. No background screen monitoring, no app backend server, and no telemetry.",
                            color = Color(0xFFE2DFFF),
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                Button(
                    onClick = { step = 1 },
                    colors = ButtonDefaults.buttonColors(containerColor = GistPurple),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("onboarding_get_started_button")
                ) {
                    Text("Accept & Get Started", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = {
                        scope.launch { onCompleteAndTrySample(false) }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("try_sample_article_button")
                ) {
                    Text("Try Sample Article Immediately")
                }

                Spacer(modifier = Modifier.height(12.dp))
                TextButton(
                    onClick = onOpenFullSettings,
                    modifier = Modifier.testTag("onboarding_open_settings_button")
                ) {
                    Text("Open Settings", color = Color(0xFFB5AEED))
                }
            } else if (step == 1) {
                // Step 2: Enable Accessibility Service + Android 13+ Restricted Settings Guide (§3.1)
                Text(
                    text = "Step 1 of 3 · Enable Accessibility Service",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1A1C1E)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Gist uses an Accessibility Service overlay to display the floating bubble over reading apps and capture article text when you tap it.",
                    fontSize = 14.sp,
                    color = Color(0xFF44474E),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = if (isAccessibilityEnabled) "✓ Gist Accessibility Service is Enabled" else "Accessibility Service Status: Not Enabled",
                            fontWeight = FontWeight.Bold,
                            color = if (isAccessibilityEnabled) Color(0xFF2E7D32) else Color(0xFFD32F2F)
                        )
                        Button(
                            onClick = onOpenAccessibilitySettings,
                            colors = ButtonDefaults.buttonColors(containerColor = GistPurple),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("open_accessibility_settings_button")
                        ) {
                            Text("Open Accessibility Settings")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Android 13+ Sideloaded Apps: \"Allow Restricted Settings\"",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color(0xFF5D4037)
                        )
                        Text(
                            text = "If Android greys out the switch with \"Restricted setting\":\n1. Tap \"Open App Info\" below.\n2. Tap the ⋮ menu in the top-right corner.\n3. Select \"Allow restricted settings\", then return to Accessibility Settings.",
                            fontSize = 13.sp,
                            color = Color(0xFF5D4037),
                            lineHeight = 19.sp
                        )
                        OutlinedButton(
                            onClick = onOpenAppInfoRestrictedSettings,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Open App Info (⋮ → Allow restricted settings)")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = { step = 0 }) { Text("Back") }
                    Button(
                        onClick = { step = 2 },
                        colors = ButtonDefaults.buttonColors(containerColor = GistPurple),
                        modifier = Modifier.testTag("step1_next_button")
                    ) {
                        Text("Next: Choose AI Backend")
                    }
                }
            } else if (step == 2) {
                // Step 3: Choose a Backend (§3.1)
                Text(
                    text = "Step 2 of 3 · Choose Your AI Backend",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1A1C1E)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Bring your own API key, run on-device with Gemini Nano, or hand off to your installed ChatGPT/Claude/Gemini app.",
                    fontSize = 14.sp,
                    color = Color(0xFF44474E),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))

                val options = listOf(
                    BackendId.GEMINI to "Gemini API (Cloud · Fast 1M context)",
                    BackendId.GEMINI_NANO to "On-device Gemini Nano (Private · Offline)",
                    BackendId.OPENAI to "OpenAI Responses API (GPT-4o / o3-mini)",
                    BackendId.ANTHROPIC to "Anthropic Claude (Prompt Caching)",
                    BackendId.HANDOFF to "Use my ChatGPT / Claude / Gemini App (Hand-off)"
                )

                options.forEach { (backend, label) ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedBackend == backend) GistPurpleLight else Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { selectedBackend = backend }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedBackend == backend,
                                onClick = { selectedBackend = backend }
                            )
                            Text(text = label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                if (selectedBackend.isCloud) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = { Text("${selectedBackend.displayName} API Key (optional for Demo)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = { step = 1 }) { Text("Back") }
                    Button(
                        onClick = {
                            scope.launch {
                                onSaveBackendAndKey(selectedBackend, apiKeyInput)
                                onSelectDefaultBackend(selectedBackend)
                                step = 3
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GistPurple),
                        modifier = Modifier.testTag("step2_next_button")
                    ) {
                        Text("Next: Google Docs Share")
                    }
                }
            } else {
                // Step 4 & 5: Optional Google Sign-In + Done / "Try it" (§3.1)
                Text(
                    text = "Step 3 of 3 · Export to Google Docs (Optional)",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1A1C1E)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Connect your Google account (`drive.file` scope only) to export summaries directly as formatted Google Docs in your /Gist Drive folder.",
                    fontSize = 14.sp,
                    color = Color(0xFF44474E),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (googleSignedInEmail != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Connected: $googleSignedInEmail", fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val demoAccount = "reader@gmail.com"
                                        googleSignedInEmail = demoAccount
                                        onSignInGoogle(demoAccount)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Sign in with Google (drive.file)")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                Button(
                    onClick = {
                        scope.launch { onCompleteAndTrySample(false) }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GistPurple),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("finish_try_it_button")
                ) {
                    Text("Done · Try it on Sample Article", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = {
                        scope.launch { onCompleteAndTrySample(true) }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Sample Article in Chrome")
                }
            }
        }
    }
}

/**
 * Interactive Chrome-styled sample article reader matching `gist_demo_video.mp4` (`ft.com/markets/central-banks/fed-rate-cut`)
 * so that local verification and E2E tests can test the exact article, Accessibility tree extraction,
 * floating bubble tap, streaming summary, quick chips (`ELI5`), and Google Docs export.
 */
@Composable
fun SampleArticleReaderScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onTriggerBubbleTap: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .testTag("sample_article_reader")
    ) {
        // Simulated Chrome URL bar (`com.android.chrome:id/url_bar` semantics) matching 00:00 of gist_demo_video.mp4
        Surface(
            color = Color(0xFFF1F3F4),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = "HTTPS Secure",
                        tint = Color(0xFF5F6368),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ft.com/markets/central-banks/fed-rate-cut",
                        fontSize = 14.sp,
                        color = Color(0xFF3C4043)
                    )
                }
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Settings",
                        tint = Color(0xFF5F6368),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Scrollable long-form article text matching 00:00 of gist_demo_video.mp4
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Federal Reserve Enacts Aggressive 50 bps Interest Rate Cut as Inflation Cools",
                fontSize = 26.sp,
                lineHeight = 33.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF111315)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "By Economics Desk · Updated 1h ago · 8 min read",
                fontSize = 13.sp,
                color = Color(0xFF6C7078)
            )
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "WASHINGTON — The Federal Reserve voted Wednesday to lower benchmark borrowing costs by a half percentage point, pivoting boldly toward economic preservation as inflation nears its official 2 percent target and the US labor market shows subtle signs of deceleration.",
                fontSize = 16.sp,
                lineHeight = 25.sp,
                color = Color(0xFF202124)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "The move brings the federal funds target rate to a range between 4.75% and 5%, concluding a two-year campaign of monetary tightening that propelled interest rates to two-decade highs.",
                fontSize = 16.sp,
                lineHeight = 25.sp,
                color = Color(0xFF202124)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Fed Chair Jerome Powell emphasized that the central bank is acting proactively to safeguard steady employment while price pressures continue their downward trajectory across goods and housing categories.",
                fontSize = 16.sp,
                lineHeight = 25.sp,
                color = Color(0xFF202124)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Major stock indexes advanced sharply following the announcement, led by technology and consumer discretionary shares, while Treasury yields adjusted lower to reflect expectations of additional easing over the coming quarters.",
                fontSize = 16.sp,
                lineHeight = 25.sp,
                color = Color(0xFF202124)
            )
            Spacer(modifier = Modifier.height(24.dp))
            TextButton(onClick = onBack) {
                Text("← Back to Onboarding")
            }
        }
    }
}
