package com.smartcalc.app.data.api

import com.smartcalc.app.data.history.Solution
import com.smartcalc.app.data.DocumentException
import com.smartcalc.app.data.history.SolutionStep
import com.smartcalc.app.data.plot.Plot
import com.smartcalc.app.data.settings.ApiSettings
import com.smartcalc.app.data.library.agent.LibraryTools
import com.smartcalc.app.data.library.agent.LibraryAgentReport
import com.smartcalc.app.data.library.agent.redact
import com.smartcalc.app.data.library.agent.redactSecret
import com.smartcalc.app.data.search.TaskDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.IOException
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

open class ApiException(message: String, val httpCode: Int? = null) : Exception(message)

/** Приложение рисует формулы LaTeX, поэтому просим именно его — и с правильными разделителями. */
private const val FORMULA_RULE =
    "Все формулы, выражения и числа с единицами пиши в LaTeX: внутри текста — между $ и $, " +
        "крупную формулу отдельной строкой — между $$ и $$. Русские слова внутри формулы — в \\text{}. " +
        "Обычный текст пиши без LaTeX. Из markdown можно только **жирный** и таблицы: строки вида | a | b |, " +
        "вторая строка | --- | --- |, у всех строк одинаковое число ячеек, в ячейках можно \$формулы\$. " +
        "В JSON обратный слэш экранируй: \\\\frac{a}{b}. " +
        "В answer — только итоговый ответ, коротко: число, выражение или короткая фраза, формулы тоже в $…$."

/** Графики рисует приложение — модель присылает только формулы и диапазоны. */
private val PLOT_RULE = """
    Если в задаче нужно построить график (или он заметно помогает понять решение), добавь в соответствующий шаг поле plot — приложение нарисует его само. Пример:
    "plot": {"title": "АЧХ", "x": {"label": "ω, рад/с", "var": "w", "from": 0.01, "to": 100, "log": true}, "y": {"label": "A(ω)"}, "params": {"k": 2, "T": 0.5}, "series": [{"label": "A(ω)", "y": "k/sqrt(1+(w*T)^2)"}]}
    Кривая y(x) — поле y: формула от переменной x.var на отрезке x.from…x.to. Параметрическая кривая (годограф, АФЧХ, окружность) — поля x и y с формулами от своей переменной var на отрезке from…to: {"x": "k/(1+(w*T)^2)", "y": "-k*w*T/(1+(w*T)^2)", "var": "w", "from": 0, "to": 100}. Таблица значений — "points": [[x1, y1], [x2, y2]]. Несколько кривых на одном графике — несколько элементов series; несколько графиков — в разных шагах.
    Формулы — как в программировании: умножение * обязательно, ^ — степень, функции со скобками: sin, cos, tan, atan, atan2(y, x), sqrt, exp, ln, lg, abs; константы pi и e. Буквенные параметры задай числами в params; если в условии чисел нет, возьми удобные и скажи об этом в content. "log": true — логарифмическая шкала. y.from и y.to необязательны.
""".trimIndent()

/** Схемы модель рисует в SVG; правила — чтобы код без ошибок лёг в JSON и читался на телефоне. */
private val SCHEME_RULE = """
    Если к задаче нужна схема или рисунок (структурная схема САУ, электрическая цепь, чертёж к задаче по геометрии, рисунок сил в физике), добавь в соответствующий шаг поле svg — строку с SVG-кодом. Правила: обязательно viewBox (например, viewBox='0 0 400 300'); атрибуты пиши в одинарных кавычках; линии и текст чёрные, фон прозрачный; только line, polyline, polygon, path, rect, circle, ellipse, text, g и marker для стрелок; подписи — обычный текст с символами Unicode (не LaTeX), font-size 14–16, подписи не должны налезать на линии и друг на друга; без скриптов, картинок и внешних шрифтов. Графики функций рисуй через plot, а не через svg.
""".trimIndent()

/** Больше — это уже не схема, а что-то пошедшее не так. */
private const val MAX_SVG_LENGTH = 200_000

