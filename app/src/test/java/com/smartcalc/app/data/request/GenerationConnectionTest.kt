package com.smartcalc.app.data.request

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.smartcalc.app.data.format.SolutionFormat
import com.smartcalc.app.data.history.HistoryStore
import com.smartcalc.app.data.library.agent.LibraryCatalog
import com.smartcalc.app.data.session.SessionStore
import com.smartcalc.app.data.settings.ApiConnection
import com.smartcalc.app.data.settings.ApiFormat
import com.smartcalc.app.data.settings.SettingsRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real controller/runner/checkpoints and loopback HTTP; no Android context or real credentials. */
class GenerationConnectionTest {
    @get:Rule val temporary = TemporaryFolder()

    private suspend fun fixture(startService: (SettingsRepository) -> Unit = {},
        block: suspend (GenerationController, SettingsRepository, HistoryStore, File) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val root = temporary.newFolder()
        try {
            val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(root, "settings.preferences_pb") })
            val history = HistoryStore(root)
            val controller = GenerationController(scope, history, SessionStore(File(root, "session.json"), scope),
                currentFormat = { SolutionFormat() }, readFormatSample = { null },
                loadTemplatePrompt = { error("No template in fixture") }, libraryCatalog = { LibraryCatalog(emptyList()) },
                requestDirectory = File(root, "requests"), startService = { startService(settings) },
                loadGallery = { error("Not used") }, loadDocument = { error("Not used") })
            block(controller, settings, history, root)
        } finally { scope.cancel() }
    }

    @Test fun delayedAttachmentKeepsAAndExplicitRetryUsesB() = runBlocking {
        LoopbackServer(listOf(503 to null)).use { a ->
            LoopbackServer(listOf(200 to finalMessage())).use { b -> fixture { controller, settings, history, root ->
                val first = a.connection("A")
                val second = b.connection("B")
                settings.saveConnection(first, activate = true)
                settings.saveConnection(second)
                settings.saveSolving("prompt-A", libraryToolsEnabled = false)
                val preparing = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                controller.submit(settings.settings.first(), "Attachment") {
                    preparing.complete(Unit)
                    release.await()
                    PreparedRequest(text = "synthetic attachment", images = listOf("AAAA"))
                }
                withTimeout(5000) { preparing.await() }
                settings.selectConnection(second.id)
                settings.saveSolving("prompt-B", libraryToolsEnabled = false)
                release.complete(Unit)
                withTimeout(10000) { controller.state.first { it?.status in listOf(RequestStatus.FAILED, RequestStatus.SUCCEEDED) } }
                assertTrue("First attempt must use A", a.matches(first, "prompt-A", expected = 1))
                assertTrue("B must not receive the first attempt", b.requests.isEmpty())
                assertNoRequestSecrets(root, first.apiKey, second.apiKey)

                controller.retry(settings.settings.first())
                controller.awaitStatus(RequestStatus.SUCCEEDED)
                assertTrue("Explicit retry must use B", b.matches(second, "prompt-B", expected = 1))
                assertEquals(1, a.requests.size)
                assertEquals(1, history.items.value.size)
                assertNoRequestSecrets(root, first.apiKey, second.apiKey)
            } }
        }
    }

    @Test fun switchingDuringToolRoundKeepsEveryPostOnA() = runBlocking {
        val received = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Unit>()
        LoopbackServer(listOf(200 to toolMessage(), 200 to finalMessage()), received, reply).use { a ->
            LoopbackServer(listOf(200 to finalMessage())).use { b -> fixture { controller, settings, _, root ->
                val first = a.connection("A")
                val second = b.connection("B")
                settings.saveConnection(first, activate = true)
                settings.saveConnection(second)
                settings.saveSolving("tool-prompt-A", libraryToolsEnabled = true)
                controller.text("Найди похожую задачу", null, settings.settings.first())
                withTimeout(5000) { received.await() }
                settings.selectConnection(second.id)
                settings.saveSolving("tool-prompt-B", libraryToolsEnabled = false)
                reply.complete(Unit)
                controller.awaitStatus(RequestStatus.SUCCEEDED)
                assertTrue("Every tool round must use A", a.matches(first, "tool-prompt-A", expected = 2))
                assertTrue(b.requests.isEmpty())
                assertNoRequestSecrets(root, first.apiKey, second.apiKey)
            } }
        }
    }

    @Test fun snapshotIsAlreadyFixedBeforeServiceStartupOrAnyCoroutineWork() = runBlocking {
        LoopbackServer(listOf(200 to finalMessage())).use { a ->
            LoopbackServer(listOf(200 to finalMessage())).use { b ->
                fixture(startService = { repo -> runBlocking {
                    repo.selectConnection("B")
                    repo.saveSolving("after-submit", libraryToolsEnabled = false)
                } }) { controller, settings, _, root ->
                    val first = a.connection("A")
                    val second = b.connection("B")
                    settings.saveConnection(first, activate = true)
                    settings.saveConnection(second)
                    settings.saveSolving("before-submit", libraryToolsEnabled = false)
                    // As in SolutionViewModel: the immutable loaded value is handed to submit synchronously.
                    val snapshot = settings.settings.first()
                    controller.text("synthetic task", null, snapshot)
                    controller.awaitStatus(RequestStatus.SUCCEEDED)
                    assertTrue("Capture must precede service startup", a.matches(first, "before-submit", 1))
                    assertTrue(b.requests.isEmpty())
                    assertNoRequestSecrets(root, first.apiKey, second.apiKey)
                }
            }
        }
    }

    @Test fun unloadedSettingsFailBeforePreparingOrSendingInsteadOfChoosingALaterConnection() = runBlocking {
        fixture { controller, _, _, _ ->
            var prepared = false
            controller.submit(null, "not loaded") { prepared = true; PreparedRequest(text = "synthetic") }
            controller.awaitStatus(RequestStatus.FAILED)
            assertFalse(prepared)
            assertTrue(controller.state.value!!.error!!.contains("ещё загружаются"))
        }
    }

    @Test fun serverEchoedCredentialsNeverEnterResponseCheckpointOrHistory() = runBlocking {
        val echo = buildJsonObject {
            put("role", "assistant")
            put("content", """{"problem":"fixture-attempt-A","answer":"fixture-attempt-A","steps":[{"title":"fixture-attempt-A","content":"fixture-attempt-A","svg":"fixture-attempt-A"}]}""")
        }
        LoopbackServer(listOf(200 to echo)).use { server -> fixture { controller, settings, _, root ->
            val connection = server.connection("A")
            settings.saveConnection(connection, activate = true)
            val historyTarget = File(root, "history.json").apply { mkdir() }
            File(historyTarget, "blocker").writeText("synthetic")
            controller.text("synthetic task", null, settings.settings.first())
            controller.awaitStatus(RequestStatus.FAILED)
            val checkpoint = RequestFiles(File(root, "requests/request")).readResult(controller.state.value!!.id)
            assertNotNull(checkpoint)
            assertNoRequestSecrets(root, connection.apiKey)
            File(historyTarget, "blocker").delete()
            historyTarget.delete()
            controller.retry(null)
            controller.awaitStatus(RequestStatus.SUCCEEDED)
            assertNoRequestSecrets(root, connection.apiKey)
            assertEquals(1, server.requests.size)
        } }
    }

    @Test fun explicitRetryPublishesCheckpointWithoutAnyNewHttp() = runBlocking {
        LoopbackServer(listOf(200 to finalMessage())).use { a ->
            LoopbackServer(listOf(200 to finalMessage())).use { b -> fixture { controller, settings, history, root ->
                val first = a.connection("A")
                val second = b.connection("B")
                settings.saveConnection(first, activate = true)
                settings.saveConnection(second)
                // History cannot replace a non-empty directory, after the response has been checkpointed.
                val historyTarget = File(root, "history.json").apply { mkdir() }
                File(historyTarget, "blocker").writeText("synthetic")
                controller.text("synthetic task", null, settings.settings.first())
                controller.awaitStatus(RequestStatus.FAILED)
                val id = controller.state.value!!.id
                assertNotNull(RequestFiles(File(root, "requests/request")).readResult(id))
                settings.selectConnection(second.id)
                File(historyTarget, "blocker").delete()
                historyTarget.delete()
                controller.retry(null) // A checkpoint needs neither loaded settings nor credentials.
                controller.awaitStatus(RequestStatus.SUCCEEDED)
                assertEquals(1, a.requests.size)
                assertTrue("Checkpoint retry must never call B", b.requests.isEmpty())
                assertEquals(1, history.items.value.size)
                assertNoRequestSecrets(root, first.apiKey, second.apiKey)
            } }
        }
    }

    private suspend fun GenerationController.awaitStatus(status: RequestStatus) =
        withTimeout(10000) { state.first { it?.status == status } }

    private fun assertNoRequestSecrets(root: File, vararg secrets: String) {
        root.walkTopDown().filter { it.isFile && it.extension != "preferences_pb" }.forEach { file ->
            val text = try { file.readText() } catch (_: java.io.FileNotFoundException) { "" } // Success cleanup may remove checkpoints.
            assertTrue("Credentials must not enter persisted request/history/diagnostics", secrets.none(text::contains))
        }
    }
}

