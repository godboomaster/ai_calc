package com.smartcalc.app.ui.calculator

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import com.smartcalc.app.data.session.CalculatorSession

/** Что калькулятор просит у помощника и системы. Экран передаёт сюда ViewModel и клавиатуру, тесты — запись вызовов. */
internal interface CalculatorHost {
    fun hideKeyboard()
    fun hideFromDisplay()
    fun showOnDisplay()
    fun toggleAssistant()
    fun solveText(text: String)
    fun askFollowUp(text: String)
    /** Удержание ✦ или свайп: вернуть ожидание, ответ или ошибку вместе с местом чтения. */
    fun restoreAssistant()
}

/** Помощник глазами калькулятора: значения из ViewModel на момент нажатия. */
internal data class AssistantStatus(
    /** Дисплей занят помощником: ожидание, ошибка или ответ. */
    val visible: Boolean = false,
    val solving: Boolean = false,
    val failed: Boolean = false,
    val hasSolution: Boolean = false,
    /** Повтор после ошибки — дополнение к ответу, а не новая задача. */
    val retryIsFollowUp: Boolean = false,
    val retryText: String? = null,
    /** Код кнопки ✦: null — настройка ещё не прочитана, пустой — кода нет. */
    val code: String? = "",
    val revealed: Boolean = false,
) {
    val concealed: Boolean get() = assistantConcealed(code, revealed)

    /** На дисплее ответ: удержание цифры открывает шаг, удержание «=» — всё решение. */
    val answerShown: Boolean get() = visible && !solving && !failed && hasSolution
}

/**
 * Режим и черновики калькулятора: обычный счёт, действия ИИ на цифрах, строка ввода задачи или дополнения.
 * Экран только рисует это состояние, а переходы между режимами проверяются unit-тестами.
 */
