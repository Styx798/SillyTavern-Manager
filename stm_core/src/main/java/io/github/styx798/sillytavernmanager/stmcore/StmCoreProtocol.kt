package io.github.styx798.sillytavernmanager.stmcore

import android.os.Bundle
import android.os.Message
import android.os.ParcelFileDescriptor

internal object StmCoreProtocol {
    const val MESSAGE_REGISTER_CLIENT = 1
    const val MESSAGE_UNREGISTER_CLIENT = 2
    const val MESSAGE_START = 3
    const val MESSAGE_STOP = 4
    const val MESSAGE_STATE = 5
    const val MESSAGE_INSTALL_CACHED_ARTIFACT = 6
    const val MESSAGE_CANCEL_JOB = 7
    const val MESSAGE_ACTIVATE_SLOT = 8
    const val MESSAGE_ROLLBACK_SLOT = 9
    const val MESSAGE_REMOVE_SLOT = 10
    const val MESSAGE_IMPORT_ARTIFACT = 11
    const val MESSAGE_INSTALL_IMPORTED_ARTIFACT = 12
    const val MESSAGE_VERIFY_SLOT = 13
    const val MESSAGE_CONTINUE_WAITING = 14
    const val MESSAGE_RESTART_CORE = 15
    const val MESSAGE_CLOSE_CORE = 16
    const val MESSAGE_APP_TASK_REMOVED = 17
    const val MESSAGE_CREATE_USER_DATA_BACKUP = 18
    const val MESSAGE_REPLACE_USER_DATA = 19
    const val MESSAGE_RESTORE_USER_DATA_BACKUP = 20
    const val MESSAGE_DELETE_USER_DATA_BACKUP = 21
    const val MESSAGE_MIGRATE_LEGACY_USER_DATA = 22
    const val MESSAGE_FINALIZE_LEGACY_USER_DATA_MIGRATION = 23

    private const val KEY_OPERATION_ID = "operation_id"
    private const val KEY_TARGET_ID = "target_id"
    private const val KEY_CACHE_FILE_NAME = "cache_file_name"
    private const val KEY_ARTIFACT = "artifact"
    private const val KEY_SOURCE_DESCRIPTOR = "source_descriptor"
    private const val KEY_INSTALL_MODE = "install_mode"
    private const val KEY_INSTANCE_ID = "instance_id"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_BACKUP_FILE_NAME = "backup_file_name"
    private const val KEY_BACKUP_FIRST = "backup_first"

    fun commandMessage(what: Int, operationId: String): Message =
        Message.obtain(null, what).apply {
            data = Bundle().apply { putString(KEY_OPERATION_ID, operationId) }
        }

    fun operationIdFrom(message: Message): String? =
        message.data.getString(KEY_OPERATION_ID)?.takeIf(::isUuid)

    fun startMessage(operationId: String, instanceId: String?): Message =
        commandMessage(MESSAGE_START, operationId).apply {
            instanceId?.let { data.putString(KEY_INSTANCE_ID, it) }
        }

    fun instanceIdFrom(message: Message): String? =
        message.data.getString(KEY_INSTANCE_ID)?.takeIf(::isValidStmCoreInstanceId)

    fun hasInvalidInstanceId(message: Message): Boolean =
        message.data.containsKey(KEY_INSTANCE_ID) && instanceIdFrom(message) == null

    fun userDataCommandMessage(
        what: Int,
        operationId: String,
        instanceId: String,
        displayName: String? = null,
        backupFileName: String? = null,
    ): Message = commandMessage(what, operationId).apply {
        data.putString(KEY_INSTANCE_ID, instanceId)
        displayName?.let { data.putString(KEY_DISPLAY_NAME, it) }
        backupFileName?.let { data.putString(KEY_BACKUP_FILE_NAME, it) }
    }

    fun replaceUserDataMessage(
        operationId: String,
        instanceId: String,
        displayName: String,
        sourceDescriptor: ParcelFileDescriptor,
        backupFirst: Boolean,
    ): Message = userDataCommandMessage(
        MESSAGE_REPLACE_USER_DATA,
        operationId,
        instanceId,
        displayName = displayName,
    ).apply {
        data.putParcelable(KEY_SOURCE_DESCRIPTOR, sourceDescriptor)
        data.putBoolean(KEY_BACKUP_FIRST, backupFirst)
    }

