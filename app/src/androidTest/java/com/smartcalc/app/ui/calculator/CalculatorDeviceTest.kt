package com.smartcalc.app.ui.calculator

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.smartcalc.app.MainActivity
import com.smartcalc.app.SmartCalcApplication
import com.smartcalc.app.ui.settings.LayoutFixturesDeviceTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** Opt-in isolated APK only: the calculator's mode and drafts survive Activity recreation, no HTTP. */
class CalculatorDeviceTest {
    private val ui = createAndroidComposeRule<MainActivity>()
    @get:Rule val isolatedApp: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName == "com.smartcalc.app.connectionscheck") {
                "Use -PconnectionsDeviceCheck; refuse to launch the user's app"
            }
            runBlocking { (context.applicationContext as SmartCalcApplication).settingsRepository.setAssistantCode("") }
        }
    }).around(ui)

    @Test fun keypadExpressionSurvivesRotation() {
        clearCalculator()
        listOf("1", "2", "+", "3", "0").forEach(::key)
        ui.onNodeWithText("12+30", substring = false).assertIsDisplayed()
        ui.onNodeWithText("= 42", substring = false).assertIsDisplayed()
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        ui.onNodeWithText("12+30", substring = false).assertExists()
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        key("=")
        ui.onNodeWithText("42", substring = false).assertIsDisplayed()
        clearCalculator()
    }

    @Test fun taskDraftSurvivesRotationAndReopens() {
        clearCalculator()
        openEditor()
        editor().performTextReplacement("Найдите площадь круга радиуса 3")
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        assertEditor("Найдите площадь круга радиуса 3")
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        assertEditor("Найдите площадь круга радиуса 3")
        back()
        editor().assertDoesNotExist()
        openEditor()
        assertEditor("Найдите площадь круга радиуса 3")
        // Пустой черновик не оставляет задачу следующему прогону.
        editor().performTextReplacement("")
        back()
    }

    @Test fun followUpSurvivesRotationAndReturnsToAnswer() {
        LayoutFixturesDeviceTest().prepareOfflineVisualFixtures()
        ui.waitForIdle()
        ui.onNodeWithContentDescription("ИИ-помощник").performClick()
        ui.onNodeWithText("История", substring = false).performClick()
        ui.onNodeWithText("Проверка компоновки — без API", substring = false).performClick()
        ui.waitForIdle()
        openEditor()
        ui.onNodeWithText("Дополнение к ответу", substring = true).assertExists()
        editor().performTextReplacement("а если 12 + 9?")
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        ui.onNodeWithText("Дополнение к ответу", substring = true).assertExists()
        assertEditor("а если 12 + 9?")
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        back()
        editor().assertDoesNotExist()
        // Закрытое дополнение возвращает на дисплей прежний ответ, а черновик ждёт следующего нажатия.
        ui.onNodeWithText("Проверка компоновки — без API", substring = false).assertIsDisplayed()
        openEditor()
        assertEditor("а если 12 + 9?")
        editor().performTextReplacement("")
        back()
        clearCalculator()
    }

    private fun key(label: String) {
        ui.onNodeWithText(label, substring = false).performClick()
        ui.waitForIdle()
    }

    /** Первое «C» убирает помощника с дисплея, второе очищает расчёт. */
    private fun clearCalculator() {
        key("C")
        key("C")
    }

    /** ✦ → «Написать»: то же, что нажатие на дисплей. */
    private fun openEditor() {
        ui.onNodeWithContentDescription("ИИ-помощник").performClick()
        ui.onNodeWithText("Написать", substring = false).performClick()
        ui.waitForIdle()
    }

    private fun editor() = ui.onNode(hasSetTextAction())

    private fun assertEditor(expected: String) {
        assertEquals(expected, editor().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    private fun back() {
        ui.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        ui.waitForIdle()
    }

    private fun rotate(requested: Int, expected: Int) {
        lateinit var before: MainActivity
        ui.activityRule.scenario.onActivity { before = it; it.requestedOrientation = requested }
        ui.waitUntil(15000) {
            var recreated = false
            ui.activityRule.scenario.onActivity {
                recreated = it !== before && it.resources.configuration.orientation == expected
            }
            recreated
        }
        ui.waitForIdle()
    }
}
