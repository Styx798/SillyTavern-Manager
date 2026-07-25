package io.github.styx798.sillytavernmanager.data.downloads

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubCommitResponseParserTest {
    @Test
    fun `reads only the top level exact sha and skips nested values`() {
        val json = """
            {
              "nested": {"sha": "not-the-answer", "values": [1, true, null, {"x": -2.5e3}]},
              "sha": "$SHA1",
              "message": "escaped \"value\" and \u2603"
            }
        """.trimIndent()

        assertEquals(SHA1, parseGitHubCommitResponse(json.encodeToByteArray()))
    }

    @Test
    fun `accepts a future exact SHA-256 object identifier`() {
        assertEquals(
            SHA256,
            parseGitHubCommitResponse("{\"sha\":\"$SHA256\"}".encodeToByteArray()),
        )
    }

    @Test
    fun `recognizes an escaped top level sha key`() {
        assertEquals(
            SHA1,
            parseGitHubCommitResponse("{\"\\u0073ha\":\"$SHA1\"}".encodeToByteArray()),
        )
    }

    @Test
    fun `rejects duplicate missing and non string top level sha fields`() {
        listOf(
            "{}",
            "{\"sha\":null}",
            "{\"sha\":\"$SHA1\",\"sha\":\"$SHA1\"}",
            "{\"payload\":{\"sha\":\"$SHA1\"}}",
        ).forEach { json ->
            assertThrows(IllegalArgumentException::class.java) {
                parseGitHubCommitResponse(json.encodeToByteArray())
            }
        }
    }

    @Test
    fun `rejects abbreviated non hexadecimal and overlong sha strings`() {
        listOf(
            "a".repeat(39),
            "g".repeat(40),
            "a".repeat(65),
        ).forEach { sha ->
            assertThrows(IllegalArgumentException::class.java) {
                parseGitHubCommitResponse("{\"sha\":\"$sha\"}".encodeToByteArray())
            }
        }
    }

    @Test
    fun `rejects truncated malformed and trailing JSON`() {
        listOf(
            "{",
            "{\"sha\":",
            "{\"sha\":\"$SHA1\"",
            "{\"sha\":\"$SHA1\",}",
            "{\"sha\":\"$SHA1\"} trailing",
            "[\"$SHA1\"]",
        ).forEach { json ->
            assertThrows(IllegalArgumentException::class.java) {
                parseGitHubCommitResponse(json.encodeToByteArray())
            }
        }
    }

    @Test
    fun `rejects malformed UTF-8`() {
        assertThrows(Exception::class.java) {
            parseGitHubCommitResponse(byteArrayOf(0xc3.toByte(), 0x28))
        }
    }

    @Test
    fun `reads exact branch tip from git upload pack advertisement`() {
        val advertisement = gitAdvertisement(
            "$SHA1 HEAD\u0000multi_ack thin-pack\n",
            "$SHA2 refs/heads/release\n",
            "$SHA1 refs/heads/staging\n",
        )

        assertEquals(
            SHA2,
            parseGitUploadPackAdvertisement(advertisement, "refs/heads/release"),
        )
        assertEquals(
            SHA1,
            parseGitUploadPackAdvertisement(advertisement, "refs/heads/staging"),
        )
    }

    @Test
    fun `accepts future SHA-256 branch tip from git advertisement`() {
        val advertisement = gitAdvertisement("$SHA256 refs/heads/release\n")

        assertEquals(
            SHA256,
            parseGitUploadPackAdvertisement(advertisement, "refs/heads/release"),
        )
    }

    @Test
    fun `rejects missing abbreviated non hexadecimal and conflicting git refs`() {
        listOf(
            gitAdvertisement("$SHA1 refs/heads/staging\n"),
            gitAdvertisement("${"a".repeat(39)} refs/heads/release\n"),
            gitAdvertisement("${"g".repeat(40)} refs/heads/release\n"),
            gitAdvertisement(
                "$SHA1 refs/heads/release\n",
                "$SHA2 refs/heads/release\n",
            ),
        ).forEach { advertisement ->
            assertThrows(IllegalArgumentException::class.java) {
                parseGitUploadPackAdvertisement(advertisement, "refs/heads/release")
            }
        }
    }

    @Test
    fun `rejects malformed truncated and invalid utf8 git packets`() {
        listOf(
            "zzzz".encodeToByteArray(),
            "0003".encodeToByteArray(),
            "0008abc".encodeToByteArray(),
            byteArrayOf(
                '0'.code.toByte(),
                '0'.code.toByte(),
                '0'.code.toByte(),
                '6'.code.toByte(),
                0xc3.toByte(),
                0x28,
            ),
        ).forEach { advertisement ->
            assertThrows(Exception::class.java) {
                parseGitUploadPackAdvertisement(advertisement, "refs/heads/release")
            }
        }
    }

    @Test
    fun `requires a fully named branch ref for git advertisement`() {
        val advertisement = gitAdvertisement("$SHA1 refs/heads/release\n")

        listOf("", "release", "refs/tags/release", "refs/heads/").forEach { ref ->
            assertThrows(IllegalArgumentException::class.java) {
                parseGitUploadPackAdvertisement(advertisement, ref)
            }
        }
    }

    @Test
    fun `bounded reader rejects a response larger than its cap`() {
        assertEquals(
            "abcd",
            readAtMost(ByteArrayInputStream("abcd".encodeToByteArray()), 4).decodeToString(),
        )
        assertThrows(IOException::class.java) {
            readAtMost(ByteArrayInputStream("abcde".encodeToByteArray()), 4)
        }
    }

    private fun gitAdvertisement(vararg refs: String): ByteArray =
        buildList {
            add(gitPacket("# service=git-upload-pack\n"))
            add("0000".encodeToByteArray())
            refs.forEach { add(gitPacket(it)) }
            add("0000".encodeToByteArray())
        }.fold(ByteArray(0), ByteArray::plus)

    private fun gitPacket(payload: String): ByteArray {
        val encoded = payload.encodeToByteArray()
        val length = (encoded.size + 4).toString(16).padStart(4, '0')
        return length.encodeToByteArray() + encoded
    }

    private companion object {
        const val SHA1 = "0123456789abcdef0123456789abcdef01234567"
        const val SHA2 = "89abcdef0123456789abcdef0123456789abcdef"
        const val SHA256 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
