package io.github.styx798.sillytavernmanager.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernLoopbackOriginTest {
    @Test
    fun `allows only the exact Core loopback origin`() {
        val origin = TavernLoopbackOrigin.fromCoreUrl("http://127.0.0.1:8000")

        assertTrue(origin.allowsMainFrame("http://127.0.0.1:8000/"))
        assertTrue(origin.allowsMainFrame("http://127.0.0.1:8000/settings?tab=1#anchor"))
        assertTrue(origin.allowsMainFrame("about:blank"))
        assertFalse(origin.allowsMainFrame("http://127.0.0.1:8001/"))
        assertFalse(origin.allowsMainFrame("http://localhost:8000/"))
        assertFalse(origin.allowsMainFrame("https://127.0.0.1:8000/"))
        assertFalse(origin.allowsMainFrame("https://example.com/"))
        assertFalse(origin.allowsMainFrame("file:///data/local/tmp/page.html"))
        assertFalse(origin.allowsMainFrame("javascript:alert(1)"))
        assertFalse(origin.allowsMainFrame("http://user@127.0.0.1:8000/"))
    }

    @Test
    fun `rejects malformed or non-loopback Core origins`() {
        listOf(
            "https://127.0.0.1:8000",
            "http://localhost:8000",
            "http://127.0.0.1",
            "http://127.0.0.1:8000/path",
            "http://user@127.0.0.1:8000",
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                TavernLoopbackOrigin.fromCoreUrl(value)
            }
        }
    }

}
