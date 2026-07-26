package io.github.styx798.sillytavernmanager.stmcore

import java.security.SecureRandom

const val STM_CORE_PROTOCOL_VERSION = 5
const val STM_CORE_WEB_SESSION_COOKIE_NAME = "stm_core_session"

class StmCoreWebSessionCredential private constructor(
    val value: String,
) {
    override fun equals(other: Any?): Boolean =
        other is StmCoreWebSessionCredential && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "StmCoreWebSessionCredential([redacted])"

    internal companion object {
        fun generate(): StmCoreWebSessionCredential {
            val bytes = ByteArray(BYTE_COUNT)
            SecureRandom().nextBytes(bytes)
            return StmCoreWebSessionCredential(
                bytes.joinToString(separator = "") { byte ->
                    "%02x".format(byte.toInt() and 0xff)
                },
            )
        }

        fun fromProtocol(value: String): StmCoreWebSessionCredential {
            require(value.matches(ENCODED_VALUE)) {
                "Core Web session credential has an invalid encoding"
            }
            return StmCoreWebSessionCredential(value)
        }

        private const val BYTE_COUNT = 32
        private val ENCODED_VALUE = Regex("[0-9a-f]{64}")
    }
}

enum class StmCoreRunState {
    STOPPED,
    STARTING,
    RUNNING,
    DRAINING,
    CRASHED,
}

enum class StmCoreWorkload {
    DIAGNOSTIC,
    SILLY_TAVERN,
}

enum class StmCoreWaitKind {
    NPM_INSTALL,
    BUNDLE_BUILD,
    RUNNABLE_ACCEPTANCE,
    SILLY_TAVERN_START,
}

enum class StmCoreSlotState {
    ABSENT,
    STAGING,
    VERIFYING,
    READY,
    BROKEN,
    RETIRED,
}

