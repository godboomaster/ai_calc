package com.smartcalc.app.data.session

import com.smartcalc.app.data.history.Solution

/** Group 0 is the original response; later groups are append-only followups. */
internal object SolutionBlockId {
    fun question(group: Int = 0) = "$group:question"
    fun step(group: Int, index: Int) = "$group:step:$index"
    fun answer(group: Int = 0) = "$group:answer"
}

internal fun Solution.blockIds(followUpCount: Int = followUps.size): Set<String> = buildSet {
    add(SolutionBlockId.question())
    steps.indices.forEach { add(SolutionBlockId.step(0, it)) }
    add(SolutionBlockId.answer())
    followUps.take(followUpCount.coerceAtLeast(0)).forEachIndexed { index, followUp ->
        val group = index + 1
        add(SolutionBlockId.question(group))
        followUp.steps.indices.forEach { add(SolutionBlockId.step(group, it)) }
        add(SolutionBlockId.answer(group))
    }
}

/** Preserve card choices for this solution even when a new response resets reading positions. */
internal fun ReadingSession.forSolution(solution: Solution, resetPosition: Boolean = false): ReadingSession {
    if (solutionId != solution.id) return ReadingSession(solution.id, solution.followUps.size)
    val retained = collapsedBlocks.intersect(solution.blockIds(followUpCount))
    return if (resetPosition || followUpCount != solution.followUps.size) {
        ReadingSession(solution.id, solution.followUps.size, collapsedBlocks = retained)
    } else copy(collapsedBlocks = retained)
}
