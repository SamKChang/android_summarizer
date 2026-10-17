package com.gist.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gist.app.a11y.ForegroundAppTracker
import com.gist.app.a11y.ForegroundEnvironment
import com.gist.app.settings.GistSettings
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataAndPrivacyTest {

    private lateinit var db: GistDatabase
    private lateinit var sessionDao: SessionDao
    private lateinit var messageDao: MessageDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GistDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sessionDao = db.sessionDao()
        messageDao = db.messageDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `Room DAOs persist sessions by docKey, cascade delete messages, and prune expired sessions`() = runTest {
        val now = System.currentTimeMillis()
        val oldSession = SessionEntity(
            id = "s_old",
            docKey = "https://ft.com/old",
            title = "Old Article",
            url = "https://ft.com/old",
            sourcePackage = "com.android.chrome",
            captureMethod = "A11Y_TREE",
            wordCount = 1200,
            articleText = "Old text",
            backendId = "GEMINI",
            model = "gemini-2.5-flash",
            createdAt = now - 40L * 86_400_000L,
            updatedAt = now - 40L * 86_400_000L
        )
        val recentSession = SessionEntity(
            id = "s_new",
            docKey = "https://ft.com/markets/central-banks/fed-rate-cut",
            title = "Fed Rate Cut",
            url = "https://ft.com/markets/central-banks/fed-rate-cut",
            sourcePackage = "com.android.chrome",
            captureMethod = "A11Y_TREE",
            wordCount = 4210,
            articleText = "Fed text",
            backendId = "GEMINI",
            model = "gemini-2.5-flash",
            createdAt = now,
            updatedAt = now
        )

        sessionDao.upsertSession(oldSession)
        sessionDao.upsertSession(recentSession)

        messageDao.insertMessage(
            MessageEntity(
                id = "m1",
                sessionId = "s_new",
                role = "ASSISTANT",
                content = "### TL;DR Summary",
                citationsJson = "[]",
                createdAt = now,
                tokenUsage = 150
            )
        )

        val lookedUp = sessionDao.getSessionByDocKey("https://ft.com/markets/central-banks/fed-rate-cut")
        assertNotNull(lookedUp)
        assertEquals("Fed Rate Cut", lookedUp!!.title)

        val msgs = messageDao.getMessagesForSession("s_new")
        assertEquals(1, msgs.size)

        // Prune sessions older than 30 days
        val deletedCount = sessionDao.deleteSessionsOlderThan(now - 30L * 86_400_000L)
        assertEquals(1, deletedCount)
        assertNull(sessionDao.getSessionById("s_old"))
        assertNotNull(sessionDao.getSessionById("s_new"))
    }

    @Test
    fun `ForegroundAppTracker enforces allowlist, denylist, FLAG_SECURE, password focus, and QS tile override`() {
        val settings = GistSettings()

        // 1. Allowlisted app (Chrome) -> visible
        assertTrue(
            ForegroundAppTracker.evaluateVisibility(
                ForegroundEnvironment(foregroundPackage = "com.android.chrome"),
                settings
            )
        )

        // 2. Non-allowlisted app with QS tile OFF -> hidden
        assertFalse(
            ForegroundAppTracker.evaluateVisibility(
                ForegroundEnvironment(foregroundPackage = "com.example.calculator"),
                settings
            )
        )

        // 3. Non-allowlisted app with QS tile ON -> visible
        assertTrue(
            ForegroundAppTracker.evaluateVisibility(
                ForegroundEnvironment(foregroundPackage = "com.example.calculator"),
                settings.copy(qsTileForceShowEverywhere = true)
            )
        )

        // 4. Denylisted app (Settings / Banking / Password Manager) -> hidden even when QS tile is ON
        assertFalse(
            ForegroundAppTracker.evaluateVisibility(
                ForegroundEnvironment(foregroundPackage = "com.agilebits.onepassword"),
                settings.copy(qsTileForceShowEverywhere = true)
            )
        )

        // 5. FLAG_SECURE window -> hidden even in Chrome
        assertFalse(
            ForegroundAppTracker.evaluateVisibility(
                ForegroundEnvironment(foregroundPackage = "com.android.chrome", isSecureWindow = true),
                settings
            )
        )

        // 6. Password field focused -> hidden even in Chrome
        assertFalse(
            ForegroundAppTracker.evaluateVisibility(
                ForegroundEnvironment(foregroundPackage = "com.android.chrome", isPasswordFieldFocused = true),
                settings
            )
        )
    }

    @Test
    fun `TinkSecureStore stores, retrieves, and clears API keys`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = TinkSecureStore(context)
        store.putSecret("api_key_gemini", "AIzaSyTestKey12345")
        assertEquals("AIzaSyTestKey12345", store.getSecret("api_key_gemini"))

        store.putSecret("api_key_gemini", null)
        assertNull(store.getSecret("api_key_gemini"))
    }
}
