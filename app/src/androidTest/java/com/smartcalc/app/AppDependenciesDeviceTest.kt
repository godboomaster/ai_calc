package com.smartcalc.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.smartcalc.app.data.history.Solution
import com.smartcalc.app.data.session.ReadingTarget
import com.smartcalc.app.data.session.SessionState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** Synthetic, isolated app only: never launches or alters the user's installed calculator. */
class AppDependenciesDeviceTest {
    private val ui = createAndroidComposeRule<MainActivity>()
    @get:Rule val isolatedApp: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName == "com.smartcalc.app.importcheck") { "Use -PisolatedDeviceCheck" }
            val app = context.applicationContext as SmartCalcApplication
            runBlocking { app.settingsRepository.setAssistantCode("") }
            app.sessionStore.update { SessionState() }
        }
    }).around(ui)
    private val app get() = ui.activity.application as SmartCalcApplication
    private val vm get() = ViewModelProvider(ui.activity)[SolutionViewModel::class.java]

    @Test fun activityRecreationKeepsControllersSessionAndReadingState() {
        val dependencies = app.dependencies
        val session = app.sessionStore
        val generation = app.generation
        val imports = app.libraryImport
        ui.onNodeWithText("C", substring = false).performClick()
        ui.onNodeWithText("2", substring = false).performClick()
        ui.onNodeWithText("0", substring = false).performClick()
        val answer = (1..50).joinToString("\n") { "Reading line $it" }
        val solution = runBlocking { app.historyStore.add(Solution(problem = "Synthetic lifecycle check", answer = answer)) }
        ui.runOnIdle {
            vm.open(solution)
            vm.setReadingTarget(ReadingTarget.BRIEF)
        }
        ui.onNode(hasScrollAction() and hasAnyDescendant(hasText("Reading line 1", substring = true)), useUnmergedTree = true)
            .assertExists(ui.onRoot(useUnmergedTree = true).printToString())
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 120f) }
        ui.waitForIdle()
        val saved = session.state.value
        assertTrue(saved.reading.answerScroll > 0)
        assertEquals("20", saved.calculator.expression)
        ui.activityRule.scenario.recreate()
        ui.waitForIdle()
        ui.runOnIdle {
            assertSame(dependencies, app.dependencies)
            assertSame(session, app.sessionStore)
            assertSame(generation, app.generation)
            assertSame(imports, app.libraryImport)
            assertSame(dependencies.solutionSession.state, vm.session)
            assertEquals(solution.id, vm.current.value?.id)
            assertEquals(saved.reading, vm.session.value.reading)
            assertEquals(saved.calculator, vm.session.value.calculator)
        }
    }

    @Test fun freshViewModelHasOwnSelectionAndSharesProcessDependencies() {
        val dependencies = app.dependencies
        val generation = app.generation
        val imports = app.libraryImport
        runBlocking { app.templateStore.save(null, "Synthetic selection", "", emptyList()) }
        val template = app.templateStore.items.value.first { it.name == "Synthetic selection" }
        val firstOwner = ViewModelStore()
        val secondOwner = ViewModelStore()
        try {
            ui.runOnIdle {
                val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(app)
                val first = ViewModelProvider(firstOwner, factory)[SolutionViewModel::class.java]
                first.armTemplate(template)
            }
            ui.waitForIdle()
            ui.runOnIdle {
                val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(app)
                val first = ViewModelProvider(firstOwner, factory)[SolutionViewModel::class.java]
                val second = ViewModelProvider(secondOwner, factory)[SolutionViewModel::class.java]
                assertEquals(template.id, first.armedTemplate.value?.id)
                assertNull(second.armedTemplate.value)
                assertSame(first.session, second.session)
                assertSame(dependencies, app.dependencies)
                assertSame(generation, app.generation)
                assertSame(imports, app.libraryImport)
            }
        } finally { ui.runOnUiThread { firstOwner.clear(); secondOwner.clear() } }
    }
}
