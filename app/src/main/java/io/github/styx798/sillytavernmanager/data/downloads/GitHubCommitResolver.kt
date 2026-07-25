package io.github.styx798.sillytavernmanager.data.downloads

import io.github.styx798.sillytavernmanager.core.downloads.StDownloadChannel
import io.github.styx798.sillytavernmanager.core.downloads.requireExactCommitSha
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection

internal data class ResolvedStDownload(
    val channel: StDownloadChannel,
    val exactCommit: String,
    val archiveUrl: String,
    val fileName: String,
    val resolvedAtEpochMillis: Long,
)

internal class GitHubCommitResolver(
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
) {
    fun resolve(channel: StDownloadChannel): ResolvedStDownload {
        val exactCommit = try {
            resolveFromRestApi(channel)
        } catch (primaryFailure: Exception) {
            if (
                primaryFailure !is IOException &&
                primaryFailure !is IllegalArgumentException
            ) {
                throw primaryFailure
            }
            try {
                resolveFromGitAdvertisement(channel)
            } catch (fallbackFailure: Exception) {
                fallbackFailure.addSuppressed(primaryFailure)
                throw fallbackFailure
            }
        }
        return ResolvedStDownload(
            channel = channel,
            exactCommit = exactCommit,
            archiveUrl = channel.exactArchiveUrl(exactCommit),
            fileName = channel.exactArchiveFileName(exactCommit),
            resolvedAtEpochMillis = nowEpochMillis(),
        )
    }

    private fun resolveFromRestApi(channel: StDownloadChannel): String {
        val endpoint = URL(channel.commitApiUrl)
        val connection = openHttpsConnection(endpoint)

        return try {
            connection.requestMethod = HTTP_GET
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", GITHUB_JSON_ACCEPT)
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION)

            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub commit resolution failed with HTTP $status.")
            }
            if (connection.contentLengthLong > MAX_API_RESPONSE_BYTES) {
                throw IOException("GitHub commit response exceeds the size limit.")
            }

            val response = connection.inputStream.use { input ->
                readAtMost(input, MAX_API_RESPONSE_BYTES)
            }
            parseGitHubCommitResponse(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun resolveFromGitAdvertisement(channel: StDownloadChannel): String {
        val endpoint = URL(
            "${StDownloadChannel.GITHUB_REPOSITORY_URL}.git/info/refs" +
                "?service=git-upload-pack",
        )
        val connection = openHttpsConnection(endpoint)
        return try {
            connection.requestMethod = HTTP_GET
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", GIT_ADVERTISEMENT_ACCEPT)
            connection.setRequestProperty("User-Agent", USER_AGENT)

            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub ref advertisement failed with HTTP $status.")
            }
            val contentType = connection.contentType.orEmpty()
            if (!contentType.startsWith(GIT_ADVERTISEMENT_ACCEPT)) {
                throw IOException("GitHub ref advertisement returned an unexpected content type.")
            }
            if (connection.contentLengthLong > MAX_GIT_ADVERTISEMENT_BYTES) {
                throw IOException("GitHub ref advertisement exceeds the size limit.")
            }
            val response = connection.inputStream.use { input ->
                readAtMost(input, MAX_GIT_ADVERTISEMENT_BYTES)
            }
            parseGitUploadPackAdvertisement(
                response = response,
                expectedRef = "refs/heads/${channel.branch}",
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun openHttpsConnection(endpoint: URL): HttpsURLConnection {
        if (endpoint.protocol != HTTPS_SCHEME) {
            throw IOException("GitHub commit resolution requires HTTPS.")
        }
        val connection = connectionFactory(endpoint)
        if (connection !is HttpsURLConnection) {
            connection.disconnect()
            throw IOException("GitHub commit resolution requires an HTTPS connection.")
        }
        return connection
    }
}

internal fun parseGitHubCommitResponse(response: ByteArray): String {
    require(response.size <= MAX_API_RESPONSE_BYTES) {
        "GitHub commit response exceeds the size limit."
    }
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val text = decoder.decode(ByteBuffer.wrap(response)).toString()
    return TopLevelShaJsonParser(text).parse()
}

internal fun parseGitUploadPackAdvertisement(
    response: ByteArray,
    expectedRef: String,
): String {
    require(response.size <= MAX_GIT_ADVERTISEMENT_BYTES) {
        "Git ref advertisement exceeds the size limit."
    }
    require(expectedRef.startsWith(GIT_HEAD_REF_PREFIX) && expectedRef.length > GIT_HEAD_REF_PREFIX.length) {
        "Only a fully named branch ref can be resolved."
    }

    var offset = 0
    var exactCommit: String? = null
    while (offset < response.size) {
        require(offset + PACKET_LENGTH_HEX_DIGITS <= response.size) {
            "Truncated Git packet length."
        }
        var packetLength = 0
        repeat(PACKET_LENGTH_HEX_DIGITS) {
            val digit = response[offset + it].toInt().toChar().digitToIntOrNull(16)
                ?: throw IllegalArgumentException("Invalid Git packet length.")
            packetLength = (packetLength shl 4) or digit
        }
        offset += PACKET_LENGTH_HEX_DIGITS
        if (packetLength == 0) continue
        require(packetLength >= PACKET_LENGTH_HEX_DIGITS) {
            "Unsupported Git control packet."
        }
        val payloadLength = packetLength - PACKET_LENGTH_HEX_DIGITS
        require(offset + payloadLength <= response.size) {
            "Truncated Git packet payload."
        }
        val payload = strictUtf8(
            response.copyOfRange(offset, offset + payloadLength),
        ).removeSuffix("\n")
        offset += payloadLength
        if (payload.startsWith("# service=")) continue

        val separator = payload.indexOf(' ')
        if (separator <= 0) continue
        val candidate = payload.substring(0, separator)
        val ref = payload
            .substring(separator + 1)
            .substringBefore('\u0000')
        if (ref != expectedRef) continue
        val canonical = requireExactCommitSha(candidate)
        require(exactCommit == null || exactCommit == canonical) {
            "Git ref advertisement contains conflicting branch tips."
        }
        exactCommit = canonical
    }
    return requireNotNull(exactCommit) {
        "Git ref advertisement did not contain $expectedRef."
    }
}

private fun strictUtf8(bytes: ByteArray): String {
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return decoder.decode(ByteBuffer.wrap(bytes)).toString()
}

internal fun readAtMost(input: InputStream, maximumBytes: Int): ByteArray {
    require(maximumBytes > 0)
    val output = ByteArrayOutputStream(minOf(DEFAULT_RESPONSE_BUFFER_BYTES, maximumBytes))
    val buffer = ByteArray(DEFAULT_RESPONSE_BUFFER_BYTES)
    var totalBytes = 0
    while (true) {
        val read = input.read(buffer)
        if (read == -1) break
        if (read == 0) continue
        totalBytes += read
        if (totalBytes > maximumBytes) {
            throw IOException("Response exceeds the size limit.")
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private class TopLevelShaJsonParser(private val source: String) {
    private var index = 0

    fun parse(): String {
        skipWhitespace()
        expect('{')
        skipWhitespace()

        var sha: String? = null
        var foundSha = false
        if (consume('}')) {
            fail("Missing top-level sha field.")
        }

        while (true) {
            val key = parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            if (key == SHA_FIELD) {
                if (foundSha) fail("Duplicate top-level sha field.")
                if (peek() != '"') fail("Top-level sha field must be a JSON string.")
                sha = parseString()
                foundSha = true
            } else {
                skipValue(depth = 1)
            }

            skipWhitespace()
            when {
                consume(',') -> {
                    skipWhitespace()
                    if (peek() == '}') fail("Trailing commas are not valid JSON.")
                }

                consume('}') -> break
                else -> fail("Expected a comma or the end of the top-level object.")
            }
        }

        skipWhitespace()
        if (index != source.length) fail("Trailing content after the top-level object.")
        if (!foundSha) fail("Missing top-level sha field.")
        return try {
            requireExactCommitSha(checkNotNull(sha))
        } catch (exception: IllegalArgumentException) {
            fail(exception.message ?: "Invalid exact commit SHA.")
        }
    }

    private fun skipValue(depth: Int) {
        if (depth > MAX_JSON_DEPTH) fail("JSON nesting exceeds the supported limit.")
        when (peek()) {
            '"' -> parseString()
            '{' -> skipObject(depth)
            '[' -> skipArray(depth)
            't' -> consumeLiteral("true")
            'f' -> consumeLiteral("false")
            'n' -> consumeLiteral("null")
            '-', in '0'..'9' -> skipNumber()
            else -> fail("Expected a JSON value.")
        }
    }

    private fun skipObject(depth: Int) {
        expect('{')
        skipWhitespace()
        if (consume('}')) return
        while (true) {
            parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            skipValue(depth + 1)
            skipWhitespace()
            when {
                consume(',') -> {
                    skipWhitespace()
                    if (peek() == '}') fail("Trailing commas are not valid JSON.")
                }

                consume('}') -> return
                else -> fail("Expected a comma or the end of an object.")
            }
        }
    }

    private fun skipArray(depth: Int) {
        expect('[')
        skipWhitespace()
        if (consume(']')) return
        while (true) {
            skipValue(depth + 1)
            skipWhitespace()
            when {
                consume(',') -> {
                    skipWhitespace()
                    if (peek() == ']') fail("Trailing commas are not valid JSON.")
                }

                consume(']') -> return
                else -> fail("Expected a comma or the end of an array.")
            }
        }
    }

    private fun skipNumber() {
        consume('-')
        when (val first = peek()) {
            '0' -> {
                index += 1
                if (peek() in '0'..'9') fail("A JSON number cannot have a leading zero.")
            }

            in '1'..'9' -> {
                index += 1
                while (peek() in '0'..'9') index += 1
            }

            else -> fail("Invalid JSON number near '$first'.")
        }

        if (consume('.')) {
            if (peek() !in '0'..'9') fail("A JSON fraction requires at least one digit.")
            while (peek() in '0'..'9') index += 1
        }
        if (peek() == 'e' || peek() == 'E') {
            index += 1
            if (peek() == '+' || peek() == '-') index += 1
            if (peek() !in '0'..'9') fail("A JSON exponent requires at least one digit.")
            while (peek() in '0'..'9') index += 1
        }
    }

    private fun consumeLiteral(literal: String) {
        if (!source.regionMatches(index, literal, 0, literal.length)) {
            fail("Invalid JSON literal.")
        }
        index += literal.length
    }

    private fun parseString(): String {
        expect('"')
        val value = StringBuilder()
        while (index < source.length) {
            val character = source[index++]
            when {
                character == '"' -> return value.toString()
                character == '\\' -> appendEscape(value)
                character.code < JSON_CONTROL_CHARACTER_LIMIT -> {
                    fail("Unescaped control character in JSON string.")
                }

                character.isHighSurrogate() -> {
                    val low = source.getOrNull(index)
                    if (low == null || !low.isLowSurrogate()) {
                        fail("Unpaired high surrogate in JSON string.")
                    }
                    value.append(character)
                    value.append(low)
                    index += 1
                }

                character.isLowSurrogate() -> fail("Unpaired low surrogate in JSON string.")
                else -> value.append(character)
            }
        }
        fail("Truncated JSON string.")
    }

    private fun appendEscape(value: StringBuilder) {
        val escaped = source.getOrNull(index++) ?: fail("Truncated JSON escape.")
        when (escaped) {
            '"', '\\', '/' -> value.append(escaped)
            'b' -> value.append('\b')
            'f' -> value.append('\u000c')
            'n' -> value.append('\n')
            'r' -> value.append('\r')
            't' -> value.append('\t')
            'u' -> {
                val first = parseUnicodeEscape()
                when {
                    first.isHighSurrogate() -> {
                        if (!consume('\\') || !consume('u')) {
                            fail("A high surrogate escape must be followed by a low surrogate escape.")
                        }
                        val second = parseUnicodeEscape()
                        if (!second.isLowSurrogate()) {
                            fail("A high surrogate escape must be followed by a low surrogate escape.")
                        }
                        value.append(first)
                        value.append(second)
                    }

                    first.isLowSurrogate() -> fail("Unpaired low surrogate escape in JSON string.")
                    else -> value.append(first)
                }
            }

            else -> fail("Invalid JSON string escape.")
        }
    }

    private fun parseUnicodeEscape(): Char {
        if (index + UNICODE_ESCAPE_HEX_LENGTH > source.length) {
            fail("Truncated Unicode escape in JSON string.")
        }
        var value = 0
        repeat(UNICODE_ESCAPE_HEX_LENGTH) {
            val digit = source[index++].digitToIntOrNull(16)
                ?: fail("Invalid Unicode escape in JSON string.")
            value = (value shl 4) or digit
        }
        return value.toChar()
    }

    private fun skipWhitespace() {
        while (peek() == ' ' || peek() == '\t' || peek() == '\r' || peek() == '\n') {
            index += 1
        }
    }

    private fun expect(expected: Char) {
        if (!consume(expected)) fail("Expected '$expected'.")
    }

    private fun consume(expected: Char): Boolean {
        if (peek() != expected) return false
        index += 1
        return true
    }

    private fun peek(): Char? = source.getOrNull(index)

    private fun fail(message: String): Nothing =
        throw IllegalArgumentException("$message (offset $index)")
}

internal const val MAX_API_RESPONSE_BYTES = 1024 * 1024
internal const val MAX_GIT_ADVERTISEMENT_BYTES = 2 * 1024 * 1024
private const val MAX_JSON_DEPTH = 64
private const val DEFAULT_RESPONSE_BUFFER_BYTES = 8 * 1024
private const val UNICODE_ESCAPE_HEX_LENGTH = 4
private const val JSON_CONTROL_CHARACTER_LIMIT = 0x20
private const val SHA_FIELD = "sha"
private const val HTTPS_SCHEME = "https"
private const val HTTP_GET = "GET"
private const val CONNECT_TIMEOUT_MILLIS = 10_000
private const val READ_TIMEOUT_MILLIS = 15_000
private const val GITHUB_JSON_ACCEPT = "application/vnd.github+json"
private const val GITHUB_API_VERSION = "2022-11-28"
private const val USER_AGENT = "SillyTavern-Manager/0.0.1"
private const val GIT_ADVERTISEMENT_ACCEPT = "application/x-git-upload-pack-advertisement"
private const val GIT_HEAD_REF_PREFIX = "refs/heads/"
private const val PACKET_LENGTH_HEX_DIGITS = 4
