package com.smartcalc.app.data.session

import com.smartcalc.app.data.history.FollowUp
import com.smartcalc.app.data.history.Solution
import com.smartcalc.app.data.history.SolutionStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SessionRestorationTest {
    private val solution = Solution(
        id = "saved", problem = "problem", answer = "answer",
        steps = listOf(SolutionStep("one", "body"), SolutionStep("two", "body")),
    )

    @Test fun `deleted solution clears assistant state but keeps calculation and new task draft`() {
        val state = SessionState(
            calculator = CalculatorSession("12+3", taskDraft = "new task", followUpDraft = "more", followUpSolutionId = "saved"),
            reading = ReadingSession(solutionId = "saved", target = ReadingTarget.STEP, selectedStep = 1),
            unread = true,
        ).restoredFor(emptyList())

        assertEquals("12+3", state.calculator.expression)
        assertEquals("new task", state.calculator.taskDraft)
        assertEquals("", state.calculator.followUpDraft)
        assertEquals(ReadingSession(), state.reading)
        assertFalse(state.unread)
    }

    @Test fun `existing solution keeps reading position and attached draft`() {
        val state = SessionState(
            calculator = CalculatorSession(followUpDraft = "explain", followUpSolutionId = "saved"),
            reading = ReadingSession("saved", target = ReadingTarget.STEP, selectedStep = 1,
                stepScroll = mapOf(0 to 45, 1 to 345), solutionScroll = ListPosition(2, 100)),
            unread = true,
        )
        assertEquals(state, state.restoredFor(listOf(solution)))
    }

    @Test fun `new followup revision discards old reading positions`() {
        val state = SessionState(reading = ReadingSession(
            "saved", target = ReadingTarget.STEP, selectedStep = 1,
            stepScroll = mapOf(1 to 400), solutionScroll = ListPosition(2, 100),
        ))
        val updated = solution.copy(followUps = listOf(FollowUp("why", "because")))
        assertEquals(ReadingSession(solutionId = "saved", followUpCount = 1), state.restoredFor(listOf(updated)).reading)
    }

    @Test fun `out of range positions are bounded and unrelated draft is discarded`() {
        val state = SessionState(
            calculator = CalculatorSession(followUpDraft = "old question", followUpSolutionId = "other"),
            reading = ReadingSession("saved", target = ReadingTarget.STEP, selectedStep = 99,
                stepScroll = mapOf(-1 to 50, 0 to -20, 1 to 70, 5 to 200), solutionScroll = ListPosition(999, 40)),
        ).restoredFor(listOf(solution))
        assertEquals(1, state.reading.selectedStep)
        assertEquals(mapOf(0 to 0, 1 to 70), state.reading.stepScroll)
        assertEquals(ListPosition(4, 0), state.reading.solutionScroll)
        assertEquals("", state.calculator.followUpDraft)
    }

    @Test fun `solution without steps restores brief answer instead of an empty step`() {
        val state = SessionState(reading = ReadingSession("saved", target = ReadingTarget.STEP))
        assertEquals(ReadingTarget.BRIEF, state.restoredFor(listOf(solution.copy(steps = emptyList()))).reading.target)
    }
}
