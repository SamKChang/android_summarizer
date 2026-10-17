# Gist — On-Screen AI Reading Assistant for Android

**Gist** puts an LLM right on top of whatever long-form article you are reading on Android (minSdk 31 / Android 12+):

1. **Capture** the article text from the current screen—*including content scrolled off-screen*—via a 3-stage hybrid pipeline (Accessibility tree walk → URL fetch + Readability4J/JSON-LD → Auto-scroll capture with rolling-window deduplication).
2. **Chat** about it in a draggable bottom half-sheet (`55% → 90%`) with streaming Markdown, citation chips, one-tap quick-action chips (`Key facts`, `ELI5`, `Counter-arguments`, `Timeline`), and provider-native web search (`🌐`).
3. **Share** the result directly as a formatted **Google Doc** in your `/Gist` Google Drive folder (`drive.file` scope).

---

## Demo

See [`gist_demo_video.mp4`](./gist_demo_video.mp4) for the end-to-end user flow:
1. Reading a long-form article in Chrome (`ft.com/markets/central-banks/fed-rate-cut`).
2. Tapping the floating purple Gist bubble (`TYPE_ACCESSIBILITY_OVERLAY`) to capture text immediately.
3. Streaming the `TL;DR Summary` with citation pills (`Source: Federal Reserve PR [¹]`).
4. Interactive follow-up Q&A with one-tap chips (`ELI5`).
5. Exporting the summary directly to Google Docs (`Google Doc created! Shared to Drive /Gist`).

---

## Architecture & Key Features

- **Pluggable LLM Backends (`com.gist.app.llm`)**:
  - **Cloud BYO Key**: Gemini (`streamGenerateContent` + `google_search` grounding), OpenAI (`POST /v1/responses` + `web_search`), Anthropic (`POST /v1/messages` + ephemeral prompt caching + `web_search`), and OpenAI-compatible (OpenRouter, local Ollama).
  - **On-Device Gemini Nano**: AICore / ML Kit GenAI integration with map-reduce summarization and model download progress in Settings.
  - **Subscription Hand-off**: Targeted `Intent.ACTION_SEND` to the official ChatGPT (`com.openai.chatgpt`), Claude (`com.anthropic.claude`), or Gemini (`com.google.android.apps.bard`) app.
- **Hybrid Capture Engine (`com.gist.app.capture`)**:
  - **Strategy 1**: Iterative DFS over `AccessibilityNodeInfo`, WebView subtree Readability scoring, browser URL bar extraction (`com.android.chrome:id/url_bar`, Firefox toolbar, generic top-15% regex), boilerplate removal, and completeness evaluation.
  - **Strategy 2**: Ktor HTTP GET + `jsoup` + `Readability4J` + JSON-LD `articleBody`.
  - **Strategy 3**: Consent-gated auto-scroll capture with rolling-window deduplication and scroll-back restoration.
  - **Intent Entry Points**: `ACTION_SEND` ("Share → Gist") and `ACTION_PROCESS_TEXT` ("Ask Gist").
- **Privacy & Security (`com.gist.app.data`, `com.gist.app.a11y`)**:
  - Tap-only capture (no background text event streaming).
  - API keys encrypted with **Tink AEAD** backed by the Android Keystore (`TinkSecureStore`) and excluded from cloud backup/device transfer.
  - Bubble automatically hides on `FLAG_SECURE` windows, focused password fields (`isPassword`), lockscreen, and denylisted apps (banking, authenticators, password managers, Settings).
  - Room chat session persistence (`docKey` resume) with daily `WorkManager` retention cleanup (`RetentionWorker`).

---

## Setup & Building

### Prerequisites
- JDK 17 or 21
- Android SDK Platform 35

### Build & Run Tests Locally
```bash
# Assemble debug APK
./gradlew assembleDebug

# Run unit, Robolectric, and Compose UI tests
./gradlew testDebugUnitTest
```

### Enabling Accessibility on Android 13+ (Restricted Settings)
When sideloading the APK on Android 13+:
1. Open **Settings → Apps → Gist**.
2. Tap the **⋮** menu in the top-right corner and select **Allow restricted settings**.
3. Open **Settings → Accessibility → Gist Screen Summarizer** and toggle it on.
