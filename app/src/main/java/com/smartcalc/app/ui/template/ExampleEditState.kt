package com.smartcalc.app.ui.template

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.smartcalc.app.data.library.LibraryVerification
import com.smartcalc.app.data.template.ExampleDraft
import com.smartcalc.app.data.template.ImageDraft
import com.smartcalc.app.data.template.TemplateExample

/** One draft owns both parts so edits cannot retain an earlier verification choice. */
internal class ExampleEditState(original: TemplateExample = TemplateExample()) {
    val library = original.library
    private var edited by mutableStateOf(false)
    private var verificationChoice by mutableStateOf<LibraryVerification?>(null)
    var enabled by mutableStateOf(library?.enabled ?: true)
    val condition = ExampleSectionState(original.condition, original.conditionImages, ::contentChanged)
    val solution = ExampleSectionState(original.solution, original.solutionImages, ::contentChanged)
    val answer = ExampleSectionState(original.answer, emptyList(), ::contentChanged)

    val verification: LibraryVerification
        get() = verificationChoice ?: if (edited) LibraryVerification.UNVERIFIED
            else library?.verification ?: LibraryVerification.UNVERIFIED

    val hasContentEdits: Boolean get() = edited

    fun verify(value: LibraryVerification) { verificationChoice = value }

    private fun contentChanged() {
        edited = true
        verificationChoice = null
    }

    fun toDraft() = ExampleDraft(
        condition.text, condition.images.toList(), solution.text, solution.images.toList(),
        libraryId = library?.id,
        expectedVersion = library?.version,
        verification = verificationChoice ?: LibraryVerification.UNVERIFIED.takeIf { edited },
        enabled = enabled,
        answer = answer.text,
    )
}

internal class ExampleSectionState(text: String, paths: List<String>, private val onChange: () -> Unit) {
    var text by mutableStateOf(text)
        private set
    private val mutableImages = mutableStateListOf<ImageDraft>().apply { addAll(paths.map { ImageDraft(path = it) }) }
    val images: List<ImageDraft> get() = mutableImages

    fun updateText(value: String) {
        if (text == value) return
        text = value
        onChange()
    }

    fun addImage(image: ImageDraft) {
        mutableImages += image
        onChange()
    }

    fun removeImage(index: Int) {
        mutableImages.removeAt(index)
        onChange()
    }
}
