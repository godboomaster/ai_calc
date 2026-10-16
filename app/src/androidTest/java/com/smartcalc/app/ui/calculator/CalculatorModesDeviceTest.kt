package com.smartcalc.app.ui.calculator

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.smartcalc.app.MainActivity
import com.smartcalc.app.SmartCalcApplication
import com.smartcalc.app.SolutionViewModel
import com.smartcalc.app.data.history.Solution
import com.smartcalc.app.data.session.SessionState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** Real AppRoot and Compose adapter, synthetic data only, no requests and no access to the user's APK. */
class CalculatorModesDeviceTest {
    private val ui = createAndroidComposeRule<MainActivity>()
    @get:Rule val isolatedApp: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName == "com.smartcalc.app.importcheck") {
                "Use -PisolatedDeviceCheck; refuse to launch the user's app"
            }
            val app = context.applicationContext as SmartCalcApplication
            runBlocking { app.settingsRepository.setAssistantCode("") }
            app.sessionStore.update { SessionState() }
        }
    }).around(ui)

    private val vm get() = ViewModelProvider(ui.activity)[SolutionViewModel::class.java]
    private val app get() = ui.activity.application as SmartCalcApplication

    @Before fun startOnCalculator() {
        ui.runOnIdle { vm.hideFromDisplay() }
        ui.onNodeWithText("C", substring = false).performClick()
    }

    @Test fun arithmeticEditorSurvivesActivityRecreationAndCalculatesLocally() {
        ui.runOnIdle { vm.editRequest = SolutionViewModel.EditRequest("12+3", false) }
        ui.onNode(hasSetTextAction()).assertTextEquals("12+3")
        ui.activityRule.scenario.recreate()
        ui.waitForIdle()
        ui.onNode(hasSetTextAction()).assertTextEquals("12+3").performImeAction()
        ui.onNodeWithText("15", substring = false).assertIsDisplayed()
        ui.runOnIdle {
            assertEquals("15", vm.session.value.calculator.expression)
            assertFalse(vm.solving.value)
        }
    }

    @Test fun calculatorDisplayTapDoesNotOpenEditorButAssistantWriteDoes() {
        ui.onAllNodesWithText("0", substring = false).onFirst().performTouchInput { click() }
        ui.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        ui.onNodeWithContentDescription("ИИ-помощник").performClick()
        ui.onNodeWithText("Написать", substring = false).performClick()
        ui.onNode(hasSetTextAction()).assertIsDisplayed()
    }

    @Test fun hiddenAnswerBlocksDisplayInputUntilRestored() {
        val solution = runBlocking {
            app.historyStore.add(Solution(id = "calculator-input-fixture", problem = "Synthetic problem", answer = "314159"))
        }
        ui.runOnIdle { vm.open(solution) }
        ui.onNodeWithText("314159", substring = false).assertIsDisplayed()
        ui.onNodeWithText("C", substring = false).performClick()
        ui.activityRule.scenario.recreate()
        ui.waitForIdle()
        ui.onAllNodesWithText("0", substring = false).onFirst().performTouchInput { click() }
        ui.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        ui.onNodeWithContentDescription("ИИ-помощник").performTouchInput { longClick() }
        ui.onNodeWithText("314159", substring = false).performClick()
        ui.onNode(hasSetTextAction()).assertIsDisplayed()
        ui.onNodeWithText("Дополнение к ответу", substring = false).assertIsDisplayed()
        ui.runOnIdle { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.onNodeWithText("C", substring = false).performClick()
        ui.onNodeWithContentDescription("ИИ-помощник").performClick()
        ui.onAllNodesWithText("0", substring = false).onFirst().performTouchInput { click() }
        ui.onNode(hasSetTextAction()).assertIsDisplayed()
    }

    @Test fun subtractingPercentUsesTheDisplayedAmountForPreviewAndResult() {
        for (key in listOf("2", "0", "−", "2", "%")) ui.onNodeWithText(key, substring = false).performClick()
        ui.onNodeWithText("= 19.6", substring = false).assertIsDisplayed()
        ui.onNodeWithText("=", substring = false).performClick()
        ui.onNodeWithText("19.6", substring = false).assertIsDisplayed()
        ui.runOnIdle { assertFalse(vm.solving.value) }
    }

    @Test fun followUpDraftSurvivesRecreationAndBackRestoresAnswer() {
        val solution = runBlocking {
            app.historyStore.add(Solution(id = "calculator-modes-fixture", problem = "Synthetic problem", answer = "271828"))
        }
        ui.runOnIdle { vm.open(solution) }
        ui.onNodeWithText("271828", substring = false).performClick()
        ui.onNode(hasSetTextAction()).performTextReplacement("Почему?")
        ui.activityRule.scenario.recreate()
        ui.waitForIdle()
        ui.onNode(hasSetTextAction()).assertTextEquals("Почему?")
        ui.runOnIdle { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.onNodeWithText("271828", substring = false).assertIsDisplayed()
        ui.runOnIdle {
            assertEquals("Почему?", vm.session.value.calculator.followUpDraft)
            assertEquals("calculator-modes-fixture", vm.session.value.calculator.followUpSolutionId)
        }
        ui.onNodeWithText("271828", substring = false).performClick()
        ui.onNode(hasSetTextAction()).assertTextEquals("Почему?")
    }

    @Test fun clearAndAssistantLongPressKeepCalculationAndExistingAnswer() {
        // Enter only arithmetic: these tests must never send to the configured provider.
        for (digit in listOf("1", "2")) ui.onNodeWithText(digit, substring = false).performClick()
        val solution = runBlocking {
            app.historyStore.add(Solution(id = "calculator-restore-fixture", problem = "Synthetic problem", answer = "161803"))
        }
        ui.runOnIdle { vm.open(solution) }
        ui.onNodeWithText("C", substring = false).performClick()
        ui.onNodeWithText("12", substring = false).assertIsDisplayed()
        ui.onNodeWithContentDescription("ИИ-помощник").performClick()
        ui.onNodeWithText("Библиотека", substring = false).assertIsDisplayed()
        ui.onNodeWithContentDescription("К калькулятору").performTouchInput { longClick() }
        ui.onNodeWithText("161803", substring = false).assertIsDisplayed()
        ui.onNodeWithText("C", substring = false).performClick()
        ui.onNodeWithText("12", substring = false).assertIsDisplayed()
    }
}
