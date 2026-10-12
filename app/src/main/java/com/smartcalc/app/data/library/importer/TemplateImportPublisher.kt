package com.smartcalc.app.data.library.importer

import com.smartcalc.app.data.template.TemplateStore
import com.smartcalc.app.data.library.source.*
import java.io.File

class TemplateImportPublisher(private val library: TemplateStore, private val sources: LibrarySourceStore) : ImportPublisher {
    override fun receipt(commitId: String) = library.importReceipt(commitId)
    override suspend fun publish(intent: PublicationIntent): ImportReceipt {
        // A durable receipt remains authoritative even after a user deletes the original/section.
        receipt(intent.commitId)?.let { PublicationIdentity.verify(it, intent); return it }
        check(sources.state.value.error == null) { "Каталог источников недоступен" }
        val catalog = sources.state.value.sources.associateBy { it.id }
        val names = linkedSetOf<String>()
        intent.items.forEach { item ->
            require(item.refs.isNotEmpty())
            val roots = item.refs.map { ref ->
                val source = catalog[ref.sourceId] ?: throw IllegalArgumentException("Источник не найден")
                require(source.status == SourceStatus.ACTIVE && source.segmentCount != null && ref.ordinal <= source.segmentCount) { "Источник отключён, удалён или не разобран" }
                val expectedKind = when (source.format) {
                    SourceFormat.PDF -> SegmentKind.PDF_PAGE
                    SourceFormat.JPEG, SourceFormat.PNG -> SegmentKind.IMAGE
                    else -> SegmentKind.DOCUMENT_BLOCK
                }
                val unit = when (expectedKind) { SegmentKind.PDF_PAGE -> "page"; SegmentKind.IMAGE -> "image"; else -> "block" }
                require(ref.kind == expectedKind && ref.segmentId == "${source.id}:$unit:${ref.ordinal}") { "Недопустимая ссылка на источник" }
                names += source.name
                sources.originalFile(source.id).parentFile!!.canonicalFile.toPath()
            }
            (item.conditionImages + item.solutionImages).forEach { path ->
                val file = File(path).canonicalFile
                require(file.isFile && roots.any { file.toPath().startsWith(it) }) { "Изображение вне разрешённых источников" }
            }
        }
        return library.publishImport(intent, names.joinToString(", ").take(250))
    }
}
