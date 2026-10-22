package com.smartcalc.app.data.search

import com.smartcalc.app.data.library.source.SourceCatalog
import com.smartcalc.app.data.template.ExampleFingerprint
import com.smartcalc.app.data.template.TaskTemplate
import com.smartcalc.app.data.template.TemplateExample

/**
 * Что модель поняла о новой задаче. По этому, а не по точным словам условия, ищутся похожие задачи:
 * [topic] и [keywords] называют тип и метод решения так, как его называют в учебниках, а [sections] —
 * разделы библиотеки, задачи которых, по мнению модели, решаются тем же способом.
 */
data class TaskDescription(
    val condition: String = "",
    val topic: String = "",
    val keywords: List<String> = emptyList(),
    val sections: List<String> = emptyList(),
) {
    val query: String
        get() = listOf(condition, topic, keywords.joinToString(" ")).filter { it.isNotBlank() }.joinToString("\n")
}

/** Задача библиотеки, которую можно предложить примером решения. */
class LibraryCandidate(val id: String, val section: String, val example: TemplateExample) {
    internal val entry = SearchEntry(id, FoundSource.BASE, section, example.condition,
        listOf(example.solution, example.answer).filter { it.isNotBlank() }.joinToString("\n"),
        example.conditionImages, example.solutionImages, library = example.library, answer = example.answer)
}

/** Подбор похожих задач библиотеки перед отправкой новой задачи. */
object SimilarTasks {
    const val LIMIT = 8

    /** Выбранные примеры уходят модели как одноразовый образец с этим ID; в хранилище образцов его нет. */
    const val TEMPLATE_ID = "similar-library-tasks"
    const val TEMPLATE_NAME = "похожие задачи из библиотеки"

    /**
     * Раздел, названный моделью, весит как половина лучшего совпадения по словам: задача того же типа
     * с другими словами поднимается выше случайных совпадений, но не выше той же самой задачи.
     */
    private const val SECTION_WEIGHT = 0.5

    /** Короче — это уже не название раздела, а случайное слово («ДЗ», «КР»). */
    private const val MIN_SECTION_PART = 8

    /** Доступные задачи библиотеки; одинаковая задача из двух разделов предлагается один раз. */
    fun candidates(sections: List<TaskTemplate>, sources: SourceCatalog = SourceCatalog()): List<LibraryCandidate> {
        val seen = HashSet<String>()
        return sections.flatMap { section ->
            section.examples.mapIndexedNotNull { index, example ->
                if (!TaskSearch.libraryExampleAvailable(example, sources) ||
                    !seen.add(ExampleFingerprint.key(example))) null
                else LibraryCandidate("${section.id}#$index", section.name, example)
            }
        }
    }

    fun sectionNames(candidates: List<LibraryCandidate>): List<String> = candidates.map { it.section }.distinct()

    /**
     * Ближайшие задачи, лучшие первыми. Совпадение слов и формул нормируется по лучшему, к нему
     * добавляется вес раздела. Если слова не совпали ни с чем, остаются задачи названных разделов.
     */
    fun rank(candidates: List<LibraryCandidate>, description: TaskDescription, limit: Int = LIMIT): List<LibraryCandidate> {
        val hits = if (description.query.isBlank()) emptyList() else TaskSearch.search(candidates.map { it.entry }, description.query)
        val best = hits.maxOfOrNull { it.score }?.takeIf { it > 0.0 } ?: 1.0
        val words = hits.associate { it.entry.key to it.score / best }
        val named = description.sections.map(::normalize).filter { it.isNotEmpty() }
        return candidates.mapNotNull { candidate ->
            val section = if (named.any { sameSection(normalize(candidate.section), it) }) SECTION_WEIGHT else 0.0
            val score = (words[candidate.id] ?: 0.0) + section
            if (score > 0.0) candidate to score else null
        }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    /** Одноразовый образец из отмеченных задач: модель решает новую задачу тем же способом. */
    fun template(chosen: List<LibraryCandidate>) = TaskTemplate(id = TEMPLATE_ID, name = TEMPLATE_NAME,
        examples = chosen.map { it.example }, updated = 0)

    private fun normalize(name: String) = name.lowercase().replace('ё', 'е')
        .replace(Regex("[«»\"'“”]"), "").replace(Regex("\\s+"), " ").trim().trimEnd('.')

    /** Модель может назвать раздел короче («Критерий Михайлова» вместо «Лекции. Критерий Михайлова»). */
    private fun sameSection(section: String, named: String) =
        section == named || named.length >= MIN_SECTION_PART && section.contains(named)
}
