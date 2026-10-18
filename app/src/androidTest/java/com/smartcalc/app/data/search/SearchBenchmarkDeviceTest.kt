package com.smartcalc.app.data.search

import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.smartcalc.app.data.library.LibraryMetadata
import com.smartcalc.app.data.library.LibraryVerification
import com.smartcalc.app.data.library.agent.LibraryCatalog
import com.smartcalc.app.data.library.agent.LibraryTools
import com.smartcalc.app.data.library.agent.LibrarySearchIndex
import com.smartcalc.app.data.template.TaskTemplate
import com.smartcalc.app.data.template.TemplateExample
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlin.math.ceil
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in, offline measurement of production search. Never opens stores or provider settings. */
class SearchBenchmarkDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val json = Json { prettyPrint = true }
    private var sink = 0

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.ids(key: String) = get(key)?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    private fun uuid(id: String) = UUID.nameUUIDFromBytes(id.toByteArray(Charsets.UTF_8)).toString()
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun idsJson(ids: List<String>) = buildJsonArray { ids.forEach { add(it) } }

    private fun examples(data: JsonObject, query: JsonObject? = null): List<TemplateExample> {
        val disabled = query?.ids("disabled_ids").orEmpty()
        val overlay = query?.get("example_overlay")?.jsonObject
        return data.getValue("examples").jsonArray.map { element ->
            val e = element.jsonObject
            val id = e.text("id")
            TemplateExample(
                condition = e.text("condition"),
                solution = e.text("solution") + if (overlay != null && overlay.text("id") == id) "\n" + overlay.text("append_solution") else "",
                answer = e.text("answer"),
                library = LibraryMetadata(
                    id = uuid(id),
                    enabled = e["enabled"]?.jsonPrimitive?.boolean != false && id !in disabled,
                    verification = if (e["verification"]?.jsonPrimitive?.content == "rejected")
                        LibraryVerification.REJECTED else LibraryVerification.UNVERIFIED,
                ),
            )
        }
    }

    private fun sections(examples: List<TemplateExample>) = listOf(
        TaskTemplate(id = "benchmark-section", name = "Синтетические задачи", examples = examples, updated = 0),
    )

    private fun queries(data: JsonObject) = data.getValue("queries").jsonArray.flatMap { raw ->
        val q = raw.jsonObject
        q["subqueries"]?.jsonArray?.map { sub ->
            JsonObject(sub.jsonObject + ("class" to q.getValue("class")))
        } ?: listOf(q)
    }

    private suspend fun quality(data: JsonObject): JsonObject {
        val sharedIndex = LibrarySearchIndex()
        val readable = data.getValue("examples").jsonArray.associate { e ->
            e.jsonObject.text("id").let { uuid(it) to it }
        }
        val rows = queries(data).map { q ->
            val catalog = LibraryCatalog(sections(examples(data, q)))
            val entries = TaskSearch.entries(catalog.sections, emptyList(), emptyList())
            val hits = TaskSearch.search(entries, q.text("text"))
            val ranked = hits.map { readable.getValue(it.entry.library!!.id) }
            val reverse = TaskSearch.search(entries.reversed(), q.text("text"))
                .map { readable.getValue(it.entry.library!!.id) }
            val tools = LibraryTools(sharedIndex) { catalog }
            val tool = tools.execute("search_examples", buildJsonObject { put("query", q.text("text")) }.toString())
            val toolIds = tool["matches"]?.jsonArray?.map { readable.getValue(it.jsonObject.text("id")) }.orEmpty()
            assertEquals("Manual and tool search must agree", ranked.take(5), toolIds)
            val reused = TaskSearch.search(sharedIndex.entries(catalog), q.text("text"))
            assertEquals(hits.map { it.entry.key }, reused.map { it.entry.key })
            assertEquals(hits.map { it.score }, reused.map { it.score })
            assertTrue("Disabled/rejected entry must never be returned", ranked.none { it in q.ids("disabled_ids") || it == "E12" })
            val relevant = q.ids("relevant")
            val rank = ranked.indexOfFirst { it in relevant }.let { if (it < 0) 0 else it + 1 }
            buildJsonObject {
                put("id", q.text("id")); put("class", q.text("class")); put("query", q.text("text"))
                put("indexed_count", entries.size); put("relevant", idsJson(relevant))
                put("must_rank_first", idsJson(q.ids("must_rank_first")))
                put("top5", idsJson(ranked.take(5))); put("reverse_order_top1", reverse.firstOrNull()?.let(::JsonPrimitive) ?: JsonNull)
                put("top1_relevant", ranked.firstOrNull() in relevant)
                put("top1_required", ranked.firstOrNull() in q.ids("must_rank_first"))
                put("hit3", ranked.take(3).any { it in relevant }); put("first_relevant_rank", rank)
                put("hard_negatives_top3", idsJson(ranked.take(3).filter { it in q.ids("hard_negatives") }))
                put("hard_negatives_top5", idsJson(ranked.take(5).filter { it in q.ids("hard_negatives") }))
                put("result_count", hits.size); put("tool_status", tool.text("status"))
                put("requires_clarification", q["requires_clarification"]?.jsonPrimitive?.boolean == true)
                put("scores_top5", buildJsonArray { hits.take(5).forEach { add(it.score) } })
            }
        }
        val positives = rows.filter { it.ids("relevant").isNotEmpty() }
        val negatives = rows.filter { it.ids("relevant").isEmpty() && !it.getValue("requires_clarification").jsonPrimitive.boolean }
        fun count(key: String) = positives.count { it.getValue(key).jsonPrimitive.boolean }
        return buildJsonObject {
            put("query_count", rows.size); put("positive_count", positives.size)
            put("top1_relevant_count", count("top1_relevant")); put("top1_required_count", count("top1_required"))
            put("hit3_count", count("hit3"))
            put("mrr", positives.sumOf { val rank = it.getValue("first_relevant_rank").jsonPrimitive.int; if (rank == 0) 0.0 else 1.0 / rank } / positives.size)
            put("negative_count", negatives.size); put("empty_negative_count", negatives.count { it.getValue("result_count").jsonPrimitive.int == 0 })
            put("hard_negative_top3_query_count", positives.count { it.ids("hard_negatives_top3").isNotEmpty() })
            put("order_sensitive_top1_query_count", positives.count { it.ids("top5").firstOrNull() != it["reverse_order_top1"]?.jsonPrimitive?.contentOrNull })
            put("rows", JsonArray(rows))
        }
    }

    /** Varied generated load only: these records are not relevance judgments. */
    private fun loadCorpus(data: JsonObject, size: Int): List<TaskTemplate> {
        val result = examples(data).filter { e -> e.library?.let { it.enabled && it.verification != LibraryVerification.REJECTED } == true }.toMutableList()
        var i = 0
        while (result.size < size) {
            val a = 2 + i / 8
            val b = 3 + i % 17
            val (condition, solution, answer) = when (i % 8) {
                0 -> Triple("На резисторе $a Ом напряжение $b В. Найти ток.", "По закону Ома I=U/R=$b/$a.", "${b.toDouble()/a} А")
                1 -> Triple("Автомобиль движется $a км/ч в течение $b часов. Найти путь.", "Путь s=v*t=$a*$b.", "${a*b} км")
                2 -> Triple("Тело массой $a кг испытывает силу $b Н. Найти ускорение.", "Второй закон Ньютона: a=F/m=$b/$a.", "${b.toDouble()/a} м/с^2")
                3 -> Triple("Прямоугольник со сторонами $a м и $b м. Найти площадь.", "Площадь S=a*b=$a*$b.", "${a*b} м^2")
                4 -> Triple("Тело массой $a кг имеет скорость $b м/с. Найти кинетическую энергию.", "E=m*v^2/2=$a*$b^2/2.", "${a*b*b/2.0} Дж")
                5 -> Triple("Решить x^2-${a+b}x+${a*b}=0.", "(x-$a)*(x-$b)=0.", "x=$a или x=$b")
                6 -> Triple("Найти АЧХ и ФЧХ звена W(s)=$a/(${b}s+1).", "Подставить s=j*omega, найти модуль и аргумент.", "A=$a/sqrt(1+${b*b}*omega^2); phi=-atan($b*omega)")
                else -> Triple("Для RC-цепи R=$a Ом и C=$b мкФ найти постоянную времени.", "Перевести микрофарады в фарады, tau=R*C.", "${a*b/1000000.0} с")
            }
            result += TemplateExample(condition = condition, solution = solution, answer = answer, library = LibraryMetadata(id = uuid("load-$i")))
            i++
        }
        return sections(result.take(size))
    }

    private fun timed(block: () -> Unit): Double {
        val start = System.nanoTime(); block(); return (System.nanoTime() - start) / 1_000_000.0
    }

    private fun distribution(samples: List<Double>): JsonObject {
        val sorted = samples.sorted()
        fun percentile(p: Double) = sorted[(ceil(p * sorted.size).toInt() - 1).coerceAtLeast(0)]
        return buildJsonObject {
            put("n", samples.size); put("p50_ms", percentile(0.5)); put("p95_ms", percentile(0.95))
            put("p99_ms", percentile(0.99)); put("max_ms", sorted.last()); put("mean_ms", samples.average())
            put("samples_ms", buildJsonArray { samples.forEach { add(it) } })
        }
    }

    @Test fun measureCurrentSearchOffline() = runBlocking {
        assumeTrue("Opt in with -e searchBenchmark true", args.getString("searchBenchmark") == "true")
        val context = instrumentation.targetContext
        check(context.packageName == "com.smartcalc.app.importcheck") { "Use isolatedDeviceCheck; never benchmark in the user's app" }
        val sourceBytes = instrumentation.context.assets.open("benchmark.json").use { it.readBytes() }
        val controlBytes = instrumentation.context.assets.open("search-holdout.json").use { it.readBytes() }
        val source = json.parseToJsonElement(sourceBytes.toString(Charsets.UTF_8)).jsonObject
        val control = json.parseToJsonElement(controlBytes.toString(Charsets.UTF_8)).jsonObject
        val large = loadCorpus(source, 500)
        // First search/index construction in this instrumentation process, before any warm-up.
        val firstUse = timed { sink += TaskSearch.search(TaskSearch.entries(large, emptyList(), emptyList()), "Найти АЧХ и ФЧХ W(s)=4/(0.5s+1).").size }
        val report = buildJsonObject {
            put("timestamp_utc", Instant.now().toString()); put("source_commit", args.getString("sourceCommit") ?: "unspecified")
            put("app_version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
            put("build_type", "debug, isolated instrumentation, unminified")
            put("device_model", Build.MODEL); put("android_api", Build.VERSION.SDK_INT); put("android_release", Build.VERSION.RELEASE)
            put("abis", idsJson(Build.SUPPORTED_ABIS.toList())); put("available_processors", Runtime.getRuntime().availableProcessors())
            put("benchmark_sha256", digest(sourceBytes)); put("holdout_sha256", digest(controlBytes))
            put("first_use_index_and_search_500_ms", firstUse)
            put("evaluation", "shared agent index; unchanged ranking; fresh-index control in the same run")
            if (args.getString("searchBenchmarkMode") != "cold") {
                put("reference_quality", quality(source)); put("control_quality", quality(control))
                val perfQueries = queries(source).filter { it.text("id") in setOf("Q01", "Q02", "Q06", "Q13", "Q16", "Q17") }
                put("performance", buildJsonArray {
                    for (size in listOf(12, 100, 500)) {
                        val library = loadCorpus(source, size)
                        val entries = TaskSearch.entries(library, emptyList(), emptyList())
                        assertEquals(size, entries.size)
                        val sharedIndex = LibrarySearchIndex()
                        var currentCatalog = LibraryCatalog(library)
                        val cached = mutableListOf<Double>(); val indexing = mutableListOf<Double>()
                        val tools = mutableListOf<Double>(); val freshTools = mutableListOf<Double>(); val reads = mutableListOf<Double>()
                        Log.i("SearchBenchmark", "Starting $size active entries")
                        repeat(120) { n ->
                            val q = perfQueries[n % perfQueries.size].text("text")
                            val cachedMs = timed { sink += TaskSearch.search(entries, q).size }
                            val indexMs = timed { sink += TaskSearch.entries(library, emptyList(), emptyList()).size }
                            val request = LibraryTools(sharedIndex) { currentCatalog.copy() }
                            val arguments = buildJsonObject { put("query", q) }.toString()
                            val start = System.nanoTime()
                            val response = request.execute("search_examples", arguments)
                            sink += response.size
                            val toolsMs = (System.nanoTime() - start) / 1_000_000.0
                            val firstHit = response["matches"]?.jsonArray?.firstOrNull()?.jsonObject
                            val readMs = if (firstHit != null) {
                                val readArgs = buildJsonObject { put("id", firstHit.getValue("id")); put("version", firstHit.getValue("version")) }.toString()
                                val readStart = System.nanoTime()
                                val read = request.execute("read_example", readArgs)
                                val elapsed = (System.nanoTime() - readStart) / 1_000_000.0
                                assertEquals("ok", read.text("status"))
                                sink += read.size
                                elapsed
                            } else null
                            val uncachedRequest = LibraryTools { currentCatalog }
                            val freshStart = System.nanoTime()
                            val uncachedResponse = uncachedRequest.execute("search_examples", arguments)
                            val freshMs = (System.nanoTime() - freshStart) / 1_000_000.0
                            assertEquals(uncachedResponse, response)
                            if (n >= 20) {
                                cached += cachedMs; indexing += indexMs; tools += toolsMs; freshTools += freshMs
                                if (readMs != null) reads += readMs
                            }
                        }
                        // A policy change preserves ranking but must invalidate cached metadata.
                        currentCatalog = currentCatalog.copy(sections = library.mapIndexed { sectionIndex, section ->
                            if (sectionIndex != 0) section else section.copy(examples = section.examples.mapIndexed { exIndex, ex ->
                                if (exIndex != 0) ex else ex.copy(library = ex.library!!.copy(verification = LibraryVerification.VERIFIED_BY_USER))
                            })
                        })
                        val changedArgs = buildJsonObject { put("query", perfQueries.first().text("text")) }.toString()
                        val changedStart = System.nanoTime()
                        sink += LibraryTools(sharedIndex) { currentCatalog }.execute("search_examples", changedArgs).size
                        val changedMs = (System.nanoTime() - changedStart) / 1_000_000.0
                        val followingStart = System.nanoTime()
                        sink += LibraryTools(sharedIndex) { currentCatalog }.execute("search_examples", changedArgs).size
                        val followingMs = (System.nanoTime() - followingStart) / 1_000_000.0
                        Log.i("SearchBenchmark", "$size entries: shared p95=${tools.sorted()[94]} ms; fresh p95=${freshTools.sorted()[94]} ms")
                        add(buildJsonObject {
                            put("active_entries", size); put("warmup", 20); put("query_ids", idsJson(perfQueries.map { it.text("id") }))
                            put("cached_search", distribution(cached)); put("index_build", distribution(indexing))
                            put("tool_search_with_shared_index", distribution(tools)); put("tool_search_with_fresh_index", distribution(freshTools))
                            put("tool_read_with_shared_index", distribution(reads))
                            put("first_tool_search_after_policy_change_ms", changedMs); put("following_tool_search_after_policy_change_ms", followingMs)
                            put("condition_solution_answer_characters", library.sumOf { section -> section.examples.sumOf { it.condition.length + it.solution.length + it.answer.length } })
                        })
                    }
                })
            }
            put("sink", sink)
        }
        val output = File(context.filesDir, "search-benchmark-result.json")
        output.writeText(report.toString(), Charsets.UTF_8)
        Log.i("SearchBenchmark", "Result: ${output.absolutePath}")
        println("SEARCH_BENCHMARK_RESULT=${output.absolutePath}")
    }
}
