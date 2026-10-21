package com.smartcalc.app.ui.calculator

import com.smartcalc.app.data.session.CalculatorSession
import kotlinx.serialization.Serializable

@Serializable
internal enum class EditorMode { NONE, TASK, FOLLOW_UP }

internal enum class CalculatorDisplayMode { EDITOR, CALCULATOR, SOLVING, ERROR, ANSWER, EMPTY }

/** External facts have their existing owners (AssistantDisplay, request controller and settings). */
internal data class CalculatorContext(
    val assistantVisible: Boolean = false,
    val assistantUnread: Boolean = false,
    val solving: Boolean = false,
    val hasError: Boolean = false,
    val hasSolution: Boolean = false,
    val retryText: String? = null,
    val retryIsFollowUp: Boolean = false,
    val assistantCode: String? = "",
    val assistantRevealed: Boolean = false,
    val swipeToRestore: Boolean = false,
) {
    val concealed get() = assistantConcealed(assistantCode, assistantRevealed)
}

internal data class CalculatorPresentation(
    val displayMode: CalculatorDisplayMode,
    val answerVisible: Boolean = false,
    val canSwipeToRestore: Boolean = false,
    val canTapDisplay: Boolean = false,
    val assistantButtonVisible: Boolean = false,
    val showConnectionPicker: Boolean = false,
)

internal sealed interface CalculatorEvent {
    data object TapDisplay : CalculatorEvent
    data object CloseEditor : CalculatorEvent
    data object DropFollowUp : CalculatorEvent
    data object ToggleKeys : CalculatorEvent
    data object DismissKeys : CalculatorEvent
    data object RestoreAssistant : CalculatorEvent
    data object Clear : CalculatorEvent
    data object Delete : CalculatorEvent
    data object Submit : CalculatorEvent
    data object Conceal : CalculatorEvent
    data class Input(val token: String) : CalculatorEvent
    data class KeyboardVisibility(val visible: Boolean) : CalculatorEvent
    data class Shown(val count: Int) : CalculatorEvent
    data class SolutionChanged(val id: String?) : CalculatorEvent
    data class EditRequested(val text: String, val followUp: Boolean) : CalculatorEvent
}

internal sealed interface CalculatorEffect {
    data object HideAssistant : CalculatorEffect
    data object ShowAssistant : CalculatorEffect
    data object RestoreAssistant : CalculatorEffect
    data object HideKeyboard : CalculatorEffect
    data object ToggleAssistant : CalculatorEffect
    data class EditField(val text: String) : CalculatorEffect
    data class Solve(val text: String) : CalculatorEffect
    data class AskFollowUp(val text: String) : CalculatorEffect
}

internal data class CalculatorTransition(val state: CalculatorModes, val effects: List<CalculatorEffect> = emptyList())

