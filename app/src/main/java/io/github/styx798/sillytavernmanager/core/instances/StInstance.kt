package io.github.styx798.sillytavernmanager.core.instances

import io.github.styx798.sillytavernmanager.core.downloads.StDownloadChannel
import io.github.styx798.sillytavernmanager.stmcore.StmCoreInstallMode
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

enum class StInstanceDataMode {
    LEGACY_SHARED_ROOT,
    ISOLATED,
}

data class StInstance(
    val id: String,
    val displayName: String,
    val slotId: String,
    val slotRevision: Long,
    val stVersion: String,
    val createdAtEpochMs: Long,
    val dataMode: StInstanceDataMode,
) {
    val coreDataInstanceId: String?
        get() = id.takeIf { dataMode == StInstanceDataMode.ISOLATED }
}

data class StPendingInstanceInstall(
    val instanceId: String,
    val displayName: String,
    val slotId: String,
    val channel: StDownloadChannel,
    val installMode: StmCoreInstallMode,
    val expectedCommitSha: String?,
    val createdAtEpochMs: Long,
)

data class StInstanceState(
    val instances: List<StInstance> = emptyList(),
    val activeInstanceId: String? = null,
    val pendingInstall: StPendingInstanceInstall? = null,
    val error: String? = null,
) {
    val activeInstance: StInstance?
        get() = instances.singleOrNull { it.id == activeInstanceId }
}

interface StInstanceRepository {
    val state: StateFlow<StInstanceState>

    fun add(instance: StInstance, makeActive: Boolean): Result<Unit>

    fun beginInstall(pending: StPendingInstanceInstall): Result<Unit>

    fun completeInstall(instance: StInstance, makeActive: Boolean): Result<Unit>

    fun clearPendingInstall(instanceId: String): Result<Unit>

    fun rename(instanceId: String, displayName: String): Result<Unit>

    fun select(instanceId: String): Result<Unit>

    fun remove(instanceId: String): Result<Unit>

    fun clearError()
}

internal fun normalizedInstanceName(value: String): String =
    Normalizer.normalize(value.trim(), Normalizer.Form.NFC)

internal fun instanceNameCollisionKey(value: String): String =
    Normalizer.normalize(normalizedInstanceName(value), Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)

internal fun requireValidInstanceName(value: String): String {
    val normalized = normalizedInstanceName(value)
    require(normalized.isNotBlank()) { "Instance name cannot be empty" }
    require(normalized.codePointCount(0, normalized.length) <= MAX_INSTANCE_NAME_CODE_POINTS) {
        "Instance name is too long"
    }
    require(normalized.none(Char::isISOControl)) {
        "Instance name cannot contain control characters"
    }
    return normalized
}

private const val MAX_INSTANCE_NAME_CODE_POINTS = 40