private fun finalMessage() = buildJsonObject {
    put("role", "assistant")
    put("content", """{"problem":"synthetic task","answer":"4","steps":[]}""")
}

private fun toolMessage() = buildJsonObject {
    put("role", "assistant")
    putJsonArray("tool_calls") {
        add(buildJsonObject {
            put("id", "search-1"); put("type", "function")
            put("function", buildJsonObject { put("name", "search_examples"); put("arguments", """{"query":"synthetic"}""") })
        })
    }
}

private class LoopbackServer(
    replies: List<Pair<Int, JsonObject?>>,
    private val received: CompletableDeferred<Unit>? = null,
    private val release: CompletableDeferred<Unit>? = null,
) : AutoCloseable {
    // Credentials are compared only in memory and never appear in assertion output.
    data class Received(val authorization: String, val body: JsonObject)
    val requests = CopyOnWriteArrayList<Received>()
    private val pending = ArrayDeque(replies)
    private val server = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
    private val worker = thread(isDaemon = true, name = "connection-attempt-fixture") {
        while (!server.isClosed) try { server.accept().use { socket ->
            socket.soTimeout = 10000
            val input = socket.getInputStream()
            val headers = ByteArrayOutputStream()
            var tail = 0
            while (tail != 0x0d0a0d0a) {
                val next = input.read(); check(next >= 0 && headers.size() < 32000)
                headers.write(next); tail = (tail shl 8) or next
            }
            val lines = headers.toString("ISO-8859-1").lineSequence().toList()
            val length = lines.first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
            check(length in 0..4000000)
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) { val n = input.read(bytes, offset, length - offset); check(n > 0); offset += n }
            val auth = lines.first { it.startsWith("Authorization:", true) }.substringAfter(':').trim()
            requests += Received(auth, Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject)
            if (requests.size == 1) {
                received?.complete(Unit)
                release?.let { runBlocking { withTimeout(10000) { it.await() } } }
            }
            val (code, message) = if (pending.isEmpty()) 500 to null else pending.removeFirst()
            val response = if (message == null) "{}" else buildJsonObject {
                putJsonArray("choices") { add(buildJsonObject { put("message", message) }) }
            }.toString()
            val responseBytes = response.toByteArray(Charsets.UTF_8)
            val output = socket.getOutputStream()
            output.write("HTTP/1.1 $code Response\r\nContent-Type: application/json\r\nContent-Length: ${responseBytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.write(responseBytes); output.flush()
        } } catch (_: java.net.SocketException) { if (!server.isClosed) error("Loopback socket failed") }
    }
    fun connection(id: String) = ApiConnection(id = id, name = id, baseUrl = "http://127.0.0.1:${server.localPort}",
        apiKey = "fixture-attempt-$id", model = "model-$id", supportsImages = true, apiFormat = ApiFormat.ENABLE_THINKING)
    fun matches(connection: ApiConnection, prompt: String, expected: Int) = requests.size == expected && requests.all {
        it.authorization == "Bearer ${connection.apiKey}" && it.body["model"]?.jsonPrimitive?.content == connection.model &&
            it.body["enable_thinking"]?.jsonPrimitive?.boolean == connection.thinkingEnabled &&
            it.body["messages"]!!.jsonArray.any { message -> message.jsonObject["content"]?.toString()?.contains(prompt) == true }
    }
    override fun close() { release?.complete(Unit); server.close(); worker.join(1000) }
}
