package io.github.styx798.sillytavernmanager.data.downloads

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.os.Environment
import androidx.core.net.toUri
import io.github.styx798.sillytavernmanager.core.downloads.ActiveStDownload
import io.github.styx798.sillytavernmanager.core.downloads.DownloadedStArchive
import io.github.styx798.sillytavernmanager.core.downloads.StDownloadChannel
import io.github.styx798.sillytavernmanager.core.downloads.StDownloadFailure
import io.github.styx798.sillytavernmanager.core.downloads.StDownloadFailureReason
import io.github.styx798.sillytavernmanager.core.downloads.StDownloadPhase
import io.github.styx798.sillytavernmanager.core.downloads.StDownloadRepository
import io.github.styx798.sillytavernmanager.core.downloads.StDownloadState
import io.github.styx798.sillytavernmanager.core.downloads.requireExactCommitSha
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AndroidStDownloadRepository(context: Context) : StDownloadRepository {
    private val appContext = context.applicationContext
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resolver = GitHubCommitResolver()
    private val lock = Any()
    private val mutableState = MutableStateFlow(StDownloadState())

    private var operationGeneration = 0L
    private var activeDownloadId: Long? = null
    private var activeResolvedDownload: ResolvedStDownload? = null
    private var activeAssociationMayBeRemoved = false
    private var resolveJob: Job? = null
    private var monitorJob: Job? = null
    private var consecutiveQueryFailures = 0

    override val state: StateFlow<StDownloadState> = mutableState.asStateFlow()

    init {
        initializeState()
    }

    override fun start(channel: StDownloadChannel, exactCommit: String?) {
        val requestedCommit = exactCommit?.let(::requireExactCommitSha)
        synchronized(lock) {
            if (mutableState.value.active != null) return
            if (discoverArchives().any { archive ->
                    archive.channel == channel &&
                        (requestedCommit == null || archive.identity.exactCommit == requestedCommit)
                }
            ) {
                setFailure(channel, StDownloadFailureReason.ALREADY_DOWNLOADED)
                return
            }

            operationGeneration += 1
            val generation = operationGeneration
            mutableState.value = mutableState.value.copy(
                active = ActiveStDownload(
                    channel = channel,
                    phase = StDownloadPhase.RESOLVING,
                ),
                failure = null,
            )
            val job = scope.launch(start = CoroutineStart.LAZY) {
                resolveAndEnqueue(channel, generation, requestedCommit)
            }
            resolveJob = job
            job.start()
        }
    }

    override fun cancel() {
        val cancellation: RecoveredCancellation?
        synchronized(lock) {
            if (mutableState.value.active == null) return
            operationGeneration += 1
            resolveJob?.cancel()
            resolveJob = null
            monitorJob?.cancel()
            monitorJob = null

            val downloadId = activeDownloadId
            val resolved = activeResolvedDownload
            cancellation = if (downloadId != null && resolved != null) {
                RecoveredCancellation(
                    downloadId = downloadId,
                    resolved = resolved,
                    mayRemoveImmediately = activeAssociationMayBeRemoved,
                )
            } else {
                null
            }

            activeDownloadId = null
            activeResolvedDownload = null
            activeAssociationMayBeRemoved = false
            consecutiveQueryFailures = 0
            clearActivePreferences()
            mutableState.value = mutableState.value.copy(active = null, failure = null)
        }

        cancellation?.let { activeCancellation ->
            if (activeCancellation.mayRemoveImmediately) {
                downloadManager?.remove(activeCancellation.downloadId)
                archiveFile(activeCancellation.resolved)?.delete()
                refreshArchives(failure = null)
            } else {
                scope.launch { cancelRecoveredDownload(activeCancellation) }
            }
        }
    }

    override fun delete(channel: StDownloadChannel): Boolean {
        val completed = loadCompletedMetadata(channel)
        val deleted = synchronized(lock) {
            if (mutableState.value.active?.channel == channel) return false
            val files = downloadDirectory()
                ?.listFiles()
                .orEmpty()
                .filter { file -> file.isFile && classifyOwnedArchiveFileName(file.name) == channel }
            var deletedAny = false
            files.forEach { file ->
                if (file.delete()) deletedAny = true
            }
            clearCompletedPreferences(channel)
            refreshArchives(failure = null)
            deletedAny
        }

        if (completed != null && completed.downloadId != NO_DOWNLOAD_ID) {
            scope.launch {
                if (downloadRowMatches(completed.downloadId, completed.resolved)) {
                    downloadManager?.remove(completed.downloadId)
                }
            }
        }
        return deleted
    }

    override fun deleteAll(): Int {
        var deletedCount = 0
        StDownloadChannel.entries.forEach { channel ->
            if (delete(channel)) deletedCount += 1
        }
        return deletedCount
    }

    override fun clearFailure() {
        mutableState.value = mutableState.value.copy(failure = null)
    }

    private suspend fun resolveAndEnqueue(
        channel: StDownloadChannel,
        generation: Long,
        exactCommit: String?,
    ) {
        try {
            val resolved = exactCommit?.let { commit ->
                resolveExactStDownload(channel, commit, System.currentTimeMillis())
            } ?: resolver.resolve(channel)
            if (!isCurrentResolution(channel, generation)) return

            val manager = downloadManager
            if (manager == null) {
                finishResolutionFailure(
                    channel,
                    generation,
                    StDownloadFailureReason.DOWNLOAD_MANAGER_UNAVAILABLE,
                )
                return
            }
            val target = archiveFile(resolved)
            if (target == null) {
                finishResolutionFailure(
                    channel,
                    generation,
                    StDownloadFailureReason.STORAGE_UNAVAILABLE,
                )
                return
            }

            synchronized(lock) {
                if (!isCurrentResolutionLocked(channel, generation)) return
                if (discoverArchives().any { archive ->
                        archive.channel == channel &&
                            archive.identity.exactCommit == resolved.exactCommit
                    } ||
                    target.exists()
                ) {
                    finishResolutionFailureLocked(
                        channel,
                        generation,
                        StDownloadFailureReason.ALREADY_DOWNLOADED,
                    )
                    return
                }
            }

            val request = DownloadManager.Request(resolved.archiveUrl.toUri())
                .setTitle("SillyTavern ${channel.branch} ${resolved.exactCommit.take(COMMIT_TITLE_LENGTH)}")
                .setDescription("Downloading official SillyTavern source archive at an exact commit")
                .setMimeType(ZIP_MIME_TYPE)
                .setAllowedNetworkTypes(
                    DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE,
                )
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                )
                .setDestinationInExternalFilesDir(
                    appContext,
                    Environment.DIRECTORY_DOWNLOADS,
                    resolved.fileName,
                )

            val downloadId = manager.enqueue(request)
            synchronized(lock) {
                if (!isCurrentResolutionLocked(channel, generation)) {
                    manager.remove(downloadId)
                    target.delete()
                    return
                }
                if (!persistActiveDownload(downloadId, resolved)) {
                    manager.remove(downloadId)
                    target.delete()
                    finishResolutionFailureLocked(
                        channel,
                        generation,
                        StDownloadFailureReason.STORAGE_UNAVAILABLE,
                    )
                    return
                }

                activeDownloadId = downloadId
                activeResolvedDownload = resolved
                activeAssociationMayBeRemoved = true
                consecutiveQueryFailures = 0
                resolveJob = null
                mutableState.value = mutableState.value.copy(
                    active = ActiveStDownload(
                        channel = channel,
                        phase = StDownloadPhase.DOWNLOADING,
                        exactCommit = resolved.exactCommit,
                        archiveUrl = resolved.archiveUrl,
                    ),
                    failure = null,
                )
                monitor(downloadId, resolved)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: IOException) {
            finishResolutionFailure(
                channel,
                generation,
                StDownloadFailureReason.VERSION_RESOLUTION_FAILED,
            )
        } catch (_: IllegalArgumentException) {
            finishResolutionFailure(
                channel,
                generation,
                StDownloadFailureReason.VERSION_RESOLUTION_FAILED,
            )
        } catch (_: RuntimeException) {
            finishResolutionFailure(channel, generation, StDownloadFailureReason.DOWNLOAD_FAILED)
        }
    }

    private fun initializeState() {
        refreshArchives(failure = null)
        val active = loadActiveMetadata()
        if (active == null) {
            clearActivePreferences()
            recoverOrphanedDownload()
            return
        }

        synchronized(lock) {
            operationGeneration += 1
            activeDownloadId = active.downloadId
            activeResolvedDownload = active.resolved
            activeAssociationMayBeRemoved = false
            consecutiveQueryFailures = 0
            mutableState.value = mutableState.value.copy(
                active = ActiveStDownload(
                    channel = active.resolved.channel,
                    phase = StDownloadPhase.DOWNLOADING,
                    exactCommit = active.resolved.exactCommit,
                    archiveUrl = active.resolved.archiveUrl,
                ),
                failure = null,
            )
            refreshArchives(failure = null)
            monitor(active.downloadId, active.resolved)
        }
    }

    private fun monitor(downloadId: Long, resolved: ResolvedStDownload) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive && isCurrentDownload(downloadId, resolved)) {
                if (refreshActiveDownload(downloadId, resolved)) break
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun refreshActiveDownload(downloadId: Long, resolved: ResolvedStDownload): Boolean {
        val manager = downloadManager ?: run {
            finishDownloadFailure(
                downloadId,
                resolved,
                StDownloadFailureReason.DOWNLOAD_MANAGER_UNAVAILABLE,
            )
            return true
        }

        return try {
            manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                if (cursor == null || !cursor.moveToFirst()) {
                    return@use deferTransientQueryFailure(downloadId, resolved)
                }

                if (!cursorMatches(cursor, resolved)) {
                    return@use deferTransientQueryFailure(downloadId, resolved)
                }
                synchronized(lock) {
                    if (isCurrentDownloadLocked(downloadId, resolved)) {
                        activeAssociationMayBeRemoved = true
                        consecutiveQueryFailures = 0
                    }
                }

                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val downloaded = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                ).coerceAtLeast(0L)
                val total = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                ).takeIf { it > 0 }

                when (status) {
                    DownloadManager.STATUS_PENDING,
                    DownloadManager.STATUS_RUNNING,
                    DownloadManager.STATUS_PAUSED,
                    -> {
                        synchronized(lock) {
                            if (isCurrentDownloadLocked(downloadId, resolved)) {
                                mutableState.value = mutableState.value.copy(
                                    active = ActiveStDownload(
                                        channel = resolved.channel,
                                        phase = StDownloadPhase.DOWNLOADING,
                                        exactCommit = resolved.exactCommit,
                                        archiveUrl = resolved.archiveUrl,
                                        bytesDownloaded = downloaded,
                                        totalBytes = total,
                                    ),
                                    failure = null,
                                )
                            }
                        }
                        false
                    }

                    DownloadManager.STATUS_SUCCESSFUL -> {
                        finishSuccess(downloadId, resolved, expectedLength = total)
                        true
                    }

                    DownloadManager.STATUS_FAILED -> {
                        val reason = cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON),
                        )
                        finishDownloadFailure(
                            downloadId = downloadId,
                            resolved = resolved,
                            reason = StDownloadFailureReason.DOWNLOAD_FAILED,
                            detailCode = reason,
                        )
                        true
                    }

                    else -> false
                }
            }
        } catch (_: RuntimeException) {
            deferTransientQueryFailure(downloadId, resolved)
        }
    }

    private fun finishSuccess(
        downloadId: Long,
        resolved: ResolvedStDownload,
        expectedLength: Long?,
    ) {
        synchronized(lock) {
            if (!isCurrentDownloadLocked(downloadId, resolved)) return
            mutableState.value = mutableState.value.copy(
                active = ActiveStDownload(
                    channel = resolved.channel,
                    phase = StDownloadPhase.RECORDING_CONTENT_HASH,
                    exactCommit = resolved.exactCommit,
                    archiveUrl = resolved.archiveUrl,
                    bytesDownloaded = expectedLength ?: 0L,
                    totalBytes = expectedLength,
                ),
                failure = null,
            )
        }

        val target = archiveFile(resolved)
        if (target == null || !hasZipFormatHint(target)) {
            finishDownloadFailure(
                downloadId,
                resolved,
                StDownloadFailureReason.INVALID_ARCHIVE,
            )
            return
        }

        val content = try {
            recordArchiveContent(target)
        } catch (_: IOException) {
            finishDownloadFailure(
                downloadId,
                resolved,
                StDownloadFailureReason.INVALID_ARCHIVE,
            )
            return
        } catch (_: SecurityException) {
            finishDownloadFailure(
                downloadId,
                resolved,
                StDownloadFailureReason.STORAGE_UNAVAILABLE,
            )
            return
        } catch (_: RuntimeException) {
            finishDownloadFailure(
                downloadId,
                resolved,
                StDownloadFailureReason.INVALID_ARCHIVE,
            )
            return
        }
        if (expectedLength != null && content.byteLength != expectedLength) {
            finishDownloadFailure(
                downloadId,
                resolved,
                StDownloadFailureReason.INVALID_ARCHIVE,
            )
            return
        }

        synchronized(lock) {
            if (!isCurrentDownloadLocked(downloadId, resolved)) return
            val completed = CompletedArchiveMetadata(
                downloadId = downloadId,
                resolved = resolved,
                byteLength = content.byteLength,
                sha256 = content.sha256,
                downloadedAtEpochMillis = System.currentTimeMillis(),
            )
            val persisted = persistCompletedArchive(completed)
            activeDownloadId = null
            activeResolvedDownload = null
            activeAssociationMayBeRemoved = false
            consecutiveQueryFailures = 0
            monitorJob = null
            clearActivePreferences()
            if (!persisted) clearCompletedPreferences(resolved.channel)
            mutableState.value = mutableState.value.copy(active = null)
            refreshArchives(
                failure = if (persisted) {
                    null
                } else {
                    StDownloadFailure(
                        channel = resolved.channel,
                        reason = StDownloadFailureReason.STORAGE_UNAVAILABLE,
                    )
                },
            )
        }
    }

    private fun finishDownloadFailure(
        downloadId: Long,
        resolved: ResolvedStDownload,
        reason: StDownloadFailureReason,
        detailCode: Int? = null,
    ) {
        val mayRemove: Boolean
        synchronized(lock) {
            if (!isCurrentDownloadLocked(downloadId, resolved)) return
            mayRemove = activeAssociationMayBeRemoved
            activeDownloadId = null
            activeResolvedDownload = null
            activeAssociationMayBeRemoved = false
            consecutiveQueryFailures = 0
            monitorJob = null
            clearActivePreferences()
            mutableState.value = mutableState.value.copy(active = null)
        }
        if (mayRemove) downloadManager?.remove(downloadId)
        archiveFile(resolved)?.delete()
        refreshArchives(
            failure = StDownloadFailure(
                channel = resolved.channel,
                reason = reason,
                detailCode = detailCode,
            ),
        )
    }

    private fun abandonMismatchedAssociation(downloadId: Long, resolved: ResolvedStDownload) {
        synchronized(lock) {
            if (!isCurrentDownloadLocked(downloadId, resolved)) return
            activeDownloadId = null
            activeResolvedDownload = null
            activeAssociationMayBeRemoved = false
            consecutiveQueryFailures = 0
            monitorJob = null
            clearActivePreferences()
            mutableState.value = mutableState.value.copy(active = null)
            refreshArchives(
                failure = StDownloadFailure(
                    channel = resolved.channel,
                    reason = StDownloadFailureReason.DOWNLOAD_FAILED,
                ),
            )
        }
    }

    private fun deferTransientQueryFailure(
        downloadId: Long,
        resolved: ResolvedStDownload,
    ): Boolean {
        val shouldAbandon = synchronized(lock) {
            if (!isCurrentDownloadLocked(downloadId, resolved)) return true
            consecutiveQueryFailures += 1
            consecutiveQueryFailures > MAX_CONSECUTIVE_QUERY_FAILURES
        }
        if (!shouldAbandon) return false
        abandonMismatchedAssociation(downloadId, resolved)
        return true
    }

    private fun finishResolutionFailure(
        channel: StDownloadChannel,
        generation: Long,
        reason: StDownloadFailureReason,
    ) {
        synchronized(lock) {
            finishResolutionFailureLocked(channel, generation, reason)
        }
    }

    private fun finishResolutionFailureLocked(
        channel: StDownloadChannel,
        generation: Long,
        reason: StDownloadFailureReason,
    ) {
        if (!isCurrentResolutionLocked(channel, generation)) return
        resolveJob = null
        clearActivePreferences()
        mutableState.value = mutableState.value.copy(
            active = null,
            failure = StDownloadFailure(channel = channel, reason = reason),
        )
    }

    private fun isCurrentResolution(channel: StDownloadChannel, generation: Long): Boolean =
        synchronized(lock) { isCurrentResolutionLocked(channel, generation) }

    private fun isCurrentResolutionLocked(channel: StDownloadChannel, generation: Long): Boolean =
        operationGeneration == generation &&
            activeDownloadId == null &&
            mutableState.value.active?.channel == channel &&
            mutableState.value.active?.phase == StDownloadPhase.RESOLVING

    private fun isCurrentDownload(downloadId: Long, resolved: ResolvedStDownload): Boolean =
        synchronized(lock) { isCurrentDownloadLocked(downloadId, resolved) }

    private fun isCurrentDownloadLocked(downloadId: Long, resolved: ResolvedStDownload): Boolean =
        activeDownloadId == downloadId && activeResolvedDownload == resolved

    private suspend fun cancelRecoveredDownload(cancellation: RecoveredCancellation) {
        if (downloadRowMatches(cancellation.downloadId, cancellation.resolved)) {
            downloadManager?.remove(cancellation.downloadId)
            archiveFile(cancellation.resolved)?.delete()
            refreshArchives(failure = null)
        }
    }

    private fun downloadRowMatches(downloadId: Long, resolved: ResolvedStDownload): Boolean {
        val manager = downloadManager ?: return false
        return try {
            manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                cursor != null && cursor.moveToFirst() && cursorMatches(cursor, resolved)
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun cursorMatches(cursor: Cursor, resolved: ResolvedStDownload): Boolean {
        val remoteColumn = cursor.getColumnIndex(DownloadManager.COLUMN_URI)
        if (remoteColumn < 0 || cursor.getString(remoteColumn) != resolved.archiveUrl) return false

        val localColumn = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
        if (localColumn < 0 || cursor.isNull(localColumn)) return true
        val localUri = cursor.getString(localColumn)?.toUri() ?: return false
        if (localUri.scheme != FILE_URI_SCHEME) return true
        val localPath = localUri.path ?: return false
        val expected = archiveFile(resolved) ?: return false
        return try {
            File(localPath).canonicalFile == expected.canonicalFile
        } catch (_: IOException) {
            false
        }
    }

    private fun recoverOrphanedDownload(): Boolean {
        val recovered = findRecoverableDownload() ?: return false
        synchronized(lock) {
            if (mutableState.value.active != null || activeDownloadId != null) return false
            operationGeneration += 1
            if (!persistActiveDownload(recovered.downloadId, recovered.resolved)) return false
            activeDownloadId = recovered.downloadId
            activeResolvedDownload = recovered.resolved
            activeAssociationMayBeRemoved = false
            consecutiveQueryFailures = 0
            mutableState.value = mutableState.value.copy(
                active = ActiveStDownload(
                    channel = recovered.resolved.channel,
                    phase = StDownloadPhase.DOWNLOADING,
                    exactCommit = recovered.resolved.exactCommit,
                    archiveUrl = recovered.resolved.archiveUrl,
                ),
                failure = null,
            )
            refreshArchives(failure = null)
            monitor(recovered.downloadId, recovered.resolved)
            return true
        }
    }

    private fun findRecoverableDownload(): RecoverableDownload? {
        val manager = downloadManager ?: return null
        val directory = downloadDirectory() ?: return null
        val candidates = mutableListOf<RecoverableDownload>()
        return try {
            manager.query(DownloadManager.Query()).use { cursor ->
                if (cursor == null) return@use
                while (cursor.moveToNext()) {
                    val status = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS),
                    )
                    if (status !in RECOVERABLE_DOWNLOAD_STATUSES) continue
                    val modifiedAt = cursor.getLong(
                        cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP,
                        ),
                    ).takeIf { it > 0 } ?: System.currentTimeMillis()
                    val resolved = recoverResolvedDownloadFromOwnedRow(
                        remoteUri = cursor.stringOrNull(DownloadManager.COLUMN_URI),
                        localUri = cursor.stringOrNull(DownloadManager.COLUMN_LOCAL_URI),
                        downloadDirectory = directory,
                        resolvedAtEpochMillis = modifiedAt,
                    ) ?: continue
                    if (loadCompletedMetadata(resolved.channel) != null) continue
                    candidates += RecoverableDownload(
                        downloadId = cursor.getLong(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID),
                        ),
                        resolved = resolved,
                        status = status,
                        modifiedAtEpochMillis = modifiedAt,
                    )
                }
            }
            candidates.maxWithOrNull(
                compareBy<RecoverableDownload>(
                    { if (it.status == DownloadManager.STATUS_SUCCESSFUL) 0 else 1 },
                    { it.modifiedAtEpochMillis },
                ),
            )
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun Cursor.stringOrNull(columnName: String): String? {
        val column = getColumnIndex(columnName)
        return if (column < 0 || isNull(column)) null else getString(column)
    }

    private fun refreshArchives(failure: StDownloadFailure?) {
        synchronized(lock) {
            val archives = discoverArchives()
            mutableState.value = mutableState.value.copy(
                archives = archives,
                failure = failure,
            )
        }
    }

    private fun discoverArchives(): List<DownloadedStArchive> {
        val directory = downloadDirectory() ?: return emptyList()
        val activeFileName = activeResolvedDownload?.fileName
        val exactArchives = StDownloadChannel.entries.mapNotNull { channel ->
            val metadata = loadCompletedMetadata(channel) ?: return@mapNotNull null
            if (metadata.resolved.fileName == activeFileName) return@mapNotNull null
            val file = File(directory, metadata.resolved.fileName)
            describeRecordedExactArchive(
                file = file,
                resolved = metadata.resolved,
                content = ArchiveContentRecord(
                    byteLength = metadata.byteLength,
                    sha256 = metadata.sha256,
                ),
                downloadedAtEpochMillis = metadata.downloadedAtEpochMillis,
            )
        }
        val exactNames = exactArchives.mapTo(mutableSetOf()) { archive -> archive.fileName }
        val legacyArchives = directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { file -> file.isFile && file.name != activeFileName && file.name !in exactNames }
            .mapNotNull(::describeLegacyUnidentifiedArchive)
            .toList()

        return (exactArchives + legacyArchives).sortedWith(
            compareBy<DownloadedStArchive>({ it.channel.ordinal }, { it.fileName }),
        )
    }

    private fun loadActiveMetadata(): ActiveDownloadMetadata? {
        val downloadId = preferences.getLong(KEY_ACTIVE_DOWNLOAD_ID, NO_DOWNLOAD_ID)
        if (downloadId == NO_DOWNLOAD_ID) return null
        val resolved = loadResolvedMetadata(ACTIVE_KEY_PREFIX) ?: return null
        return ActiveDownloadMetadata(downloadId = downloadId, resolved = resolved)
    }

    private fun loadCompletedMetadata(channel: StDownloadChannel): CompletedArchiveMetadata? {
        val prefix = completedKeyPrefix(channel)
        val resolved = loadResolvedMetadata(prefix) ?: return null
        if (resolved.channel != channel) return null
        val byteLength = preferences.getLong("${prefix}byte_length", INVALID_LENGTH)
        val sha256 = preferences.getString("${prefix}sha256", null)
            ?.takeIf(::isLowerSha256)
            ?: return null
        val downloadedAt = preferences.getLong("${prefix}downloaded_at", INVALID_TIMESTAMP)
        if (byteLength < 0 || downloadedAt <= 0) return null
        return CompletedArchiveMetadata(
            downloadId = preferences.getLong("${prefix}download_id", NO_DOWNLOAD_ID),
            resolved = resolved,
            byteLength = byteLength,
            sha256 = sha256,
            downloadedAtEpochMillis = downloadedAt,
        )
    }

    private fun loadResolvedMetadata(prefix: String): ResolvedStDownload? {
        val channel = preferences.getString("${prefix}channel", null)
            ?.let { stored -> StDownloadChannel.entries.firstOrNull { it.name == stored } }
            ?: return null
        val commit = preferences.getString("${prefix}commit", null)
            ?.let { stored -> runCatching { requireExactCommitSha(stored) }.getOrNull() }
            ?: return null
        val archiveUrl = preferences.getString("${prefix}archive_url", null) ?: return null
        val fileName = preferences.getString("${prefix}file_name", null) ?: return null
        val resolvedAt = preferences.getLong("${prefix}resolved_at", INVALID_TIMESTAMP)
        if (
            archiveUrl != channel.exactArchiveUrl(commit) ||
            fileName != channel.exactArchiveFileName(commit) ||
            resolvedAt <= 0
        ) {
            return null
        }
        return ResolvedStDownload(
            channel = channel,
            exactCommit = commit,
            archiveUrl = archiveUrl,
            fileName = fileName,
            resolvedAtEpochMillis = resolvedAt,
        )
    }

    private fun persistActiveDownload(downloadId: Long, resolved: ResolvedStDownload): Boolean =
        preferences.edit()
            .putLong(KEY_ACTIVE_DOWNLOAD_ID, downloadId)
            .putResolvedMetadata(ACTIVE_KEY_PREFIX, resolved)
            .commit()

    private fun persistCompletedArchive(metadata: CompletedArchiveMetadata): Boolean {
        val prefix = completedKeyPrefix(metadata.resolved.channel)
        return preferences.edit()
            .putLong("${prefix}download_id", metadata.downloadId)
            .putResolvedMetadata(prefix, metadata.resolved)
            .putLong("${prefix}byte_length", metadata.byteLength)
            .putString("${prefix}sha256", metadata.sha256)
            .putLong("${prefix}downloaded_at", metadata.downloadedAtEpochMillis)
            .removeActiveMetadata()
            .commit()
    }

    private fun clearActivePreferences() {
        preferences.edit().removeActiveMetadata().commit()
    }

    private fun clearCompletedPreferences(channel: StDownloadChannel) {
        val prefix = completedKeyPrefix(channel)
        preferences.edit()
            .remove("${prefix}download_id")
            .removeResolvedMetadata(prefix)
            .remove("${prefix}byte_length")
            .remove("${prefix}sha256")
            .remove("${prefix}downloaded_at")
            .remove(legacyCompletedIdKey(channel))
            .commit()
    }

    private fun android.content.SharedPreferences.Editor.putResolvedMetadata(
        prefix: String,
        resolved: ResolvedStDownload,
    ): android.content.SharedPreferences.Editor =
        putString("${prefix}channel", resolved.channel.name)
            .putString("${prefix}commit", resolved.exactCommit)
            .putString("${prefix}archive_url", resolved.archiveUrl)
            .putString("${prefix}file_name", resolved.fileName)
            .putLong("${prefix}resolved_at", resolved.resolvedAtEpochMillis)

    private fun android.content.SharedPreferences.Editor.removeResolvedMetadata(
        prefix: String,
    ): android.content.SharedPreferences.Editor =
        remove("${prefix}channel")
            .remove("${prefix}commit")
            .remove("${prefix}archive_url")
            .remove("${prefix}file_name")
            .remove("${prefix}resolved_at")

    private fun android.content.SharedPreferences.Editor.removeActiveMetadata():
        android.content.SharedPreferences.Editor =
        remove(KEY_ACTIVE_DOWNLOAD_ID).removeResolvedMetadata(ACTIVE_KEY_PREFIX)

    private fun setFailure(channel: StDownloadChannel?, reason: StDownloadFailureReason) {
        mutableState.value = mutableState.value.copy(
            failure = StDownloadFailure(channel = channel, reason = reason),
        )
    }

    private fun downloadDirectory(): File? =
        appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)

    private fun archiveFile(resolved: ResolvedStDownload): File? =
        downloadDirectory()?.let { directory -> File(directory, resolved.fileName) }

    private fun completedKeyPrefix(channel: StDownloadChannel): String =
        "completed_${channel.name.lowercase()}_"

    private fun legacyCompletedIdKey(channel: StDownloadChannel): String =
        "completed_download_id_${channel.branch}"

    private data class ActiveDownloadMetadata(
        val downloadId: Long,
        val resolved: ResolvedStDownload,
    )

    private data class CompletedArchiveMetadata(
        val downloadId: Long,
        val resolved: ResolvedStDownload,
        val byteLength: Long,
        val sha256: String,
        val downloadedAtEpochMillis: Long,
    )

    private data class RecoveredCancellation(
        val downloadId: Long,
        val resolved: ResolvedStDownload,
        val mayRemoveImmediately: Boolean,
    )

    private data class RecoverableDownload(
        val downloadId: Long,
        val resolved: ResolvedStDownload,
        val status: Int,
        val modifiedAtEpochMillis: Long,
    )

    companion object {
        private const val PREFERENCES_NAME = "stm_downloads"
        private const val ACTIVE_KEY_PREFIX = "active_"
        private const val KEY_ACTIVE_DOWNLOAD_ID = "active_download_id"
        private const val NO_DOWNLOAD_ID = -1L
        private const val INVALID_LENGTH = -1L
        private const val INVALID_TIMESTAMP = -1L
        private const val POLL_INTERVAL_MS = 500L
        private const val MAX_CONSECUTIVE_QUERY_FAILURES = 10
        private const val COMMIT_TITLE_LENGTH = 12
        private const val ZIP_MIME_TYPE = "application/zip"
        private const val FILE_URI_SCHEME = "file"
        private val RECOVERABLE_DOWNLOAD_STATUSES = setOf(
            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_RUNNING,
            DownloadManager.STATUS_PAUSED,
            DownloadManager.STATUS_SUCCESSFUL,
        )
    }
}
