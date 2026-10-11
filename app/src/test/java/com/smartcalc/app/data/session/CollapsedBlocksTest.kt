package com.smartcalc.app.data.session

import com.smartcalc.app.data.history.FollowUp
import com.smartcalc.app.data.history.Solution
import com.smartcalc.app.data.history.SolutionStep
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class CollapsedBlocksTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val solution = Solution(id = "saved", problem = "problem", answer = "answer",
        steps = listOf(SolutionStep("one", "body"), SolutionStep("two", "body")),
        followUps = listOf(FollowUp("why", "because", listOf(SolutionStep("one", "body")))))

    private fun saved(): SessionState = json.decodeFromString("""
        {"reading":{"solutionId":"saved","followUpCount":1,
        "collapsedBlocks":["0:question","0:step:1","1:step:0","1:answer"],
        "solutionScroll":{"index":2,"offset":10}}}
    """)

    private fun collapsed(state: SessionState): Set<String> =
        json.parseToJsonElement(json.encodeToString(state)).jsonObject["reading"]!!.jsonObject["collapsedBlocks"]
            ?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()

    @Test fun `restoring a solution retains independently collapsed main and followup cards`() {
        val restored = saved().restoredFor(listOf(solution))
        assertEquals(setOf("0:question", "0:step:1", "1:step:0", "1:answer"), collapsed(restored))
        assertEquals(ListPosition(2, 10), restored.reading.solutionScroll)
    }

    @Test fun `new followup opens expanded and retains earlier collapsed cards`() {
        val updated = solution.copy(followUps = solution.followUps + FollowUp("more", "new answer"))
        val restored = saved().restoredFor(listOf(updated))
        assertEquals(setOf("0:question", "0:step:1", "1:step:0", "1:answer"), collapsed(restored))
        assertEquals(null, restored.reading.solutionScroll)
        assertEquals(2, restored.reading.followUpCount)
    }

    @Test fun `removed steps and followups cannot leave stale collapsed cards`() {
        val updated = solution.copy(steps = solution.steps.take(1), followUps = emptyList())
        assertEquals(setOf("0:question"), collapsed(saved().restoredFor(listOf(updated))))
    }
}
