package io.github.styx798.sillytavernmanager.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TavernDownloadRequestTest {
    private val origin = TavernLoopbackOrigin.fromCoreUrl("http://127.0.0.1:8000")

    @Test
    fun `accepts only current loopback HTTP and Blob downloads`() {
        assertEquals(
            TavernDownloadKind.LOOPBACK_HTTP,
            request("http://127.0.0.1:8000/api/export").kind,
        )
        assertEquals(
            TavernDownloadKind.LOOPBACK_BLOB,
            request("blob:http://127.0.0.1:8000/transfer-id").kind,
        )

        listOf(
            "http://127.0.0.1:8001/api/export",
            "blob:http://127.0.0.1:8001/transfer-id",
            "blob:https://127.0.0.1:8000/transfer-id",
            "https://example.com/export.zip",
            "file:///data/local/tmp/export.zip",
            "data:application/zip;base64,AA==",
        ).forEach { url ->
            assertNull(
                TavernDownloadRequest.fromWebView(
                    origin = origin,
                    url = url,
                    contentDisposition = null,
                    mimeType = "application/zip",
                    userAgent = null,
                ),
            )
        }
    }

    @Test
    fun `sanitizes server supplied file names`() {
        assertEquals(
            "backup.zip",
            safeDownloadName(
                url = "blob:http://127.0.0.1:8000/id",
                contentDisposition = """attachment; filename="../../backup.zip"""",
                mimeType = "application/zip",
            ),
        )
        assertEquals(
            "用户备份.zip",
            safeDownloadName(
                url = "blob:http://127.0.0.1:8000/id",
                contentDisposition = "attachment; filename*=UTF-8''%E7%94%A8%E6%88%B7%E5%A4%87%E4%BB%BD.zip",
                mimeType = "application/zip",
            ),
        )
        assertEquals(
            "SillyTavern-download.zip",
            safeDownloadName(
                url = "blob:http://127.0.0.1:8000/",
                contentDisposition = null,
                mimeType = "application/zip",
            ),
        )
    }

    @Test
    fun `uses sanitized page download name for Blob exports`() {
        val original = request("blob:http://127.0.0.1:8000/transfer-id")

        assertEquals(
            "STM-backup.zip",
            blobRequestWithPageMetadata(
                original,
                """"..%2FSTM-backup.zip"""",
            ).suggestedName,
        )
        assertEquals(original, blobRequestWithPageMetadata(original, "null"))
        assertEquals(original, blobRequestWithPageMetadata(original, "\"bad\\\\name\""))
    }

    private fun request(url: String): TavernDownloadRequest = requireNotNull(
        TavernDownloadRequest.fromWebView(
            origin = origin,
            url = url,
            contentDisposition = """attachment; filename="backup.zip"""",
            mimeType = "application/zip",
            userAgent = "Gate4",
        ),
    )
}
