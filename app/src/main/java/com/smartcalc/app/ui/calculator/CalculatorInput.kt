package com.smartcalc.app.ui.calculator

/** Input rules shared by the Compose keypad and its regression tests. */
internal fun appendCalculatorToken(expression: String, evaluated: Boolean, token: String): String {
    val base = if (!isArithmetic(expression) ||
        (evaluated && (token.first().isDigit() || token == "("))) "" else expression
    return base + token
}

internal fun deleteCalculatorCharacter(expression: String): String =
    if (!isArithmetic(expression)) "" else expression.dropLast(1)

internal fun retryEditorText(draft: String, retryText: String?): String =
    draft.ifEmpty { retryText.orEmpty() }

internal enum class InputSubmission { NONE, CALCULATE, ASK, FOLLOW_UP }

internal fun inputSubmission(text: String, editing: Boolean, followUp: Boolean): InputSubmission = when {
    text.isBlank() -> InputSubmission.NONE
    editing && followUp -> InputSubmission.FOLLOW_UP
    isArithmetic(text) -> InputSubmission.CALCULATE
    editing -> InputSubmission.ASK
    else -> InputSubmission.NONE
}

private const val ARITHMETIC = "0123456789.,+-−×÷*/()%Ee∞"
internal fun isArithmetic(text: String) = text.all { it in ARITHMETIC || it.isWhitespace() }

/** Результат под набираемым выражением; незаконченное «2+» и не выражение его не дают. */
internal fun expressionPreview(expression: String): String? =
    if (expression.isBlank() || expression.last() in "+−×÷(" || !isArithmetic(expression)) null
    else try {
        ExpressionParser.format(ExpressionParser.evaluate(expression))
    } catch (e: Exception) {
        null
    }

/** Код, скрывающий кнопку ✦: только цифры, чтобы его можно было набрать на клавишах калькулятора. */
internal val ASSISTANT_CODE_LENGTH = 2..12

internal fun isValidAssistantCode(code: String) =
    code.length in ASSISTANT_CODE_LENGTH && code.all { it in '0'..'9' }

/** Кнопка ✦ видна, если кода нет или его набрали. Пока настройка не прочитана ([code] = null), её нет. */
internal fun assistantButtonVisible(code: String?, revealed: Boolean) = code != null && (code.isEmpty() || revealed)

/** Помощник спрятан кодом: недоступны и кнопка, и строка ввода задачи, и возврат к ответу. */
internal fun assistantConcealed(code: String?, revealed: Boolean) = !code.isNullOrEmpty() && !revealed

/**
 * «=» после набранного на клавишах кода показывает или снова прячет ✦. Повторное «=» по результату
 * ([evaluated]) ничего не переключает: иначе «42 = =» открыло бы и тут же закрыло кнопку.
 */
internal fun togglesAssistant(expression: String, evaluated: Boolean, code: String?) =
    !code.isNullOrEmpty() && !evaluated && expression == code
