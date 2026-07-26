package io.github.styx798.sillytavernmanager.stmcore

import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

internal data class RawHttpResponse(
    val statusLine: String,
    val statusCode: Int?,
    val headers: Map<String, String>,
    val body: ByteArray,
    val rawBytes: ByteArray,
) {
    fun bodyUtf8(): String = body.toString(Charsets.UTF_8)
}

internal sealed interface LoopbackProbeResult {
    data class Healthy(val response: RawHttpResponse) : LoopbackProbeResult

    data class Failed(
        val detail: String,
        val response: RawHttpResponse? = null,
    ) : LoopbackProbeResult
}

internal object LoopbackHealthProbe {
    private const val TIMEOUT_MILLIS = 2_000

    fun execute(baseUrl: String): LoopbackProbeResult {
        val response = when (val capture = capture(baseUrl, SyntheticCoreService.HEALTH_PATH)) {
            is LoopbackProbeResult.Failed -> return capture
            is LoopbackProbeResult.Healthy -> capture.response
        }
        val declaredLength = response.headers["content-length"]?.toIntOrNull()
        return when {
            response.statusCode != 200 ->
                LoopbackProbeResult.Failed(
                    "Health check returned HTTP ${response.statusCode}",
                    response,
                )

            declaredLength == null ->
                LoopbackProbeResult.Failed("Health check omitted Content-Length", response)

            declaredLength != response.body.size ->
                LoopbackProbeResult.Failed(
                    "Health body length ${response.body.size} did not match Content-Length $declaredLength",
                    response,
                )

            response.headers["connection"]?.lowercase() != "close" ->
                LoopbackProbeResult.Failed("Health check did not declare Connection: close", response)

            response.bodyUtf8() != SyntheticCoreService.HEALTH_BODY ->
                LoopbackProbeResult.Failed("Health check returned an unexpected payload", response)

            else -> LoopbackProbeResult.Healthy(response)
        }
    }

    fun capture(
        baseUrl: String,
        path: String,
        cookie: String? = null,
    ): LoopbackProbeResult {
        val endpoint = try {
            URI(baseUrl)
        } catch (error: Exception) {
            return LoopbackProbeResult.Failed("Health URL failed: ${error.safeMessage()}")
        }
        return try {
            require(endpoint.host == "127.0.0.1" && endpoint.port in 1..65_535) {
                "Health endpoint must be an IPv4 loopback URL with an explicit port"
            }
            require(cookie == null || (
                cookie.length in 1..256 &&
                    cookie.all { character -> character.code in 0x21..0x7e && character != ';' }
                )
            ) {
                "Health cookie contains an unsafe HTTP header value"
            }
            val rawBytes = Socket().use { socket ->
                socket.connect(InetSocketAddress(endpoint.host, endpoint.port), TIMEOUT_MILLIS)
                socket.soTimeout = TIMEOUT_MILLIS
                val request =
                    "GET $path HTTP/1.1\r\n" +
                        "Host: 127.0.0.1:${endpoint.port}\r\n" +
                        "Connection: close\r\n" +
                        "Accept: application/json\r\n" +
                        cookie?.let { "Cookie: $it\r\n" }.orEmpty() +
                        "\r\n"
                socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
                socket.getOutputStream().flush()
                socket.getInputStream().readBytes()
            }
            val response = parse(rawBytes)
                ?: return LoopbackProbeResult.Failed("Health check returned malformed HTTP bytes")
            LoopbackProbeResult.Healthy(response)
        } catch (error: Exception) {
            LoopbackProbeResult.Failed("Health check failed: ${error.safeMessage()}")
        }
    }

    internal fun parse(rawBytes: ByteArray): RawHttpResponse? {
        val delimiter = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val headerEnd = rawBytes.indexOf(delimiter)
        if (headerEnd < 0) return null
        val headerText = rawBytes.copyOfRange(0, headerEnd).toString(Charsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        val statusLine = lines.firstOrNull()?.takeIf(String::isNotBlank) ?: return null
        val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
        val headers = linkedMapOf<String, String>()
        lines.drop(1).forEach { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) return null
            headers[line.substring(0, separator).trim().lowercase()] =
                line.substring(separator + 1).trim()
        }
        return RawHttpResponse(
            statusLine = statusLine,
            statusCode = statusCode,
            headers = headers,
            body = rawBytes.copyOfRange(headerEnd + delimiter.size, rawBytes.size),
            rawBytes = rawBytes,
        )
    }
}

private fun ByteArray.indexOf(needle: ByteArray): Int {
    if (needle.isEmpty() || size < needle.size) return -1
    for (start in 0..size - needle.size) {
        var matches = true
        for (offset in needle.indices) {
            if (this[start + offset] != needle[offset]) {
                matches = false
                break
            }
        }
        if (matches) return start
    }
    return -1
}
