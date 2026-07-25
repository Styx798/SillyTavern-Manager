package io.github.styx798.sillytavernmanager.stmcore.testing

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifact
import io.github.styx798.sillytavernmanager.stmcore.StmCoreProtocol
import io.github.styx798.sillytavernmanager.stmcore.StmCoreService
import java.io.File

/** Debug-only raw IPC fixture for verifying fail-closed import schema and descriptor handling. */
class StmCoreRawImportTestClient(
    context: Context,
    private val listener: StmCoreRawImportTestListener,
) {
    private val appContext = context.applicationContext
    private var outgoing: Messenger? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            outgoing = Messenger(service)
            listener.onRawImportServiceReady()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            outgoing = null
            listener.onRawImportServiceDisconnected()
        }

        override fun onBindingDied(name: ComponentName) {
            outgoing = null
            listener.onRawImportServiceDisconnected()
        }

        override fun onNullBinding(name: ComponentName) {
            outgoing = null
            listener.onRawImportServiceDisconnected()
        }
    }

    fun connect(): Boolean {
        checkMainThread()
        if (bound) return true
        bound = appContext.bindService(
            Intent(appContext, StmCoreService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        return bound
    }

    /** Sends a request with a valid descriptor but a deliberately omitted artifact bundle. */
    fun sendMissingArtifactSchema(
        operationId: String,
        targetId: String,
        source: File,
        artifact: StmCoreArtifact,
    ): Boolean {
        checkMainThread()
        return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            val message = StmCoreProtocol.importArtifactMessage(
                operationId,
                targetId,
                descriptor,
                artifact,
            )
            message.data.remove(ARTIFACT_BUNDLE_KEY)
            send(message)
        }
    }

    /** Sends a complete schema whose source is a pipe instead of a regular archive file. */
    fun sendPipeDescriptor(
        operationId: String,
        targetId: String,
        artifact: StmCoreArtifact,
    ): Boolean {
        checkMainThread()
        val pipe = ParcelFileDescriptor.createPipe()
        return pipe[0].use { readEnd ->
            pipe[1].use {
                send(
                    StmCoreProtocol.importArtifactMessage(
                        operationId,
                        targetId,
                        readEnd,
                        artifact,
                    ),
                )
            }
        }
    }

    fun disconnect() {
        checkMainThread()
        outgoing = null
        if (bound) {
            runCatching { appContext.unbindService(connection) }
            bound = false
        }
    }

    private fun send(message: Message): Boolean {
        val target = outgoing ?: return false
        return try {
            target.send(message)
            true
        } catch (_: RemoteException) {
            outgoing = null
            listener.onRawImportServiceDisconnected()
            false
        }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "StmCoreRawImportTestClient must be controlled from the main thread"
        }
    }

    private companion object {
        const val ARTIFACT_BUNDLE_KEY = "artifact"
    }
}

interface StmCoreRawImportTestListener {
    fun onRawImportServiceReady()

    fun onRawImportServiceDisconnected()
}