    fun userDataRequestFrom(message: Message): StmCoreUserDataRequest? {
        val operationId = operationIdFrom(message) ?: return null
        val instanceId = instanceIdFrom(message) ?: return null
        val displayName = message.data.getString(KEY_DISPLAY_NAME)
            ?.takeIf { it.isNotBlank() && it.length <= 160 }
        val backupFileName = message.data.getString(KEY_BACKUP_FILE_NAME)
            ?.takeIf(::isSafeBackupFileName)
        return StmCoreUserDataRequest(
            operationId = operationId,
            instanceId = instanceId,
            displayName = displayName,
            backupFileName = backupFileName,
            backupFirst = message.data.getBoolean(KEY_BACKUP_FIRST),
        )
    }

    @Suppress("DEPRECATION")
    fun userDataImportRequestFrom(message: Message): StmCoreUserDataImportRequest? {
        val descriptor = message.data.getParcelable<ParcelFileDescriptor>(
            KEY_SOURCE_DESCRIPTOR,
        ) ?: return null
        val request = userDataRequestFrom(message) ?: run {
            descriptor.close()
            return null
        }
        if (request.displayName == null) {
            descriptor.close()
            return null
        }
        return StmCoreUserDataImportRequest(request, descriptor)
    }

    fun targetCommandMessage(what: Int, operationId: String, targetId: String): Message =
        commandMessage(what, operationId).apply {
            data.putString(KEY_TARGET_ID, targetId)
        }

    fun targetIdFrom(message: Message): String? =
        message.data.getString(KEY_TARGET_ID)?.takeIf(::isSafeId)

    fun installMessage(
        operationId: String,
        slotId: String,
        cacheFileName: String,
        artifact: StmCoreArtifact,
        installMode: StmCoreInstallMode,
    ): Message = targetCommandMessage(MESSAGE_INSTALL_CACHED_ARTIFACT, operationId, slotId).apply {
        data.putString(KEY_CACHE_FILE_NAME, cacheFileName)
        data.putBundle(KEY_ARTIFACT, artifact.toBundle())
        data.putString(KEY_INSTALL_MODE, installMode.name)
    }

    fun installRequestFrom(message: Message): StmCoreInstallRequest? {
        val operationId = operationIdFrom(message) ?: return null
        val slotId = targetIdFrom(message) ?: return null
        val cacheFileName = message.data.getString(KEY_CACHE_FILE_NAME)
            ?.takeIf(::isSafeCacheFileName)
            ?: return null
        val artifact = message.data.getBundle(KEY_ARTIFACT)?.toCoreArtifact() ?: return null
        val installMode = installModeFrom(message) ?: return null
        return StmCoreInstallRequest(operationId, slotId, cacheFileName, artifact, installMode)
    }

    fun importArtifactMessage(
        operationId: String,
        slotId: String,
        sourceDescriptor: ParcelFileDescriptor,
        artifact: StmCoreArtifact,
    ): Message = targetCommandMessage(MESSAGE_IMPORT_ARTIFACT, operationId, slotId).apply {
        data.putParcelable(KEY_SOURCE_DESCRIPTOR, sourceDescriptor)
        data.putBundle(KEY_ARTIFACT, artifact.toBundle())
    }

    fun installImportedArtifactMessage(
        operationId: String,
        slotId: String,
        sourceDescriptor: ParcelFileDescriptor,
        artifact: StmCoreArtifact,
        installMode: StmCoreInstallMode,
    ): Message = targetCommandMessage(
        MESSAGE_INSTALL_IMPORTED_ARTIFACT,
        operationId,
        slotId,
    ).apply {
        data.putParcelable(KEY_SOURCE_DESCRIPTOR, sourceDescriptor)
        data.putBundle(KEY_ARTIFACT, artifact.toBundle())
        data.putString(KEY_INSTALL_MODE, installMode.name)
    }

