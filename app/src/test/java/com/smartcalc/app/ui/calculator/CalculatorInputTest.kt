package com.smartcalc.app.ui.calculator

import org.junit.Assert.*
import org.junit.Test

class CalculatorInputTest {
    @Test fun reopeningRetryPreservesEditsToTextTasksAndFollowUps() {
        for (original in listOf("Исходная задача", "Исходное дополнение")) {
            assertEquals(original, retryEditorText("", original))
            val edited = "Исправлено: $original"
            assertEquals(edited, retryEditorText(edited, original))
        }
    }

    @Test fun reopeningFailedPhotoPreservesItsTextDraft() {
        assertEquals("", retryEditorText("", null))
        assertEquals("Review draft 123", retryEditorText("Review draft 123", null))
    }

    @Test fun operatorAfterErrorNeverBecomesAnAiTask() {
        val afterOperator = appendCalculatorToken("Ошибка", evaluated = true, token = "+")
        val expression = appendCalculatorToken(afterOperator, evaluated = false, token = "1")
        assertEquals("+1", expression)
        assertEquals(InputSubmission.CALCULATE, inputSubmission(expression, editing = false, followUp = false))
        assertEquals(1.0, ExpressionParser.evaluate(expression), 0.0)
    }

    @Test fun invalidStoredResultCannotBeSubmittedFromKeypad() {
        for (text in listOf("Ошибка+1", "Не числ", "Ошибка", "задача")) {
            assertEquals(InputSubmission.NONE, inputSubmission(text, editing = false, followUp = false))
        }
        assertEquals("", deleteCalculatorCharacter("Не число"))
        assertEquals("", deleteCalculatorCharacter("Ошибка"))
    }

    @Test fun resultContinuationAndExplicitTextEntryKeepTheirMeaning() {
        assertEquals("1E15+", appendCalculatorToken("1E15", true, "+"))
        assertEquals("7", appendCalculatorToken("42", true, "7"))
        assertEquals(InputSubmission.ASK, inputSubmission("Реши задачу", true, false))
        assertEquals(InputSubmission.FOLLOW_UP, inputSubmission("2+3", true, true))
        assertEquals(InputSubmission.NONE, inputSubmission("  ", true, false))
    }
}
