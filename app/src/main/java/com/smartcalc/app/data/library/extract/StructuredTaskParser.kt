package com.smartcalc.app.data.library.extract

import com.smartcalc.app.data.library.source.SourceSegmentRef

object StructuredTaskParser {
    private val taskHeading = Regex("^(?:#{1,3}\\s*)?(?:задача|пример)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val fieldHeading = Regex("^(?:#{1,3}\\s*)?(условие|решение|ответ)\\s*:?\\s*(.*)$", RegexOption.IGNORE_CASE)
    fun parse(segments: List<ExtractedSegment>): StructuredParseResult {
        val tasks = mutableListOf<ParsedTask>(); val unresolved = mutableSetOf<String>()
        var condition = StringBuilder(); var solution = StringBuilder(); var answer = StringBuilder()
        var field = "условие"; var started = false
        val refs = linkedSetOf<SourceSegmentRef>(); val warnings = mutableListOf<ExtractionWarning>()
        val conditionImages = linkedSetOf<String>(); val solutionImages = linkedSetOf<String>()
        fun flush() {
            if (started && condition.isNotBlank() && (solution.isNotBlank() || answer.isNotBlank() || solutionImages.isNotEmpty())) {
                tasks += ParsedTask(condition.toString().trim(), solution.toString().trim(), answer.toString().trim(), refs.toList(), warnings.distinct(), conditionImages.toList(), solutionImages.toList())
            } else unresolved += refs.map { it.segmentId }
            condition = StringBuilder(); solution = StringBuilder(); answer = StringBuilder()
            refs.clear(); warnings.clear(); conditionImages.clear(); solutionImages.clear(); field = "условие"
        }
        segments.forEach { segment ->
            fun retain() {
                if (started) {
                    refs += segment.ref; warnings += segment.warnings
                    if (field == "условие") conditionImages += segment.imagePaths else solutionImages += segment.imagePaths
                } else unresolved += segment.ref.segmentId
            }
            segment.text.lineSequence().forEach { raw ->
                val line = raw.trim(); if (line.isEmpty()) return@forEach
                val heading = taskHeading.matchEntire(line)
                if (heading != null && !heading.groupValues[1].contains("продолжение", ignoreCase = true)) {
                    if (started) flush()
                    started = true
                } else if (!started) {
                    unresolved += segment.ref.segmentId
                } else {
                    val section = fieldHeading.matchEntire(line)
                    val value = if (section != null) { field = section.groupValues[1].lowercase(); section.groupValues[2] } else line
                    if (value.isNotEmpty() && heading == null) when (field) {
                        "условие" -> condition.appendLine(value)
                        "решение" -> solution.appendLine(value)
                        "ответ" -> answer.appendLine(value)
                    }
                }
                retain()
            }
            if (segment.text.isBlank()) retain()
        }
        if (started) flush()
        return StructuredParseResult(tasks, unresolved.toList())
    }
}