    @Suppress("DEPRECATION")
    fun importRequestFrom(message: Message): StmCoreImportRequest? {
        val sourceDescriptor = message.data.getParcelable<ParcelFileDescriptor>(
            KEY_SOURCE_DESCRIPTOR,
        ) ?: return null
        val operationId = operationIdFrom(message) ?: run {
            sourceDescriptor.close()
            return null
        }
        val slotId = targetIdFrom(message) ?: run {
            sourceDescriptor.close()
            return null
        }
        val artifact = try {
            message.data.getBundle(KEY_ARTIFACT)?.toCoreArtifact()
        } catch (error: Exception) {
            sourceDescriptor.close()
            throw error
        }
        if (artifact == null) {
            sourceDescriptor.close()
            return null
        }
        val installMode = installModeFrom(message)
        if (installMode == null) {
            sourceDescriptor.close()
            return null
        }
        return StmCoreImportRequest(
            operationId,
            slotId,
            sourceDescriptor,
            artifact,
            installMode,
        )
    }

    @Suppress("DEPRECATION")
    fun closeImportDescriptor(message: Message) {
        runCatching {
            message.data.getParcelable<ParcelFileDescriptor>(KEY_SOURCE_DESCRIPTOR)?.close()
        }
    }

    fun closeSourceDescriptor(message: Message) = closeImportDescriptor(message)

    fun stateMessage(state: StmCoreState): Message =
        Message.obtain(null, MESSAGE_STATE).apply {
            data = StmCoreStateBundleCodec.toBundle(state)
        }

    fun appTaskRemovedMessage(): Message =
        Message.obtain(null, MESSAGE_APP_TASK_REMOVED)

    fun stateFrom(message: Message): StmCoreState? {
        if (message.what != MESSAGE_STATE) return null
        return StmCoreStateBundleCodec.fromBundle(message.data)
    }

    private fun isUuid(value: String): Boolean = runCatching {
        java.util.UUID.fromString(value).toString().equals(value, ignoreCase = true)
    }.getOrDefault(false)

    private fun isSafeId(value: String): Boolean =
        value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))

    private fun isSafeCacheFileName(value: String): Boolean =
        value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}\\.zip"))

    private fun isSafeBackupFileName(value: String): Boolean =
        value.matches(Regex("[\\p{L}\\p{N}_-][\\p{L}\\p{N}_.-]{0,119}\\.zip"))

    private fun installModeFrom(message: Message): StmCoreInstallMode? {
        val encoded = message.data.getString(KEY_INSTALL_MODE)
            ?: return StmCoreInstallMode.FAST_SIGNED_RUNTIME
        return runCatching { StmCoreInstallMode.valueOf(encoded) }.getOrNull()
    }
}

internal fun isValidStmCoreInstanceId(value: String): Boolean = runCatching {
    java.util.UUID.fromString(value).toString().equals(value, ignoreCase = true)
}.getOrDefault(false)

enum class StmCoreInstallMode {
    FAST_SIGNED_RUNTIME,
    LOCAL_NPM_BUILD,
}

data class StmCoreInstallRequest(
    val operationId: String,
    val slotId: String,
    val cacheFileName: String,
    val artifact: StmCoreArtifact,
    val installMode: StmCoreInstallMode,
)

data class StmCoreImportRequest(
    val operationId: String,
    val slotId: String,
    val sourceDescriptor: ParcelFileDescriptor,
    val artifact: StmCoreArtifact,
    val installMode: StmCoreInstallMode,
)

data class StmCoreUserDataRequest(
    val operationId: String,
    val instanceId: String,
    val displayName: String?,
    val backupFileName: String?,
    val backupFirst: Boolean,
)

data class StmCoreUserDataImportRequest(
    val request: StmCoreUserDataRequest,
    val sourceDescriptor: ParcelFileDescriptor,
)

