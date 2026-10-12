package com.smartcalc.app.data.library.importer

import java.util.UUID
import com.smartcalc.app.data.library.extract.ExtractionWarning

object ImportDraftEditor {
    private val boundary = ExtractionWarning("boundary_review", "Проверьте границы и распределение источников после объединения или разделения.")
    fun merge(drafts: List<ImportDraft>): ImportDraft {
        require(drafts.size >= 2)
        return ImportDraft(UUID.randomUUID().toString(), condition = drafts.joinToString("\n\n") { it.condition },
            solution = drafts.joinToString("\n\n") { it.solution }, answer = drafts.joinToString("\n") { it.answer },
            refs = drafts.flatMap { it.refs }.distinct(), conditionImages = drafts.flatMap { it.conditionImages }.distinct(),
            solutionImages = drafts.flatMap { it.solutionImages }.distinct(),
            warnings = (drafts.flatMap { it.warnings } + boundary).distinct())
    }
    fun split(draft: ImportDraft, parts: List<ImportDraft>): List<ImportDraft> {
        require(parts.size >= 2 && parts.map { it.id }.distinct().size == parts.size)
        require(parts.flatMap { it.refs }.toSet() == draft.refs.toSet()) { "Разделение должно сохранить ссылки всех частей" }
        return parts.map { it.copy(revision = 1, warnings = (it.warnings + boundary).distinct()) }
    }
}
