package com.smartcalc.app.data.api

import com.smartcalc.app.data.plot.Plot
import com.smartcalc.app.data.settings.ApiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ApiException(message: String, val httpCode: Int? = null) : Exception(message)

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

data class StepPayload(
    val title: String,
    val content: String,
    val plot: Plot? = null,
    val svg: String? = null,
)

data class SolutionPayload(
    val problem: String,
    val answer: String,
    val steps: List<StepPayload>,
)

class OpenAiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
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
     * (требования текстом и/или фото образца оформления).
     */
    suspend fun solve(
        settings: ApiSettings,
        images: List<String>,
        text: String?,
        formatText: String? = null,
        formatSampleBase64: String? = null,
    ): SolutionPayload =
        withContext(Dispatchers.IO) {
            val hasFormat = !formatText.isNullOrBlank() || formatSampleBase64 != null
            val userContent: JsonArray = buildJsonArray {
                if (hasFormat) {
                    add(textPart(formatInstructions(formatText, formatSampleBase64 != null)))
                    if (formatSampleBase64 != null) add(imagePart(formatSampleBase64))
                }
                val task = when {
                    text != null -> if (hasFormat) "Задача: $text" else text
                    images.size > 1 -> "Реши задачу со страниц документа на следующих изображениях."
                    // Рядом с образцом нужно явно указать, какое фото — задача.
                    formatSampleBase64 != null -> "Задача, которую нужно решить, — на следующем фото."
                    else -> "Реши задачу на фото."
                }
                add(textPart(task))
                images.forEach { add(imagePart(it)) }
            }
            val messages = buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put(
                        "content",
                        listOf(settings.systemPrompt.trimEnd(), FORMULA_RULE, PLOT_RULE, SCHEME_RULE).joinToString("\n"),
                    )
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userContent)
                })
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
            parseSolution(raw)
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

    private fun execute(settings: ApiSettings, body: JsonObject): String {
        if (settings.apiKey.isBlank()) {
            throw ApiException("API-ключ не задан. Откройте настройки и введите ключ.")
        }
        val url = settings.baseUrl.trim().trimEnd('/') + "/chat/completions"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${settings.apiKey}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            val respBody = resp.body?.string().orEmpty()
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
            return root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                ?: throw ApiException("Модель вернула пустой ответ")
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
