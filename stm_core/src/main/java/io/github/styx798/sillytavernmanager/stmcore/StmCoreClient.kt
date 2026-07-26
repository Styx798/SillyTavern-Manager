package io.github.styx798.sillytavernmanager.stmcore

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException

interface StmCoreClientListener {
    fun onCoreStateChanged(state: StmCoreState)

    fun onCoreProcessDisconnected()

    fun onCoreAppTaskRemoved()
}

class StmCoreClient(
    context: Context,
    private val listener: StmCoreClientListener,
) {
    private val appContext = context.applicationContext
    private val incomingMessenger = Messenger(IncomingHandler(Looper.getMainLooper()))
    private var outgoingMessenger: Messenger? = null
    private var bound = false
    private val pendingCommands = ArrayDeque<Message>()
    private var disconnectDelivered = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            markAppTaskOwned()
            outgoingMessenger = Messenger(service)
            disconnectDelivered = false
            send(Message.obtain(null, StmCoreProtocol.MESSAGE_REGISTER_CLIENT).apply {
                replyTo = incomingMessenger
            })
            while (pendingCommands.isNotEmpty()) {
                if (!send(pendingCommands.first())) break
                pendingCommands.removeFirst()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            outgoingMessenger = null
            deliverDisconnectedOnce()
            // Android retains this binding and reconnects it if the private Core process restarts.
        }

        override fun onBindingDied(name: ComponentName) {
            outgoingMessenger = null
            deliverDisconnectedOnce()
            rebind()
        }

        override fun onNullBinding(name: ComponentName) {
            outgoingMessenger = null
            deliverDisconnectedOnce()
            if (bound) {
                runCatching { appContext.unbindService(this) }
                bound = false
            }
        }
    }

    fun connect(): Boolean {
        checkMainThread()
        if (bound) return true
        if (!markAppTaskOwned()) return false
        disconnectDelivered = false
        bound = appContext.bindService(
            Intent(appContext, StmCoreService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        return bound
    }

    private fun markAppTaskOwned(): Boolean =
        runCatching {
            appContext.startService(
                StmCoreService.serviceIntent(
                    appContext,
                    StmCoreService.ACTION_MARK_APP_TASK_OWNED,
                ),
            )
        }.isSuccess

    fun connectAndStart(operationId: String): Boolean {
        checkMainThread()
        val command = StmCoreProtocol.commandMessage(StmCoreProtocol.MESSAGE_START, operationId)
        outgoingMessenger?.let { return send(command) }
        if (!enqueue(command)) return false
        if (connect()) return true
        pendingCommands.remove(command)
        return false
    }

    fun prepareForSillyTavernStart(): Boolean {
        checkMainThread()
        return runCatching {
            appContext.startForegroundService(
                StmCoreService.serviceIntent(
                    appContext,
                    StmCoreService.ACTION_PREPARE_SILLY_TAVERN,
                ),
            )
        }.isSuccess
    }

    fun releasePreparedSillyTavernForeground() {
        checkMainThread()
        runCatching {
            appContext.startService(
                StmCoreService.serviceIntent(
                    appContext,
                    StmCoreService.ACTION_RELEASE_SILLY_TAVERN_FOREGROUND,
                ),
            )
        }
    }

    fun prepareForCoreShutdown(): Boolean {
        checkMainThread()
        return runCatching {
            appContext.startService(
                StmCoreService.serviceIntent(
                    appContext,
                    StmCoreService.ACTION_PREPARE_CORE_SHUTDOWN,
                ),
            )
        }.isSuccess
    }

    fun requestStop(operationId: String): Boolean {
        checkMainThread()
        val command = StmCoreProtocol.commandMessage(StmCoreProtocol.MESSAGE_STOP, operationId)
        outgoingMessenger?.let { return send(command) }
        if (bound) {
            return enqueue(command)
        }
        return false
    }

    fun requestInstall(
        operationId: String,
        slotId: String,
        cacheFileName: String,
        artifact: StmCoreArtifact,
        installMode: StmCoreInstallMode,
    ): Boolean = requestBoundCommand(
        StmCoreProtocol.installMessage(
            operationId,
            slotId,
            cacheFileName,
            artifact,
            installMode,
        ),
    )

    /** File descriptors are sent immediately and are never retained in the reconnect queue. */
    fun requestImportArtifact(
        operationId: String,
        slotId: String,
        sourceDescriptor: ParcelFileDescriptor,
        artifact: StmCoreArtifact,
    ): Boolean {
        checkMainThread()
        if (outgoingMessenger == null) return false
        return send(
            StmCoreProtocol.importArtifactMessage(
                operationId,
                slotId,
                sourceDescriptor,
                artifact,
            ),
        )
    }

    /** File descriptors are sent immediately and are never retained in the reconnect queue. */
    fun requestInstallImportedArtifact(
        operationId: String,
        slotId: String,
        sourceDescriptor: ParcelFileDescriptor,
        artifact: StmCoreArtifact,
        installMode: StmCoreInstallMode,
    ): Boolean {
        checkMainThread()
        if (outgoingMessenger == null) return false
        return send(
            StmCoreProtocol.installImportedArtifactMessage(
                operationId,
                slotId,
                sourceDescriptor,
                artifact,
                installMode,
            ),
        )
    }

    fun requestCancelJob(operationId: String, targetOperationId: String): Boolean =
        requestBoundCommand(
            StmCoreProtocol.targetCommandMessage(
                StmCoreProtocol.MESSAGE_CANCEL_JOB,
                operationId,
                targetOperationId,
            ),
        )

    fun requestActivate(operationId: String, slotId: String): Boolean = requestBoundCommand(
        StmCoreProtocol.targetCommandMessage(
            StmCoreProtocol.MESSAGE_ACTIVATE_SLOT,
            operationId,
            slotId,
        ),
    )

    fun requestRollback(operationId: String): Boolean = requestBoundCommand(
        StmCoreProtocol.commandMessage(StmCoreProtocol.MESSAGE_ROLLBACK_SLOT, operationId),
    )

    fun requestRemove(operationId: String, slotId: String): Boolean = requestBoundCommand(
        StmCoreProtocol.targetCommandMessage(
            StmCoreProtocol.MESSAGE_REMOVE_SLOT,
            operationId,
            slotId,
        ),
    )

    fun requestVerifySlot(operationId: String, slotId: String): Boolean = requestBoundCommand(
        StmCoreProtocol.targetCommandMessage(
            StmCoreProtocol.MESSAGE_VERIFY_SLOT,
            operationId,
            slotId,
        ),
    )

    fun requestContinueWaiting(operationId: String, targetOperationId: String): Boolean =
        requestBoundCommand(
            StmCoreProtocol.targetCommandMessage(
                StmCoreProtocol.MESSAGE_CONTINUE_WAITING,
                operationId,
                targetOperationId,
            ),
        )

    fun requestRestartCore(operationId: String): Boolean = requestBoundCommand(
        StmCoreProtocol.commandMessage(StmCoreProtocol.MESSAGE_RESTART_CORE, operationId),
    )

    fun requestCloseCore(operationId: String): Boolean = requestBoundCommand(
        StmCoreProtocol.commandMessage(StmCoreProtocol.MESSAGE_CLOSE_CORE, operationId),
    )

    fun disconnect() {
        checkMainThread()
        outgoingMessenger?.let {
            send(Message.obtain(null, StmCoreProtocol.MESSAGE_UNREGISTER_CLIENT).apply {
                replyTo = incomingMessenger
            })
        }
        outgoingMessenger = null
        pendingCommands.clear()
        if (bound) {
            runCatching { appContext.unbindService(connection) }
            bound = false
        }
    }

    private fun rebind() {
        if (bound) {
            runCatching { appContext.unbindService(connection) }
            bound = false
        }
        connect()
    }

    private fun deliverDisconnectedOnce() {
        if (!disconnectDelivered) {
            disconnectDelivered = true
            listener.onCoreProcessDisconnected()
        }
    }

    private fun requestBoundCommand(command: Message): Boolean {
        checkMainThread()
        outgoingMessenger?.let { return send(command) }
        return bound && enqueue(command)
    }

    private fun enqueue(command: Message): Boolean {
        if (pendingCommands.size >= MAX_PENDING_COMMANDS) return false
        pendingCommands.addLast(command)
        return true
    }

    private fun send(message: Message): Boolean {
        val target = outgoingMessenger ?: return false
        return try {
            target.send(message)
            true
        } catch (_: RemoteException) {
            outgoingMessenger = null
            deliverDisconnectedOnce()
            false
        }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "StmCoreClient must be controlled from the main thread"
        }
    }

    private inner class IncomingHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(message: Message) {
            if (message.what == StmCoreProtocol.MESSAGE_APP_TASK_REMOVED) {
                listener.onCoreAppTaskRemoved()
                return
            }
            val state = StmCoreProtocol.stateFrom(message)
            if (state != null) {
                listener.onCoreStateChanged(state)
            } else {
                super.handleMessage(message)
            }
        }
    }

    private companion object {
        const val MAX_PENDING_COMMANDS = 16
    }
}
