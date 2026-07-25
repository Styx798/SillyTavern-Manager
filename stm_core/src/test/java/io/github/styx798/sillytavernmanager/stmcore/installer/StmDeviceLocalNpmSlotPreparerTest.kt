package io.github.styx798.sillytavernmanager.stmcore.installer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StmDeviceLocalNpmSlotPreparerTest {
    @Test
    fun `accepts the upstream shake256 webpack cache directory`() {
        assertTrue(stmIsSafeWebpackCacheVersion("14b7dd319136b345"))
    }

    @Test
    fun `rejects paths and non-canonical webpack cache directories`() {
        assertFalse(stmIsSafeWebpackCacheVersion("../14b7dd319136b345"))
        assertFalse(stmIsSafeWebpackCacheVersion("14B7DD319136B345"))
        assertFalse(stmIsSafeWebpackCacheVersion("5.101.3"))
        assertFalse(stmIsSafeWebpackCacheVersion("14b7dd319136b34"))
        assertFalse(stmIsSafeWebpackCacheVersion("14b7dd319136b3450"))
    }
}
