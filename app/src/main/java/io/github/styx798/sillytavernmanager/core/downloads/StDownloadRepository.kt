package io.github.styx798.sillytavernmanager.core.downloads

import kotlinx.coroutines.flow.StateFlow

interface StDownloadRepository {
    val state: StateFlow<StDownloadState>

    fun start(channel: StDownloadChannel)

    fun cancel()

    fun delete(channel: StDownloadChannel): Boolean

    fun deleteAll(): Int

    fun clearFailure()
}
