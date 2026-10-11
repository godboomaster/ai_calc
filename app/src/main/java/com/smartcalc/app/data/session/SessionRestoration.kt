package com.smartcalc.app.data.session

import com.smartcalc.app.data.history.Solution

/** Восстановление использует только ссылки на существующие записи истории. */
internal fun SessionState.restoredFor(history: List<Solution>): SessionState {
    val state = normalizeSessionState(this)
    val solution = history.firstOrNull { it.id == state.reading.solutionId }
    val calculator = if (solution != null && state.calculator.followUpSolutionId == solution.id) {
        state.calculator
    } else {
        state.calculator.copy(followUpDraft = "", followUpSolutionId = null)
    }
    if (solution == null) return state.copy(calculator = calculator, reading = ReadingSession(), unread = false)
    if (state.reading.followUpCount != solution.followUps.size) {
        return state.copy(calculator = calculator, reading = state.reading.forSolution(solution))
    }
    val itemCount = 3 + (if (solution.template != null || solution.format != null) 1 else 0) +
        solution.steps.size + solution.followUps.sumOf { it.steps.size + 2 }
    val position = state.reading.solutionScroll?.let {
        val index = it.index.coerceIn(0, itemCount - 1)
        ListPosition(index, if (index == it.index) it.offset else 0)
    }
    return state.copy(
        calculator = calculator,
        reading = state.reading.forSolution(solution).copy(
            // Index after the last step is the answer, including index 0 for a response without steps.
            selectedStep = state.reading.selectedStep.coerceIn(0, solution.latestSteps.size),
            stepScroll = state.reading.stepScroll.filterKeys { it in 0..solution.latestSteps.size },
            solutionScroll = position,
        ),
    )
}