private object StmCoreStateBundleCodec {
    private const val PROTOCOL_VERSION = "protocol_version"
    private const val REVISION = "revision"
    private const val OPERATION_ID = "operation_id"
    private const val UPDATED_AT = "updated_at"
    private const val PROCESS_IDENTITY = "process_identity"
    private const val PROCESS_ID = "process_id"
    private const val INSTALLER_RECOVERY_COMPLETE = "installer_recovery_complete"
    private const val SESSION_ID = "session_id"
    private const val WEB_SESSION_CREDENTIAL = "web_session_credential"
    private const val RUN_STATE = "run_state"
    private const val WORKLOAD = "workload"
    private const val LOCAL_BASE_URL = "local_base_url"
    private const val PORT = "port"
    private const val LAST_HEALTHY_AT = "last_healthy_at"
    private const val SUMMARY = "summary"
    private const val ERROR = "error"
    private const val CORE_VERSION = "core_version"
    private const val JAVET_ARTIFACT = "javet_artifact"
    private const val NODE_VERSION = "node_version"
    private const val SLOTS = "slots"
    private const val ACTIVE_SLOT = "active_slot"
    private const val RUNNING_SLOT = "running_slot"
    private const val JOBS = "jobs"
    private const val WAIT_PROMPT = "wait_prompt"
    private const val RUNTIME_TRANSFER = "runtime_transfer"

    fun toBundle(state: StmCoreState): Bundle = Bundle().apply {
        state.requireValidCoreSnapshot()
        putInt(PROTOCOL_VERSION, state.protocolVersion)
        putLong(REVISION, state.revision)
        putString(OPERATION_ID, state.operationId)
        putLong(UPDATED_AT, state.updatedAtEpochMs)
        putString(PROCESS_IDENTITY, state.processIdentity)
        putInt(PROCESS_ID, requireNotNull(state.processId))
        putBoolean(INSTALLER_RECOVERY_COMPLETE, state.installerRecoveryComplete)
        putString(SESSION_ID, state.sessionId)
        putString(WEB_SESSION_CREDENTIAL, state.webSessionCredential?.value)
        putString(RUN_STATE, state.runState.name)
        putString(WORKLOAD, state.workload.name)
        putString(LOCAL_BASE_URL, state.localBaseUrl)
        state.port?.let { putInt(PORT, it) }
        state.lastHealthyAtEpochMs?.let { putLong(LAST_HEALTHY_AT, it) }
        putString(SUMMARY, state.summary)
        state.error?.let { putBundle(ERROR, it.toBundle()) }
        putString(CORE_VERSION, state.coreVersion)
        putString(JAVET_ARTIFACT, state.javetArtifact)
        putString(NODE_VERSION, state.nodeVersion)
        putParcelableArray(SLOTS, state.slots.map(StmCoreSlot::toBundle).toTypedArray())
        state.activeSlot?.let { putBundle(ACTIVE_SLOT, it.toBundle()) }
        state.runningSlot?.let { putBundle(RUNNING_SLOT, it.toBundle()) }
        putParcelableArray(JOBS, state.jobs.map(StmCoreJob::toBundle).toTypedArray())
        state.waitPrompt?.let { putBundle(WAIT_PROMPT, it.toBundle()) }
        state.runtimeTransfer?.let { putBundle(RUNTIME_TRANSFER, it.toBundle()) }
    }

