package com.smartcalc.app.ui.template

import com.smartcalc.app.data.library.LibraryMetadata
import com.smartcalc.app.data.library.LibraryOrigin
import com.smartcalc.app.data.library.LibraryOriginKind
import com.smartcalc.app.data.library.LibraryVerification
import com.smartcalc.app.data.template.ImageDraft
import com.smartcalc.app.data.template.TemplateExample
import org.junit.Assert.*
import org.junit.Test

class ExampleEditStateTest {
    private fun draft() = ExampleEditState(TemplateExample(
        condition = "Скорость 12", conditionImages = listOf("condition.jpg"), solution = "24 м",
        library = LibraryMetadata(id = "stable", version = 3, origin = LibraryOrigin(LibraryOriginKind.MANUAL),
            verification = LibraryVerification.VERIFIED_BY_USER),
    ))

    @Test fun unchangedDraftDoesNotPretendUserVerifiedAgain() {
        val state = draft()
        assertEquals(LibraryVerification.VERIFIED_BY_USER, state.verification)
        assertNull(state.toDraft().verification)
        assertEquals("stable", state.toDraft().libraryId)
        assertEquals(3, state.toDraft().expectedVersion)
        state.enabled = false
        assertNull(state.toDraft().verification)
        assertEquals(false, state.toDraft().enabled)
    }

    @Test fun verificationAppliesOnlyToContentReviewedAfterLastEdit() {
        val state = draft()
        state.condition.updateText("Скорость 18")
        assertEquals(LibraryVerification.UNVERIFIED, state.verification)
        assertEquals(LibraryVerification.UNVERIFIED, state.toDraft().verification)
        state.verify(LibraryVerification.VERIFIED_BY_USER)
        assertEquals(LibraryVerification.VERIFIED_BY_USER, state.toDraft().verification)
        state.solution.updateText("36 м")
        assertEquals(LibraryVerification.UNVERIFIED, state.toDraft().verification)
    }

    @Test fun addingOrRemovingImagesInvalidatesExplicitVerification() {
        val state = draft()
        state.verify(LibraryVerification.VERIFIED_BY_USER)
        state.solution.addImage(ImageDraft(bytes = byteArrayOf(1, 2)))
        assertEquals(LibraryVerification.UNVERIFIED, state.verification)
        state.verify(LibraryVerification.VERIFIED_BY_USER)
        state.condition.removeImage(0)
        assertEquals(LibraryVerification.UNVERIFIED, state.toDraft().verification)
        assertTrue(state.toDraft().conditionImages.isEmpty())
    }
}
