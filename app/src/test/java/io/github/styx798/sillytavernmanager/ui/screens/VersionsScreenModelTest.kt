package io.github.styx798.sillytavernmanager.ui.screens

import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifact
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactIntegrity
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactKind
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactTrust
import io.github.styx798.sillytavernmanager.stmcore.StmCoreSlot
import io.github.styx798.sillytavernmanager.stmcore.StmCoreSlotState
import org.junit.Assert.assertEquals
import org.junit.Test

class VersionsScreenModelTest {
    @Test
    fun `ST manager hides synthetic Gate 2 fixtures but keeps real and legacy slots`() {
        val synthetic = slot(
            id = "gate2-a",
            kind = StmCoreArtifactKind.GATE2_SYNTHETIC,
        )
        val real = slot(
            id = "st-release",
            kind = StmCoreArtifactKind.SILLY_TAVERN_SOURCE,
        )
        val legacy = StmCoreSlot(
            id = "legacy",
            state = StmCoreSlotState.READY,
            revision = 1,
        )

        assertEquals(
            listOf(real, legacy),
            listOf(synthetic, real, legacy).userVisibleStSlots(),
        )
    }

    private fun slot(id: String, kind: StmCoreArtifactKind) = StmCoreSlot(
        id = id,
        state = StmCoreSlotState.READY,
        revision = 1,
        artifact = StmCoreArtifact(
            kind = kind,
            repository = "https://example.invalid/repository",
            channel = "test",
            commitSha = "a".repeat(40),
            downloadUrl = "https://example.invalid/archive.zip",
            downloadedAtEpochMs = 1,
            archiveLength = 1,
            archiveSha256 = "b".repeat(64),
            integrity = StmCoreArtifactIntegrity.VERIFIED,
            trust = StmCoreArtifactTrust.DEGRADED_UNSIGNED_CATALOG,
        ),
    )
}