    @Suppress("DEPRECATION")
    fun fromBundle(bundle: Bundle): StmCoreState? = runCatching {
        StmCoreState(
            protocolVersion = bundle.getInt(PROTOCOL_VERSION),
            revision = bundle.getLong(REVISION),
            operationId = bundle.getString(OPERATION_ID),
            updatedAtEpochMs = bundle.getLong(UPDATED_AT),
            processIdentity = bundle.getString(PROCESS_IDENTITY),
            processId = bundle.getInt(PROCESS_ID).takeIf { it > 0 },
            installerRecoveryComplete = bundle.getBoolean(INSTALLER_RECOVERY_COMPLETE),
            sessionId = bundle.getString(SESSION_ID),
            webSessionCredential = bundle.getString(WEB_SESSION_CREDENTIAL)
                ?.let(StmCoreWebSessionCredential::fromProtocol),
            runState = StmCoreRunState.valueOf(requireNotNull(bundle.getString(RUN_STATE))),
            workload = StmCoreWorkload.valueOf(requireNotNull(bundle.getString(WORKLOAD))),
            localBaseUrl = bundle.getString(LOCAL_BASE_URL),
            port = bundle.getInt(PORT).takeIf { bundle.containsKey(PORT) },
            lastHealthyAtEpochMs = bundle.getLong(LAST_HEALTHY_AT)
                .takeIf { bundle.containsKey(LAST_HEALTHY_AT) },
            summary = bundle.getString(SUMMARY),
            error = bundle.getBundle(ERROR)?.toCoreError(),
            coreVersion = requireNotNull(bundle.getString(CORE_VERSION)),
            javetArtifact = requireNotNull(bundle.getString(JAVET_ARTIFACT)),
            nodeVersion = bundle.getString(NODE_VERSION),
            slots = bundle.getParcelableArray(SLOTS)
                .orEmpty()
                .map { (it as Bundle).toCoreSlot() },
            activeSlot = bundle.getBundle(ACTIVE_SLOT)?.toActiveSlot(),
            runningSlot = bundle.getBundle(RUNNING_SLOT)?.toActiveSlot(),
            jobs = bundle.getParcelableArray(JOBS)
                .orEmpty()
                .map { (it as Bundle).toCoreJob() },
            waitPrompt = bundle.getBundle(WAIT_PROMPT)?.toCoreWaitPrompt(),
            runtimeTransfer = bundle.getBundle(RUNTIME_TRANSFER)?.toCoreTransferProgress(),
        ).requireValidCoreSnapshot()
    }.getOrNull()
}

private fun StmCoreError.toBundle(): Bundle = Bundle().apply {
    putString("domain", domain)
    putString("code", code)
    putString("summary", summary)
    putString("diagnostic_detail", diagnosticDetail)
}

private fun Bundle.toCoreError(): StmCoreError = StmCoreError(
    domain = requireNotNull(getString("domain")),
    code = requireNotNull(getString("code")),
    summary = requireNotNull(getString("summary")),
    diagnosticDetail = getString("diagnostic_detail"),
)

private fun StmCoreSlot.toBundle(): Bundle = Bundle().apply {
    putString("id", id)
    putString("state", state.name)
    putLong("revision", revision)
    putString("repository", repository)
    putString("commit_sha", commitSha)
    artifact?.let { putBundle("artifact", it.toBundle()) }
    putString("manifest_sha256", manifestSha256)
    manifestFileCount?.let { putInt("manifest_file_count", it) }
    manifestTotalBytes?.let { putLong("manifest_total_bytes", it) }
}

private fun Bundle.toCoreSlot(): StmCoreSlot = StmCoreSlot(
    id = requireNotNull(getString("id")),
    state = StmCoreSlotState.valueOf(requireNotNull(getString("state"))),
    revision = getLong("revision"),
    repository = getString("repository"),
    commitSha = getString("commit_sha"),
    artifact = getBundle("artifact")?.toCoreArtifact(),
    manifestSha256 = getString("manifest_sha256"),
    manifestFileCount = getInt("manifest_file_count").takeIf { containsKey("manifest_file_count") },
    manifestTotalBytes = getLong("manifest_total_bytes")
        .takeIf { containsKey("manifest_total_bytes") },
)

private fun StmCoreArtifact.toBundle(): Bundle = Bundle().apply {
    putString("kind", kind.name)
    putString("repository", repository)
    putString("channel", channel)
    putString("commit_sha", commitSha)
    putString("download_url", downloadUrl)
    putLong("downloaded_at", downloadedAtEpochMs)
    putLong("archive_length", archiveLength)
    putString("archive_sha256", archiveSha256)
    putString("integrity", integrity.name)
    putString("trust", trust.name)
    putString("catalog_version", catalogVersion)
    putString("archive_root", archiveRoot)
    putString("st_version", stVersion)
    putString("node_requirement", nodeRequirement)
    putString("package_lock_sha256", packageLockSha256)
    putString("license_status", licenseStatus)
}

