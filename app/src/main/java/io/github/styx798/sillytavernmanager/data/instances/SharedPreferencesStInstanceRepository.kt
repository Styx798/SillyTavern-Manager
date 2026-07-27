package io.github.styx798.sillytavernmanager.data.instances

import android.content.Context
import android.util.Base64
import io.github.styx798.sillytavernmanager.core.instances.StInstance
import io.github.styx798.sillytavernmanager.core.instances.StInstanceDataMode
import io.github.styx798.sillytavernmanager.core.instances.StInstanceRepository
import io.github.styx798.sillytavernmanager.core.instances.StInstanceState
import io.github.styx798.sillytavernmanager.core.instances.StPendingInstanceInstall
import io.github.styx798.sillytavernmanager.core.downloads.StDownloadChannel
import io.github.styx798.sillytavernmanager.core.downloads.requireExactCommitSha
import io.github.styx798.sillytavernmanager.core.instances.instanceNameCollisionKey
import io.github.styx798.sillytavernmanager.core.instances.requireValidInstanceName
import io.github.styx798.sillytavernmanager.stmcore.StmCoreInstallMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SharedPreferencesStInstanceRepository(context: Context) : StInstanceRepository {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val mutableState = MutableStateFlow(load())

    override val state: StateFlow<StInstanceState> = mutableState.asStateFlow()

    @Synchronized
    override fun add(instance: StInstance, makeActive: Boolean): Result<Unit> = runCatching {
        requireValidInstance(instance)
        val current = mutableState.value
        require(current.instances.none { it.id == instance.id }) {
            "Instance already exists"
        }
        require(current.instances.none { it.slotId == instance.slotId }) {
            "Instance slot is already registered"
        }
        requireUniqueName(instance.displayName, current.instances)
        val instances = (current.instances + instance).sortedBy(StInstance::createdAtEpochMs)
        val activeId = when {
            makeActive -> instance.id
            current.activeInstanceId != null -> current.activeInstanceId
            else -> null
        }
        persist(instances, activeId, current.pendingInstall)
    }.onFailure(::publishFailure)

    @Synchronized
    override fun beginInstall(pending: StPendingInstanceInstall): Result<Unit> = runCatching {
        requireValidPendingInstall(pending)
        val current = mutableState.value
        require(current.pendingInstall == null) { "Another instance install is already pending" }
        require(current.instances.none { it.id == pending.instanceId }) {
            "Instance already exists"
        }
        require(current.instances.none { it.slotId == pending.slotId }) {
            "Instance slot is already registered"
        }
        requireUniqueName(pending.displayName, current.instances)
        persist(current.instances, current.activeInstanceId, pending)
    }.onFailure(::publishFailure)

    @Synchronized
    override fun completeInstall(instance: StInstance, makeActive: Boolean): Result<Unit> =
        runCatching {
            requireValidInstance(instance)
            val current = mutableState.value
            val pending = current.pendingInstall
                ?: error("No instance install is pending")
            require(pending.instanceId == instance.id && pending.slotId == instance.slotId) {
                "The READY slot does not match the pending instance"
            }
            require(pending.displayName == instance.displayName) {
                "The READY instance name does not match the pending install"
            }
            require(current.instances.none { it.id == instance.id }) {
                "Instance already exists"
            }
            require(current.instances.none { it.slotId == instance.slotId }) {
                "Instance slot is already registered"
            }
            requireUniqueName(instance.displayName, current.instances)
            val instances = (current.instances + instance).sortedBy(StInstance::createdAtEpochMs)
            val activeId = when {
                makeActive -> instance.id
                current.activeInstanceId != null -> current.activeInstanceId
                else -> null
            }
            persist(instances, activeId, pendingInstall = null)
        }.onFailure(::publishFailure)

    @Synchronized
    override fun clearPendingInstall(instanceId: String): Result<Unit> = runCatching {
        val current = mutableState.value
        val pending = current.pendingInstall ?: return@runCatching
        require(pending.instanceId == instanceId) {
            "The pending install belongs to another instance"
        }
        persist(current.instances, current.activeInstanceId, pendingInstall = null)
    }.onFailure(::publishFailure)

    @Synchronized
    override fun rename(instanceId: String, displayName: String): Result<Unit> = runCatching {
        val name = requireValidInstanceName(displayName)
        val current = mutableState.value
        val target = current.instances.singleOrNull { it.id == instanceId }
            ?: error("Instance does not exist")
        requireUniqueName(name, current.instances.filterNot { it.id == instanceId })
        persist(
            current.instances.map { if (it.id == target.id) it.copy(displayName = name) else it },
            current.activeInstanceId,
            current.pendingInstall,
        )
    }.onFailure(::publishFailure)

    @Synchronized
    override fun select(instanceId: String): Result<Unit> = runCatching {
        val current = mutableState.value
        require(current.instances.any { it.id == instanceId }) { "Instance does not exist" }
        persist(current.instances, instanceId, current.pendingInstall)
    }.onFailure(::publishFailure)

    @Synchronized
    override fun remove(instanceId: String): Result<Unit> = runCatching {
        val current = mutableState.value
        require(current.instances.any { it.id == instanceId }) { "Instance does not exist" }
        persist(
            current.instances.filterNot { it.id == instanceId },
            current.activeInstanceId?.takeIf { it != instanceId },
            current.pendingInstall,
        )
    }.onFailure(::publishFailure)

    @Synchronized
    override fun updateDataMode(
        instanceId: String,
        dataMode: StInstanceDataMode,
    ): Result<Unit> = runCatching {
        val current = mutableState.value
        require(current.instances.any { it.id == instanceId }) { "Instance does not exist" }
        persist(
            current.instances.map {
                if (it.id == instanceId) it.copy(dataMode = dataMode) else it
            },
            current.activeInstanceId,
            current.pendingInstall,
        )
    }.onFailure(::publishFailure)

    override fun clearError() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    private fun persist(
        instances: List<StInstance>,
        activeId: String?,
        pendingInstall: StPendingInstanceInstall?,
    ) {
        val ids = instances.map(StInstance::id).toSet()
        val editor = preferences.edit().clear().putStringSet(KEY_IDS, ids)
        instances.forEach { instance ->
            editor.putString(KEY_RECORD_PREFIX + instance.id, encode(instance))
        }
        activeId?.let { editor.putString(KEY_ACTIVE_ID, it) }
        pendingInstall?.let { editor.putString(KEY_PENDING_INSTALL, encodePendingInstall(it)) }
        check(editor.commit()) { "Instance registry could not be saved" }
        mutableState.value = StInstanceState(
            instances = instances,
            activeInstanceId = activeId,
            pendingInstall = pendingInstall,
        )
    }

    private fun load(): StInstanceState {
        val invalid = mutableListOf<String>()
        val instances = preferences.getStringSet(KEY_IDS, emptySet())
            .orEmpty()
            .mapNotNull { id ->
                runCatching {
                    decode(requireNotNull(preferences.getString(KEY_RECORD_PREFIX + id, null)))
                        .also(::requireValidInstance)
                }.onFailure { invalid += id }.getOrNull()
            }
            .sortedBy(StInstance::createdAtEpochMs)
        val active = preferences.getString(KEY_ACTIVE_ID, null)
            ?.takeIf { id -> instances.any { it.id == id } }
        val pending = preferences.getString(KEY_PENDING_INSTALL, null)
            ?.let { encoded ->
                runCatching {
                    decodePendingInstall(encoded).also(::requireValidPendingInstall)
                }.onFailure { invalid += KEY_PENDING_INSTALL }.getOrNull()
            }
        return StInstanceState(
            instances = instances,
            activeInstanceId = active,
            pendingInstall = pending,
            error = if (invalid.isEmpty()) null else "Some ST instance records were invalid",
        )
    }

    private fun requireValidInstance(instance: StInstance) {
        requireValidInstanceIdentity(instance.id, instance.slotId)
        require(instance.slotRevision > 0) { "Instance slot revision is invalid" }
        require(instance.stVersion.isNotBlank()) { "Instance ST version is missing" }
        require(instance.createdAtEpochMs > 0) { "Instance creation time is invalid" }
        require(requireValidInstanceName(instance.displayName) == instance.displayName) {
            "Instance name is not normalized"
        }
    }

    private fun requireValidPendingInstall(pending: StPendingInstanceInstall) {
        requireValidInstanceIdentity(pending.instanceId, pending.slotId)
        require(pending.slotId == "st-${pending.instanceId}") {
            "Pending instance slot does not match its identity"
        }
        require(requireValidInstanceName(pending.displayName) == pending.displayName) {
            "Instance name is not normalized"
        }
        require(pending.createdAtEpochMs > 0) { "Instance creation time is invalid" }
        pending.expectedCommitSha?.let(::requireExactCommitSha)
    }

    private fun requireValidInstanceIdentity(instanceId: String, slotId: String) {
        require(UUID.fromString(instanceId).toString() == instanceId.lowercase()) {
            "Instance ID is invalid"
        }
        require(slotId.matches(SAFE_SLOT_ID)) { "Instance slot ID is invalid" }
    }

    private fun requireUniqueName(name: String, instances: List<StInstance>) {
        val key = instanceNameCollisionKey(name)
        require(instances.none { instanceNameCollisionKey(it.displayName) == key }) {
            "Instance name is already in use"
        }
    }

    private fun publishFailure(error: Throwable) {
        mutableState.value = mutableState.value.copy(
            error = error.message ?: "Instance operation failed",
        )
    }

    private fun encode(instance: StInstance): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(RECORD_VERSION)
            output.writeUTF(instance.id)
            output.writeUTF(instance.displayName)
            output.writeUTF(instance.slotId)
            output.writeLong(instance.slotRevision)
            output.writeUTF(instance.stVersion)
            output.writeLong(instance.createdAtEpochMs)
            output.writeUTF(instance.dataMode.name)
        }
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }

    private fun decode(encoded: String): StInstance {
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == RECORD_VERSION) { "Instance record version is unsupported" }
            StInstance(
                id = input.readUTF(),
                displayName = input.readUTF(),
                slotId = input.readUTF(),
                slotRevision = input.readLong(),
                stVersion = input.readUTF(),
                createdAtEpochMs = input.readLong(),
                dataMode = StInstanceDataMode.valueOf(input.readUTF()),
            ).also {
                require(input.read() == -1) { "Instance record contains trailing bytes" }
            }
        }
    }

    private fun encodePendingInstall(pending: StPendingInstanceInstall): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(PENDING_RECORD_VERSION)
            output.writeUTF(pending.instanceId)
            output.writeUTF(pending.displayName)
            output.writeUTF(pending.slotId)
            output.writeUTF(pending.channel.name)
            output.writeUTF(pending.installMode.name)
            output.writeBoolean(pending.expectedCommitSha != null)
            pending.expectedCommitSha?.let(output::writeUTF)
            output.writeLong(pending.createdAtEpochMs)
        }
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }

    private fun decodePendingInstall(encoded: String): StPendingInstanceInstall {
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == PENDING_RECORD_VERSION) {
                "Pending instance record version is unsupported"
            }
            StPendingInstanceInstall(
                instanceId = input.readUTF(),
                displayName = input.readUTF(),
                slotId = input.readUTF(),
                channel = StDownloadChannel.valueOf(input.readUTF()),
                installMode = StmCoreInstallMode.valueOf(input.readUTF()),
                expectedCommitSha = if (input.readBoolean()) input.readUTF() else null,
                createdAtEpochMs = input.readLong(),
            ).also {
                require(input.read() == -1) { "Pending instance record contains trailing bytes" }
            }
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "stm_instances_v1"
        const val KEY_IDS = "instance_ids"
        const val KEY_ACTIVE_ID = "active_instance_id"
        const val KEY_RECORD_PREFIX = "instance."
        const val KEY_PENDING_INSTALL = "pending_install"
        const val RECORD_VERSION = 1
        const val PENDING_RECORD_VERSION = 1
        val SAFE_SLOT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
    }
}
