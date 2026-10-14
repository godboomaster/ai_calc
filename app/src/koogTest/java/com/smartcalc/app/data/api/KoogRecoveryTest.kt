package com.smartcalc.app.data.api

import com.smartcalc.app.data.history.*
import com.smartcalc.app.data.library.LibraryMetadata
import com.smartcalc.app.data.library.agent.*
import com.smartcalc.app.data.request.*
import com.smartcalc.app.data.settings.ApiSettings
import com.smartcalc.app.data.template.*
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real Koog + durable request/history stores. No app preferences or user files are opened.
 * Scope disposal models process loss; actual OS termination is checked separately on device.
 */
class KoogRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val exampleId = "11111111-1111-1111-1111-111111111111"
    private fun record() = RequestRecord("recovery-fixture", RequestStatus.PREPARING, "6*7", retryText = "6*7")
    private fun settings(server: MockWebServer) = ApiSettings(
        baseUrl = server.url("/v1").newBuilder().host("127.0.0.1").build().toString(),
        apiKey = "synthetic-key", model = "deepseek-chat")
    private fun response(message: JsonObject) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody(buildJsonObject {
            put("id", "fixture"); put("object", "chat.completion"); put("created", 1)
            put("model", "deepseek-chat"); put("system_fingerprint", "fixture")
            put("choices", buildJsonArray { add(buildJsonObject {
                put("index", 0); put("message", message)
                put("finish_reason", if (message.containsKey("tool_calls")) "tool_calls" else "stop")
            }) })
            put("usage", buildJsonObject { put("prompt_tokens", 1); put("completion_tokens", 1); put("total_tokens", 2) })
        }.toString())
    private fun answer() = response(buildJsonObject {
        put("role", "assistant"); put("content", """{"problem":"6*7","answer":"42","steps":[]}""")
    })
    private fun call(name: String, args: String) = response(buildJsonObject {
        put("role", "assistant"); put("content", JsonNull); put("reasoning_content", "fixture-private-reasoning")
        put("tool_calls", buildJsonArray { add(buildJsonObject {
            put("id", name); put("type", "function")
            put("function", buildJsonObject { put("name", name); put("arguments", args) })
        }) })
    })
    private suspend fun <T> bounded(block: suspend () -> T): T = withTimeout(10_000) { block() }
    private suspend fun scopeTest(block: suspend (CoroutineScope, MockWebServer) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try { block(scope, server) } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); server.shutdown() }
    }

    @Test fun completionWithoutScreenPersistsExactlyOneAnswer() = runBlocking { scopeTest { scope, server ->
        val journalFile = File(temporary.root, "journal.json")
        val files = RequestFiles(File(temporary.root, "payload"))
        val history = HistoryStore(temporary.root)
        val runner = RequestRunner<Solution>(scope, RequestJournal(journalFile), commit = { id, result ->
            files.saveResult(id, result); history.update(result); result.id
        })
        server.enqueue(answer().setHeadersDelay(200, TimeUnit.MILLISECONDS))
        runner.start(record(), {}, {
            files.saveInput(record().id, PreparedRequest(text = "6*7"))
            val payload = OpenAiClient(generationTransport()).solve(settings(server), emptyList(), "6*7")
            Solution(id = record().id, problem = payload.problem, answer = payload.answer)
        })
        val done = bounded { runner.state.first { it != null && !it.active } }!!
        assertEquals(done.error, RequestStatus.SUCCEEDED, done.status)
        assertEquals("42", HistoryStore(temporary.root).items.value.single().answer)
        assertTrue(RequestJournal(journalFile).state.value!!.unread)
        assertEquals(1, server.requestCount)
    } }

    @Test fun lostInFlightCallRestoresInterruptedWithoutAutomaticPost() = runBlocking { scopeTest { scope, server ->
        val journalFile = File(temporary.root, "journal.json")
        val files = RequestFiles(File(temporary.root, "payload"))
        server.enqueue(answer().setHeadersDelay(2, TimeUnit.SECONDS))
        val runner = RequestRunner<Solution>(scope, RequestJournal(journalFile), commit = { _, _ -> error("No answer yet") })
        runner.start(record(), {}, {
            files.saveInput(record().id, PreparedRequest(text = "6*7"))
            val payload = OpenAiClient(generationTransport()).solve(settings(server), emptyList(), "6*7")
            Solution(problem = payload.problem, answer = payload.answer)
        })
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
        scope.coroutineContext[Job]!!.cancelAndJoin()
        val restoredScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val restored = RequestRunner<Solution>(restoredScope, RequestJournal(journalFile),
                commit = { _, _ -> error("Recovery must not send") })
            bounded { restored.state.first { it?.status == RequestStatus.INTERRUPTED } }
            assertEquals("6*7", files.readInput(record().id)!!.text)
            assertNull(files.readResult(record().id))
            assertEquals(1, server.requestCount)
            assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
        } finally { restoredScope.coroutineContext[Job]!!.cancelAndJoin() }
    } }

    @Test fun lossAfterReadingExampleKeepsReportAndRetryIsExplicit() = runBlocking { scopeTest { scope, server ->
        val journalFile = File(temporary.root, "journal.json")
        val files = RequestFiles(File(temporary.root, "payload"))
        val paused = CompletableDeferred<Unit>()
        val tools = LibraryTools { LibraryCatalog(listOf(TaskTemplate(name = "Fixture", examples = listOf(
            TemplateExample("Резистор 2 Ом, 6 В", solution = "I=U/R", answer = "3 A", library = LibraryMetadata(id = exampleId)))))) }
        server.enqueue(call("search_examples", """{"query":"резистор"}"""))
        server.enqueue(call("read_example", """{"id":"$exampleId","version":1}"""))
        val runner = RequestRunner<Solution>(scope, RequestJournal(journalFile), commit = { _, _ -> error("No final answer yet") })
        runner.start(record(), {}, {
            files.saveInput(record().id, PreparedRequest(text = "Найди похожую задачу про резистор"))
            OpenAiClient(generationTransport()).solve(settings(server), emptyList(), "Найди похожую задачу про резистор",
                libraryTools = tools, agentProgress = {
                    files.saveAgentReport(record().id, it)
                    if (it.examples.isNotEmpty()) { paused.complete(Unit); awaitCancellation() }
                })
            error("The final POST must not run")
        })
        bounded { paused.await() }
        scope.coroutineContext[Job]!!.cancelAndJoin()
        val restoredScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val history = HistoryStore(temporary.root)
            val restored = RequestRunner<Solution>(restoredScope, RequestJournal(journalFile),
                commit = { _, result -> history.update(result); result.id })
            val interrupted = bounded { restored.state.first { it?.status == RequestStatus.INTERRUPTED } }!!
            assertEquals(2, files.readAgentReport(record().id)!!.posts)
            assertEquals(exampleId, files.readAgentReport(record().id)!!.examples.single().id)
            assertEquals(2, server.requestCount)
            // An explicit new attempt starts afresh. It must not silently replay old tool turns.
            server.enqueue(answer())
            restored.start(interrupted.copy(status = RequestStatus.PREPARING, error = null, startedAt = interrupted.startedAt + 1), {}, {
                val input = files.readInput(record().id)!!
                val payload = OpenAiClient(generationTransport()).solve(settings(server), input.images, input.text)
                Solution(id = record().id, problem = payload.problem, answer = payload.answer)
            })
            bounded { restored.state.first { it?.status == RequestStatus.SUCCEEDED } }
            assertEquals(3, server.requestCount)
            assertEquals("42", HistoryStore(temporary.root).items.value.single().answer)
            val persisted = File(temporary.root, "payload").walkTopDown().filter { it.isFile }.joinToString { it.readText() }
            assertFalse(persisted.contains("synthetic-key"))
            assertFalse(persisted.contains("fixture-private-reasoning"))
        } finally { restoredScope.coroutineContext[Job]!!.cancelAndJoin() }
    } }

    @Test fun savedFollowUpRecoversTwiceWithoutNetworkOrDuplicates() = runBlocking {
        for (alreadyPublished in listOf(false, true)) {
            val directory = temporary.newFolder()
            val journalFile = File(directory, "journal.json")
            val files = RequestFiles(File(directory, "payload"))
            val base = Solution(id = "base", problem = "6*6", answer = "36")
            HistoryStore(directory).update(base)
            val completed = base.copy(followUps = listOf(FollowUp("Plus 6", "42")))
            files.saveResult(record().id, completed)
            if (alreadyPublished) HistoryStore(directory).update(completed, requireExisting = true)
            RequestJournal(journalFile).replace(record().copy(status = RequestStatus.RUNNING,
                followUp = true, baseSolutionId = base.id))
            repeat(2) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val runner = RequestRunner<Solution>(scope, RequestJournal(journalFile),
                        commit = { _, _ -> error("Recovery must not generate") }, recoverResult = { old ->
                            files.readResult(old.id)?.let { result ->
                                check(HistoryStore(directory).update(result, requireExisting = true)); result.id
                            }
                        })
                    bounded { runner.state.first { it?.status == RequestStatus.SUCCEEDED } }
                    val restored = HistoryStore(directory).items.value.single()
                    assertEquals("36", restored.answer)
                    assertEquals("42", restored.followUps.single().answer)
                } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
            }
        }
    }
}
