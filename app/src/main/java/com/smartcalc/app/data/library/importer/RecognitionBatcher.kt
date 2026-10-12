package com.smartcalc.app.data.library.importer

import com.smartcalc.app.data.library.extract.ExtractedSegment

object RecognitionBatcher {
    fun next(segments: List<ExtractedSegment>, carry: RecognitionCarry?): RecognitionBatch? {
        if (segments.isEmpty()) return null
        val selected = mutableListOf<ExtractedSegment>()
        val visual = segments.first().imagePaths.isNotEmpty()
        var images = carry?.segments.orEmpty().sumOf { it.imagePaths.size }
        var characters = carry?.text?.let { it.codePointCount(0, it.length) } ?: 0
        for (segment in segments) {
            if (segment.imagePaths.isNotEmpty() != visual) break
            val chars = segment.text.codePointCount(0, segment.text.length)
            if (characters + chars > 12_000 || images + segment.imagePaths.size > 2) break
            selected += segment; characters += chars; images += segment.imagePaths.size
        }
        if (selected.isEmpty()) throw ManualBatchRequired()
        return RecognitionBatch(selected, carry)
    }
    class ManualBatchRequired : IllegalArgumentException("Фрагмент или продолжение не помещается в пакет. Разделите его вручную; текст не обрезан.")
}
