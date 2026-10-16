package com.smartcalc.app.data.session

import com.smartcalc.app.data.history.Solution

/** Shared session rules for UI and background completion. SessionStore remains the only file writer. */
class SolutionSession(private val store: SessionStore) {
    val state = store.state

    fun restoreFor(history: List<Solution>): SessionState {
        val restored = state.value.restoredFor(history)
        store.update { restored }
        return restored
    }

    fun saveCalculator(value: CalculatorSession) {
        store.update { saved ->
            val attached = value.followUpSolutionId != null && value.followUpSolutionId == saved.reading.solutionId
            saved.copy(calculator = if (attached) value else value.copy(followUpDraft = "", followUpSolutionId = null))
        }
    }

    fun markViewed() = store.update { it.copy(unread = false) }

    fun rememberSolution(solution: Solution?) {
        store.update { saved ->
            saved.copy(
                reading = solution?.let { saved.reading.forSolution(it, resetPosition = true) } ?: ReadingSession(),
                calculator = if (saved.calculator.followUpSolutionId == solution?.id) saved.calculator
                    else saved.calculator.copy(followUpDraft = "", followUpSolutionId = null),
                unread = false,
            )
        }
    }

    /** Background completion preserves reading positions for an unchanged solution revision. */
    fun updateFromRequest(solution: Solution?, unread: Boolean) {
        store.update { saved ->
            saved.copy(reading = solution?.let { saved.reading.forSolution(it) } ?: ReadingSession(), unread = unread)
        }
    }

    fun setReadingTarget(solution: Solution?, target: ReadingTarget, step: Int = state.value.reading.selectedStep) {
        solution ?: return
        updateReading(solution.id, solution.followUps.size) {
            it.copy(target = target, selectedStep = step.coerceIn(0, solution.latestSteps.size))
        }
    }

    fun saveStepScroll(id: String, revision: Int, index: Int, offset: Int) = updateReading(id, revision) {
        it.copy(stepScroll = it.stepScroll + (index to offset.coerceAtLeast(0)))
    }

    fun saveSolutionScroll(id: String, revision: Int, position: ListPosition) = updateReading(id, revision) {
        it.copy(solutionScroll = position)
    }

    fun saveAnswerScroll(id: String, revision: Int, offset: Int) = updateReading(id, revision) {
        it.copy(answerScroll = offset.coerceAtLeast(0))
    }

    fun setSolutionBlocksCollapsed(solution: Solution?, id: String, revision: Int, blocks: Set<String>, collapsed: Boolean) {
        val current = solution?.takeIf { it.id == id && it.followUps.size == revision } ?: return
        val validBlocks = blocks.intersect(current.blockIds())
        updateReading(id, revision) {
            it.copy(collapsedBlocks = if (collapsed) it.collapsedBlocks + validBlocks else it.collapsedBlocks - validBlocks)
        }
    }

    fun toggleSolutionBlock(solution: Solution?, id: String, revision: Int, block: String) {
        val current = solution?.takeIf { it.id == id && it.followUps.size == revision } ?: return
        if (block !in current.blockIds()) return
        updateReading(id, revision) {
            it.copy(collapsedBlocks = if (block in it.collapsedBlocks) it.collapsedBlocks - block else it.collapsedBlocks + block)
        }
    }

    private fun updateReading(id: String, revision: Int, update: (ReadingSession) -> ReadingSession) {
        store.update { saved ->
            if (saved.reading.solutionId == id && saved.reading.followUpCount == revision) {
                saved.copy(reading = update(saved.reading))
            } else saved // An outgoing screen must not overwrite a new task/revision.
        }
    }
}
