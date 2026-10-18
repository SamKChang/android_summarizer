package com.gist.app.chat

import com.gist.app.capture.CapturedDocument
import com.gist.app.data.MessageDao
import com.gist.app.data.MessageEntity
import com.gist.app.data.SessionDao
import com.gist.app.data.SessionEntity
import com.gist.app.llm.Availability
import com.gist.app.llm.BackendId
import com.gist.app.llm.ChatEvent
import com.gist.app.llm.ChatMessage
import com.gist.app.llm.ChatRequest
import com.gist.app.llm.Citation
import com.gist.app.llm.ErrorKind
import com.gist.app.llm.LlmRouter
import com.gist.app.llm.MessageRole
import com.gist.app.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class UiChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val citations: List<Citation> = emptyList(),
    val isStreaming: Boolean = false,
    val tokenUsage: Int = 0,
    val errorKind: ErrorKind? = null,
    val retryCountdownSeconds: Int? = null,
)

data class ChatUiState(
    val sessionId: String? = null,
    val document: CapturedDocument? = null,
    val backendId: BackendId = BackendId.GEMINI,
    val model: String = "gemini-2.5-flash",
    val messages: List<UiChatMessage> = emptyList(),
    val isCapturing: Boolean = false,
    val isStreaming: Boolean = false,
    val webSearchEnabled: Boolean = false,
    val needsCloudConsentFor: BackendId? = null,
    val needsAutoScrollConsent: Boolean = false,
    val showKeepChatDialog: Boolean = false,
    val showCapturePreviewDialog: Boolean = false,
    val lastError: ErrorKind? = null,
    val snackbarMessage: String? = null,
)

/**
 * Manages active chat sessions, persistence to Room (`sessions` and `messages`),
 * auto-running the default prompt on new captures (§3.4), and streaming responses from [LlmRouter].
 */
