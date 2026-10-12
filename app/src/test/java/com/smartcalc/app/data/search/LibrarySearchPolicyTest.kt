package com.smartcalc.app.data.search

import com.smartcalc.app.data.history.Solution
import com.smartcalc.app.data.library.LibraryMetadata
import com.smartcalc.app.data.library.LibraryOrigin
import com.smartcalc.app.data.library.LibraryOriginKind
import com.smartcalc.app.data.library.LibraryVerification
import com.smartcalc.app.data.template.TaskTemplate
import com.smartcalc.app.data.template.TemplateExample
import org.junit.Assert.*
import org.junit.Test

class LibrarySearchPolicyTest {
    private fun example(id: String, enabled: Boolean = true, verification: LibraryVerification = LibraryVerification.UNVERIFIED) =
        TemplateExample(condition = "Скорость $id", solution = "Ответ: 24 м", library = LibraryMetadata(
            id = id, origin = LibraryOrigin(LibraryOriginKind.MANUAL), enabled = enabled, verification = verification,
        ))

    private fun section(vararg examples: TemplateExample) = TaskTemplate(name = "Движение", examples = examples.toList())

    @Test fun unverifiedAndVerifiedAreSearchableWithMetadataWhileDisabledAndRejectedAreNot() {
        val unverified = example("12")
        val verified = example("13", verification = LibraryVerification.VERIFIED_BY_USER)
        val disabled = example("14", enabled = false)
        val rejected = example("15", verification = LibraryVerification.REJECTED)
        val indexed = TaskSearch.entries(listOf(section(unverified, verified, disabled, rejected)), emptyList(), emptyList())
        assertEquals(listOf(unverified.library, verified.library), indexed.map { it.library })
        assertEquals(2, TaskSearch.search(indexed, "скорость").size)
    }

    @Test fun disablingAndRejectingDoNotResurfacePreviouslyOpenedSnapshots() {
        val original = example("12")
        val saved = FoundSolution.of(TaskSearch.entries(listOf(section(original)), emptyList(), emptyList()).single())
        val own = Solution(problem = "Скорость 18", answer = "36")
        listOf(
            original.copy(library = original.library!!.copy(enabled = false)),
            original.copy(library = original.library!!.copy(verification = LibraryVerification.REJECTED)),
        ).forEach { blocked ->
            val indexed = TaskSearch.entries(listOf(section(blocked)), emptyList(), listOf(saved, own))
            assertEquals(listOf(own), indexed.map { it.history })
        }
        val enabled = TaskSearch.entries(listOf(section(original)), emptyList(), listOf(saved, own))
        assertEquals(2, enabled.size)
        assertEquals(saved, enabled.first().history)
    }

    @Test fun updatedExampleDoesNotIndexSupersededSnapshotAndTemplatesStayIndependent() {
        val original = example("12")
        val saved = FoundSolution.of(TaskSearch.entries(listOf(section(original)), emptyList(), emptyList()).single())
        val revised = original.copy(condition = "Скорость 30", library = original.library!!.copy(version = 2))
        val indexed = TaskSearch.entries(listOf(section(revised)), emptyList(), listOf(saved))
        assertEquals(listOf("Скорость 30"), indexed.map { it.condition })
        val disabled = original.copy(library = original.library!!.copy(enabled = false))
        val independentTemplate = original.copy(library = null)
        val withTemplate = TaskSearch.entries(listOf(section(disabled)), listOf(section(independentTemplate)), listOf(saved))
        assertEquals(FoundSource.TEMPLATE, withTemplate.single().source)
        assertNull(withTemplate.single().library)
    }
}
