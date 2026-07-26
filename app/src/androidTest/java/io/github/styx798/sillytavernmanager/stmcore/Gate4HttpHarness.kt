package io.github.styx798.sillytavernmanager.stmcore

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal data class Gate4HttpResponse(
    val code: Int,
    val body: ByteArray,
    val headers: Map<String, List<String>>,
    val firstByteElapsedMillis: Long?,
    val completedElapsedMillis: Long,
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
}

internal class Gate4HttpSession(
    private val baseUrl: String,
    webSessionCredential: StmCoreWebSessionCredential,
    private val timeoutMillis: Int,
) {
    private val cookies = linkedMapOf(
        STM_CORE_WEB_SESSION_COOKIE_NAME to webSessionCredential.value,
    )

    val cookieNames: Set<String>
        get() = cookies.keys.toSet()

    fun request(
        path: String,
        method: String = "GET",
        accept: String = "*/*",
        contentType: String? = null,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
    ): Gate4HttpResponse {
        require(path.startsWith('/')) { "Gate 4 HTTP path must be origin-relative" }
        val startedAt = System.nanoTime()
        val connection = URL(baseUrl + path).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        return try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty(
                "Cookie",
                cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" },
            )
            headers.forEach(connection::setRequestProperty)
            contentType?.let { connection.setRequestProperty("Content-Type", it) }
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { output -> output.write(body) }
            }

            val code = connection.responseCode
            absorbCookies(connection)
            val source = if (code in 200..399) connection.inputStream else connection.errorStream
            var firstByteElapsedMillis: Long? = null
            val responseBody = ByteArrayOutputStream()
            source?.use { input ->
                val first = input.read()
                if (first >= 0) {
                    firstByteElapsedMillis = elapsedMillis(startedAt)
                    responseBody.write(first)
                    input.copyTo(responseBody)
                }
            }
            Gate4HttpResponse(
                code = code,
                body = responseBody.toByteArray(),
                headers = connection.headerFields
                    .filterKeys { it != null }
                    .mapKeys { requireNotNull(it.key) },
                firstByteElapsedMillis = firstByteElapsedMillis,
                completedElapsedMillis = elapsedMillis(startedAt),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun absorbCookies(connection: HttpURLConnection) {
        connection.headerFields.entries
            .filter { (name, _) -> name?.equals("Set-Cookie", ignoreCase = true) == true }
            .flatMap { it.value }
            .forEach { setCookie ->
                val nameAndValue = setCookie.substringBefore(';')
                val separator = nameAndValue.indexOf('=')
                if (separator > 0) {
                    val name = nameAndValue.substring(0, separator).trim()
                    val value = nameAndValue.substring(separator + 1).trim()
                    if (value.isEmpty()) {
                        cookies.remove(name)
                    } else {
                        cookies[name] = value
                    }
                }
            }
    }

    private fun elapsedMillis(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000L

    companion object {
        fun multipart(
            fields: Map<String, String>,
            fileField: String,
            fileName: String,
            fileContentType: String,
            fileBytes: ByteArray,
        ): Pair<String, ByteArray> {
            val boundary = "stm-gate4-${UUID.randomUUID()}"
            val body = ByteArrayOutputStream()

            fun write(value: String) {
                body.write(value.toByteArray(StandardCharsets.UTF_8))
            }

            fields.forEach { (name, value) ->
                write("--$boundary\r\n")
                write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                write(value)
                write("\r\n")
            }
            write("--$boundary\r\n")
            write(
                "Content-Disposition: form-data; name=\"$fileField\"; " +
                    "filename=\"$fileName\"\r\n",
            )
            write("Content-Type: $fileContentType\r\n\r\n")
            body.write(fileBytes)
            write("\r\n--$boundary--\r\n")
            return "multipart/form-data; boundary=$boundary" to body.toByteArray()
        }
    }
}

internal data class Gate4SseRequest(
    val requestLine: String,
    val body: String,
)

internal class Gate4SseFixture : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val request = AtomicReference<Gate4SseRequest?>()
    private val failure = AtomicReference<Throwable?>()
    private val thread = Thread(
        {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = FIXTURE_TIMEOUT_MILLIS
                    val input = socket.getInputStream()
                    val headerBytes = readHeaders(input)
                    val headerText = headerBytes.toString(StandardCharsets.ISO_8859_1)
                    val headerLines = headerText.split("\r\n")
                    val contentLength = headerLines
                        .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                        ?.substringAfter(':')
                        ?.trim()
                        ?.toIntOrNull()
                        ?: 0
                    val bodyBytes = ByteArray(contentLength)
                    var offset = 0
                    while (offset < bodyBytes.size) {
                        val count = input.read(bodyBytes, offset, bodyBytes.size - offset)
                        check(count >= 0) {
                            "Gate 4 SSE fixture reached EOF before the request body"
                        }
                        offset += count
                    }
                    val body = bodyBytes.toString(StandardCharsets.UTF_8)
                    request.set(Gate4SseRequest(headerLines.first(), body))

                    val output = socket.getOutputStream()
                    output.write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/event-stream\r\n" +
                                "Cache-Control: no-cache\r\n" +
                                "Connection: close\r\n\r\n" +
                                "data: {\"choices\":[{\"text\":\"STM-Gate4-SSE\",\"index\":0}]}\n\n"
                            ).toByteArray(StandardCharsets.UTF_8),
                    )
                    output.flush()
                    Thread.sleep(STREAM_GAP_MILLIS)
                    output.write("data: [DONE]\n\n".toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                }
            } catch (error: Throwable) {
                if (!server.isClosed) failure.set(error)
            }
        },
        "STM-Gate4-SSE-Fixture",
    ).apply { start() }

    val port: Int
        get() = server.localPort

    fun awaitRequest(): Gate4SseRequest {
        thread.join(FIXTURE_TIMEOUT_MILLIS.toLong())
        check(!thread.isAlive) { "Gate 4 SSE fixture did not finish" }
        failure.get()?.let { throw AssertionError("Gate 4 SSE fixture failed", it) }
        return requireNotNull(request.get()) { "Gate 4 SSE fixture received no request" }
    }

    override fun close() {
        server.close()
        thread.interrupt()
        thread.join(FIXTURE_TIMEOUT_MILLIS.toLong())
    }

    private fun readHeaders(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        var matched = 0
        val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (output.size() < MAX_HEADER_BYTES) {
            val next = input.read()
            check(next >= 0) { "Gate 4 SSE fixture reached EOF before request headers" }
            output.write(next)
            matched = if (next.toByte() == terminator[matched]) matched + 1 else 0
            if (matched == terminator.size) return output.toByteArray()
        }
        error("Gate 4 SSE fixture request headers exceeded $MAX_HEADER_BYTES bytes")
    }

    private companion object {
        const val FIXTURE_TIMEOUT_MILLIS = 10_000
        const val STREAM_GAP_MILLIS = 200L
        const val MAX_HEADER_BYTES = 64 * 1024
    }
}
