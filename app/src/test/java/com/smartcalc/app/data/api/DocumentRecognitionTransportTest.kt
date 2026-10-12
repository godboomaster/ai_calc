package com.smartcalc.app.data.api

import com.smartcalc.app.data.settings.ApiSettings
import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

class DocumentRecognitionTransportTest {
    private suspend fun serve(response: (Socket, Int) -> Unit, check: suspend (Int, AtomicInteger) -> Unit) {
        ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")).use { server ->
            val calls = AtomicInteger()
            val worker = thread(isDaemon = true) {
                while (!server.isClosed) {
                    try { server.accept().use { socket ->
                        socket.soTimeout = 3000
                        val input = socket.getInputStream().buffered()
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n")) {
                            val b = input.read(); if (b < 0) break
                            header.append(b.toChar()); require(header.length < 65536)
                        }
                        val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)?.toInt() ?: 0
                        repeat(length) { input.read() }
                        calls.incrementAndGet(); response(socket, server.localPort)
                    } } catch (_: Exception) { if (server.isClosed) break }
                }
            }
            try { check(server.localPort, calls) } finally { server.close(); worker.join(1000) }
        }
    }
    @Test fun redirectIsNotFollowedAndPostIsNotReplayed() = runBlocking {
        serve({ socket, port ->
            socket.getOutputStream().write("HTTP/1.1 307 Temporary Redirect\r\nLocation: http://127.0.0.1:$port/again\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        }) { port, calls ->
            val result = DocumentRecognitionTransport().post(ApiSettings(baseUrl = "http://127.0.0.1:$port", apiKey = "synthetic"), buildJsonObject { })
            assertEquals(307, result.code); assertEquals(1, calls.get())
        }
    }
    @Test fun responseLimitUsesReceivedBytes() = runBlocking {
        serve({ socket, _ ->
            val size = 2 * 1024 * 1024 + 1
            socket.getOutputStream().use { out ->
                out.write("HTTP/1.1 200 OK\r\nContent-Length: $size\r\nConnection: close\r\n\r\n".toByteArray())
                val chunk = ByteArray(8192) { 65 }; repeat(size / chunk.size) { out.write(chunk) }; out.write(65)
            }
        }) { port, _ ->
            try { DocumentRecognitionTransport().post(ApiSettings(baseUrl = "http://127.0.0.1:$port", apiKey = "synthetic"), buildJsonObject { }); fail("response must be bounded") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
