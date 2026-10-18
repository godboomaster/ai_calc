package com.smartcalc.app.ui.library

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.smartcalc.app.MainActivity
import com.smartcalc.app.SmartCalcApplication
import com.smartcalc.app.SolutionViewModel
import com.smartcalc.app.ui.template.TemplatesScreen
import com.smartcalc.app.ui.theme.SmartCalcTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** Real screens at 200%, synthetic errors only; optional pause for Computer Use inspection. */
class LibraryLayoutDeviceTest {
    private val ui = createAndroidComposeRule<MainActivity>()
    @get:Rule val isolatedApp: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "com.smartcalc.app.connectionscheck") {
                "Use -PconnectionsDeviceCheck; never launch the user's calculator"
            }
        }
    }).around(ui)
    private val app get() = ui.activity.application as SmartCalcApplication
    private val vm get() = ViewModelProvider(ui.activity)[SolutionViewModel::class.java]
    private val libraryVm get() = ViewModelProvider(ui.activity)[LibraryImportViewModel::class.java]

    private fun screen(content: @Composable () -> Unit) {
        ui.runOnUiThread {
            ui.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    SmartCalcTheme {
                        Scaffold { padding ->
                            Box(Modifier.padding(padding).consumeWindowInsets(padding)) { content() }
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
    }

    private fun visualPause() {
        val seconds = InstrumentationRegistry.getArguments().getString("visualPauseSeconds")
            ?.toLongOrNull()?.coerceIn(0, 60) ?: 0
        val until = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < until) { ui.waitForIdle(); Thread.sleep(200) }
    }

    @Test fun selectedTemplateKeepsReadableTextAndCanBeDisarmedAtTwoHundredPercent() {
        val name = "Образец с длинным названием для проверки переноса текста"
        runBlocking { app.templateStore.save(null, name, "", emptyList()) }
        val template = app.templateStore.items.value.first { it.name == name }
        ui.runOnIdle { vm.armTemplate(template) }
        val viewModel = vm
        screen { TemplatesScreen(viewModel, onBack = {}, onUse = {}, onEdit = {}) }
        val layouts = mutableListOf<TextLayoutResult>()
        ui.onNodeWithText("Выбран «$name» — прикрепите задачу на калькуляторе", useUnmergedTree = true)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.size)
        assertFalse(layouts.single().hasVisualOverflow)
        assertTrue("Selected template is squeezed into ${layouts.single().lineCount} lines", layouts.single().lineCount <= 10)
        val action = ui.onNodeWithText("Не использовать").performScrollTo().assertIsDisplayed()
        visualPause()
        action.performClick()
        ui.runOnIdle { assertNull(viewModel.armedTemplate.value) }
    }

    private fun assertErrorAlignedWithAction(message: String, action: String) {
        val error = ui.onNodeWithText(message, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
        val reference = ui.onNode(hasClickAction() and hasAnyDescendant(hasText(action)), useUnmergedTree = true)
            .fetchSemanticsNode()
        assertEquals("Error must align with the surrounding content", reference.boundsInRoot.left, error.boundsInRoot.left, 1f)
    }

    @Test fun addMaterialErrorAlignsWithContentAndCanBeDismissed() {
        val message = "Проверка ошибки: материал не удалось открыть."
        val viewModel = vm
        val imports = libraryVm
        ui.runOnIdle { imports.error.value = message }
        screen { AddLibraryMaterialScreen(viewModel, imports, onBack = {}, onSelection = {}, onCamera = {}, onManual = {}, onCollectionImported = {}) }
        assertErrorAlignedWithAction(message, "Документ")
        visualPause()
        ui.onNodeWithText("Понятно").performScrollTo().performClick()
        ui.onNodeWithText(message).assertDoesNotExist()
        ui.runOnIdle { assertNull(imports.error.value) }
    }

    @Test fun missingSourceErrorAlignsWithFooterAction() {
        val imports = libraryVm
        screen { SourceViewerScreen(imports, "layout-missing-source", 1, onBack = {}) }
        val message = "Не удалось открыть фрагмент оригинала."
        ui.waitUntil(5_000) { ui.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
        assertErrorAlignedWithAction(message, "Открыть исходный файл")
        visualPause()
    }
}