/**
 * Команды LaTeX, которые начинаются как escape JSON (\f, \b, \n, \r, \t): неэкранированный «\frac»
 * парсится без ошибки — как перевод страницы и «rac». Короткие \ne и \nu не включены: «\ne» чаще
 * означает перевод строки и «e».
 */
private const val ESCAPE_LIKE_LATEX =
    "frac|dfrac|tfrac|forall|beta|bar|binom|big|bigl|bigr|boxed|bullet|backslash|begin|bmod|" +
        "neq|nabla|neg|not|notin|nmid|newline|right|rightarrow|rho|rangle|rceil|rfloor|rm|" +
        "times|tau|theta|text|textbf|tan|tanh|tg|tfrac|to|top|triangle|tilde|therefore"

/**
 * Escape-последовательность JSON: команда LaTeX из [ESCAPE_LIKE_LATEX] (группа 1), допустимый escape
 * (группа 2) или одиночный «\» — первую и последний нужно экранировать.
 */
private val JSON_ESCAPE = Regex("""\\(?:((?:$ESCAPE_LIKE_LATEX)(?![a-zA-Z]))|(["\\/bfnrt]|u[0-9a-fA-F]{4}))?""")

/** Подбор похожих задач: модель только читает задачу и называет её тип, решать её не нужно. */
private const val DESCRIBE_RULE =
    "Ты подбираешь из библиотеки пользователя решённые задачи, которые решаются тем же способом, что и новая. " +
        "Новую задачу не решай. Отвечай строго одним JSON-объектом, без пояснений и без markdown."

/** Названия разделов уходят модели целиком; больше — уже не каталог, а лишние токены. */
private const val MAX_DESCRIBE_SECTIONS = 200
private const val MAX_DESCRIBE_TEXT = 8_000

data class StepPayload(
    val title: String,
    val content: String,
    val plot: Plot? = null,
    val svg: String? = null,
)

/** Шаблон заданий в запросе: требования и примеры, картинки уже в base64. */
@Serializable
class TemplatePrompt(val name: String, val requirements: String, val examples: List<Example>) {
    @Serializable
    class Example(
        val condition: String,
        val conditionImages: List<String>,
        val solution: String,
        val solutionImages: List<String>,
    )
}

data class SolutionPayload(
    val problem: String,
    val answer: String,
    val steps: List<StepPayload>,
    val agentReport: LibraryAgentReport? = null,
)

class OpenAiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** График разбирается снисходительно: числа модель пишет и строками («2*pi»), и числами. */
    private val plotJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private fun plot(el: JsonElement?): Plot? =
        (el as? JsonObject)?.let { runCatching { plotJson.decodeFromJsonElement(Plot.serializer(), it) }.getOrNull() }

    /** Сам SVG без обёрток: модели любят окружать его ```svg … ``` и пояснениями. */
    private fun svg(el: JsonElement?): String? {
        val text = (el as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val start = text.indexOf("<svg")
        val end = text.lastIndexOf("</svg>")
        if (start < 0 || end < start) return null
        return text.substring(start, end + "</svg>".length).takeIf { it.length <= MAX_SVG_LENGTH }
    }

    /**
     * [formatText] и [formatSampleBase64] — формат решения, заданный пользователем
     * (требования текстом и/или фото образца оформления); [template] — шаблон заданий.
     */
    suspend fun solve(
        settings: ApiSettings,
        images: List<String>,
        text: String?,
        formatText: String? = null,
        formatSampleBase64: String? = null,
        template: TemplatePrompt? = null,
        libraryTools: LibraryTools? = null,
        agentProgress: suspend (LibraryAgentReport) -> Unit = {},
    ): SolutionPayload =
        withContext(Dispatchers.IO) {
            val userContent = taskContent(images, text, formatText, formatSampleBase64, template)
            val messages = buildJsonArray {
                add(systemMessage(settings))
                add(message("user", userContent))
            }
            complete(settings, messages, libraryTools, LibraryTools.explicitSearch(text.orEmpty()), agentProgress)
        }

    /**
     * Дополнительный вопрос к решённой задаче: модель получает всю цепочку — исходную задачу, свой
     * ответ, прежние дополнения с ответами — и новый вопрос. Для готового примера из библиотеки
     * передаются также фотографии: его условие и решение могли ещё не распознаваться моделью.
     */
    suspend fun followUp(
        settings: ApiSettings,
        base: Solution,
        question: String,
        template: TemplatePrompt? = null,
        libraryTools: LibraryTools? = null,
        agentProgress: suspend (LibraryAgentReport) -> Unit = {},
    ): SolutionPayload =
        withContext(Dispatchers.IO) {
            complete(settings, followUpMessages(settings, base, question, template), libraryTools, LibraryTools.explicitSearch(question), agentProgress)
        }

    /** [template] — шаблон, по которому нужно ответить на дополнение: уходит прямо перед вопросом. */
    internal fun followUpMessages(
        settings: ApiSettings,
        base: Solution,
        question: String,
        template: TemplatePrompt? = null,
    ) = buildJsonArray {
        add(systemMessage(settings))
        add(message("user", followUpTaskContent(base)))
        add(message("assistant", JsonPrimitive(turnJson(base.problem, base.answer, base.steps))))
        base.followUps.forEach { f ->
            add(message("user", JsonPrimitive("Дополнение: ${f.question}")))
            add(message("assistant", JsonPrimitive(turnJson(f.question, f.answer, f.steps))))
        }
        val ask = "Дополнение: $question\n" +
            "Ответь тем же JSON-объектом: answer — ответ именно на это дополнение, steps — его решение " +
            "(то, что уже решено выше, не повторяй), problem — коротко, о чём дополнение."
        val content = if (template == null) {
            JsonPrimitive(ask)
        } else {
            buildJsonArray {
                templateParts(template, followUp = true).forEach { add(it) }
                add(textPart(ask))
            }
        }
        add(message("user", content))
    }

    private fun followUpTaskContent(base: Solution): JsonElement {
        val text = "Задача: ${base.problem}"
        // Generated solutions already contain the recognized problem and steps in text.
        if (base.source == null) return JsonPrimitive(text)
        val attachments = buildList {
            base.imagePath?.let { add("Фото условия" to it) }
            base.steps.forEach { step -> step.images.forEach { add("Материал шага «${step.title}»" to it) } }
            base.followUps.forEach { f ->
                f.steps.forEach { step -> step.images.forEach { add("Дополнение «${f.question}», шаг «${step.title}»" to it) } }
            }
        }
        if (attachments.isEmpty()) return JsonPrimitive(text)
        // JSON + base64 duplicate bytes in memory. Reject excessive context explicitly before POST.
        var remaining = 20L * 1024 * 1024
        val seen = mutableSetOf<File>()
        return buildJsonArray {
            add(textPart(text))
            attachments.forEach { (label, path) ->
                try {
                    val source = File(path).canonicalFile
                    if (!seen.add(source)) return@forEach
                    if (!source.isFile) throw IOException("Missing attachment")
                    if (source.length() > remaining) throw DocumentException("Фотографии решения слишком велики для дополнения (больше 20 МБ). Уменьшите изображения или добавьте условие текстом.")
                    val bytes = source.inputStream().buffered().use { input ->
                        ByteArrayOutputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                remaining -= read
                                if (remaining < 0) throw DocumentException("Фотографии решения слишком велики для дополнения (больше 20 МБ).")
                                output.write(buffer, 0, read)
                            }
                            output.toByteArray()
                        }
                    }
                    add(textPart(label))
                    add(imagePart(Base64.getEncoder().encodeToString(bytes)))
                } catch (_: IOException) {
                    throw DocumentException("Не удалось прочитать фотографии решения. Восстановите вложения или добавьте условие текстом.")
                }
            }
        }
    }

    /** Прежний ответ в том виде, в каком модель его прислала: так она продолжит в том же формате. */
    private fun turnJson(problem: String, answer: String, steps: List<SolutionStep>) = buildJsonObject {
        put("problem", problem)
        put("answer", answer)
        putJsonArray("steps") {
            steps.forEach { step ->
                add(buildJsonObject {
                    put("title", step.title)
                    // Код схемы большой, а для продолжения разговора хватит упоминания.
                    put("content", if (step.svg != null) step.content + "\n[схема]" else step.content)
                })
            }
        }
    }.toString()

    private fun systemMessage(settings: ApiSettings) = message(
        "system",
        JsonPrimitive(listOf(settings.systemPrompt.trimEnd(), SOLUTION_STEPS_RULE, FORMULA_RULE, PLOT_RULE, SCHEME_RULE).joinToString("\n")),
    )

    private fun message(role: String, content: JsonElement) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private suspend fun complete(settings: ApiSettings, messages: JsonArray, libraryTools: LibraryTools? = null,
        forceSearch: Boolean = false, progress: suspend (LibraryAgentReport) -> Unit = {}): SolutionPayload {
        requireImageSupport(settings, messages)
        if (libraryTools != null) {
            if (settings.apiKey.isBlank()) throw ApiConfigurationException("API-ключ не задан. Откройте настройки и введите ключ.")
            val adapter = apiAdapter(settings)
            if (!adapter.chatToolsSupported) throw ApiConfigurationException("Эта модель OpenAI не поддерживает инструменты через Chat Completions. Выберите совместимую модель или выключите поиск агентом.")
            val result = LibraryAgentLoop(settings.model, libraryTools,
                { progress(it.redact(settings.apiKey)) },
                { it.redactSecret(settings.apiKey).jsonObject },
                thinkingToolMode = adapter.thinkingToolMode(settings),
                requireReasoning = adapter.requireReasoning,
                autoToolsOnly = adapter.autoToolsOnly) { executeMessage(settings, it) }.run(messages, forceSearch)
            return redactPayload(parseSolution(result.content).copy(agentReport = result.report), settings.apiKey)
        }
        fun body(jsonMode: Boolean) = buildJsonObject {
            put("model", settings.model)
            put("messages", messages)
            // Сервер сам гарантирует валидный JSON (OpenAI, Ollama, DeepSeek, Qwen это умеют).
            if (jsonMode) put("response_format", buildJsonObject { put("type", "json_object") })
        }
        val raw = try {
            execute(settings, body(jsonMode = true))
        } catch (e: ApiException) {
            // Не все OpenAI-совместимые серверы знают response_format — повторяем без него.
            if (e.httpCode == 400 || e.httpCode == 422) execute(settings, body(jsonMode = false)) else throw e
        }
        return redactPayload(parseSolution(raw), settings.apiKey)
    }

    /** A provider may echo credentials in its response; none may reach history or checkpoints. */
    private fun redactPayload(payload: SolutionPayload, secret: String): SolutionPayload {
        if (secret.isBlank()) return payload
        fun clean(value: String) = value.replace(secret, "[скрыто]")
        return payload.copy(problem = clean(payload.problem), answer = clean(payload.answer),
            steps = payload.steps.map { step -> step.copy(title = clean(step.title), content = clean(step.content),
                svg = step.svg?.let(::clean), plot = step.plot?.let {
                    plotJson.decodeFromJsonElement(Plot.serializer(), json.encodeToJsonElement(Plot.serializer(), it).redactSecret(secret))
                }) }, agentReport = payload.agentReport?.redact(secret))
    }

    /**
     * Сообщение с задачей: сначала шаблон (требования и примеры), потом формат, потом сама задача.
     * Рядом с картинками примеров и образца явно сказано, какое фото — новая задача.
     */
    internal fun taskContent(
        images: List<String>,
        text: String?,
        formatText: String?,
        formatSampleBase64: String?,
        template: TemplatePrompt?,
    ): JsonArray = buildJsonArray {
        if (template != null) {
            templateParts(template).forEach { add(it) }
        }
        val hasFormat = !formatText.isNullOrBlank() || formatSampleBase64 != null
        if (hasFormat) {
            add(textPart(formatInstructions(formatText, formatSampleBase64 != null)))
            if (formatSampleBase64 != null) add(imagePart(formatSampleBase64))
        }
        val hasContext = hasFormat || template != null
        val task = when {
            text != null -> if (hasContext) "Новая задача: $text" else text
            images.size > 1 -> "Реши задачу со страниц документа на следующих изображениях."
            // Рядом с образцами нужно явно указать, какое фото — задача.
            hasContext -> "Новая задача, которую нужно решить, — на следующем фото."
            else -> "Реши задачу на фото."
        }
        add(textPart(task))
        images.forEach { add(imagePart(it)) }
    }

    /** [followUp] — шаблон к дополнению: ответить на него так же, как в примерах. */
    private fun templateParts(t: TemplatePrompt, followUp: Boolean = false): List<JsonObject> = buildList {
        add(
            textPart(
                buildString {
                    if (followUp) {
                        append("Ответ на дополнение дай по шаблону «${t.name}»: тем же способом и в том же ")
                        append("оформлении, что в примерах ниже; примеры решать не нужно.")
                    } else {
                        append("Это задача по шаблону «${t.name}». Реши её тем же способом и оформи так же, ")
                        append("как в примерах ниже; примеры решать не нужно.")
                    }
                    if (t.requirements.isNotBlank()) {
                        append("\n\nТребования к таким задачам:\n")
                        append(t.requirements)
                    }
                },
            ),
        )
        t.examples.forEachIndexed { i, ex ->
            val n = i + 1
            if (ex.condition.isNotBlank() || ex.conditionImages.isNotEmpty()) {
                add(textPart("Пример $n. Условие:" + if (ex.condition.isNotBlank()) "\n${ex.condition}" else " на фото ниже."))
                ex.conditionImages.forEach { add(imagePart(it)) }
            }
            if (ex.solution.isNotBlank() || ex.solutionImages.isNotEmpty()) {
                add(textPart("Пример $n. Решение:" + if (ex.solution.isNotBlank()) "\n${ex.solution}" else " на фото ниже."))
                ex.solutionImages.forEach { add(imagePart(it)) }
            }
        }
    }

    private fun textPart(text: String) = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    private fun imagePart(base64: String) = buildJsonObject {
        put("type", "image_url")
        put("image_url", buildJsonObject {
            put("url", "data:image/jpeg;base64,$base64")
        })
    }

    private fun formatInstructions(formatText: String?, hasSample: Boolean) = buildString {
        appendLine("Пользователь задал свой формат решения. Реши задачу именно так и оформи решение строго в этом формате:")
        appendLine("- используй тот способ решения, который указан, даже если есть более короткий;")
        appendLine("- каждый раздел или шаг формата — отдельный элемент steps: title — название раздела так, как оно записано в формате, content — его содержание;")
        appendLine("- в answer — итоговый ответ;")
        append("- ответ по-прежнему верни одним JSON-объектом.")
        if (!formatText.isNullOrBlank()) {
            appendLine()
            appendLine()
            appendLine("Требования пользователя к решению:")
            append(formatText.trim())
        }
        if (hasSample) {
            appendLine()
            appendLine()
            append(
                "Следующее изображение — образец оформления. Повтори его структуру, порядок разделов, " +
                    "обозначения и стиль записи. Задачу с образца решать не нужно.",
            )
        }
    }

    /**
     * Короткий запрос перед решением: модель читает задачу (фото, текст или документ) и называет её тип
     * и подходящие разделы [sections], чтобы найти в библиотеке похожие. Рассуждения выключены: ответ
     * нужен быстрый и дешёвый, а решать задачу здесь не нужно.
     */
    suspend fun describeTask(settings: ApiSettings, images: List<String>, text: String?, sections: List<String>): TaskDescription =
        withContext(Dispatchers.IO) {
            val quick = settings.copy(thinkingEnabled = false)
            val messages = buildJsonArray {
                add(message("system", JsonPrimitive(DESCRIBE_RULE)))
                add(message("user", buildJsonArray {
                    add(textPart(describeInstructions(sections)))
                    if (!text.isNullOrBlank()) add(textPart("Новая задача:\n" + text.take(MAX_DESCRIBE_TEXT)))
                    if (images.isNotEmpty()) add(textPart(if (text.isNullOrBlank()) "Новая задача — на фото ниже." else "Фото к новой задаче:"))
                    images.forEach { add(imagePart(it)) }
                }))
            }
            requireImageSupport(quick, messages)
            fun body(jsonMode: Boolean) = buildJsonObject {
                put("model", quick.model)
                put("messages", messages)
                if (jsonMode) put("response_format", buildJsonObject { put("type", "json_object") })
            }
            val raw = try {
                execute(quick, body(jsonMode = true))
            } catch (e: ApiException) {
                if (e.httpCode == 400 || e.httpCode == 422) execute(quick, body(jsonMode = false)) else throw e
            }
            val found = parseTaskDescription(raw)
            if (settings.apiKey.isBlank()) found else {
                fun clean(value: String) = value.replace(settings.apiKey, "[скрыто]")
                TaskDescription(clean(found.condition), clean(found.topic), found.keywords.map(::clean), found.sections.map(::clean))
            }
        }

    private fun describeInstructions(sections: List<String>) = buildString {
        appendLine("Прочитай новую задачу и верни JSON-объект:")
        appendLine("{\"condition\": \"условие задачи полностью, как написано; формулы — в LaTeX между $ и $\",")
        appendLine(" \"topic\": \"тип задачи одной фразой: что дано, что найти и каким методом такие задачи решают\",")
        appendLine(" \"keywords\": [\"5–12 слов и коротких терминов, которыми такие задачи называют в учебниках, включая метод решения и синонимы\"],")
        appendLine(" \"sections\": [\"до 3 разделов из списка ниже, задачи которых решаются тем же способом, — название точно как в списке; [], если подходящих нет\"]}")
        appendLine()
        appendLine("Разделы библиотеки:")
        sections.take(MAX_DESCRIBE_SECTIONS).forEach { appendLine("- " + it.take(150)) }
    }

    /** Разбор так же снисходителен, как у решения: обёртки, неэкранированный LaTeX, строка вместо списка. */
    internal fun parseTaskDescription(raw: String): TaskDescription {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw ApiException("Модель вернула ответ не в формате JSON")
        val repaired = JSON_ESCAPE.replace(text.substring(start, end + 1)) { m -> if (m.groups[2] != null) m.value else "\\" + m.value }
        val obj = try {
            json.parseToJsonElement(repaired).jsonObject
        } catch (e: Exception) {
            throw ApiException("Не удалось разобрать ответ модели")
        }
        fun string(key: String, limit: Int) = (obj[key] as? JsonPrimitive)?.contentOrNull.orEmpty().replace("`", "").trim().take(limit)
        fun list(key: String, count: Int, limit: Int): List<String> = when (val value = obj[key]) {
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> value.contentOrNull.orEmpty().split(',', ';', '\n')
            else -> emptyList()
        }.map { it.trim().take(limit) }.filter { it.isNotEmpty() }.distinct().take(count)
        return TaskDescription(string("condition", 4_000), string("topic", 300), list("keywords", 20, 60), list("sections", 5, 200))
    }

    suspend fun testConnection(settings: ApiSettings): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", settings.model)
            put("max_tokens", 16)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "Ответь одним словом: ок")
                })
            }
        }
        execute(settings, body)
        "Соединение успешно: модель отвечает"
    }

    private suspend fun execute(settings: ApiSettings, body: JsonObject): String =
        (executeMessage(settings, body)["content"] as? JsonPrimitive)?.contentOrNull ?: throw ApiException("Модель вернула пустой ответ")

    private suspend fun executeMessage(settings: ApiSettings, body: JsonObject): JsonObject {
        if (settings.apiKey.isBlank()) {
            throw ApiConfigurationException("API-ключ не задан. Откройте настройки и введите ключ.")
        }
        val url = apiEndpoint(settings.baseUrl)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${settings.apiKey}")
            .post(configuredApiBody(settings, body).toString().toRequestBody("application/json".toMediaType()).oneShot())
            .build()
        val resp = client.newCall(request).awaitResponse()
        val respBody = resp.body
        if (!resp.isSuccessful) {
            val apiMessage = try {
                json.parseToJsonElement(respBody).jsonObject["error"]
                    ?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            } catch (e: Exception) {
                null
            }
            throw ApiException("Ошибка API (HTTP ${resp.code}): ${apiMessage ?: respBody.take(300)}", resp.code)
        }
        val root = try {
            json.parseToJsonElement(respBody).jsonObject
        } catch (e: Exception) {
            throw ApiException("Сервер вернул некорректный ответ")
        }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        return choice?.get("message") as? JsonObject ?: throw ApiException("Модель вернула пустой ответ")
    }

    // OkHttp's HTTP follow-ups (e.g. 503 Retry-After: 0) otherwise replay even with
    // retryOnConnectionFailure(false). Only the counted application fallback may send again.
    private fun RequestBody.oneShot(): RequestBody {
        val delegate = this
        return object : RequestBody() {
            override fun contentType() = delegate.contentType()
            override fun contentLength() = delegate.contentLength()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
        }
    }

    internal fun parseSolution(raw: String): SolutionPayload {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```json").removePrefix("```").trim()
            if (text.endsWith("```")) text = text.dropLast(3).trim()
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw ApiException("Модель вернула ответ не в формате JSON")
        text = text.substring(start, end + 1)

        // Модели нередко не экранируют «\» в LaTeX (\omega, \frac) — экранируем сами. Правильный JSON
        // от этого не меняется: допустимые escape остаются как есть.
        val repaired = JSON_ESCAPE.replace(text) { m -> if (m.groups[2] != null) m.value else "\\" + m.value }
        val obj = try {
            json.parseToJsonElement(repaired).jsonObject
        } catch (e: Exception) {
            throw ApiException("Не удалось разобрать ответ модели")
        }
        // Обратные кавычки markdown на экране только мешают.
        fun clean(s: String) = s.replace("`", "").trim()
        val problem = obj["problem"]?.jsonPrimitive?.contentOrNull?.let(::clean) ?: "Условие не распознано"
        val answer = obj["answer"]?.jsonPrimitive?.contentOrNull?.let(::clean) ?: "—"
        val steps = (obj["steps"] as? JsonArray)?.mapNotNull { el ->
            when (el) {
                is JsonObject -> StepPayload(
                    title = el["title"]?.jsonPrimitive?.contentOrNull?.let(::clean) ?: "Шаг",
                    content = el["content"]?.jsonPrimitive?.contentOrNull?.let(::clean) ?: "",
                    plot = plot(el["plot"]),
                    svg = svg(el["svg"] ?: el["scheme"]),
                )

                is JsonPrimitive -> StepPayload(title = "Шаг", content = clean(el.content))
                else -> null
            }
        } ?: emptyList()
        // Бывает, что график или схему кладут не в шаг, а рядом с ответом, — тогда это отдельный шаг.
        val loosePlots = (listOf(obj["plot"]) + ((obj["plots"] as? JsonArray) ?: emptyList())).mapNotNull(::plot)
        val plotSteps = loosePlots.map { StepPayload(title = it.title ?: "График", content = "", plot = it) }
        val schemeSteps = listOfNotNull(svg(obj["svg"] ?: obj["scheme"]))
            .map { StepPayload(title = "Схема", content = "", svg = it) }
        return SolutionPayload(problem, answer, steps + plotSteps + schemeSteps)
    }
}

private data class HttpResponse(
    val code: Int,
    val isSuccessful: Boolean,
    val body: String,
)

/** Читает тело в callback OkHttp; отмена корутины закрывает сокет и освобождает response. */
private suspend fun Call.awaitResponse(): HttpResponse = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (!continuation.isActive) {
                response.close()
                return
            }
            val result = try {
                response.use {
                    HttpResponse(
                        code = it.code,
                        isSuccessful = it.isSuccessful,
                        body = it.body?.string().orEmpty(),
                    )
                }
            } catch (e: Exception) {
                continuation.resumeWithException(e)
                return
            }
            continuation.resume(result)
        }
    })
}
