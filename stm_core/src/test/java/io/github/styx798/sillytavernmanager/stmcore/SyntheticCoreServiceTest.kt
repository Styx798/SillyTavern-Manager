package io.github.styx798.sillytavernmanager.stmcore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticCoreServiceTest {
    @Test
    fun `service is loopback only and requests an ephemeral port`() {
        assertTrue(SyntheticCoreService.script.contains("server.listen(0, '127.0.0.1'"))
        assertFalse(SyntheticCoreService.script.contains("0.0.0.0"))
    }

    @Test
    fun `health and not-found responses are length delimited without forcing close`() {
        assertTrue(SyntheticCoreService.script.contains("request.url === '/health'"))
        assertTrue(SyntheticCoreService.script.contains("'Content-Length'"))
        assertFalse(SyntheticCoreService.script.contains("'Connection': 'close'"))
        assertTrue(SyntheticCoreService.script.contains(SyntheticCoreService.HEALTH_BODY))
    }
}
