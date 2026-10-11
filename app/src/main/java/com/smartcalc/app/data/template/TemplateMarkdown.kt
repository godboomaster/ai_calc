package com.smartcalc.app.data.template

import java.util.UUID

/**
 * Шаблоны в Markdown — текст, который удобно читать и писать вручную:
 *
 * ```
 * # Шаблон: Название
 * ## Требования
 * текст
 * ## Пример 1
 * ### Условие
 * текст
 * ### Решение
 * текст
 * ```
 *
 * Фото в Markdown не сохраняются. При экспорте под заголовком шаблона в комментарии пишется его id —
 * повторный импорт того же файла обновляет шаблон, а не дублирует его.
 */
object TemplateMarkdown {

    private val COMMENT = Regex("""<!--(.*?)-->""", RegexOption.DOT_MATCHES_ALL)
    private val ID_COMMENT = Regex("""<!--\s*id:\s*([A-Za-z0-9-]{1,64})\s*-->""")
    private val HEADING = Regex("""^(#{1,3})\s+(.+?)\s*#*\s*$""")
    private val NAME_PREFIX = Regex("""^шаблон\s*:\s*""", RegexOption.IGNORE_CASE)

    fun write(templates: List<TaskTemplate>): String = buildString {
        templates.forEachIndexed { i, t ->
            if (i > 0) append("\n---\n\n")
            append("# Шаблон: ${t.name}\n")
            append("<!-- id: ${t.id} -->\n\n")
            if (t.requirements.isNotBlank()) {
                append("## Требования\n\n${t.requirements.trim()}\n\n")
            }
            t.examples.forEachIndexed { n, ex ->
                append("## Пример ${n + 1}\n\n")
                section("Условие", ex.condition, ex.conditionImages.size)
                section("Решение", ex.solution, ex.solutionImages.size)
            }
        }
    }.trimEnd() + "\n"

    private fun StringBuilder.section(title: String, text: String, photos: Int) {
        if (text.isBlank() && photos == 0) return
        append("### $title\n\n")
        if (text.isNotBlank()) append(text.trim()).append("\n\n")
        if (photos > 0) append("<!-- фото: $photos, в Markdown не сохраняются -->\n\n")
    }

    /**
     * Шаблоны из текста. Разбор снисходительный: «Шаблон:» в заголовке необязательно, текст сразу под
     * заголовком шаблона считается требованиями, текст примера без «### Условие» — условием.
     */
    fun parse(text: String): List<TaskTemplate> {
        val templates = mutableListOf<TaskTemplate>()
        var name: String? = null
        var id: String? = null
        val requirements = StringBuilder()
        val examples = mutableListOf<Pair<StringBuilder, StringBuilder>>()
        // Куда сейчас идёт текст: требования, условие или решение текущего примера.
        var target: StringBuilder? = null

        fun flush() {
            val n = name ?: return
            templates += TaskTemplate(
                id = id ?: UUID.randomUUID().toString(),
                name = n,
                requirements = requirements.toString().trim(),
                examples = examples
                    .map { (c, s) -> TemplateExample(condition = c.toString().trim(), solution = s.toString().trim()) }
                    .filterNot { it.isEmpty },
            )
            name = null
            id = null
            requirements.clear()
            examples.clear()
            target = null
        }

        for (rawLine in text.removePrefix("﻿").lines()) {
            ID_COMMENT.find(rawLine)?.let { if (name != null && id == null) id = it.groupValues[1] }
            val line = COMMENT.replace(rawLine, "")
            val heading = HEADING.find(line)
            if (heading == null) {
                // Разделитель между шаблонами при экспорте — не часть текста.
                if (line.trim() != "---") target?.append(line)?.append('\n')
                continue
            }
            val level = heading.groupValues[1].length
            val title = heading.groupValues[2].trim()
            when {
                level == 1 -> {
                    flush()
                    name = NAME_PREFIX.replace(title, "").ifBlank { "Шаблон" }
                    target = requirements
                }

                name == null -> Unit
                level == 2 && title.startsWith("Требования", ignoreCase = true) -> target = requirements
                level == 2 && title.startsWith("Пример", ignoreCase = true) -> {
                    examples += StringBuilder() to StringBuilder()
                    target = examples.last().first
                }

                level == 3 && examples.isNotEmpty() && title.startsWith("Решение", ignoreCase = true) ->
                    target = examples.last().second

                level == 3 && examples.isNotEmpty() && title.startsWith("Условие", ignoreCase = true) ->
                    target = examples.last().first

                // Другие заголовки внутри текста — его часть.
                else -> target?.append(line)?.append('\n')
            }
        }
        flush()
        return templates
    }
}
