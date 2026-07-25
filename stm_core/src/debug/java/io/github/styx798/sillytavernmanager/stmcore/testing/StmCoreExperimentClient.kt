package io.github.styx798.sillytavernmanager.stmcore.testing

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

enum class StmCoreExperiment {
    NODE_SEMANTICS,
    TERMINATE_EXECUTION,
    PROCESS_EXIT,
    UNCAUGHT_EXCEPTION,
    UNHANDLED_REJECTION,
    GATE3A_REAL_ST,
    GATE3A_REAL_ST_PERFORMANCE,
    GATE3A_REAL_ST_PERFORMANCE_NO_COMPRESSION,
    GATE3A_REAL_ST_PERFORMANCE_PREBUILT_BUNDLE,
    GATE3B_NPM_CLI,
    GATE3B_NPM_CLI_RUNNABLE,
    GATE3B_NPM_CLI_LOCAL_BUNDLE_RUNNABLE,
    GATE3B_NPM_CLI_BOUNDED_INTERRUPTION,
    GATE3B_NPM_CLI_CANCEL,
    GATE3B_ARBORIST,
    GATE3B_ARBORIST_RUNNABLE,
    GATE3B_ARBORIST_BOUNDED_INTERRUPTION,
    GATE3B_ARBORIST_CANCEL,
    GATE3B_SIGNED_PREBUILT,
    GATE3B_TREE_DIFF,
    GATE3B_READY_SLOT,
    GATE3B_READY_SLOT_COLD,
    GATE3B_RUNTIME_IMAGE_OBB,
    GATE3B_FAULT_MATRIX,
    TEARDOWN_PROTOCOL_PROBE,
    GATE2_FIXTURES,
    GATE2_INTERRUPT_FIXTURE,
    COUNT_OPEN_FILE_DESCRIPTORS,
    ARM_BEFORE_INSTALL_EXTRACTION_KILL,
    ARM_BEFORE_ACTIVE_POINTER_WRITE_KILL,
    ARM_ACTIVE_AFTER_TEMP_SYNC_BEFORE_ATOMIC_REPLACE_KILL,
    ARM_ACTIVE_AFTER_ATOMIC_REPLACE_BEFORE_DIRECTORY_SYNC_KILL,
    ARM_AFTER_TERMINAL_JOURNAL_COMMIT_BEFORE_JOB_EVENT_KILL,
    CLEAR_INSTALLER_KILL_FAILPOINT,
}

data class StmCoreExperimentResult(
    val requestId: String,
    val experiment: StmCoreExperiment,
    val values: Map<String, String>,
    val teardownComplete: Boolean,
)

internal data class StmCoreExperimentRequest(
    val requestId: String,
    val experiment: StmCoreExperiment,
) {
    fun matches(resultRequestId: String, resultExperiment: StmCoreExperiment): Boolean =
        requestId == resultRequestId && experiment == resultExperiment
}

internal data class StmCoreExperimentResultTransition(
    val deliverResult: Boolean,
    val teardownComplete: Boolean,
)

internal class StmCoreExperimentClientProtocolState {
    private var activeRequest: StmCoreExperimentRequest? = null
    private var resultDelivered = false

    fun begin(request: StmCoreExperimentRequest) {
        check(activeRequest == null) { "An STM Core experiment is already in flight" }
        activeRequest = request
        resultDelivered = false
    }

    fun activeRequest(): StmCoreExperimentRequest? = activeRequest

    fun abandon(request: StmCoreExperimentRequest) {
        if (activeRequest == request) clear()
    }

    fun acceptResult(
        requestId: String,
        experiment: StmCoreExperiment,
        teardownComplete: Boolean,
    ): StmCoreExperimentResultTransition? {
        val expected = activeRequest ?: return null
        if (!expected.matches(requestId, experiment)) return null
        val deliverResult = !resultDelivered
        resultDelivered = true
        if (teardownComplete) clear()
        return StmCoreExperimentResultTransition(deliverResult, teardownComplete)
    }

    fun acceptTeardown(requestId: String): Boolean {
        val expected = activeRequest ?: return false
        if (expected.requestId != requestId) return false
        clear()
        return true
    }

    fun clearForRemoteDisconnect() {
        clear()
    }

    fun hasActiveRequest(): Boolean = activeRequest != null

    private fun clear() {
        activeRequest = null
        resultDelivered = false
    }
}

internal class StmCoreExperimentProcessLease {
    private val owner = AtomicReference<String?>(null)

    fun tryAcquire(requestId: String): Boolean {
        require(requestId.isNotBlank()) { "Experiment request ID cannot be blank" }
        return owner.compareAndSet(null, requestId)
    }

    fun release(requestId: String): Boolean = owner.compareAndSet(requestId, null)

    fun ownerRequestId(): String? = owner.get()
}

interface StmCoreExperimentListener {
    fun onExperimentServiceReady(processId: Int)

    fun onExperimentResult(result: StmCoreExperimentResult)

    fun onExperimentTeardown(requestId: String)

    fun onExperimentServiceDisconnected()
}

