package com.smartcalc.app.data.library

import com.smartcalc.app.data.history.HistoryStore
import com.smartcalc.app.data.history.Solution
import com.smartcalc.app.data.library.source.LibrarySourceStore
import com.smartcalc.app.data.search.FoundSolution
import com.smartcalc.app.data.search.SearchEntry
import com.smartcalc.app.data.search.TaskSearch
import com.smartcalc.app.data.template.*
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

/** ViewModel-lifetime collection operations and selection; stores keep their application lifetime. */
class ExampleWorkspace(
    private val templateStore: TemplateStore,
    private val libraryStore: TemplateStore,
    private val historyStore: HistoryStore,
    sources: LibrarySourceStore,
    scope: CoroutineScope,
) {
    val templates = templateStore.items
    val library = libraryStore.items
    /** Всё, по чему ищет поиск; считается, только пока открыт экран поиска. null — ещё не собрано. */
    val searchEntries: StateFlow<List<SearchEntry>?> =
        combine(libraryStore.items, templateStore.items, historyStore.items, sources.state) { base, templates, solved, sources ->
            TaskSearch.entries(base, templates, solved, sources)
        }
            // Building fingerprints reads local example images; keep that work off the CPU pool.
            .flowOn(Dispatchers.IO)
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Шаблон, выбранный для следующей задачи. Действует на одну задачу: после ответа снимается,
     * после ошибки остаётся — повтор пойдёт с ним же.
     */
    private val armedId = MutableStateFlow<String?>(null)
    val armedTemplate: StateFlow<TaskTemplate?> = combine(templateStore.items, armedId) { list, id ->
        list.firstOrNull { it.id == id }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    fun armTemplate(template: TaskTemplate) {
        armedId.value = template.id
    }

    fun disarmTemplate() {
        armedId.value = null
    }

    private fun store(kind: TemplateKind) = when (kind) {
        TemplateKind.TEMPLATES -> templateStore
        TemplateKind.LIBRARY -> libraryStore
    }

    fun items(kind: TemplateKind): StateFlow<List<TaskTemplate>> = store(kind).items
    fun storageError(kind: TemplateKind): StateFlow<String?> = store(kind).loadError

    suspend fun saveTemplate(
        id: String?,
        name: String,
        requirements: String,
        examples: List<ExampleDraft>,
        kind: TemplateKind = TemplateKind.TEMPLATES,
        expectedSection: TaskTemplate? = null,
    ) = store(kind).save(id, name, requirements, examples, expectedSection)

    suspend fun deleteTemplate(id: String, kind: TemplateKind = TemplateKind.TEMPLATES, expectedSection: TaskTemplate? = null) =
        store(kind).delete(id, expectedSection)

    /** Файл шаблонов (или разделов базы) с [ids] (null — все) — для сохранения или отправки. */
    suspend fun exportTemplates(
        ids: Collection<String>?,
        out: OutputStream,
        format: TemplateFormat,
        kind: TemplateKind = TemplateKind.TEMPLATES,
    ): Int = store(kind).export(ids, out, format)

    suspend fun importTemplates(input: InputStream, kind: TemplateKind = TemplateKind.TEMPLATES, sourceName: String? = null): ImportResult =
        store(kind).import(input, sourceName)

    /** Задачи из базы дописываются примерами к шаблону: с ним они уйдут ИИ. Возвращает, сколько добавлено. */
    suspend fun attachToTemplate(templateId: String, examples: List<TemplateExample>): Int =
        templateStore.attach(templateId, examples)

    /**
     * Найденная задача — на дисплей калькулятора, как решение из истории. Задача из базы или шаблона
     * сохраняется в историю: к ней можно задать дополнение ИИ, и она переживёт перезапуск. Открытая
     * повторно, она берётся из истории — вместе с дополнениями.
     */
    suspend fun resolveFound(entry: SearchEntry): Solution {
        entry.history?.let { return it }
        val solution = FoundSolution.of(entry)
        historyStore.items.value.firstOrNull { it.id == solution.id }?.let { return it }
        // Only a successfully persisted/localized snapshot may become the current solution.
        return historyStore.add(solution)
    }

    fun disarmIfSelected(id: String?) {
        if (armedId.value == id) armedId.value = null
    }

    fun followUpTemplate(templateId: String?): TaskTemplate? =
        armedTemplate.value ?: templateId?.let { id -> templates.value.firstOrNull { it.id == id } }
}
