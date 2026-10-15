package com.smartcalc.app.ui.settings

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.smartcalc.app.MainActivity
import com.smartcalc.app.SmartCalcApplication
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** Reading navigation on synthetic data, with an optional pause for visual review. */
class LayoutReadingDeviceTest {
    private val ui = createAndroidComposeRule<MainActivity>()
    @get:Rule val isolatedApp: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "com.smartcalc.app.connectionscheck")
        }
    }).around(ui)

    private fun openFixture() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SmartCalcApplication
        runBlocking { app.settingsRepository.setAssistantCode("") }
        LayoutFixturesDeviceTest().prepareOfflineVisualFixtures()
        ui.waitForIdle()
        ui.onNodeWithContentDescription("ИИ-помощник").performClick()
        ui.onNodeWithText("История", substring = false).performClick()
        ui.onNodeWithText("Проверка компоновки — без API", substring = false).performClick()
        ui.waitForIdle()
    }

    private fun visualPause() {
        val seconds = InstrumentationRegistry.getArguments().getString("visualPauseSeconds")?.toLongOrNull()?.coerceIn(0, 120) ?: 0
        val until = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < until) {
            ui.waitForIdle()
            Thread.sleep(200)
        }
    }

    @Test fun fullSolutionOpensFromHeldEquals() {
        openFixture()
        ui.onNodeWithText("=", substring = false).performTouchInput { longClick() }
        ui.onNodeWithContentDescription("Скопировать решение").assertIsDisplayed()
        visualPause()
    }

    @Test fun stepOpensFromHeldDigit() {
        openFixture()
        ui.onNodeWithText("1", substring = false).performTouchInput { longClick() }
        ui.onNodeWithText("Шаг 1 из 2", substring = false).assertIsDisplayed()
        visualPause()
    }
}