class StmCoreExperimentClient(
    context: Context,
    private val listener: StmCoreExperimentListener,
) {
    private val appContext = context.applicationContext
    private val incoming = Messenger(IncomingHandler(Looper.getMainLooper()))
    private var outgoing: Messenger? = null
    private var bound = false
    private var pending: StmCoreExperimentRequest? = null
    private val protocol = StmCoreExperimentClientProtocolState()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            outgoing = Messenger(service)
            send(Message.obtain(null, MESSAGE_REGISTER).apply { replyTo = incoming })
        }

        override fun onServiceDisconnected(name: ComponentName) {
            handleRemoteDisconnected()
        }

        override fun onBindingDied(name: ComponentName) {
            handleRemoteDisconnected()
        }

        override fun onNullBinding(name: ComponentName) {
            handleRemoteDisconnected()
        }
    }

    fun run(experiment: StmCoreExperiment): Boolean {
        checkMainThread()
        check(pending == null && !protocol.hasActiveRequest()) {
            "An STM Core experiment is already pending"
        }
        pending = StmCoreExperimentRequest(UUID.randomUUID().toString(), experiment)
        if (outgoing != null) return sendPending()
        if (bound) return true
        bound = appContext.bindService(
            Intent(appContext, StmCoreExperimentService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        if (!bound) pending = null
        return bound
    }

    fun cancelPending(): Boolean {
        checkMainThread()
        pending?.let { request ->
            pending = null
            listener.onExperimentTeardown(request.requestId)
            return true
        }
        val request = protocol.activeRequest() ?: return false
        return send(Message.obtain(null, MESSAGE_CANCEL).apply {
            replyTo = incoming
            data = Bundle().apply { putString(KEY_REQUEST_ID, request.requestId) }
        })
    }

    fun disconnect() {
        checkMainThread()
        check(pending == null && !protocol.hasActiveRequest()) {
            "Cannot disconnect before the STM Core experiment teardown completes"
        }
        outgoing = null
        if (bound) {
            runCatching { appContext.unbindService(connection) }
            bound = false
        }
    }

    private fun sendPending(): Boolean {
        val request = pending ?: return false
        pending = null
        protocol.begin(request)
        val sent = send(Message.obtain(null, MESSAGE_RUN).apply {
            replyTo = incoming
            data = Bundle().apply {
                putString(KEY_REQUEST_ID, request.requestId)
                putString(KEY_EXPERIMENT, request.experiment.name)
            }
        })
        if (!sent) protocol.abandon(request)
        return sent
    }

    private fun handleRemoteDisconnected() {
        outgoing = null
        protocol.clearForRemoteDisconnect()
        pending = null
        listener.onExperimentServiceDisconnected()
    }

    private fun send(message: Message): Boolean {
        val target = outgoing ?: return false
        return try {
            target.send(message)
            true
        } catch (_: RemoteException) {
            handleRemoteDisconnected()
            false
        }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "StmCoreExperimentClient must be controlled from the main thread"
        }
    }

    private inner class IncomingHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(message: Message) {
            when (message.what) {
                MESSAGE_READY -> {
                    listener.onExperimentServiceReady(message.data.getInt(KEY_PROCESS_ID))
                    sendPending()
                }

                MESSAGE_RESULT -> {
                    val requestId = message.data.getString(KEY_REQUEST_ID) ?: return
                    val experiment = message.data.getString(KEY_EXPERIMENT)
                        ?.let(StmCoreExperiment::valueOf)
                        ?: return
                    val teardownComplete = message.data.getBoolean(KEY_TEARDOWN_COMPLETE, false)
                    val transition = protocol.acceptResult(
                        requestId,
                        experiment,
                        teardownComplete,
                    ) ?: return
                    val values = message.data.getBundle(KEY_VALUES)
                        ?.keySet()
                        ?.associateWith { key -> message.data.getBundle(KEY_VALUES)?.getString(key).orEmpty() }
                        .orEmpty()
                    if (transition.deliverResult) {
                        listener.onExperimentResult(
                            StmCoreExperimentResult(
                                requestId,
                                experiment,
                                values,
                                teardownComplete,
                            ),
                        )
                    }
                    if (transition.teardownComplete) {
                        listener.onExperimentTeardown(requestId)
                    }
                }

                MESSAGE_TEARDOWN -> {
                    val requestId = message.data.getString(KEY_REQUEST_ID) ?: return
                    if (!protocol.acceptTeardown(requestId)) return
                    listener.onExperimentTeardown(requestId)
                }

                else -> super.handleMessage(message)
            }
        }
    }

    internal companion object {
        const val MESSAGE_REGISTER = 1
        const val MESSAGE_RUN = 2
        const val MESSAGE_READY = 3
        const val MESSAGE_RESULT = 4
        const val MESSAGE_CANCEL = 5
        const val MESSAGE_TEARDOWN = 6
        const val KEY_EXPERIMENT = "experiment"
        const val KEY_REQUEST_ID = "request_id"
        const val KEY_PROCESS_ID = "process_id"
        const val KEY_TEARDOWN_COMPLETE = "teardown_complete"
        const val KEY_VALUES = "values"
    }
}