enum class StmCoreJobState {
    QUEUED,
    RUNNING,
    CANCELLING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

enum class StmCoreArtifactKind {
    GATE2_SYNTHETIC,
    SILLY_TAVERN_SOURCE,
}

enum class StmCoreArtifactIntegrity {
    PENDING,
    VERIFIED,
    FAILED,
}

enum class StmCoreArtifactTrust {
    TRUSTED_CATALOG,
    DEGRADED_UNSIGNED_CATALOG,
    REJECTED,
}

enum class StmCoreJobType {
    DOWNLOAD,
    VERIFY,
    INSTALL,
    ACTIVATE,
    ROLLBACK,
    REMOVE,
    MIGRATE,
}

enum class StmCoreJobPhase {
    QUEUED,
    COPYING_ARTIFACT,
    PREFLIGHT,
    EXTRACTING,
    DOWNLOADING_RUNTIME_LAYER,
    VERIFYING_RUNTIME_LAYER,
    PREPARING_TOOLCHAIN,
    INSTALLING_DEPENDENCIES,
    BUILDING_BUNDLE,
    ASSEMBLING_RUNTIME,
    RUNNABLE_ACCEPTANCE,
    VALIDATING,
    WRITING_MANIFEST,
    COMMITTING_SLOT,
    SWITCHING_ACTIVE,
    REMOVING_SLOT,
    CLEANING_UP,
    COMPLETE,
}

data class StmCoreError(
    val domain: String,
    val code: String,
    val summary: String,
    val diagnosticDetail: String? = null,
)

data class StmCoreWaitPrompt(
    val operationId: String,
    val kind: StmCoreWaitKind,
    val intervalMillis: Long,
    val triggeredAtEpochMs: Long,
    val summary: String,
)

data class StmCoreTransferProgress(
    val operationId: String,
    val transferredBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val updatedAtEpochMs: Long,
) {
    val fraction: Double
        get() = if (totalBytes <= 0L) {
            0.0
        } else {
            (transferredBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0)
        }
}

/**
 * A bounded public summary. Full signed catalog records and per-file manifests remain on disk.
 */
data class StmCoreArtifact(
    val kind: StmCoreArtifactKind,
    val repository: String,
    val channel: String,
    val commitSha: String,
    val downloadUrl: String,
    val downloadedAtEpochMs: Long,
    val archiveLength: Long,
    val archiveSha256: String,
    val integrity: StmCoreArtifactIntegrity,
    val trust: StmCoreArtifactTrust,
    val catalogVersion: String? = null,
    val archiveRoot: String? = null,
    val stVersion: String? = null,
    val nodeRequirement: String? = null,
    val packageLockSha256: String? = null,
    val licenseStatus: String? = null,
)

data class StmCoreSlot(
    val id: String,
    val state: StmCoreSlotState,
    val revision: Long,
    val repository: String? = null,
    val commitSha: String? = null,
    val artifact: StmCoreArtifact? = null,
    val manifestSha256: String? = null,
    val manifestFileCount: Int? = null,
    val manifestTotalBytes: Long? = null,
)

data class StmCoreActiveSlot(
    val slotId: String,
    val slotRevision: Long,
    val activeRevision: Long,
)

data class StmCoreJob(
    val operationId: String,
    val type: StmCoreJobType,
    val targetId: String,
    val phase: StmCoreJobPhase,
    val state: StmCoreJobState,
    val startedAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val progress: Double? = null,
    val error: StmCoreError? = null,
    val artifact: StmCoreArtifact? = null,
)

data class StmCoreState(
    val protocolVersion: Int = STM_CORE_PROTOCOL_VERSION,
    val revision: Long = 0,
    val operationId: String? = null,
    val updatedAtEpochMs: Long = 0,
    val processIdentity: String? = null,
    val processId: Int? = null,
    /** True only after this Core process has durably reconciled installer recovery. */
    val installerRecoveryComplete: Boolean = false,
    val sessionId: String? = null,
    /** Ephemeral Binder-only secret; deliberately omitted from the durable checkpoint. */
    val webSessionCredential: StmCoreWebSessionCredential? = null,
    val runState: StmCoreRunState = StmCoreRunState.STOPPED,
    val workload: StmCoreWorkload = StmCoreWorkload.DIAGNOSTIC,
    val localBaseUrl: String? = null,
    val port: Int? = null,
    val lastHealthyAtEpochMs: Long? = null,
    val summary: String? = null,
    val error: StmCoreError? = null,
    val coreVersion: String = BuildConfig.STM_CORE_VERSION,
    val javetArtifact: String = BuildConfig.JAVET_ARTIFACT,
    val nodeVersion: String? = null,
    val slots: List<StmCoreSlot> = emptyList(),
    val activeSlot: StmCoreActiveSlot? = null,
    /** Frozen slot reference for a running SillyTavern session. */
    val runningSlot: StmCoreActiveSlot? = null,
    val jobs: List<StmCoreJob> = emptyList(),
    /** Ephemeral live-process signal; it is deliberately excluded from the checkpoint codec. */
    val waitPrompt: StmCoreWaitPrompt? = null,
    /** Ephemeral signed-runtime telemetry; it is never durable install evidence. */
    val runtimeTransfer: StmCoreTransferProgress? = null,
) {
    val canStart: Boolean
        get() = installerRecoveryComplete &&
            (runState == StmCoreRunState.STOPPED || runState == StmCoreRunState.CRASHED)

    val canStop: Boolean
        get() = runState == StmCoreRunState.STARTING || runState == StmCoreRunState.RUNNING

    val canOpenTavern: Boolean
        get() = workload == StmCoreWorkload.SILLY_TAVERN &&
            runState == StmCoreRunState.RUNNING &&
            localBaseUrl != null &&
            webSessionCredential != null

    val isDiagnosticReady: Boolean
        get() = workload == StmCoreWorkload.DIAGNOSTIC &&
            runState == StmCoreRunState.RUNNING &&
            localBaseUrl != null

    val componentIdentity: String
        get() = "STM Core $coreVersion / Feather Engine"
}

internal fun StmCoreState.requireValidCoreSnapshot(): StmCoreState = apply {
    require(protocolVersion == STM_CORE_PROTOCOL_VERSION) {
        "Unsupported STM Core protocol version $protocolVersion"
    }
    require(revision > 0) { "Core snapshot revision must be positive" }
    require(updatedAtEpochMs > 0) { "Core snapshot time must be positive" }
    require(!processIdentity.isNullOrBlank()) { "Core process identity is required" }
    require(processId != null && processId > 0) { "Core process ID is required" }
    require(operationId == null || operationId.matches(UUID_TEXT)) {
        "Core operation ID must be a UUID"
    }
    require(port == null || port in 1..65_535) { "Core port is outside the valid range" }
    require(localBaseUrl == null || port != null) { "A loopback URL requires a port" }
    require(localBaseUrl == null || localBaseUrl == "http://127.0.0.1:$port") {
        "Core endpoint must be the reported IPv4 loopback port"
    }
    when (runState) {
        StmCoreRunState.STARTING -> require(!sessionId.isNullOrBlank()) {
            "STARTING requires a session ID"
        }

        StmCoreRunState.RUNNING -> {
            require(!sessionId.isNullOrBlank()) { "RUNNING requires a session ID" }
            require(localBaseUrl != null && port != null) { "RUNNING requires a loopback endpoint" }
            require(lastHealthyAtEpochMs != null && lastHealthyAtEpochMs > 0) {
                "RUNNING requires real health evidence"
            }
        }

        StmCoreRunState.DRAINING -> require(!sessionId.isNullOrBlank()) {
            "DRAINING requires the session being drained"
        }

        StmCoreRunState.CRASHED -> require(error != null) {
            "CRASHED requires a structured error"
        }

        StmCoreRunState.STOPPED -> {
            require(sessionId == null) { "STOPPED cannot retain an active session" }
            require(operationId == null) { "STOPPED cannot retain an active operation" }
        }
    }

    if (runState == StmCoreRunState.STOPPED || runState == StmCoreRunState.CRASHED) {
        require(localBaseUrl == null && port == null) {
            "$runState cannot expose a current loopback endpoint"
        }
        require(webSessionCredential == null) {
            "$runState cannot retain a Web session credential"
        }
    }
    if (webSessionCredential != null) {
        require(workload == StmCoreWorkload.SILLY_TAVERN) {
            "Only a SillyTavern workload may expose a Web session credential"
        }
        require(runState == StmCoreRunState.STARTING ||
            runState == StmCoreRunState.RUNNING ||
            runState == StmCoreRunState.DRAINING
        ) {
            "A Web session credential requires an active SillyTavern session"
        }
    }

    activeSlot?.let { active ->
        active.requireValidPointer()
        val slot = slots.singleOrNull { it.id == active.slotId }
        require(slot?.state == StmCoreSlotState.READY) {
            "The active pointer must reference one READY slot"
        }
        require(slot.revision == active.slotRevision) {
            "The active pointer revision must match its READY slot"
        }
    }
    runningSlot?.let { running ->
        running.requireValidPointer()
        val slot = slots.singleOrNull { it.id == running.slotId }
        require(slot?.state == StmCoreSlotState.READY) {
            "The running pointer must reference one READY slot"
        }
        require(slot.revision == running.slotRevision) {
            "The running pointer revision must match its READY slot"
        }
    }
    if (workload == StmCoreWorkload.SILLY_TAVERN && runState != StmCoreRunState.STOPPED) {
        require(runningSlot != null) { "A SillyTavern workload requires a frozen READY slot" }
    }
    if (runState == StmCoreRunState.STOPPED) {
        require(runningSlot == null) { "STOPPED cannot retain a running slot lease" }
    }
    require(slots.map(StmCoreSlot::id).distinct().size == slots.size) {
        "Slot IDs must be unique"
    }
    slots.forEach { slot ->
        require(slot.id.matches(SAFE_ID)) { "Slot ID is invalid" }
        require(slot.revision > 0) { "Slot revision must be positive" }
        if (slot.state == StmCoreSlotState.READY) {
            require(slot.artifact != null) { "A READY slot requires artifact evidence" }
            require(slot.artifact.integrity == StmCoreArtifactIntegrity.VERIFIED) {
                "A READY slot requires verified artifact integrity"
            }
            require(slot.artifact.trust != StmCoreArtifactTrust.REJECTED) {
                "A READY slot cannot use a rejected artifact"
            }
            require(slot.manifestSha256?.matches(SHA_256) == true) {
                "A READY slot requires a manifest SHA-256"
            }
            require(slot.manifestFileCount != null && slot.manifestFileCount > 0) {
                "A READY slot requires a non-empty manifest"
            }
            require(slot.manifestTotalBytes != null && slot.manifestTotalBytes >= 0) {
                "A READY slot requires manifest byte totals"
            }
        }
        require(slot.repository == null || slot.artifact == null || slot.repository == slot.artifact.repository) {
            "Slot repository summary must match its artifact"
        }
        require(slot.commitSha == null || slot.artifact == null || slot.commitSha == slot.artifact.commitSha) {
            "Slot commit summary must match its artifact"
        }
        slot.artifact?.requireValidArtifact()
    }
    require(jobs.map(StmCoreJob::operationId).distinct().size == jobs.size) {
        "Job operation IDs must be unique"
    }
    jobs.forEach { job ->
        require(job.operationId.matches(UUID_TEXT)) { "Job operation ID must be a UUID" }
        require(job.targetId.matches(SAFE_ID)) { "Job target ID is invalid" }
        require(job.startedAtEpochMs > 0 && job.updatedAtEpochMs >= job.startedAtEpochMs) {
            "Job timestamps are invalid"
        }
        require(job.revisionSafeProgress()) { "Job progress must be between 0 and 1" }
        require(job.state != StmCoreJobState.FAILED || job.error != null) {
            "A failed job requires a structured error"
        }
        job.artifact?.let { artifact ->
            require(job.type == StmCoreJobType.VERIFY) {
                "Only a verification job may retain artifact verification evidence"
            }
            require(artifact.integrity == StmCoreArtifactIntegrity.VERIFIED) {
                "Retained verification evidence requires Core-verified integrity"
            }
            require(artifact.trust != StmCoreArtifactTrust.REJECTED) {
                "Retained verification evidence cannot use rejected trust"
            }
            artifact.requireValidArtifact()
        }
    }
    waitPrompt?.let { prompt ->
        require(prompt.operationId.matches(UUID_TEXT)) { "Wait prompt operation ID is invalid" }
        require(prompt.intervalMillis > 0L) { "Wait prompt interval must be positive" }
        require(prompt.triggeredAtEpochMs > 0L) { "Wait prompt time must be positive" }
        require(prompt.summary.isNotBlank() && prompt.summary.length <= 500) {
            "Wait prompt summary is invalid"
        }
    }
    runtimeTransfer?.let { transfer ->
        require(transfer.operationId.matches(UUID_TEXT)) {
            "Runtime transfer operation ID is invalid"
        }
        require(transfer.totalBytes > 0L) { "Runtime transfer total must be positive" }
        require(transfer.transferredBytes in 0L..transfer.totalBytes) {
            "Runtime transfer byte count is invalid"
        }
        require(transfer.bytesPerSecond >= 0L) { "Runtime transfer speed is invalid" }
        require(transfer.updatedAtEpochMs > 0L) { "Runtime transfer time is invalid" }
    }
}

private fun StmCoreActiveSlot.requireValidPointer() {
    require(slotId.matches(SAFE_ID)) { "Active slot ID is invalid" }
    require(slotRevision > 0 && activeRevision > 0) { "Active slot revisions must be positive" }
}

internal fun StmCoreArtifact.requireValidArtifact(): StmCoreArtifact = apply {
    require(repository.isNotBlank() && repository.length <= 200) { "Artifact repository is invalid" }
    require(channel.isNotBlank() && channel.length <= 80) { "Artifact channel is invalid" }
    require(commitSha.matches(COMMIT_SHA)) { "Artifact commit SHA is invalid" }
    require(downloadUrl.startsWith("https://") && downloadUrl.length <= 2_048) {
        "Artifact download URL must be bounded HTTPS"
    }
    require(downloadedAtEpochMs > 0) { "Artifact download time must be positive" }
    require(archiveLength > 0) { "Artifact archive length must be positive" }
    require(archiveSha256.matches(SHA_256)) { "Artifact SHA-256 is invalid" }
    require(packageLockSha256 == null || packageLockSha256.matches(SHA_256)) {
        "package-lock SHA-256 is invalid"
    }
    require(integrity != StmCoreArtifactIntegrity.PENDING || trust != StmCoreArtifactTrust.TRUSTED_CATALOG) {
        "Unverified bytes cannot be trusted as installable"
    }
}

private fun StmCoreJob.revisionSafeProgress(): Boolean =
    progress == null || progress in 0.0..1.0

private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
private val UUID_TEXT = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
private val COMMIT_SHA = Regex("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})")
private val SHA_256 = Regex("[0-9a-fA-F]{64}")