internal class CalculatorState(
    private val host: CalculatorHost,
    saved: CalculatorSession = CalculatorSession(),
    /** Решение на дисплее при открытии экрана: черновик дополнения относится к нему. */
    solutionId: String? = saved.followUpSolutionId,
    /** Счётчик выведенных задач при открытии экрана, см. [syncShown]. */
    shown: Int = 0,
) {
    var expression by mutableStateOf(saved.expression)
        private set
    var evaluated by mutableStateOf(saved.evaluated)
        private set
    /** Цифры 1–9 стали действиями ИИ. */
    var aiKeys by mutableStateOf(false)
        private set
    /** Открыта строка ввода с системной клавиатурой. */
    var editing by mutableStateOf(false)
        private set
    /** Строка ввода дописывает вопрос к ответу на дисплее, а не новую задачу. */
    var followUp by mutableStateOf(false)
        private set
    /** Недописанное дополнение не пропадает, если клавиатуру закрыли. */
    var followUpDraft by mutableStateOf(saved.followUpDraft)
        private set
    var taskDraft by mutableStateOf(saved.taskDraft)
        private set
    var field by mutableStateOf(TextFieldState())
        private set
    /** Строку ввода открыли поверх помощника: после закрытия на дисплей возвращается он. */
    private var returnToAssistant by mutableStateOf(false)
    private var draftSolutionId by mutableStateOf(solutionId)
    private var seenShown by mutableIntStateOf(shown)

    /** Результат под набираемым выражением. */
    val preview: String? by derivedStateOf { expressionPreview(expression) }

    /** Что попадает в файл сеанса. Учитывает и незакрытый редактор: force-stop не обязан вызывать lifecycle callbacks. */
    fun session(): CalculatorSession {
        val draft = field.text.toString()
        val expressionEditing = editing && !followUp && !returnToAssistant &&
            (draft.isBlank() || isArithmetic(draft))
        return CalculatorSession(
            expression = if (expressionEditing) draft else expression,
            evaluated = if (expressionEditing) false else evaluated,
            taskDraft = when {
                expressionEditing -> ""
                editing && !followUp -> draft
                else -> taskDraft
            },
            followUpDraft = if (editing && followUp) draft else followUpDraft,
            followUpSolutionId = draftSolutionId,
        )
    }

    fun input(token: String) {
        leaveAi()
        expression = appendCalculatorToken(expression, evaluated, token)
        evaluated = false
    }

    fun backspace() {
        leaveAi()
        expression = deleteCalculatorCharacter(expression)
        evaluated = false
    }

    /** «C» при показанном помощнике только убирает его; повторное «C» очищает расчёт. */
    fun clear(status: AssistantStatus) {
        leaveAi()
        if (status.visible) return
        expression = ""
        evaluated = false
    }

    /** «=» и кнопка отправки клавиатуры: посчитать выражение, отправить задачу или дополнение. */
    fun submit(status: AssistantStatus) {
        val text = (if (editing) field.text.toString() else expression).trim()
        if (!editing) leaveAi()
        // Код считается как обычное число: со стороны это просто «42 = 42».
        if (!editing && togglesAssistant(text, evaluated, status.code)) host.toggleAssistant()
        when (inputSubmission(text, editing, followUp)) {
            InputSubmission.NONE -> Unit
            InputSubmission.FOLLOW_UP -> {
                // Дополнение уходит ИИ целиком, даже если похоже на выражение: «а если 2+3?».
                followUp = false
                followUpDraft = ""
                closeEditor(saveDraft = false)
                host.askFollowUp(text)
            }
            InputSubmission.CALCULATE -> {
                if (editing) taskDraft = ""
                closeEditor(saveDraft = false)
                expression = try {
                    ExpressionParser.format(ExpressionParser.evaluate(text))
                } catch (e: Exception) {
                    "Ошибка"
                }
                evaluated = true
            }
            InputSubmission.ASK -> {
                // «Ошибка» и «Не число» — результаты, а не задачи: их ИИ не отправляем.
                closeEditor(saveDraft = false)
                host.solveText(text)
                taskDraft = ""
            }
        }
    }

    /**
     * Нажатие на строку ввода: продолжить выражение, поправить задачу после ошибки или, если на дисплее
     * ответ ИИ, задать к нему дополнительный вопрос. Новая задача вместо дополнения — после «C».
     */
    fun tapDisplay(status: AssistantStatus) {
        // Спрятанный помощник не выдаёт себя строкой ввода: дисплей ведёт себя как у обычного калькулятора.
        if (status.concealed) return
        when {
            !status.visible -> startEditing(
                taskDraft.ifEmpty { if (evaluated && !isArithmetic(expression)) "" else expression }, status,
            )
            status.solving -> Unit
            status.failed -> {
                val asFollowUp = status.retryIsFollowUp && status.hasSolution
                val draft = if (asFollowUp) followUpDraft else taskDraft
                startEditing(retryEditorText(draft, status.retryText), status, asFollowUp)
            }
            else -> startEditing(followUpDraft, status, asFollowUp = true)
        }
    }

    /** «Изменить условие» и «Дополнить» на экране решения: строка ввода открывается с условием или пустой. */
    fun edit(text: String, asFollowUp: Boolean, status: AssistantStatus) {
        aiKeys = false
        startEditing(text, status, asFollowUp = asFollowUp && status.hasSolution)
    }

    /** Клавиатура убрана без отправки: недописанное дополнение откладывается, ответ возвращается на дисплей. */
    fun stopEditing() = closeEditor(saveDraft = true)

    /** ✕ над строкой ввода: написать новую задачу вместо дополнения. */
    fun dropFollowUp() {
        followUp = false
    }

    /** ✦: цифры становятся действиями ИИ или снова цифрами. */
    fun toggleAiKeys() {
        stopEditing()
        aiKeys = !aiKeys
    }

    fun closeAiKeys() {
        aiKeys = false
    }

    fun restoreAssistant() {
        stopEditing()
        aiKeys = false
        host.restoreAssistant()
    }

    /** Код набран повторно или приложение запущено со спрятанной ✦ — помощник прячется целиком. */
    fun conceal() {
        stopEditing()
        aiKeys = false
        host.hideFromDisplay()
    }

    /**
     * Пошло решение или открыта запись из истории — снова цифры: на них шаги решения. Счётчик, а не
     * видимость: дисплей мог показывать ответ и до этого, а возврат из настроек клавиши ИИ не сбрасывает.
     */
    fun syncShown(shown: Int) {
        if (shown == seenShown) return
        seenShown = shown
        aiKeys = false
        editing = false
        followUp = false
    }

    /** Черновик дополнения относится к своей задаче. Возврат с экрана шага задачу не меняет, и черновик остаётся. */
    fun syncSolution(id: String?) {
        if (draftSolutionId == id) return
        followUpDraft = ""
        draftSolutionId = id
    }

    /** Свайп вверх по дисплею возвращает помощника, когда тот убран, но ему есть что показать. */
    fun canSwipeToRestore(status: AssistantStatus, swipeEnabled: Boolean) =
        swipeEnabled && !status.concealed && !status.visible && !editing && !aiKeys &&
            (status.solving || status.hasSolution || status.failed)

    /** Пока строка уже с клавиатурой или идёт решение, нажатие на дисплей ничего не делает. */
    fun displayTappable(status: AssistantStatus) = !editing && !(status.visible && status.solving)

    /** Меню, редактор и экраны поверх калькулятора не означают, что результат прочитан. */
    fun resultInView(status: AssistantStatus) = status.visible && !status.solving && !editing && !aiKeys

    /** Подключение меняют, пока открыт помощник или на дисплее ошибка: «Повторить запрос» уйдёт через выбранное. */
    fun offersConnectionChoice(status: AssistantStatus) =
        !status.concealed && (aiKeys || (status.visible && status.failed))

    /** Числа задачи можно набрать на калькуляторе до поиска — например, 12+3,5+40. */
    fun searchQuery(status: AssistantStatus) =
        if (!status.visible && !evaluated) searchNumbers(expression) else ""

    /** Клавиша нажата, пока на дисплее ИИ: калькулятор возвращается к обычному счёту. */
    private fun leaveAi() {
        aiKeys = false
        host.hideFromDisplay()
    }

    private fun startEditing(text: String, status: AssistantStatus, asFollowUp: Boolean = false) {
        returnToAssistant = status.visible
        host.hideFromDisplay()
        aiKeys = false
        field = TextFieldState(text, TextRange(text.length))
        followUp = asFollowUp
        editing = true
    }

    private fun closeEditor(saveDraft: Boolean) {
        host.hideKeyboard()
        if (!editing) return
        editing = false
        if (saveDraft) {
            val draft = field.text.toString()
            if (followUp) {
                followUpDraft = draft
            } else if (!returnToAssistant && (draft.isBlank() || isArithmetic(draft))) {
                expression = draft
                evaluated = false
                taskDraft = ""
            } else {
                taskDraft = draft
            }
            if (returnToAssistant) host.showOnDisplay()
        }
        followUp = false
    }

    companion object {
        /** Режим, черновики и строка ввода переживают поворот экрана и переход на другие экраны. */
        fun saver(host: CalculatorHost): Saver<CalculatorState, Any> = mapSaver(
            save = { state ->
                mapOf(
                    "expression" to state.expression,
                    "evaluated" to state.evaluated,
                    "aiKeys" to state.aiKeys,
                    "editing" to state.editing,
                    "followUp" to state.followUp,
                    "followUpDraft" to state.followUpDraft,
                    "taskDraft" to state.taskDraft,
                    "field" to with(TextFieldState.Saver) { save(state.field) },
                    "returnToAssistant" to state.returnToAssistant,
                    "draftSolutionId" to state.draftSolutionId,
                    "seenShown" to state.seenShown,
                )
            },
            restore = { saved ->
                CalculatorState(
                    host,
                    CalculatorSession(
                        expression = saved["expression"] as String,
                        evaluated = saved["evaluated"] as Boolean,
                        taskDraft = saved["taskDraft"] as String,
                        followUpDraft = saved["followUpDraft"] as String,
                    ),
                    solutionId = saved["draftSolutionId"] as String?,
                    shown = saved["seenShown"] as Int,
                ).apply {
                    aiKeys = saved["aiKeys"] as Boolean
                    editing = saved["editing"] as Boolean
                    followUp = saved["followUp"] as Boolean
                    saved["field"]?.let(TextFieldState.Saver::restore)?.let { field = it }
                    returnToAssistant = saved["returnToAssistant"] as Boolean
                }
            },
        )
    }
}