/** Calculator-local state; no Compose, ViewModel, navigation or Android lifecycle dependencies. */
@Serializable
internal data class CalculatorModes(
    val expression: String = "",
    val evaluated: Boolean = false,
    val aiKeys: Boolean = false,
    val editorMode: EditorMode = EditorMode.NONE,
    val taskDraft: String = "",
    val followUpDraft: String = "",
    val returnToAssistant: Boolean = false,
    val draftSolutionId: String? = null,
    val seenShown: Int = 0,
    val imeShown: Boolean = false,
) {
    val editing get() = editorMode != EditorMode.NONE
    val followUp get() = editorMode == EditorMode.FOLLOW_UP

    private fun inputAvailable(context: CalculatorContext) =
        !context.concealed && (aiKeys || context.assistantVisible)

    fun presentation(context: CalculatorContext) = CalculatorPresentation(
        displayMode = when {
            editing -> CalculatorDisplayMode.EDITOR
            !context.assistantVisible -> CalculatorDisplayMode.CALCULATOR
            context.solving -> CalculatorDisplayMode.SOLVING
            context.hasError -> CalculatorDisplayMode.ERROR
            context.hasSolution -> CalculatorDisplayMode.ANSWER
            else -> CalculatorDisplayMode.EMPTY
        },
        answerVisible = context.assistantVisible && !context.solving && !context.hasError && context.hasSolution,
        canSwipeToRestore = context.swipeToRestore && !context.concealed && !context.assistantVisible &&
            !editing && !aiKeys && (context.solving || context.hasSolution || context.hasError),
        canTapDisplay = !editing && inputAvailable(context) && !(context.assistantVisible && context.solving),
        assistantButtonVisible = assistantButtonVisible(context.assistantCode, context.assistantRevealed),
        showConnectionPicker = !context.concealed && (aiKeys || (context.assistantVisible && context.hasError)),
    )

    /**
     * Скрытый кодом помощник не показывает ни клавиш ИИ, ни строки задачи: после экстренного скрытия
     * калькулятор обычный с первого кадра, ещё до того, как [CalculatorEvent.Conceal] закроет их.
     */
    fun visibleIn(context: CalculatorContext) =
        if (context.concealed) copy(aiKeys = false, editorMode = EditorMode.NONE) else this

    fun shouldMarkViewed(context: CalculatorContext, resumed: Boolean) =
        context.assistantVisible && !context.solving && !editing && !aiKeys && resumed

    fun libraryQuery(context: CalculatorContext): String =
        if (!context.assistantVisible && !evaluated) searchNumbers(expression) else ""

    fun stepForDigit(digit: Char, context: CalculatorContext, stepCount: Int): Int? {
        if (aiKeys && digit in '1'..'9') return null
        val index = if (digit == '0') 9 else digit - '1'
        return index.takeIf { presentation(context).answerVisible && it in 0 until stepCount }
    }

    /** The field keeps cursor/selection in Compose; only its text belongs in the durable session. */
    fun session(editorText: String): CalculatorSession {
        val expressionEditing = editing && !followUp && !returnToAssistant &&
            (editorText.isBlank() || isArithmetic(editorText))
        return CalculatorSession(
            expression = if (expressionEditing) editorText else expression,
            evaluated = if (expressionEditing) false else evaluated,
            taskDraft = when {
                expressionEditing -> ""
                editing && !followUp -> editorText
                else -> taskDraft
            },
            followUpDraft = if (editing && followUp) editorText else followUpDraft,
            followUpSolutionId = draftSolutionId,
        )
    }

    fun reduce(event: CalculatorEvent, context: CalculatorContext, editorText: String): CalculatorTransition {
        var state = this
        val effects = mutableListOf<CalculatorEffect>()

        fun leaveAssistant() {
            state = state.copy(aiKeys = false)
            effects += CalculatorEffect.HideAssistant
        }

        fun startEditing(text: String, asFollowUp: Boolean = false) {
            state = state.copy(
                returnToAssistant = context.assistantVisible,
                aiKeys = false,
                editorMode = if (asFollowUp) EditorMode.FOLLOW_UP else EditorMode.TASK,
            )
            effects += CalculatorEffect.HideAssistant
            effects += CalculatorEffect.EditField(text)
        }

        fun stopEditing(saveDraft: Boolean = true) {
            effects += CalculatorEffect.HideKeyboard
            if (!state.editing) return
            if (saveDraft) {
                state = when {
                    state.followUp -> state.copy(followUpDraft = editorText)
                    !state.returnToAssistant && (editorText.isBlank() || isArithmetic(editorText)) ->
                        state.copy(expression = editorText, evaluated = false, taskDraft = "")
                    else -> state.copy(taskDraft = editorText)
                }
                if (state.returnToAssistant) effects += CalculatorEffect.ShowAssistant
            }
            state = state.copy(editorMode = EditorMode.NONE, imeShown = false)
        }

        when (event) {
            CalculatorEvent.TapDisplay -> if (inputAvailable(context)) {
                when {
                    !context.assistantVisible -> startEditing(taskDraft.ifEmpty {
                        if (evaluated && !isArithmetic(expression)) "" else expression
                    })
                    context.solving -> Unit
                    context.hasError -> {
                        val asFollowUp = context.retryIsFollowUp && context.hasSolution
                        startEditing(retryEditorText(if (asFollowUp) followUpDraft else taskDraft, context.retryText), asFollowUp)
                    }
                    else -> startEditing(followUpDraft, asFollowUp = true)
                }
            }
            CalculatorEvent.CloseEditor -> stopEditing()
            CalculatorEvent.DropFollowUp -> state = state.copy(editorMode = EditorMode.TASK)
            CalculatorEvent.ToggleKeys -> {
                stopEditing()
                state = state.copy(aiKeys = !state.aiKeys)
            }
            CalculatorEvent.DismissKeys -> state = state.copy(aiKeys = false)
            CalculatorEvent.RestoreAssistant -> {
                stopEditing()
                state = state.copy(aiKeys = false)
                effects += CalculatorEffect.RestoreAssistant
            }
            is CalculatorEvent.Input -> {
                leaveAssistant()
                state = state.copy(expression = appendCalculatorToken(expression, evaluated, event.token), evaluated = false)
            }
            CalculatorEvent.Delete -> {
                leaveAssistant()
                state = state.copy(expression = deleteCalculatorCharacter(expression), evaluated = false)
            }
            CalculatorEvent.Clear -> {
                leaveAssistant()
                // First C on an assistant answer only returns to the existing calculation.
                if (!context.assistantVisible) state = state.copy(expression = "", evaluated = false)
            }
            CalculatorEvent.Submit -> {
                val text = (if (editing) editorText else expression).trim()
                if (!editing) leaveAssistant()
                if (!editing && togglesAssistant(text, evaluated, context.assistantCode)) effects += CalculatorEffect.ToggleAssistant
                when (inputSubmission(text, editing, followUp)) {
                    InputSubmission.NONE -> Unit
                    InputSubmission.FOLLOW_UP -> {
                        state = state.copy(followUpDraft = "")
                        stopEditing(saveDraft = false)
                        effects += CalculatorEffect.AskFollowUp(text)
                    }
                    InputSubmission.CALCULATE -> {
                        if (editing) state = state.copy(taskDraft = "")
                        stopEditing(saveDraft = false)
                        val result = try {
                            ExpressionParser.format(ExpressionParser.evaluate(text))
                        } catch (_: Exception) { "Ошибка" }
                        state = state.copy(expression = result, evaluated = true)
                    }
                    InputSubmission.ASK -> {
                        stopEditing(saveDraft = false)
                        state = state.copy(taskDraft = "")
                        effects += CalculatorEffect.Solve(text)
                    }
                }
            }
            CalculatorEvent.Conceal -> {
                stopEditing()
                state = state.copy(aiKeys = false)
                effects += CalculatorEffect.HideAssistant
            }
            is CalculatorEvent.KeyboardVisibility -> when {
                !editing -> state = state.copy(imeShown = false)
                event.visible -> state = state.copy(imeShown = true)
                imeShown -> stopEditing()
            }
            is CalculatorEvent.Shown -> if (event.count != seenShown) {
                state = state.copy(seenShown = event.count, aiKeys = false, editorMode = EditorMode.NONE, imeShown = false)
            }
            is CalculatorEvent.SolutionChanged -> if (draftSolutionId != event.id) {
                state = state.copy(followUpDraft = "", draftSolutionId = event.id)
            }
            is CalculatorEvent.EditRequested -> startEditing(event.text, event.followUp && context.hasSolution)
        }
        return CalculatorTransition(state, effects)
    }

    companion object {
        fun fromSession(session: CalculatorSession, solutionId: String?, shown: Int) = CalculatorModes(
            expression = session.expression,
            evaluated = session.evaluated,
            taskDraft = session.taskDraft,
            followUpDraft = session.followUpDraft,
            draftSolutionId = solutionId,
            seenShown = shown,
        )
    }
}
