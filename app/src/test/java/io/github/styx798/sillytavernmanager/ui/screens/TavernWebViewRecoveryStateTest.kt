package io.github.styx798.sillytavernmanager.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernWebViewRecoveryStateTest {
    @Test
    fun `renderer exit requires a new WebView generation before recovery`() {
        val exited = TavernWebViewRecoveryState().onRendererGone()

        assertTrue(exited.rendererGone)
        assertEquals(0, exited.generation)

        val recovered = exited.reloadRenderer()

        assertFalse(recovered.rendererGone)
        assertEquals(1, recovered.generation)
    }

    @Test
    fun `reload is rejected while the renderer is healthy`() {
        assertThrows(IllegalStateException::class.java) {
            TavernWebViewRecoveryState().reloadRenderer()
        }
    }
}
