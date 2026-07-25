package io.github.styx798.sillytavernmanager.stmcore

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackHealthProbeTest {
    @Test
    fun `accepts exact length-delimited close response`() {
        withSingleResponseServer(200, SyntheticCoreService.HEALTH_BODY) { baseUrl ->
            val result = LoopbackHealthProbe.execute(baseUrl)

            assertTrue(result is LoopbackProbeResult.Healthy)
            val response = (result as LoopbackProbeResult.Healthy).response
            assertEquals("close", response.headers["connection"])
            assertEquals(response.body.size, response.headers["content-length"]?.toInt())
        }
    }

    @Test
    fun `rejects a declared body length that does not match the raw bytes`() {
        val body = "{}"
        withSingleResponseServer(200, body, declaredLength = 99) { baseUrl ->
            val result = LoopbackHealthProbe.execute(baseUrl)

            assertTrue(result is LoopbackProbeResult.Failed)
            val response = requireNotNull((result as LoopbackProbeResult.Failed).response)
            assertEquals(99, response.headers["content-length"]?.toInt())
            assertEquals(body, response.bodyUtf8())
        }
    }

    private fun withSingleResponseServer(
        statusCode: Int,
        body: String,
        declaredLength: Int = body.toByteArray().size,
        assertion: (String) -> Unit,
    ) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val responder = thread(name = "StmCoreHealthProbeTestServer") {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) break
                    }
                    val response =
                        "HTTP/1.1 $statusCode Test\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: $declaredLength\r\n" +
                            "Connection: close\r\n\r\n" +
                            body
                    socket.getOutputStream().write(response.toByteArray())
                    socket.getOutputStream().flush()
                }
            }
            assertion("http://127.0.0.1:${server.localPort}")
            responder.join(2_000)
            assertTrue("Test HTTP responder did not finish", !responder.isAlive)
        }
    }
}
