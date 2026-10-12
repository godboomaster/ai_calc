package com.smartcalc.app.ui.library

import com.smartcalc.app.data.library.importer.ImportDraft
import com.smartcalc.app.data.library.extract.PageSelection

data class ImportReviewState(val drafts: List<ImportDraft>, val selectedIds: Set<String>,
    val publishedIds: Set<String> = emptySet(), val busy: Boolean = false,
    val repeatRequired: Boolean = false, val repeatConfirmed: Boolean = false) {
    val canPublish: Boolean get() {
        val selected = drafts.filter { it.id in selectedIds && it.id !in publishedIds }
        return !busy && (!repeatRequired || repeatConfirmed) && selected.isNotEmpty() && selected.all { !it.excluded && it.condition.isNotBlank() &&
            (it.solution.isNotBlank() || it.answer.isNotBlank()) && it.refs.isNotEmpty() && it.warnings.none { warning -> warning.blocking } }
    }
}
data class ImportComparison(val sectionName: String, val example: com.smartcalc.app.data.template.TemplateExample)
fun comparableExamples(draft: ImportDraft, sections: List<com.smartcalc.app.data.template.TaskTemplate>, jobId: String): List<ImportComparison> =
    sections.flatMap { section -> section.examples.filter { example ->
        example.library?.origin?.importJobId != jobId && example.library?.sourceRefs.orEmpty().any { old ->
            draft.refs.any { it.sourceId == old.sourceId && it.segmentId == old.segmentId }
        }
    }.map { ImportComparison(section.name, it) } }

fun formatOrdinalRanges(ordinals: Collection<Int>): String {
    val values = ordinals.distinct().sorted()
    val groups = mutableListOf<String>(); var index = 0
    while (index < values.size) {
        val start = values[index]; var end = start
        while (index + 1 < values.size && values[index + 1] == end + 1) { index++; end = values[index] }
        groups += if (start == end) "$start" else "$start-$end"
        index++
    }
    return groups.joinToString(", ")
}
fun parsePageSelection(text: String, total: Int): PageSelection {
    require(total > 0)
    val values = mutableSetOf<Int>()
    for (part in text.split(',', ';')) {
        val range = part.trim().split('-')
        require(range.size in 1..2 && range.all { it.trim().toIntOrNull() != null }) { "Введите номера: 1-3,6" }
        val start = range[0].trim().toInt(); val end = range.last().trim().toInt()
        require(start in 1..total && end in start..total && end - start < 100) { "Диапазон вне документа или больше 100 фрагментов" }
        values += start..end
        require(values.size <= 100) { "Выберите не больше 100 фрагментов" }
    }
    return PageSelection(values.sorted())
}
