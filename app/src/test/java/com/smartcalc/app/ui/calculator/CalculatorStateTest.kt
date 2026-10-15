package com.smartcalc.app.ui.calculator

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.saveable.SaverScope
import com.smartcalc.app.data.session.CalculatorSession
import org.junit.Assert.*
import org.junit.Test

class CalculatorStateTest {
    private class RecordingHost : CalculatorHost {
        val calls = mutableListOf<String>()
        override fun hideKeyboard() { calls += "hideKeyboard" }
        override fun hideFromDisplay() { calls += "hideFromDisplay" }
        override fun showOnDisplay() { calls += "showOnDisplay" }
        override fun toggleAssistant() { calls += "toggleAssistant" }
        override fun solveText(text: String) { calls += "solveText:$text" }
        override fun askFollowUp(text: String) { calls += "askFollowUp:$text" }
        override fun restoreAssistant() { calls += "restoreAssistant" }
    }

    private val host = RecordingHost()
    private val calculator = CalculatorState(host)
    private val hidden = AssistantStatus()
    private val answer = AssistantStatus(visible = true, hasSolution = true)
    private val solving = AssistantStatus(visible = true, solving = true)

    private fun type(text: String) = calculator.field.setTextAndPlaceCursorAtEnd(text)

    private fun keys(text: String) = text.forEach { calculator.input(it.toString()) }

    @Test fun keypadCountsAndPreviewsResult() {
        keys("12+3")
        assertEquals("12+3", calculator.expression)
        assertEquals("15", calculator.preview)
        calculator.input("+")
        assertNull("Незаконченное выражение не даёт результата", calculator.preview)
        calculator.backspace()
        calculator.submit(hidden)
        assertEquals("15", calculator.expression)
        assertTrue(calculator.evaluated)
        keys("7")
        assertEquals("Новая цифра после результата начинает новое выражение", "7", calculator.expression)
    }

    @Test fun clearOnAssistantOnlyHidesItThenClearsExpression() {
        keys("42")
        host.calls.clear()
        calculator.clear(answer)
        assertEquals("42", calculator.expression)
        assertEquals(listOf("hideFromDisplay"), host.calls)
        calculator.clear(hidden)
        assertEquals("", calculator.expression)
    }

    @Test fun anyKeyLeavesAiKeys() {
        calculator.toggleAiKeys()
        assertTrue(calculator.aiKeys)
        calculator.input("5")
        assertFalse(calculator.aiKeys)
        assertEquals("5", calculator.expression)
    }

    @Test fun tappingCalculatorOpensEditorWithExpression() {
        keys("2+2")
        calculator.tapDisplay(hidden)
        assertTrue(calculator.editing)
        assertFalse(calculator.followUp)
        assertEquals("2+2", calculator.field.text.toString())
    }

    @Test fun closedKeyboardKeepsTaskDraftAndArithmeticAsExpression() {
        calculator.tapDisplay(hidden)
        type("Найти площадь круга")
        calculator.stopEditing()
        assertFalse(calculator.editing)
        assertEquals("Найти площадь круга", calculator.taskDraft)

        calculator.tapDisplay(hidden)
        assertEquals("Найти площадь круга", calculator.field.text.toString())
        type("3×4")
        calculator.stopEditing()
        assertEquals("3×4", calculator.expression)
        assertEquals("", calculator.taskDraft)
    }

    @Test fun submittedTaskGoesToAssistantAndClearsDraft() {
        calculator.tapDisplay(hidden)
        type("  Решите уравнение x² = 4  ")
        calculator.submit(hidden)
        assertFalse(calculator.editing)
        assertEquals("", calculator.taskDraft)
        assertEquals(listOf("hideFromDisplay", "hideKeyboard", "solveText:Решите уравнение x² = 4"), host.calls)
    }

    @Test fun arithmeticTypedInEditorIsCalculatedNotSent() {
        calculator.tapDisplay(hidden)
        type("(1+2")
        calculator.submit(hidden)
        assertEquals("Ошибка", calculator.expression)
        assertTrue(calculator.evaluated)
        assertTrue(host.calls.none { it.startsWith("solveText") })
    }

    @Test fun tapOnAnswerStartsFollowUpThatIsSentWhole() {
        calculator.tapDisplay(answer)
        assertTrue(calculator.followUp)
        type("а если 2+3?")
        calculator.submit(answer)
        assertEquals("askFollowUp:а если 2+3?", host.calls.last())
        assertEquals("", calculator.followUpDraft)
        assertFalse(calculator.followUp)
    }

    @Test fun dismissedFollowUpKeepsDraftAndReturnsAnswer() {
        calculator.tapDisplay(answer)
        type("а почему")
        host.calls.clear()
        calculator.stopEditing()
        assertEquals("а почему", calculator.followUpDraft)
        assertEquals(listOf("hideKeyboard", "showOnDisplay"), host.calls)

        calculator.tapDisplay(answer)
        assertEquals("а почему", calculator.field.text.toString())
    }

    @Test fun droppedFollowUpBecomesNewTaskDraft() {
        calculator.tapDisplay(answer)
        type("Новая задача")
        calculator.dropFollowUp()
        calculator.stopEditing()
        assertEquals("Новая задача", calculator.taskDraft)
        assertEquals("", calculator.followUpDraft)
    }

