package com.smartcalc.app.data.api

import com.smartcalc.app.data.settings.ApiSettings
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiClientCancellationTest {

    @Test
    fun cancellingRequestCompletesPromptlyAndClosesConnection() = runBlocking {
        supervisorScope {
            ServerSocket(0).use { server ->
            val accepted = CompletableFuture<Socket>()
            val requestReceived = CountDownLatch(1)
            val clientClosed = CompletableFuture<Unit>()
            val serverThread = thread(name = "openai-cancellation-test-server", isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        accepted.complete(socket)
                        socket.soTimeout = 5_000
                        readHttpRequest(socket.getInputStream())
                        requestReceived.countDown()
                        try {
                            if (socket.getInputStream().read() == -1) {
                                clientClosed.complete(Unit)
                            } else {
                                clientClosed.completeExceptionally(
                                    AssertionError("Client sent unexpected bytes after the request"),
                                )
                            }
                        } catch (_: SocketException) {
                            clientClosed.complete(Unit)
                        }
                    }
                } catch (t: Throwable) {
                    accepted.completeExceptionally(t)
                    clientClosed.completeExceptionally(t)
                    requestReceived.countDown()
                }
            }
                val call = async(Dispatchers.IO) {
                    OpenAiClient().testConnection(
                        ApiSettings(
                            baseUrl = "http://127.0.0.1:${server.localPort}",
                            apiKey = "test-key",
                            model = "test-model",
                        ),
                    )
                }

                try {
                    assertTrue("Server did not receive the request", requestReceived.await(3, TimeUnit.SECONDS))

                    withTimeout(1_000) {
                        call.cancelAndJoin()
                    }
                    clientClosed.get(1, TimeUnit.SECONDS)

                    assertTrue(call.isCancelled)
                } finally {
                    accepted.getNow(null)?.close()
                    server.close()
                    call.cancel()
                    withTimeout(2_000) { call.join() }
                    serverThread.join(2_000)
                }
            }
        }
    }

    private fun readHttpRequest(input: InputStream) {
        val headerBytes = ByteArrayOutputStream()
        var tail = 0
        while (tail != 0x0d0a0d0a) {
            val next = input.read()
            check(next >= 0) { "Connection closed before request headers were complete" }
            headerBytes.write(next)
            tail = (tail shl 8 or next) and 0xffffffff.toInt()
        }
        val headers = headerBytes.toString(StandardCharsets.ISO_8859_1.name())
        val contentLength = headers.lineSequence()
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.toInt()
            ?: 0
        repeat(contentLength) {
            check(input.read() >= 0) { "Connection closed before request body was complete" }
        }
    }
}
