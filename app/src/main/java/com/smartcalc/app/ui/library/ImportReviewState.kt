package com.smartcalc.app.ui.library

import com.smartcalc.app.data.library.importer.ImportDraft
import com.smartcalc.app.data.library.extract.PageSelection

data class ImportReviewState(val drafts: List<ImportDraft>, val selectedIds: Set<String>,
    val publishedIds: Set<String> = emptySet(), val busy: Boolean = false) {
    val canPublish: Boolean get() {
        val selected = drafts.filter { it.id in selectedIds && it.id !in publishedIds }
        return !busy && selected.isNotEmpty() && selected.all { !it.excluded && it.condition.isNotBlank() &&
            (it.solution.isNotBlank() || it.answer.isNotBlank()) && it.refs.isNotEmpty() && it.warnings.none { warning -> warning.blocking } }
    }
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
