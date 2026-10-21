package com.smartcalc.app.data.request

import com.smartcalc.app.data.api.ApiException
import com.smartcalc.app.data.library.LibraryMetadata
import com.smartcalc.app.data.search.LibraryCandidate
import com.smartcalc.app.data.search.SimilarTasks
import com.smartcalc.app.data.search.TaskDescription
import com.smartcalc.app.data.settings.ApiSettings
import com.smartcalc.app.data.template.TaskTemplate
import com.smartcalc.app.data.template.TemplateExample
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimilarTaskPickerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val settings = ApiSettings(apiKey = "fixture")
    private val library: List<LibraryCandidate> = SimilarTasks.candidates(listOf(
        TaskTemplate(id = "nyquist", name = "Критерий Найквиста", updated = 0, examples = listOf(
            TemplateExample(condition = "Исследовать устойчивость замкнутой системы по АФЧХ разомкнутой", solution = "Годограф не охватывает точку",
                library = LibraryMetadata(id = "n1")))),
        TaskTemplate(id = "laplace", name = "Преобразование Лапласа", updated = 0, examples = listOf(
            TemplateExample(condition = "Найти изображение функции", solution = "По таблице", library = LibraryMetadata(id = "l1")))),
    ))

    private var loads = 0
    private var described: Pair<PreparedRequest, List<String>>? = null
    private var describe: suspend () -> TaskDescription = { TaskDescription(sections = listOf("Критерий Найквиста")) }
    private var candidates: List<LibraryCandidate> = library

    private val picker: SimilarTaskPicker = SimilarTaskPicker(scope,
        load = { input ->
            loads++
            if (input is TaskInput.Text) PreparedRequest(text = input.problem) else PreparedRequest(images = listOf("AAAA"))
        },
        library = { candidates },
        describe = { _, prepared, sections -> described = prepared to sections; describe() },
        describeError = { it.message.orEmpty() }, compute = EmptyCoroutineContext)

    @After fun tearDown() = scope.cancel()

    @Test fun photoIsReadOnceAndCandidatesOfNamedTypeAreOffered() {
        picker.start(TaskInput.Image(byteArrayOf(1)), settings)
        val choice = picker.state.value!!
        assertEquals(SimilarChoice.Status.READY, choice.status)
        assertEquals(listOf("AAAA"), choice.prepared?.images)
        assertEquals(listOf("Критерий Найквиста", "Преобразование Лапласа"), described?.second)
        assertEquals(listOf("n1"), choice.candidates.map { it.example.library?.id })
        assertTrue(choice.selected.isEmpty())
        assertEquals(1, loads)
    }

    @Test fun userMarksTasksAndTakesTheChoiceOnce() {
        picker.start(TaskInput.Text("Устойчива ли система по Найквисту?"), settings)
        val id = picker.state.value!!.candidates.single().id
        picker.toggle(id)
        assertEquals(listOf(id), picker.state.value!!.chosen.map(LibraryCandidate::id))
        picker.toggle(id)
        picker.toggle(id)
        val taken = picker.take()!!
        assertEquals(setOf(id), taken.selected)
        assertNull(picker.state.value)
        assertNull("A choice is sent at most once", picker.take())
    }

    @Test fun textStillFindsTasksByWordsWhenModelFails() {
        describe = { throw ApiException("offline") }
        picker.start(TaskInput.Text("Найти изображение функции по Лапласу"), settings)
        val choice = picker.state.value!!
        assertEquals(SimilarChoice.Status.READY, choice.status)
        assertTrue(choice.approximate)
        assertEquals(listOf("l1"), choice.candidates.map { it.example.library?.id })
    }

    @Test fun photoFailureCanBeRetriedWithoutReadingThePhotoAgain() {
        describe = { throw ApiException("Нет ответа") }
        picker.start(TaskInput.Image(byteArrayOf(1)), settings)
        assertEquals(SimilarChoice.Status.FAILED, picker.state.value!!.status)
        assertEquals("Нет ответа", picker.state.value!!.error)
        describe = { TaskDescription(sections = listOf("Преобразование Лапласа")) }
        picker.retry(settings)
        assertEquals(SimilarChoice.Status.READY, picker.state.value!!.status)
        assertEquals(1, loads)
    }

    @Test fun missingSettingsFailInsteadOfGuessing() {
        picker.start(TaskInput.Image(byteArrayOf(1)), null)
        assertEquals(SimilarChoice.Status.FAILED, picker.state.value!!.status)
    }

    @Test fun emptyLibraryAsksNothing() {
        candidates = emptyList()
        picker.start(TaskInput.Text("Задача"), settings)
        assertEquals(SimilarChoice.Status.READY, picker.state.value!!.status)
        assertTrue(picker.state.value!!.candidates.isEmpty())
        assertNull(described)
    }

    @Test fun cancelledSearchCannotReappear() {
        val reply = CompletableDeferred<TaskDescription>()
        describe = { reply.await() }
        picker.start(TaskInput.Text("Задача"), settings)
        assertEquals(SimilarChoice.Status.SEARCHING, picker.state.value!!.status)
        picker.take()
        reply.complete(TaskDescription(sections = listOf("Критерий Найквиста")))
        assertNull(picker.state.value)
    }
}