    @Test fun editorOverAssistantKeepsArithmeticAsTask() {
        val failed = AssistantStatus(visible = true, failed = true, retryText = "2+2")
        calculator.tapDisplay(failed)
        assertEquals("2+2", calculator.field.text.toString())
        calculator.stopEditing()
        assertEquals("2+2", calculator.taskDraft)
        assertEquals("", calculator.expression)
        assertEquals("showOnDisplay", host.calls.last())
    }

    @Test fun retryAfterFailedFollowUpReopensFollowUp() {
        val failed = AssistantStatus(visible = true, failed = true, hasSolution = true, retryIsFollowUp = true,
            retryText = "Исходное дополнение")
        calculator.tapDisplay(failed)
        assertTrue(calculator.followUp)
        assertEquals("Исходное дополнение", calculator.field.text.toString())

        val failedWithoutSolution = failed.copy(hasSolution = false)
        calculator.stopEditing()
        calculator.tapDisplay(failedWithoutSolution)
        assertFalse(calculator.followUp)
    }

    @Test fun tapIsIgnoredWhileSolvingOrConcealed() {
        calculator.tapDisplay(solving)
        assertFalse(calculator.editing)
        assertFalse(calculator.displayTappable(solving))
        calculator.tapDisplay(AssistantStatus(code = "42"))
        assertFalse(calculator.editing)
        assertTrue(host.calls.isEmpty())
    }

    @Test fun typedCodeTogglesAssistantOnlyOnFreshExpression() {
        val locked = AssistantStatus(code = "42")
        keys("42")
        calculator.submit(locked)
        assertEquals(1, host.calls.count { it == "toggleAssistant" })
        assertEquals("42", calculator.expression)
        calculator.submit(locked)
        assertEquals("Повторное «=» по результату ничего не переключает", 1, host.calls.count { it == "toggleAssistant" })
    }

    @Test fun concealClosesEditorAndAiKeys() {
        calculator.tapDisplay(hidden)
        type("Задача")
        calculator.conceal()
        assertFalse(calculator.editing)
        assertFalse(calculator.aiKeys)
        assertEquals("Задача", calculator.taskDraft)
        assertEquals("hideFromDisplay", host.calls.last())
    }

    @Test fun restoreClosesEditorBeforeReturningAssistant() {
        calculator.toggleAiKeys()
        calculator.restoreAssistant()
        assertFalse(calculator.aiKeys)
        assertEquals("restoreAssistant", host.calls.last())
    }

    @Test fun newShownSolutionReturnsDigitsAndClosesEditor() {
        calculator.tapDisplay(answer)
        calculator.syncShown(0)
        assertTrue("Тот же счётчик ничего не меняет", calculator.editing)
        calculator.syncShown(1)
        assertFalse(calculator.editing)
        assertFalse(calculator.followUp)
    }

    @Test fun followUpDraftBelongsToItsSolution() {
        val state = CalculatorState(host, CalculatorSession(followUpDraft = "черновик"), solutionId = "a")
        state.syncSolution("a")
        assertEquals("черновик", state.followUpDraft)
        state.syncSolution("b")
        assertEquals("", state.followUpDraft)
        assertEquals("b", state.session().followUpSolutionId)
    }

    @Test fun sessionSavesOpenEditorByItsMeaning() {
        calculator.tapDisplay(hidden)
        type("1+1")
        assertEquals(CalculatorSession(expression = "1+1"), calculator.session())
        type("Задача")
        assertEquals(CalculatorSession(taskDraft = "Задача"), calculator.session())

        calculator.stopEditing()
        calculator.tapDisplay(answer)
        type("Дополнение")
        assertEquals("Дополнение", calculator.session().followUpDraft)
        assertEquals("Задача", calculator.session().taskDraft)
    }

    @Test fun swipeAndConnectionChoiceFollowAssistantState() {
        assertFalse("Нечего возвращать", calculator.canSwipeToRestore(hidden, swipeEnabled = true))
        val answerHidden = answer.copy(visible = false)
        assertTrue(calculator.canSwipeToRestore(answerHidden, swipeEnabled = true))
        assertFalse(calculator.canSwipeToRestore(answerHidden, swipeEnabled = false))
        assertFalse(calculator.canSwipeToRestore(answerHidden.copy(code = "42"), swipeEnabled = true))

        assertFalse(calculator.offersConnectionChoice(answer))
        assertTrue(calculator.offersConnectionChoice(AssistantStatus(visible = true, failed = true)))
        calculator.toggleAiKeys()
        assertTrue(calculator.offersConnectionChoice(hidden))
        assertFalse(calculator.canSwipeToRestore(answerHidden, swipeEnabled = true))
    }

    @Test fun searchUsesTypedNumbersOnly() {
        keys("12+3.5")
        assertEquals("12 3.5", calculator.searchQuery(hidden))
        assertEquals("", calculator.searchQuery(answer))
        calculator.submit(hidden)
        assertEquals("Результат — не числа задачи", "", calculator.searchQuery(hidden))
    }

    @Test fun saverRestoresModeDraftsAndEditor() {
        calculator.tapDisplay(answer)
        type("а если")
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saver = CalculatorState.saver(host)
        val restored = saver.restore(with(saver) { scope.save(calculator) }!!)!!

        assertTrue(restored.editing)
        assertTrue(restored.followUp)
        assertEquals("а если", restored.field.text.toString())
        assertEquals(calculator.session(), restored.session())
        host.calls.clear()
        restored.stopEditing()
        assertEquals("Возврат к помощнику тоже восстановлен", listOf("hideKeyboard", "showOnDisplay"), host.calls)
    }
}