@Singleton
class ChatSessionManager @Inject constructor(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val llmRouter: LlmRouter,
    private val settingsRepository: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var activeStreamJob: Job? = null
    private var pendingPromptAfterConsent: String? = null

    fun setCapturing(capturing: Boolean) {
        _uiState.update { it.copy(isCapturing = capturing, lastError = null) }
    }

    fun showSnackbar(message: String?) {
        _uiState.update { it.copy(snackbarMessage = message) }
    }

    fun setWebSearchEnabled(enabled: Boolean) {
        _uiState.update { it.copy(webSearchEnabled = enabled) }
    }

    fun setShowCapturePreview(show: Boolean) {
        _uiState.update { it.copy(showCapturePreviewDialog = show) }
    }

    fun setShowKeepChatDialog(show: Boolean) {
        _uiState.update { it.copy(showKeepChatDialog = show) }
    }

    fun setNeedsAutoScrollConsent(needs: Boolean) {
        _uiState.update { it.copy(needsAutoScrollConsent = needs) }
    }

    suspend fun switchBackend(backendId: BackendId, modelOverride: String? = null) {
        val settings = settingsRepository.settingsFlow.first()
        val model = modelOverride ?: settings.selectedModels[backendId] ?: "gemini-2.5-flash"
        settingsRepository.setDefaultBackend(backendId)
        _uiState.update { it.copy(backendId = backendId, model = model) }
    }

    /**
     * Implements the tap flow from §3.4:
     * `findOrCreate(docKey)`: if an existing session for the same `docKey` exists in Room,
     * restores the previous chat; otherwise creates a new session and auto-runs the default prompt.
     */
    suspend fun onDocumentCaptured(
        doc: CapturedDocument,
        forceNewSession: Boolean = false,
        keepMessagesOnRecapture: Boolean = false
    ) {
        val settings = settingsRepository.settingsFlow.first()
        val backendId = settings.defaultBackend
        val model = settings.selectedModels[backendId] ?: "gemini-2.5-flash"

        if (!forceNewSession && settings.historyEnabled) {
            val existing = sessionDao.getSessionByDocKey(doc.docKey)
            if (existing != null) {
                val storedMessages = messageDao.getMessagesForSession(existing.id)
                if (storedMessages.isNotEmpty()) {
                    val uiMessages = storedMessages.map { it.toUiMessage() }
                    _uiState.update {
                        it.copy(
                            sessionId = existing.id,
                            document = doc,
                            backendId = runCatching { BackendId.valueOf(existing.backendId) }.getOrDefault(backendId),
                            model = existing.model,
                            messages = uiMessages,
                            isCapturing = false,
                            webSearchEnabled = settings.defaultWebSearchEnabled,
                            showCapturePreviewDialog = settings.showCapturePreviewBeforeSending
                        )
                    }
                    return
                }
            }
        }

        val sessionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        if (settings.historyEnabled) {
            sessionDao.upsertSession(
                SessionEntity(
                    id = sessionId,
                    docKey = doc.docKey,
                    title = doc.title,
                    url = doc.url,
                    sourcePackage = doc.sourcePackage,
                    captureMethod = doc.method.name,
                    wordCount = doc.wordCount,
                    articleText = doc.text,
                    backendId = backendId.name,
                    model = model,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }

        val preservedMessages = if (keepMessagesOnRecapture) _uiState.value.messages else emptyList()
        _uiState.update {
            it.copy(
                sessionId = sessionId,
                document = doc,
                backendId = backendId,
                model = model,
                messages = preservedMessages,
                isCapturing = false,
                webSearchEnabled = settings.defaultWebSearchEnabled,
                showCapturePreviewDialog = settings.showCapturePreviewBeforeSending
            )
        }

        // In Hand-off mode, the compact hand-off card is shown instead of auto-streaming in-app (§3.5)
        if (backendId == BackendId.HANDOFF) {
            return
        }

        if (preservedMessages.isEmpty()) {
            sendPrompt(
                prompt = settings.defaultAutoRunPrompt,
                displayAsUserBubble = false
            )
        }
    }

    suspend fun loadSessionById(sessionId: String): Boolean {
        val entity = sessionDao.getSessionById(sessionId) ?: return false
        val messages = messageDao.getMessagesForSession(sessionId).map { it.toUiMessage() }
        val doc = CapturedDocument(
            sourcePackage = entity.sourcePackage,
            title = entity.title,
            url = entity.url,
            text = entity.articleText,
            wordCount = entity.wordCount,
            method = runCatching { com.gist.app.capture.CaptureMethod.valueOf(entity.captureMethod) }
                .getOrDefault(com.gist.app.capture.CaptureMethod.A11Y_TREE),
            completeness = com.gist.app.capture.Completeness.LIKELY_COMPLETE
        )
        _uiState.update {
            it.copy(
                sessionId = entity.id,
                document = doc,
                backendId = runCatching { BackendId.valueOf(entity.backendId) }.getOrDefault(BackendId.GEMINI),
                model = entity.model,
                messages = messages,
                isCapturing = false,
                isStreaming = false
            )
        }
        return true
    }

    fun confirmCloudConsentAndProceed() {
        val target = _uiState.value.needsCloudConsentFor ?: return
        val pending = pendingPromptAfterConsent
        pendingPromptAfterConsent = null
        _uiState.update { it.copy(needsCloudConsentFor = null) }
        scope.launch {
            settingsRepository.recordCloudProviderConsent(target)
            if (pending != null) {
                sendPrompt(pending, displayAsUserBubble = pending != settingsRepository.settingsFlow.first().defaultAutoRunPrompt)
            }
        }
    }

    fun dismissCloudConsent() {
        pendingPromptAfterConsent = null
        _uiState.update { it.copy(needsCloudConsentFor = null) }
    }

    fun stopStreaming() {
        activeStreamJob?.cancel()
        activeStreamJob = null
        _uiState.update { state ->
            state.copy(
                isStreaming = false,
                messages = state.messages.map { it.copy(isStreaming = false) }
            )
        }
    }

    fun startNewChat() {
        stopStreaming()
        val doc = _uiState.value.document ?: return
        scope.launch {
            onDocumentCaptured(doc, forceNewSession = true, keepMessagesOnRecapture = false)
        }
    }

    fun regenerateLastAnswer() {
        val state = _uiState.value
        val lastUserMsg = state.messages.lastOrNull { it.role == MessageRole.USER }
        val trimmed = if (state.messages.lastOrNull()?.role == MessageRole.ASSISTANT) {
            state.messages.dropLast(1)
        } else {
            state.messages
        }
        _uiState.update { it.copy(messages = trimmed) }
        scope.launch {
            val settings = settingsRepository.settingsFlow.first()
            val prompt = lastUserMsg?.content ?: settings.defaultAutoRunPrompt
            if (lastUserMsg != null) {
                _uiState.update { s -> s.copy(messages = s.messages.dropLast(1)) }
            }
            sendPrompt(prompt, displayAsUserBubble = lastUserMsg != null)
        }
    }

    fun sendPrompt(prompt: String, displayAsUserBubble: Boolean = true) {
        val doc = _uiState.value.document ?: return
        stopStreaming()

        activeStreamJob = scope.launch {
            val settings = settingsRepository.settingsFlow.first()
            val requestedBackendId = _uiState.value.backendId
            val requestedBackend = llmRouter.backendFor(requestedBackendId)

            // Resolve effective backend: if a cloud backend is selected but has no API key configured,
            // fall back smoothly to the built-in Demo/on-device backend so one-tap summaries work immediately
            // in local testing, unless the user explicitly configured an invalid key.
            val availability = requestedBackend.isAvailable()
            val effectiveBackend = if (availability is Availability.Unavailable && availability.kind == ErrorKind.AUTH) {
                llmRouter.backendFor(BackendId.DEMO)
            } else {
                requestedBackend
            }

            // Check one-time cloud consent per provider (§8.2) when sending to a real cloud backend
            if (effectiveBackend.id.isCloud && effectiveBackend.id.name !in settings.consentedCloudProviders) {
                pendingPromptAfterConsent = prompt
                _uiState.update { it.copy(needsCloudConsentFor = effectiveBackend.id) }
                return@launch
            }

            val sessionId = _uiState.value.sessionId ?: UUID.randomUUID().toString()
            val priorMessages = _uiState.value.messages
                .filter { it.errorKind == null && it.content.isNotBlank() }
                .map { ChatMessage(it.role, it.content) }

            if (displayAsUserBubble) {
                val userMsgId = UUID.randomUUID().toString()
                val userUiMsg = UiChatMessage(
                    id = userMsgId,
                    role = MessageRole.USER,
                    content = prompt
                )
                _uiState.update { it.copy(messages = it.messages + userUiMsg) }
                if (settings.historyEnabled) {
                    messageDao.insertMessage(
                        MessageEntity(
                            id = userMsgId,
                            sessionId = sessionId,
                            role = MessageRole.USER.name,
                            content = prompt,
                            citationsJson = "[]",
                            createdAt = System.currentTimeMillis(),
                            tokenUsage = ContextBudgeter.estimateTokens(prompt)
                        )
                    )
                }
            }

            val assistantMsgId = UUID.randomUUID().toString()
            val placeholderMsg = UiChatMessage(
                id = assistantMsgId,
                role = MessageRole.ASSISTANT,
                content = "",
                isStreaming = true
            )
            _uiState.update {
                it.copy(
                    isStreaming = true,
                    lastError = null,
                    messages = it.messages + placeholderMsg
                )
            }

            // Context budgeting + map-reduce if needed (§6.4)
            val modelId = _uiState.value.model
            val budgetedHistory = ContextBudgeter.trimHistoryWithRollingSummary(priorMessages)
            val articleTextForPrompt = if (ContextBudgeter.needsMapReduce(
                    articleText = doc.text,
                    historyMessages = budgetedHistory,
                    maxContextTokens = effectiveBackend.capabilities.maxContextTokens,
                    isOnDeviceNano = effectiveBackend.capabilities.onDevice && effectiveBackend.id == BackendId.GEMINI_NANO
                )
            ) {
                ContextBudgeter.mapReduceArticle(
                    articleText = doc.text,
                    backend = effectiveBackend,
                    model = modelId
                )
            } else {
                doc.text
            }

            val systemPrompt = PromptBuilder.buildSystemPrompt(
                baseSystemPrompt = settings.systemPrompt,
                responseLanguage = settings.responseLanguage,
                summaryLength = settings.summaryLength
            )
            val requestMessages = PromptBuilder.buildMessages(
                doc = doc,
                articleTextToUse = articleTextForPrompt,
                priorTurns = budgetedHistory,
                newUserPrompt = prompt,
                initialAutoPrompt = settings.defaultAutoRunPrompt
            )

            val chatRequest = ChatRequest(
                system = systemPrompt,
                messages = requestMessages,
                model = modelId,
                webSearch = _uiState.value.webSearchEnabled && effectiveBackend.capabilities.webSearch,
                temperature = settings.temperature,
                maxOutputTokens = settings.maxOutputTokens
            )

            executeStreamWithRetry(
                backend = effectiveBackend,
                request = chatRequest,
                sessionId = sessionId,
                assistantMsgId = assistantMsgId,
                historyEnabled = settings.historyEnabled,
                doc = doc
            )
        }
    }

    private suspend fun executeStreamWithRetry(
        backend: com.gist.app.llm.LlmBackend,
        request: ChatRequest,
        sessionId: String,
        assistantMsgId: String,
        historyEnabled: Boolean,
        doc: CapturedDocument,
        attempt: Int = 1
    ) {
        val accumulatedText = StringBuilder()
        val citations = mutableListOf<Citation>()
        var totalTokens = 0
        var shouldRetryRateLimit: Int? = null
        var shouldRetryMapReduce = false

        backend.stream(request).collect { event ->
            when (event) {
                is ChatEvent.Delta -> {
                    accumulatedText.append(event.text)
                    val currentText = accumulatedText.toString()
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map { msg ->
                                if (msg.id == assistantMsgId) msg.copy(content = currentText) else msg
                            }
                        )
                    }
                }
                is ChatEvent.Citations -> {
                    citations.clear()
                    citations.addAll(event.items)
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map { msg ->
                                if (msg.id == assistantMsgId) msg.copy(citations = citations.toList()) else msg
                            }
                        )
                    }
                }
                is ChatEvent.Usage -> {
                    totalTokens = event.inputTokens + event.outputTokens
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map { msg ->
                                if (msg.id == assistantMsgId) msg.copy(tokenUsage = totalTokens) else msg
                            }
                        )
                    }
                }
                is ChatEvent.Error -> {
                    if (event.kind == ErrorKind.RATE_LIMIT && attempt <= 2) {
                        shouldRetryRateLimit = event.retryAfterSeconds ?: 3
                    } else if (event.kind == ErrorKind.CONTEXT_TOO_LONG && attempt == 1) {
                        shouldRetryMapReduce = true
                    } else {
                        _uiState.update { state ->
                            state.copy(
                                isStreaming = false,
                                lastError = event.kind,
                                messages = state.messages.map { msg ->
                                    if (msg.id == assistantMsgId) {
                                        msg.copy(
                                            content = event.message,
                                            isStreaming = false,
                                            errorKind = event.kind
                                        )
                                    } else msg
                                }
                            )
                        }
                    }
                }
                is ChatEvent.Done -> {
                    _uiState.update { state ->
                        state.copy(
                            isStreaming = false,
                            messages = state.messages.map { msg ->
                                if (msg.id == assistantMsgId) msg.copy(isStreaming = false) else msg
                            }
                        )
                    }
                    if (historyEnabled && accumulatedText.isNotEmpty()) {
                        messageDao.insertMessage(
                            MessageEntity(
                                id = assistantMsgId,
                                sessionId = sessionId,
                                role = MessageRole.ASSISTANT.name,
                                content = accumulatedText.toString(),
                                citationsJson = json.encodeToString(citations),
                                createdAt = System.currentTimeMillis(),
                                tokenUsage = totalTokens
                            )
                        )
                    }
                }
            }
        }

        // Handle rate-limit countdown & retry (§6.5)
        if (shouldRetryRateLimit != null) {
            for (sec in shouldRetryRateLimit!! downTo 1) {
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.map { msg ->
                            if (msg.id == assistantMsgId) {
                                msg.copy(
                                    content = "Rate limit reached. Retrying in ${sec}s…",
                                    retryCountdownSeconds = sec
                                )
                            } else msg
                        }
                    )
                }
                delay(1_000L)
            }
            executeStreamWithRetry(backend, request, sessionId, assistantMsgId, historyEnabled, doc, attempt + 1)
        } else if (shouldRetryMapReduce) {
            val reduced = ContextBudgeter.mapReduceArticle(doc.text, backend, request.model)
            val newMessages = PromptBuilder.buildMessages(
                doc = doc,
                articleTextToUse = reduced,
                priorTurns = emptyList(),
                newUserPrompt = request.messages.last().content
            )
            executeStreamWithRetry(
                backend = backend,
                request = request.copy(messages = newMessages),
                sessionId = sessionId,
                assistantMsgId = assistantMsgId,
                historyEnabled = historyEnabled,
                doc = doc,
                attempt = attempt + 1
            )
        }
    }

    private fun MessageEntity.toUiMessage(): UiChatMessage {
        val parsedCitations = runCatching {
            json.decodeFromString<List<Citation>>(citationsJson)
        }.getOrDefault(emptyList())
        return UiChatMessage(
            id = id,
            role = runCatching { MessageRole.valueOf(role) }.getOrDefault(MessageRole.ASSISTANT),
            content = content,
            citations = parsedCitations,
            isStreaming = false,
            tokenUsage = tokenUsage
        )
    }
}
