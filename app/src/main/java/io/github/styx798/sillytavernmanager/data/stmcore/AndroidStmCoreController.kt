package io.github.styx798.sillytavernmanager.data.stmcore

import android.content.Context
import android.os.Environment
import android.os.Looper
import android.os.ParcelFileDescriptor
import io.github.styx798.sillytavernmanager.BuildConfig
import io.github.styx798.sillytavernmanager.core.downloads.DownloadedStArchive
import io.github.styx798.sillytavernmanager.core.downloads.StArchiveIdentityClassification
import io.github.styx798.sillytavernmanager.core.downloads.StArchiveIntegrityClassification
import io.github.styx798.sillytavernmanager.core.downloads.StArchiveTrust
import io.github.styx798.sillytavernmanager.core.downloads.StDownloadChannel
import io.github.styx798.sillytavernmanager.core.stmcore.StmCoreCommandResult
import io.github.styx798.sillytavernmanager.core.stmcore.StmCoreConnectionState
import io.github.styx798.sillytavernmanager.core.stmcore.StmCoreController
import io.github.styx798.sillytavernmanager.stmcore.StmCoreClient
import io.github.styx798.sillytavernmanager.stmcore.StmCoreClientListener
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifact
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactIntegrity
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactKind
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactTrust
import io.github.styx798.sillytavernmanager.stmcore.StmCoreState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreInstallMode
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class AndroidStmCoreController(context: Context) :
    StmCoreController,
    StmCoreClientListener {
    private val appContext = context.applicationContext
    private val mutableState = MutableStateFlow(
        StmCoreState(summary = "Connecting to the private STM Core process"),
    )
    private val client = StmCoreClient(context, this)
    private val mutableConnectionState = MutableStateFlow(StmCoreConnectionState.CONNECTING)
    private val snapshotEpoch = StmCoreSnapshotEpoch()
    private var closedByUser = false
    private var detachedAfterTaskRemoval = false
    private var restartRequested = false

    override val state: StateFlow<StmCoreState> = mutableState.asStateFlow()
    override val connectionState: StateFlow<StmCoreConnectionState> =
        mutableConnectionState.asStateFlow()

    val coreProcessId: Int?
        get() = mutableState.value.processId

    init {
        client.connect()
    }

    override fun resumeAppTask() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "STM app-task ownership must be resumed from the main thread"
        }
        if (!detachedAfterTaskRemoval) return
        detachedAfterTaskRemoval = false
        closedByUser = false
        restartRequested = false
        snapshotEpoch.disconnect()
        mutableConnectionState.value = StmCoreConnectionState.CONNECTING
        if (!client.connect()) {
            mutableConnectionState.value = StmCoreConnectionState.DISCONNECTED
        }
    }

    override suspend fun start(): StmCoreCommandResult = withContext(Dispatchers.Main.immediate) {
        if (mutableConnectionState.value != StmCoreConnectionState.CONNECTED) {
            return@withContext StmCoreCommandResult.Rejected(
                "STM Core is still establishing its durable control state",
            )
        }
        val current = mutableState.value
        if (!current.installerRecoveryComplete) {
            return@withContext StmCoreCommandResult.Rejected(
                "STM Core is still reconciling its durable installer state",
            )
        }
        if (!current.canStart) {
            return@withContext StmCoreCommandResult.Rejected(
                "STM Core cannot start while it is ${current.runState.name.lowercase()}",
            )
        }
        val active = current.activeSlot
        val activeSlot = active?.let { pointer ->
            current.slots.singleOrNull {
                it.id == pointer.slotId &&
                    it.revision == pointer.slotRevision &&
                    it.artifact?.kind == StmCoreArtifactKind.SILLY_TAVERN_SOURCE
            }
        }
        if (activeSlot == null && !BuildConfig.DEBUG) {
            return@withContext StmCoreCommandResult.Rejected(
                "Select a READY SillyTavern version before starting it",
            )
        }
        if (activeSlot != null && !client.prepareForSillyTavernStart()) {
            return@withContext StmCoreCommandResult.Rejected(
                "Android did not allow the SillyTavern foreground runtime to start",
            )
        }
        val operationId = UUID.randomUUID().toString()
        deliverConnectedCoreCommand(
            connectionState = mutableConnectionState.value,
            unavailableReason = "Android could not bind the private STM Core service",
            delivery = { client.connectAndStart(operationId) },
            onDeliveryFailure = {
                client.releasePreparedSillyTavernForeground()
                observeIpcFailure("Android could not bind the private STM Core service")
            },
        )
    }

    override suspend fun stop(): StmCoreCommandResult = withContext(Dispatchers.Main.immediate) {
        if (mutableConnectionState.value != StmCoreConnectionState.CONNECTED) {
            return@withContext StmCoreCommandResult.Rejected(
                "The STM Core control state is not connected",
            )
        }
        val current = mutableState.value
        if (!current.canStop) {
            return@withContext StmCoreCommandResult.Rejected(
                "STM Core cannot stop while it is ${current.runState.name.lowercase()}",
            )
        }
        val operationId = UUID.randomUUID().toString()
        deliverConnectedCoreCommand(
            connectionState = mutableConnectionState.value,
            unavailableReason = "The STM Core control connection is unavailable",
            delivery = { client.requestStop(operationId) },
            onDeliveryFailure = {
                observeIpcFailure("The STM Core control connection is unavailable")
            },
        )
    }

    override suspend fun openCore(): StmCoreCommandResult = withContext(Dispatchers.Main.immediate) {
        if (mutableConnectionState.value != StmCoreConnectionState.CLOSED) {
            return@withContext StmCoreCommandResult.Rejected("STM Core is already open")
        }
        detachedAfterTaskRemoval = false
        closedByUser = false
        restartRequested = false
        snapshotEpoch.disconnect()
        mutableConnectionState.value = StmCoreConnectionState.CONNECTING
        if (client.connect()) {
            StmCoreCommandResult.Accepted
        } else {
            mutableConnectionState.value = StmCoreConnectionState.DISCONNECTED
            StmCoreCommandResult.Rejected("Android could not bind the private STM Core service")
        }
    }

    override suspend fun restartCore(): StmCoreCommandResult =
        withContext(Dispatchers.Main.immediate) {
            if (mutableConnectionState.value != StmCoreConnectionState.CONNECTED) {
                return@withContext StmCoreCommandResult.Rejected(
                    "The STM Core control state is not connected",
                )
            }
            if (!client.prepareForCoreShutdown()) {
                return@withContext StmCoreCommandResult.Rejected(
                    "Android could not keep Core alive for its restart cleanup",
                )
            }
            val operationId = UUID.randomUUID().toString()
            restartRequested = true
            val delivered = client.requestRestartCore(operationId)
            if (!delivered) {
                restartRequested = false
                observeIpcFailure("The STM Core restart command could not be delivered")
                return@withContext StmCoreCommandResult.Rejected(
                    "The STM Core restart command could not be delivered",
                )
            }
            StmCoreCommandResult.Accepted
        }

    override suspend fun closeCore(): StmCoreCommandResult =
        withContext(Dispatchers.Main.immediate) {
            if (mutableConnectionState.value != StmCoreConnectionState.CONNECTED) {
                return@withContext StmCoreCommandResult.Rejected(
                    "The STM Core control state is not connected",
                )
            }
            if (!client.prepareForCoreShutdown()) {
                return@withContext StmCoreCommandResult.Rejected(
                    "Android could not keep Core alive for its shutdown cleanup",
                )
            }
            val operationId = UUID.randomUUID().toString()
            if (!client.requestCloseCore(operationId)) {
                return@withContext StmCoreCommandResult.Rejected(
                    "The STM Core close command could not be delivered",
                )
            }
            closedByUser = true
            detachedAfterTaskRemoval = false
            restartRequested = false
            snapshotEpoch.disconnect()
            client.disconnect()
            mutableConnectionState.value = StmCoreConnectionState.CLOSED
            StmCoreCommandResult.Accepted
        }

    override suspend fun continueWaiting(operationId: String): StmCoreCommandResult =
        withContext(Dispatchers.Main.immediate) {
            if (mutableConnectionState.value != StmCoreConnectionState.CONNECTED) {
                return@withContext StmCoreCommandResult.Rejected(
                    "The STM Core control state is not connected",
                )
            }
            val prompt = mutableState.value.waitPrompt
            if (prompt?.operationId != operationId) {
                return@withContext StmCoreCommandResult.Rejected(
                    "The wait prompt is no longer active",
                )
            }
            val commandId = UUID.randomUUID().toString()
            if (client.requestContinueWaiting(commandId, operationId)) {
                StmCoreCommandResult.Accepted
            } else {
                observeIpcFailure("The continue-waiting command could not be delivered")
                StmCoreCommandResult.Rejected(
                    "The continue-waiting command could not be delivered",
                )
            }
        }

    override suspend fun installCachedArtifact(
        slotId: String,
        cacheFileName: String,
        artifact: StmCoreArtifact,
        installMode: StmCoreInstallMode,
    ): StmCoreCommandResult = deliverMaintenanceCommand { operationId ->
        client.requestInstall(operationId, slotId, cacheFileName, artifact, installMode)
    }

    override suspend fun importDownloadedArchive(
        slotId: String,
        archive: DownloadedStArchive,
    ): StmCoreCommandResult = deliverDownloadedArchive(slotId, archive, install = false)

    override suspend fun installDownloadedArchive(
        slotId: String,
        archive: DownloadedStArchive,
        installMode: StmCoreInstallMode,
    ): StmCoreCommandResult = deliverDownloadedArchive(
        slotId,
        archive,
        install = true,
        installMode = installMode,
    )

    private suspend fun deliverDownloadedArchive(
        slotId: String,
        archive: DownloadedStArchive,
        install: Boolean,
        installMode: StmCoreInstallMode = StmCoreInstallMode.FAST_SIGNED_RUNTIME,
    ): StmCoreCommandResult {
        if (mutableConnectionState.value != StmCoreConnectionState.CONNECTED) {
            return StmCoreCommandResult.Rejected("The STM Core control state is not connected")
        }
        if (!mutableState.value.installerRecoveryComplete) {
            return StmCoreCommandResult.Rejected(
                "STM Core is still reconciling its durable installer state",
            )
        }
        val prepared = withContext(Dispatchers.IO) { prepareDownloadedArchiveImport(archive) }
        if (prepared is PreparedImport.Rejected) {
            return StmCoreCommandResult.Rejected(prepared.reason)
        }
        prepared as PreparedImport.Ready
        return withContext(Dispatchers.Main.immediate) {
            prepared.descriptor.use { descriptor ->
                if (!mutableState.value.installerRecoveryComplete) {
                    return@withContext StmCoreCommandResult.Rejected(
                        "STM Core is still reconciling its durable installer state",
                    )
                }
                val operationId = UUID.randomUUID().toString()
                deliverConnectedCoreCommand(
                    connectionState = mutableConnectionState.value,
                    unavailableReason = "The STM Core control connection is unavailable",
                    delivery = {
                        if (install) {
                            client.requestInstallImportedArtifact(
                                operationId,
                                slotId,
                            descriptor,
                            prepared.artifact,
                            installMode,
                        )
                        } else {
                            client.requestImportArtifact(
                                operationId,
                                slotId,
                                descriptor,
                                prepared.artifact,
                            )
                        }
                    },
                    onDeliveryFailure = {
                        observeIpcFailure("The STM Core control connection is unavailable")
                    },
                )
            }
        }
    }

    override suspend fun cancelJob(operationId: String): StmCoreCommandResult =
        deliverMaintenanceCommand { commandId -> client.requestCancelJob(commandId, operationId) }

    override suspend fun activate(slotId: String): StmCoreCommandResult =
        deliverMaintenanceCommand { operationId -> client.requestActivate(operationId, slotId) }

    override suspend fun rollback(): StmCoreCommandResult =
        deliverMaintenanceCommand(client::requestRollback)

    override suspend fun remove(slotId: String): StmCoreCommandResult =
        deliverMaintenanceCommand { operationId -> client.requestRemove(operationId, slotId) }

    override suspend fun verifySlot(slotId: String): StmCoreCommandResult =
        deliverMaintenanceCommand { operationId -> client.requestVerifySlot(operationId, slotId) }

    override fun onCoreStateChanged(state: StmCoreState) {
        if (closedByUser || detachedAfterTaskRemoval) return
        if (!snapshotEpoch.accept(state)) return
        restartRequested = false
        mutableState.value = state
        mutableConnectionState.value = StmCoreConnectionState.CONNECTED
    }

    override fun onCoreProcessDisconnected() {
        if (closedByUser || detachedAfterTaskRemoval) return
        if (restartRequested) {
            snapshotEpoch.disconnect()
            mutableConnectionState.value = StmCoreConnectionState.CONNECTING
            return
        }
        observeIpcFailure("The private STM Core process disconnected unexpectedly")
    }

    override fun onCoreAppTaskRemoved() {
        if (closedByUser || detachedAfterTaskRemoval) return
        detachedAfterTaskRemoval = true
        restartRequested = false
        snapshotEpoch.disconnect()
        client.disconnect()
        mutableConnectionState.value = StmCoreConnectionState.CLOSED
    }

    private fun observeIpcFailure(detail: String) {
        snapshotEpoch.disconnect()
        mutableConnectionState.value = StmCoreConnectionState.DISCONNECTED
    }

    private suspend fun deliverMaintenanceCommand(
        delivery: (String) -> Boolean,
    ): StmCoreCommandResult = withContext(Dispatchers.Main.immediate) {
        if (mutableConnectionState.value != StmCoreConnectionState.CONNECTED) {
            return@withContext StmCoreCommandResult.Rejected(
                "The STM Core control state is not connected",
            )
        }
        if (!mutableState.value.installerRecoveryComplete) {
            return@withContext StmCoreCommandResult.Rejected(
                "STM Core is still reconciling its durable installer state",
            )
        }
        val operationId = UUID.randomUUID().toString()
        deliverConnectedCoreCommand(
            connectionState = mutableConnectionState.value,
            unavailableReason = "The STM Core control connection is unavailable",
            delivery = { delivery(operationId) },
            onDeliveryFailure = {
                observeIpcFailure("The STM Core control connection is unavailable")
            },
        )
    }

    private fun prepareDownloadedArchiveImport(archive: DownloadedStArchive): PreparedImport {
        val identity = archive.identity
        val integrity = archive.integrity
        if (identity.classification != StArchiveIdentityClassification.EXACT_COMMIT ||
            integrity.classification != StArchiveIntegrityClassification.CONTENT_SHA256_RECORDED ||
            archive.trust != StArchiveTrust.DEGRADED_UNSIGNED_CATALOG
        ) {
            return PreparedImport.Rejected(
                "Only an exact-commit archive with a recorded SHA-256 can enter Core preflight",
            )
        }
        val commit = identity.exactCommit
            ?: return PreparedImport.Rejected("The archive has no exact commit identity")
        val archiveUrl = identity.archiveUrl
            ?: return PreparedImport.Rejected("The archive has no exact download URL")
        val sha256 = integrity.sha256
            ?: return PreparedImport.Rejected("The archive has no recorded SHA-256")
        val downloadedAt = archive.downloadedAtEpochMillis
            ?: return PreparedImport.Rejected("The archive has no recorded download time")
        val expectedName = archive.channel.exactArchiveFileName(commit)
        if (archive.fileName != expectedName ||
            archiveUrl != archive.channel.exactArchiveUrl(commit) ||
            integrity.byteLength <= 0 ||
            integrity.byteLength != archive.sizeBytes
        ) {
            return PreparedImport.Rejected("The external archive metadata is internally inconsistent")
        }
        val directory = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.absoluteFile
            ?: return PreparedImport.Rejected("The app download directory is unavailable")
        val unresolved = File(directory, expectedName).absoluteFile
        val unresolvedPath = unresolved.toPath()
        if (Files.isSymbolicLink(unresolvedPath) ||
            !Files.isRegularFile(unresolvedPath, LinkOption.NOFOLLOW_LINKS)
        ) {
            return PreparedImport.Rejected("The external archive is not a regular no-follow file")
        }
        val canonicalDirectory = runCatching { directory.canonicalFile }.getOrNull()
            ?: return PreparedImport.Rejected("The app download directory could not be resolved")
        val source = runCatching { unresolved.canonicalFile }.getOrNull()
            ?: return PreparedImport.Rejected("The external archive path could not be resolved")
        if (source.parentFile != canonicalDirectory || source.length() != integrity.byteLength) {
            return PreparedImport.Rejected("The external archive escaped its directory or changed length")
        }
        val descriptor = runCatching {
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        }.getOrElse { error ->
            return PreparedImport.Rejected(
                "The external archive could not be opened read-only: ${error.message.orEmpty()}",
            )
        }
        return PreparedImport.Ready(
            descriptor = descriptor,
            artifact = StmCoreArtifact(
                kind = StmCoreArtifactKind.SILLY_TAVERN_SOURCE,
                repository = StDownloadChannel.GITHUB_REPOSITORY_URL,
                channel = archive.channel.branch,
                commitSha = commit,
                downloadUrl = archiveUrl,
                downloadedAtEpochMs = downloadedAt,
                archiveLength = integrity.byteLength,
                archiveSha256 = sha256,
                integrity = StmCoreArtifactIntegrity.PENDING,
                trust = StmCoreArtifactTrust.DEGRADED_UNSIGNED_CATALOG,
            ),
        )
    }

    private sealed interface PreparedImport {
        data class Ready(
            val descriptor: ParcelFileDescriptor,
            val artifact: StmCoreArtifact,
        ) : PreparedImport

        data class Rejected(val reason: String) : PreparedImport
    }
}
