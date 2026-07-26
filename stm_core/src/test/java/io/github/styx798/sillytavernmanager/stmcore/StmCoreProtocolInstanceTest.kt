package io.github.styx798.sillytavernmanager.stmcore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StmCoreProtocolInstanceTest {
    @Test
    fun `instance identity accepts UUID and rejects path-like or arbitrary values`() {
        assertTrue(isValidStmCoreInstanceId("00000000-0000-4000-8000-000000000002"))
        assertTrue(isValidStmCoreInstanceId("00000000-0000-4000-8000-0000000000AB"))
        assertFalse(isValidStmCoreInstanceId("../escape"))
        assertFalse(isValidStmCoreInstanceId("instance-one"))
        assertFalse(isValidStmCoreInstanceId(""))
    }
}
