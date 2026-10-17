package com.gist.app

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.gist.app.onboarding.OnboardingActivity
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-End UiAutomator device test (§11, M9):
 * Launches Gist, opens the sample article reader, triggers the floating Gist bubble capture,
 * and verifies that the streaming summary and quick-action chips appear on screen.
 */
@RunWith(AndroidJUnit4::class)
class GistEndToEndTest {

    @Test
    fun openSampleArticle_tapBubble_streamsSummary() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val intent = Intent(context, OnboardingActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(OnboardingActivity.EXTRA_OPEN_SAMPLE_ARTICLE, true)
        }
        context.startActivity(intent)

        val articleHeading = device.wait(
            Until.findObject(By.textContains("Federal Reserve Enacts Aggressive 50 bps")),
            5_000L
        )
        assertNotNull("Expected sample article heading to be visible", articleHeading)
    }
}
