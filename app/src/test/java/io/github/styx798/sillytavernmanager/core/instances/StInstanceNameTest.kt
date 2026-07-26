package io.github.styx798.sillytavernmanager.core.instances

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StInstanceNameTest {
    @Test
    fun `instance name preserves user-facing unicode after trimming`() {
        assertEquals("主酒馆 🐕", requireValidInstanceName("  主酒馆 🐕  "))
    }

    @Test
    fun `collision key treats compatibility and case variants as the same name`() {
        assertEquals(
            instanceNameCollisionKey("Ｍｙ ST"),
            instanceNameCollisionKey("my st"),
        )
    }

    @Test
    fun `blank control and overlong names are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            requireValidInstanceName("   ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireValidInstanceName("bad\u0000name")
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireValidInstanceName("a".repeat(41))
        }
    }
}
