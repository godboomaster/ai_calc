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
