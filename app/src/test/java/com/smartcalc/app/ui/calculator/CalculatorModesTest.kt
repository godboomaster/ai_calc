package com.smartcalc.app.ui.calculator

import com.smartcalc.app.data.session.CalculatorSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CalculatorModesTest {
    private class Screen(
        var state: CalculatorModes = CalculatorModes(),
        var context: CalculatorContext = CalculatorContext(),
    ) {
        var text = ""
        val effects = mutableListOf<CalculatorEffect>()

        fun send(event: CalculatorEvent) {
            val next = state.reduce(event, context, text)
            state = next.state
            effects += next.effects
            next.effects.forEach {
                when (it) {
                    CalculatorEffect.HideAssistant -> context = context.copy(assistantVisible = false)
                    CalculatorEffect.ShowAssistant, CalculatorEffect.RestoreAssistant ->
                        context = context.copy(assistantVisible = true)
                    is CalculatorEffect.EditField -> text = it.text
                    else -> Unit
                }
            }
        }
    }

    private val answer = CalculatorContext(assistantVisible = true, hasSolution = true)

    @Test fun ordinaryCalculatorTapCannotOpenTaskInputEvenWithSavedDraft() {
        val screen = Screen(CalculatorModes(expression = "12+3", taskDraft = "saved task"))
        screen.send(CalculatorEvent.TapDisplay)
        assertFalse(screen.state.editing)
        assertTrue(screen.effects.isEmpty())
        assertFalse(screen.state.presentation(screen.context).canTapDisplay)
        assertEquals("12+3", screen.state.expression)
        assertEquals("saved task", screen.state.taskDraft)
    }

    @Test fun assistantWriteActionStillOpensSavedTaskDraft() {
        val screen = Screen(CalculatorModes(expression = "12+3", taskDraft = "saved task"))
        screen.send(CalculatorEvent.ToggleKeys)
        assertTrue(screen.state.presentation(screen.context).canTapDisplay)
        screen.send(CalculatorEvent.TapDisplay)
        assertEquals(EditorMode.TASK, screen.state.editorMode)
        assertEquals("saved task", screen.text)
        assertFalse(screen.state.aiKeys)
    }

    @Test fun calculatorWithReceivedSolutionStillAcceptsInput() {
        val screen = Screen(CalculatorModes(expression = "12+3", taskDraft = "saved task"),
            answer.copy(assistantVisible = false))
        assertTrue(screen.state.presentation(screen.context).canTapDisplay)
        screen.send(CalculatorEvent.TapDisplay)
        assertEquals(EditorMode.TASK, screen.state.editorMode)
        assertEquals("saved task", screen.text)
        assertEquals("12+3", screen.state.expression)
    }

    @Test fun arithmeticResultAloneDoesNotExposeTaskInputWithoutAssistantResult() {
        val screen = Screen(CalculatorModes(expression = "19.6", evaluated = true))
        screen.send(CalculatorEvent.TapDisplay)
        assertFalse(screen.state.editing)
        assertFalse(screen.state.presentation(screen.context).canTapDisplay)
    }

    @Test fun answerEditorPreservesCalculationAndRestoresDraftOnBack() {
        val screen = Screen(CalculatorModes(expression = "12+3"), answer)
        screen.send(CalculatorEvent.TapDisplay)
        assertEquals(EditorMode.FOLLOW_UP, screen.state.editorMode)
        assertFalse(screen.context.assistantVisible)
        screen.text = "Почему?"
        screen.send(CalculatorEvent.CloseEditor)
        assertTrue(screen.context.assistantVisible)
        assertEquals("Почему?", screen.state.followUpDraft)
        assertEquals("12+3", screen.state.expression)
        screen.send(CalculatorEvent.TapDisplay)
        assertEquals("Почему?", screen.text)
    }

    @Test fun arithmeticEditorOnCalculatorUpdatesExpressionAndClearsTaskDraft() {
        val screen = Screen(CalculatorModes(expression = "12", evaluated = true, aiKeys = true))
        screen.send(CalculatorEvent.TapDisplay)
        screen.text = "12+3"
        assertEquals(CalculatorSession(expression = "12+3"), screen.state.session(screen.text))
        screen.send(CalculatorEvent.CloseEditor)
        assertEquals("12+3", screen.state.expression)
        assertFalse(screen.state.evaluated)
        assertEquals("", screen.state.taskDraft)
    }

    @Test fun taskEditorKeepsSeparateDraftAndUsesItWhenReopened() {
        val screen = Screen(CalculatorModes(expression = "12+3", aiKeys = true))
        screen.send(CalculatorEvent.TapDisplay)
        screen.text = "Найти ток"
        assertEquals("Найти ток", screen.state.session(screen.text).taskDraft)
        screen.send(CalculatorEvent.CloseEditor)
        assertEquals("12+3", screen.state.expression)
        screen.send(CalculatorEvent.ToggleKeys)
        screen.send(CalculatorEvent.TapDisplay)
        assertEquals("Найти ток", screen.text)
    }

    @Test fun droppingFollowUpKeepsReturnToAnswerAndStoresNumericTextAsTaskDraft() {
        val screen = Screen(context = answer)
        screen.send(CalculatorEvent.TapDisplay)
        screen.send(CalculatorEvent.DropFollowUp)
        screen.text = "2+3"
        screen.send(CalculatorEvent.CloseEditor)
        assertEquals("2+3", screen.state.taskDraft)
        assertEquals("", screen.state.expression)
        assertTrue(screen.context.assistantVisible)
    }

    @Test fun keyboardHidingOnlyClosesEditorAfterKeyboardWasVisible() {
        val screen = Screen(context = answer)
        screen.send(CalculatorEvent.TapDisplay)
        screen.send(CalculatorEvent.KeyboardVisibility(false))
        assertTrue(screen.state.editing)
        screen.send(CalculatorEvent.KeyboardVisibility(true))
        screen.text = "Черновик"
        screen.send(CalculatorEvent.KeyboardVisibility(false))
        assertFalse(screen.state.editing)
        assertEquals("Черновик", screen.state.followUpDraft)
        assertTrue(screen.context.assistantVisible)
    }

    @Test fun firstClearOnAnswerOnlyHidesItSecondClearErasesCalculation() {
        val screen = Screen(CalculatorModes(expression = "12+3"), answer)
        screen.send(CalculatorEvent.Clear)
        assertEquals("12+3", screen.state.expression)
        assertFalse(screen.context.assistantVisible)
        screen.send(CalculatorEvent.Clear)
        assertEquals("", screen.state.expression)
    }

    @Test fun typingAndDeletingLeaveAssistantWithoutLosingCalculatorRules() {
        val screen = Screen(CalculatorModes(expression = "42", evaluated = true, aiKeys = true), answer)
        screen.send(CalculatorEvent.Input("7"))
        assertEquals("7", screen.state.expression)
        assertFalse(screen.state.aiKeys)
        assertFalse(screen.context.assistantVisible)
        screen.send(CalculatorEvent.Input("+"))
        screen.send(CalculatorEvent.Delete)
        assertEquals("7", screen.state.expression)
    }

    @Test fun numericFollowUpIsSentWholeAndNeverCalculated() {
        val screen = Screen(CalculatorModes(expression = "12+3"), answer)
        screen.send(CalculatorEvent.TapDisplay)
        screen.text = " 2+3 "
        screen.send(CalculatorEvent.Submit)
        assertEquals(CalculatorEffect.AskFollowUp("2+3"), screen.effects.last())
        assertEquals("12+3", screen.state.expression)
        assertEquals("", screen.state.followUpDraft)
        assertFalse(screen.state.editing)
        assertFalse(screen.context.assistantVisible)
    }

    @Test fun droppingFollowUpThenSubmittingArithmeticCalculatesLocally() {
        val screen = Screen(context = answer)
        screen.send(CalculatorEvent.TapDisplay)
        screen.send(CalculatorEvent.DropFollowUp)
        screen.text = "2+3"
        screen.send(CalculatorEvent.Submit)
        assertEquals("5", screen.state.expression)
        assertTrue(screen.state.evaluated)
        assertFalse(screen.effects.any { it is CalculatorEffect.Solve || it is CalculatorEffect.AskFollowUp })
    }

    @Test fun blankSubmissionKeepsEditorAndDraft() {
        val screen = Screen(context = answer)
        screen.send(CalculatorEvent.TapDisplay)
        screen.text = "  "
        screen.effects.clear()
        screen.send(CalculatorEvent.Submit)
        assertTrue(screen.state.editing)
        assertTrue(screen.effects.isEmpty())
    }

    @Test fun textTaskRequiresExplicitEditorAndClearsDraftOnlyOnSubmission() {
        val screen = Screen(CalculatorModes(expression = "Ошибка", taskDraft = "Задача"))
        screen.send(CalculatorEvent.Submit)
        assertFalse(screen.effects.any { it is CalculatorEffect.Solve })
        screen.send(CalculatorEvent.ToggleKeys)
        screen.send(CalculatorEvent.TapDisplay)
        screen.send(CalculatorEvent.Submit)
        assertEquals(CalculatorEffect.Solve("Задача"), screen.effects.last())
        assertEquals("", screen.state.taskDraft)
    }

    @Test fun exactCodeOnKeypadTogglesOnceAndStillCalculates() {
        val screen = Screen(CalculatorModes(expression = "42"), CalculatorContext(assistantCode = "42"))
        screen.send(CalculatorEvent.Submit)
        assertEquals(1, screen.effects.count { it == CalculatorEffect.ToggleAssistant })
        assertEquals("42", screen.state.expression)
        assertTrue(screen.state.evaluated)
        screen.send(CalculatorEvent.Submit)
        assertEquals(1, screen.effects.count { it == CalculatorEffect.ToggleAssistant })
    }

    @Test fun codeTypedInEditorDoesNotToggleAssistant() {
        val screen = Screen(CalculatorModes(aiKeys = true), CalculatorContext(assistantCode = "42", assistantRevealed = true))
        screen.send(CalculatorEvent.TapDisplay)
        screen.text = "42"
        screen.send(CalculatorEvent.Submit)
        assertFalse(screen.effects.contains(CalculatorEffect.ToggleAssistant))
    }

    @Test fun concealmentSavesEditorButLeavesAssistantHiddenAndUnavailable() {
        val screen = Screen(context = answer.copy(assistantCode = "42", assistantRevealed = true))
        screen.send(CalculatorEvent.TapDisplay)
        screen.text = "Черновик"
        screen.context = screen.context.copy(assistantRevealed = false)
        screen.send(CalculatorEvent.Conceal)
        assertEquals("Черновик", screen.state.followUpDraft)
        assertFalse(screen.state.editing)
        assertFalse(screen.state.aiKeys)
        assertFalse(screen.context.assistantVisible)
        screen.send(CalculatorEvent.TapDisplay)
        assertFalse(screen.state.editing)
        assertFalse(screen.state.presentation(screen.context.copy(swipeToRestore = true)).canSwipeToRestore)
    }

    @Test fun swipeAvailabilityRespectsEveryBlockingMode() {
        val context = CalculatorContext(swipeToRestore = true, hasSolution = true)
        assertTrue(CalculatorModes().presentation(context).canSwipeToRestore)
        for (blocked in listOf(context.copy(swipeToRestore = false), context.copy(assistantVisible = true),
            context.copy(assistantCode = "42"), context.copy(hasSolution = false))) {
            assertFalse(CalculatorModes().presentation(blocked).canSwipeToRestore)
        }
        assertFalse(CalculatorModes(aiKeys = true).presentation(context).canSwipeToRestore)
        assertFalse(CalculatorModes(editorMode = EditorMode.TASK).presentation(context).canSwipeToRestore)
        assertTrue(CalculatorModes().presentation(context.copy(hasSolution = false, solving = true)).canSwipeToRestore)
        assertTrue(CalculatorModes().presentation(context.copy(hasSolution = false, hasError = true)).canSwipeToRestore)
    }

    @Test fun menuAndLongPressPreserveDraftAndReturnToExistingAnswer() {
        val screen = Screen(context = answer)
        screen.send(CalculatorEvent.TapDisplay)
        screen.text = "Дополнение"
        screen.send(CalculatorEvent.ToggleKeys)
        assertTrue(screen.state.aiKeys)
        assertEquals("Дополнение", screen.state.followUpDraft)
        screen.send(CalculatorEvent.RestoreAssistant)
        assertFalse(screen.state.aiKeys)
        assertEquals(CalculatorEffect.RestoreAssistant, screen.effects.last())
    }

    @Test fun shownCounterOnlyResetsEditorAndMenuForNewPresentation() {
        val screen = Screen(CalculatorModes(aiKeys = true, seenShown = 7))
        screen.send(CalculatorEvent.Shown(7))
        assertTrue(screen.state.aiKeys)
        screen.send(CalculatorEvent.EditRequested("draft", false))
        screen.send(CalculatorEvent.Shown(8))
        assertFalse(screen.state.editing)
        assertFalse(screen.state.aiKeys)
        assertEquals(8, screen.state.seenShown)
    }

    @Test fun newSolutionDiscardsOnlyFollowUpDraftAndSameSolutionKeepsIt() {
        val screen = Screen(CalculatorModes(taskDraft = "task", followUpDraft = "more", draftSolutionId = "old"))
        screen.send(CalculatorEvent.SolutionChanged("old"))
        assertEquals("more", screen.state.followUpDraft)
        screen.send(CalculatorEvent.SolutionChanged("new"))
        assertEquals("", screen.state.followUpDraft)
        assertEquals("task", screen.state.taskDraft)
    }

    @Test fun retryUsesEditedDraftBeforeOriginalAndKeepsFollowUpMeaning() {
        for (followUp in listOf(false, true)) {
            val screen = Screen(CalculatorModes(taskDraft = "edited task", followUpDraft = "edited more"),
                answer.copy(hasError = true, retryText = "original", retryIsFollowUp = followUp))
            screen.send(CalculatorEvent.TapDisplay)
            assertEquals(if (followUp) "edited more" else "edited task", screen.text)
            assertEquals(if (followUp) EditorMode.FOLLOW_UP else EditorMode.TASK, screen.state.editorMode)
        }
        val screen = Screen(context = answer.copy(hasError = true, retryText = "original"))
        screen.send(CalculatorEvent.TapDisplay)
        assertEquals("original", screen.text)
    }

    @Test fun externalFollowUpWithoutSolutionBecomesNewTask() {
        val screen = Screen()
        screen.send(CalculatorEvent.EditRequested("text", true))
        assertEquals(EditorMode.TASK, screen.state.editorMode)
        assertEquals("text", screen.text)
    }

    @Test fun displayModeAndReadAcknowledgementFollowVisibilityAndLifecycle() {
        val state = CalculatorModes()
        assertEquals(CalculatorDisplayMode.CALCULATOR, state.presentation(answer.copy(assistantVisible = false)).displayMode)
        assertEquals(CalculatorDisplayMode.ANSWER, state.presentation(answer).displayMode)
        assertEquals(CalculatorDisplayMode.SOLVING, state.presentation(answer.copy(solving = true, hasError = true)).displayMode)
        assertEquals(CalculatorDisplayMode.ERROR, state.presentation(answer.copy(hasError = true)).displayMode)
        assertEquals(CalculatorDisplayMode.EDITOR, state.copy(editorMode = EditorMode.TASK).presentation(answer).displayMode)
        assertTrue(state.shouldMarkViewed(answer, resumed = true))
        assertFalse(state.shouldMarkViewed(answer, resumed = false))
        assertFalse(state.copy(aiKeys = true).shouldMarkViewed(answer, resumed = true))
        assertFalse(state.copy(editorMode = EditorMode.TASK).shouldMarkViewed(answer, resumed = true))
        assertFalse(state.shouldMarkViewed(answer.copy(solving = true), resumed = true))
    }

    @Test fun completionWhileCalculatingDoesNotChangeModeOrEmitShowCommand() {
        val screen = Screen(CalculatorModes(expression = "12+3"), CalculatorContext(solving = true))
        screen.context = screen.context.copy(solving = false, hasSolution = true)
        assertEquals(CalculatorDisplayMode.CALCULATOR, screen.state.presentation(screen.context).displayMode)
        assertEquals("12+3", screen.state.expression)
        assertTrue(screen.effects.isEmpty())
    }

    @Test fun stepShortcutsAndLibraryQueryKeepTheirExistingKeypadMeaning() {
        val state = CalculatorModes(expression = "12+3.5×40")
        assertEquals("12 3.5 40", state.libraryQuery(CalculatorContext()))
        assertEquals("", state.libraryQuery(answer))
        assertEquals("", state.copy(evaluated = true).libraryQuery(CalculatorContext()))
        assertEquals(8, state.stepForDigit('9', answer, stepCount = 10))
        assertEquals(9, state.stepForDigit('0', answer, stepCount = 10))
        assertNull(state.stepForDigit('9', answer, stepCount = 8))
        assertNull(state.stepForDigit('1', answer.copy(solving = true), stepCount = 10))
        assertNull(state.copy(aiKeys = true).stepForDigit('9', answer, stepCount = 10))
        // Zero remains an ordinary calculator key even while 1–9 show assistant actions.
        assertEquals(9, state.copy(aiKeys = true).stepForDigit('0', answer, stepCount = 10))
    }

    @Test fun emptyAndInvalidArithmeticNeverBecomeNetworkRequests() {
        for (text in listOf("", " ", "1+", "Ошибка", "Не число")) {
            val screen = Screen(CalculatorModes(expression = text))
            screen.send(CalculatorEvent.Submit)
            assertFalse(screen.effects.any { it is CalculatorEffect.Solve || it is CalculatorEffect.AskFollowUp })
            if (text == "1+") {
                assertEquals("Ошибка", screen.state.expression)
                assertTrue(screen.state.evaluated)
            }
        }
    }

    @Test fun percentageIsCalculatedLocallyButFollowUpKeepsOriginalQuestion() {
        val calculator = Screen(CalculatorModes(expression = "20−2%"))
        calculator.send(CalculatorEvent.Submit)
        assertEquals("19.6", calculator.state.expression)
        assertFalse(calculator.effects.any { it is CalculatorEffect.Solve || it is CalculatorEffect.AskFollowUp })
        val followUp = Screen(context = answer)
        followUp.send(CalculatorEvent.TapDisplay)
        followUp.text = "20−2%"
        followUp.send(CalculatorEvent.Submit)
        assertEquals(CalculatorEffect.AskFollowUp("20−2%"), followUp.effects.last())
    }

    @Test fun recreationKeepsEditorButProcessRestartOnlyRestoresDurableDrafts() {
        val screen = Screen(CalculatorModes(expression = "12+3", draftSolutionId = "answer"), answer)
        screen.send(CalculatorEvent.TapDisplay)
        screen.text = "Почему?"
        val recreated = Json.decodeFromString<CalculatorModes>(Json.encodeToString(screen.state))
        assertEquals(EditorMode.FOLLOW_UP, recreated.editorMode)
        assertTrue(recreated.returnToAssistant)
        val restarted = CalculatorModes.fromSession(screen.state.session(screen.text), "answer", shown = 4)
        assertEquals("Почему?", restarted.followUpDraft)
        assertEquals("12+3", restarted.expression)
        assertFalse(restarted.editing)
        assertFalse(restarted.aiKeys)
        assertEquals(4, restarted.seenShown)
    }
}
