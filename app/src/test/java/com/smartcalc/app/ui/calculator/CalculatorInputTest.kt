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

    @Test fun assistantButtonIsAlwaysVisibleWithoutCode() {
        assertTrue(assistantButtonVisible(code = "", revealed = false))
        assertFalse(assistantConcealed(code = "", revealed = false))
        assertFalse(togglesAssistant("42", evaluated = false, code = ""))
    }

    @Test fun assistantButtonWaitsForStoredSetting() {
        assertFalse(assistantButtonVisible(code = null, revealed = false))
        assertFalse(assistantConcealed(code = null, revealed = false))
        assertFalse(togglesAssistant("42", evaluated = false, code = null))
    }

    @Test fun typedCodeAndEqualsTogglesAssistant() {
        assertFalse(assistantButtonVisible(code = "42", revealed = false))
        assertTrue(assistantConcealed(code = "42", revealed = false))
        val typed = appendCalculatorToken(appendCalculatorToken("", evaluated = false, token = "4"), evaluated = false, token = "2")
        assertTrue(togglesAssistant(typed, evaluated = false, code = "42"))
        assertTrue(assistantButtonVisible(code = "42", revealed = true))
        assertFalse(assistantConcealed(code = "42", revealed = true))
    }

    @Test fun quickHideIsOfferedOnlyWhileCodeIsTyped() {
        assertTrue(quickHideAvailable(code = "42", revealed = true))
        assertFalse(quickHideAvailable(code = "42", revealed = false))
        assertFalse(quickHideAvailable(code = "", revealed = true))
        assertFalse(quickHideAvailable(code = null, revealed = false))
    }

    @Test fun onlyExactTypedCodeToggles() {
        // Повторное «=» по результату «42» не должно сразу же спрятать кнопку обратно.
        assertFalse(togglesAssistant("42", evaluated = true, code = "42"))
        for (text in listOf("4", "420", "042", "40+2", "4 2")) {
            assertFalse(text, togglesAssistant(text, evaluated = false, code = "42"))
        }
    }

    @Test fun assistantCodeIsShortDigitsOnly() {
        assertTrue(isValidAssistantCode("42"))
        assertTrue(isValidAssistantCode("123456789012"))
        for (code in listOf("", "4", "1234567890123", "4a", "4 2", "4.2", "-42")) {
            assertFalse(code, isValidAssistantCode(code))
        }
    }
}