private fun Bundle.toCoreArtifact(): StmCoreArtifact = StmCoreArtifact(
    kind = StmCoreArtifactKind.valueOf(requireNotNull(getString("kind"))),
    repository = requireNotNull(getString("repository")),
    channel = requireNotNull(getString("channel")),
    commitSha = requireNotNull(getString("commit_sha")),
    downloadUrl = requireNotNull(getString("download_url")),
    downloadedAtEpochMs = getLong("downloaded_at"),
    archiveLength = getLong("archive_length"),
    archiveSha256 = requireNotNull(getString("archive_sha256")),
    integrity = StmCoreArtifactIntegrity.valueOf(requireNotNull(getString("integrity"))),
    trust = StmCoreArtifactTrust.valueOf(requireNotNull(getString("trust"))),
    catalogVersion = getString("catalog_version"),
    archiveRoot = getString("archive_root"),
    stVersion = getString("st_version"),
    nodeRequirement = getString("node_requirement"),
    packageLockSha256 = getString("package_lock_sha256"),
    licenseStatus = getString("license_status"),
)

private fun StmCoreActiveSlot.toBundle(): Bundle = Bundle().apply {
    putString("slot_id", slotId)
    putLong("slot_revision", slotRevision)
    putLong("active_revision", activeRevision)
}

private fun Bundle.toActiveSlot(): StmCoreActiveSlot = StmCoreActiveSlot(
    slotId = requireNotNull(getString("slot_id")),
    slotRevision = getLong("slot_revision"),
    activeRevision = getLong("active_revision"),
)

private fun StmCoreJob.toBundle(): Bundle = Bundle().apply {
    putString("operation_id", operationId)
    putString("type", type.name)
    putString("target_id", targetId)
    putString("phase", phase.name)
    putString("state", state.name)
    putLong("started_at", startedAtEpochMs)
    putLong("updated_at", updatedAtEpochMs)
    progress?.let { putDouble("progress", it) }
    error?.let { putBundle("error", it.toBundle()) }
    artifact?.let { putBundle("artifact", it.toBundle()) }
}

private fun Bundle.toCoreJob(): StmCoreJob = StmCoreJob(
    operationId = requireNotNull(getString("operation_id")),
    type = StmCoreJobType.valueOf(requireNotNull(getString("type"))),
    targetId = requireNotNull(getString("target_id")),
    phase = StmCoreJobPhase.valueOf(requireNotNull(getString("phase"))),
    state = StmCoreJobState.valueOf(requireNotNull(getString("state"))),
    startedAtEpochMs = getLong("started_at"),
    updatedAtEpochMs = getLong("updated_at"),
    progress = getDouble("progress").takeIf { containsKey("progress") },
    error = getBundle("error")?.toCoreError(),
    artifact = getBundle("artifact")?.toCoreArtifact(),
)

private fun StmCoreWaitPrompt.toBundle(): Bundle = Bundle().apply {
    putString("operation_id", operationId)
    putString("kind", kind.name)
    putLong("interval_millis", intervalMillis)
    putLong("triggered_at", triggeredAtEpochMs)
    putString("summary", summary)
}

private fun Bundle.toCoreWaitPrompt(): StmCoreWaitPrompt = StmCoreWaitPrompt(
    operationId = requireNotNull(getString("operation_id")),
    kind = StmCoreWaitKind.valueOf(requireNotNull(getString("kind"))),
    intervalMillis = getLong("interval_millis"),
    triggeredAtEpochMs = getLong("triggered_at"),
    summary = requireNotNull(getString("summary")),
)

private fun StmCoreTransferProgress.toBundle(): Bundle = Bundle().apply {
    putString("operation_id", operationId)
    putLong("transferred_bytes", transferredBytes)
    putLong("total_bytes", totalBytes)
    putLong("bytes_per_second", bytesPerSecond)
    putLong("updated_at", updatedAtEpochMs)
}

private fun Bundle.toCoreTransferProgress(): StmCoreTransferProgress =
    StmCoreTransferProgress(
        operationId = requireNotNull(getString("operation_id")),
        transferredBytes = getLong("transferred_bytes"),
        totalBytes = getLong("total_bytes"),
        bytesPerSecond = getLong("bytes_per_second"),
        updatedAtEpochMs = getLong("updated_at"),
    )
