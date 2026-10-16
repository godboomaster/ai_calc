package com.smartcalc.app.data.session

import com.smartcalc.app.data.history.FollowUp
import com.smartcalc.app.data.history.Solution
import com.smartcalc.app.data.history.SolutionStep
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SolutionSessionTest {
    @get:Rule val temp = TemporaryFolder()
    private val solution = Solution(id = "a", problem = "12+8", answer = "20",
        steps = listOf(SolutionStep("sum", "12+8=20")))

    private fun withSession(block: (SessionStore, SolutionSession) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = SessionStore(File(temp.newFolder(), "session.json"), scope)
            block(store, SolutionSession(store))
        } finally { runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() } }
    }

    @Test fun staleScreensCannotOverwriteAnotherSolutionOrFollowUpRevision() = withSession { store, session ->
        session.rememberSolution(solution)
        session.saveStepScroll("a", 0, 0, 17)
        val revision = solution.copy(followUps = listOf(FollowUp("why", "because")))
        session.updateFromRequest(revision, unread = true)
        val expected = store.state.value
        session.saveStepScroll("a", 0, 0, 99)
        session.saveSolutionScroll("other", 1, ListPosition(3, 9))
        session.saveAnswerScroll("a", 0, 45)
        session.toggleSolutionBlock(revision, "a", 0, "0:answer")
        assertEquals(expected, store.state.value)
    }

    @Test fun backgroundUpdateKeepsSameRevisionReadingAndCalculatorDrafts() = withSession { store, session ->
        session.rememberSolution(solution)
        session.setReadingTarget(solution, ReadingTarget.STEP, 1)
        session.saveStepScroll("a", 0, 1, 41)
        session.toggleSolutionBlock(solution, "a", 0, "0:question")
        val calculator = CalculatorSession(expression = "6*7", taskDraft = "new task",
            followUpDraft = "why", followUpSolutionId = "a")
        session.saveCalculator(calculator)
        val reading = store.state.value.reading
        session.updateFromRequest(solution, unread = true)
        assertEquals(reading, store.state.value.reading)
        assertEquals(calculator, store.state.value.calculator)
        assertTrue(store.state.value.unread)
        session.markViewed()
        assertFalse(store.state.value.unread)
    }

    @Test fun newFollowUpResetsPositionButKeepsPreviouslyCollapsedBlocks() = withSession { store, session ->
        session.rememberSolution(solution)
        session.setReadingTarget(solution, ReadingTarget.SOLUTION)
        session.saveSolutionScroll("a", 0, ListPosition(2, 12))
        session.toggleSolutionBlock(solution, "a", 0, "0:step:0")
        val revision = solution.copy(followUps = listOf(FollowUp("why", "because")))
        session.updateFromRequest(revision, unread = true)
        assertEquals(ReadingSession(solutionId = "a", followUpCount = 1,
            collapsedBlocks = setOf("0:step:0")), store.state.value.reading)
    }

    @Test fun selectingAnotherSolutionClearsOnlyItsFollowUpDraftAndUnreadFlag() = withSession { store, session ->
        session.rememberSolution(solution)
        session.saveCalculator(CalculatorSession(expression = "42", evaluated = true, taskDraft = "new",
            followUpDraft = "old follow-up", followUpSolutionId = "a"))
        session.updateFromRequest(solution, unread = true)
        session.rememberSolution(solution.copy(id = "b"))
        assertEquals(CalculatorSession(expression = "42", evaluated = true, taskDraft = "new"), store.state.value.calculator)
        assertEquals(ReadingSession(solutionId = "b"), store.state.value.reading)
        assertFalse(store.state.value.unread)
    }

    @Test fun reopeningSameSolutionResetsPositionButPreservesCollapseAndFollowUpDraft() = withSession { store, session ->
        session.rememberSolution(solution)
        session.saveStepScroll("a", 0, 0, 42)
        session.toggleSolutionBlock(solution, "a", 0, "0:answer")
        session.saveCalculator(CalculatorSession(followUpDraft = "keep", followUpSolutionId = "a"))
        session.rememberSolution(solution)
        assertEquals(ReadingSession(solutionId = "a", collapsedBlocks = setOf("0:answer")), store.state.value.reading)
        assertEquals("keep", store.state.value.calculator.followUpDraft)
    }

    @Test fun invalidBlocksAndStepIndexesDoNotEscapeCurrentSolution() = withSession { store, session ->
        session.rememberSolution(solution)
        session.setReadingTarget(solution, ReadingTarget.STEP, 500)
        assertEquals(1, store.state.value.reading.selectedStep) // answer after the last step
        session.setSolutionBlocksCollapsed(solution, "a", 0, setOf("0:answer", "foreign"), true)
        session.toggleSolutionBlock(solution, "a", 0, "foreign")
        session.saveStepScroll("a", 0, 0, -5)
        assertEquals(setOf("0:answer"), store.state.value.reading.collapsedBlocks)
        assertEquals(mapOf(0 to 0), store.state.value.reading.stepScroll)
    }

    @Test fun calculatorCannotAttachDraftToForeignReadingTarget() = withSession { store, session ->
        session.rememberSolution(solution)
        session.saveCalculator(CalculatorSession(expression = "3", taskDraft = "task",
            followUpDraft = "foreign", followUpSolutionId = "b"))
        assertEquals(CalculatorSession(expression = "3", taskDraft = "task"), store.state.value.calculator)
    }

    @Test fun restoreDropsMissingSolutionButPreservesArithmeticAndTaskDraft() = withSession { store, session ->
        store.update { SessionState(calculator = CalculatorSession(expression = "12", taskDraft = "keep",
            followUpDraft = "drop", followUpSolutionId = "missing"),
            reading = ReadingSession(solutionId = "missing"), unread = true) }
        val restored = session.restoreFor(listOf(solution))
        assertEquals(SessionState(calculator = CalculatorSession(expression = "12", taskDraft = "keep")), restored)
        assertEquals(restored, store.state.value)
    }
}
