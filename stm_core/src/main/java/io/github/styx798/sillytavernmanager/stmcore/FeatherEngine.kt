package io.github.styx798.sillytavernmanager.stmcore

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.caoccao.javet.enums.V8AwaitMode
import com.caoccao.javet.enums.V8RuntimeTerminationMode
import com.caoccao.javet.interop.NodeRuntime
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class FeatherEngineLaunchSpec(
    val consoleArguments: Array<String>,
    val bootstrapScript: String,
    val startupErrorExpression: String,
    val readinessPortExpression: String,
    val stopScript: String,
    val closedExpression: String,
    val diagnosticsExpression: String,
    val cleanupScript: String = "",
    val readinessProbe: (String) -> LoopbackProbeResult,
)

internal class FeatherEngine(
    private val callback: Callback,
) {
    interface Callback {
        fun onNodeCreated(sessionId: String, nodeVersion: String)

        fun onReady(sessionId: String, port: Int, nodeVersion: String)

        fun onStopped(sessionId: String, terminationUsed: Boolean)

        fun onFailure(sessionId: String, detail: String)
    }

    @Volatile
    private var activeSession: FeatherEngineSession? = null

    @Volatile
    private var pendingTeardownSession: FeatherEngineSession? = null

    fun start(sessionId: String, sessionDirectory: File) {
        start(sessionId, sessionDirectory, syntheticLaunchSpec(sessionDirectory))
    }

    internal fun start(
        sessionId: String,
        sessionDirectory: File,
        launchSpec: FeatherEngineLaunchSpec,
    ) {
        val session = synchronized(this) {
            check(activeSession == null && pendingTeardownSession == null) {
                "A Feather Engine session is active or still tearing down"
            }
            FeatherEngineSession(
                sessionId,
                sessionDirectory,
                launchSpec,
                SessionCallback(),
            ).also { activeSession = it }
        }
        session.start()
    }

    fun requestGracefulStop(): Boolean =
        activeSession?.requestGracefulStop() ?: false

    fun terminateExecutionFromWatchdog(): Result<Unit> {
        val session = activeSession
            ?: return Result.failure(IllegalStateException("No Feather Engine session is active"))
        return session.terminateExecutionFromWatchdog()
    }

    fun destroy() {
        val session = synchronized(this) {
            pendingTeardownSession ?: activeSession?.also {
                activeSession = null
                pendingTeardownSession = it
            }
        }
        session?.destroy()
    }

    internal fun destroyAndAwait(timeoutSeconds: Long): Boolean {
        val session = synchronized(this) {
            pendingTeardownSession ?: activeSession?.also {
                activeSession = null
                pendingTeardownSession = it
            }
        } ?: return true
        session.destroy()
        val destroyed = session.awaitDestroyed(timeoutSeconds)
        if (destroyed) {
            synchronized(this) {
                if (pendingTeardownSession === session) pendingTeardownSession = null
            }
        }
        return destroyed
    }

    private inner class SessionCallback : FeatherEngineSession.Callback {
        override fun onNodeCreated(sessionId: String, nodeVersion: String) {
            if (activeSession?.sessionId == sessionId) callback.onNodeCreated(sessionId, nodeVersion)
        }

        override fun onReady(sessionId: String, port: Int, nodeVersion: String) {
            if (activeSession?.sessionId == sessionId) callback.onReady(sessionId, port, nodeVersion)
        }

        override fun onStopped(sessionId: String, terminationUsed: Boolean) {
            val current = synchronized(this@FeatherEngine) {
                activeSession?.takeIf { it.sessionId == sessionId }?.also { activeSession = null }
            } ?: return
            check(current.sessionId == sessionId)
            callback.onStopped(sessionId, terminationUsed)
        }

        override fun onFailure(sessionId: String, detail: String) {
            if (activeSession?.sessionId == sessionId) callback.onFailure(sessionId, detail)
        }
    }

    private fun syntheticLaunchSpec(sessionDirectory: File): FeatherEngineLaunchSpec =
        FeatherEngineLaunchSpec(
            consoleArguments = arrayOf("stm-core", sessionDirectory.absolutePath),
            bootstrapScript = SyntheticCoreService.script,
            startupErrorExpression = "globalThis.__stmCore?.error || ''",
            readinessPortExpression = "globalThis.__stmCore?.port || 0",
            stopScript =
                """
                (() => {
                  const state = globalThis.__stmCore;
                  if (!state || !state.server) {
                    if (state) state.closed = true;
                    return;
                  }
                  if (state.controlTimer) {
                    clearInterval(state.controlTimer);
                    state.controlTimer = null;
                  }
                  state.server.close(() => { state.closed = true; });
                })();
                """.trimIndent(),
            closedExpression = "Boolean(globalThis.__stmCore?.closed)",
            diagnosticsExpression =
                """
                (() => {
                  const state = globalThis.__stmCore;
                  return 'requests=' + String(state?.requestCount || 0) +
                    ', last=' + String(state?.lastRequest || '') +
                    ', error=' + String(state?.error || '');
                })();
                """.trimIndent(),
            readinessProbe = LoopbackHealthProbe::execute,
        )
}

private class FeatherEngineSession(
    val sessionId: String,
    private val sessionDirectory: File,
    private val launchSpec: FeatherEngineLaunchSpec,
    private val callback: Callback,
) {
    interface Callback {
        fun onNodeCreated(sessionId: String, nodeVersion: String)

        fun onReady(sessionId: String, port: Int, nodeVersion: String)

        fun onStopped(sessionId: String, terminationUsed: Boolean)

        fun onFailure(sessionId: String, detail: String)
    }

    private val runtimeThread = HandlerThread("STM-Core-Feather-${sessionId.take(8)}").apply { start() }
    private val runtimeHandler = Handler(runtimeThread.looper)
    private val healthExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "STM-Core-Health-${sessionId.take(8)}").apply { isDaemon = true }
    }

    @Volatile
    private var runtime: NodeRuntime? = null

    @Volatile
    private var terminationRequested = false

    private val destroyStarted = AtomicBoolean(false)
    private val teardownFinished = CountDownLatch(1)

    @Volatile
    private var teardownStarted = false

    private var nodeVersion: String? = null
    private var ready = false
    private var stopping = false
    private var closed = false
    private var healthProbeInFlight = false
    private var probeGeneration = 0
    private var stopDeadline = 0L

    private val eventLoopTick = object : Runnable {
        override fun run() {
            val activeRuntime = runtime ?: return
            try {
                activeRuntime.await(V8AwaitMode.RunOnce)
                if (stopping) {
                    if (isServerClosed(activeRuntime)) {
                        closeRuntime(activeRuntime)
                        return
                    }
                    if (SystemClock.uptimeMillis() >= stopDeadline) {
                        fail("Node HTTP server did not close before the graceful deadline")
                        return
                    }
                } else if (!ready && !healthProbeInFlight) {
                    val startupError = activeRuntime.getExecutor(
                        launchSpec.startupErrorExpression,
                    ).executeString()
                    if (!startupError.isNullOrBlank()) {
                        fail("Node HTTP server failed: ${startupError.lineSequence().first().take(300)}")
                        return
                    }
                    val port = activeRuntime.getExecutor(
                        launchSpec.readinessPortExpression,
                    ).executeInteger()
                    if (port > 0) runHealthProbe(port)
                }
                runtimeHandler.postDelayed(this, EVENT_LOOP_TICK_MILLIS)
            } catch (error: Throwable) {
                if (terminationRequested) {
                    closeRuntime(activeRuntime)
                } else {
                    fail("Node event loop failed: ${error.safeMessage()}")
                }
            }
        }
    }

    fun start() {
        runtimeHandler.post {
            try {
                check(sessionDirectory.isAbsolute) { "Feather Engine requires an absolute session path" }
                check(sessionDirectory.isDirectory || sessionDirectory.mkdirs()) {
                    "Feather Engine session directory could not be created"
                }
                check(launchSpec.consoleArguments.isNotEmpty()) {
                    "Feather Engine requires at least one console argument"
                }
                check(launchSpec.bootstrapScript.isNotBlank()) {
                    "Feather Engine requires a bootstrap script"
                }
                val createdRuntime = StmNodeRuntimeFactory.create(launchSpec.consoleArguments)
                runtime = createdRuntime
                if (destroyStarted.get()) {
                    closeRuntime(createdRuntime)
                    return@post
                }
                nodeVersion = createdRuntime.getExecutor("process.version").executeString()
                val actualNodeVersion = requireNotNull(nodeVersion) {
                    "Node.js did not report process.version"
                }
                callback.onNodeCreated(sessionId, actualNodeVersion)
                createdRuntime.getExecutor(launchSpec.bootstrapScript).executeVoid()
                runtimeHandler.post(eventLoopTick)
            } catch (error: Throwable) {
                val activeRuntime = runtime
                if (terminationRequested && activeRuntime != null) {
                    closeRuntime(activeRuntime)
                } else {
                    fail("Node runtime startup failed: ${error.safeMessage()}")
                }
            }
        }
    }

    fun requestGracefulStop(): Boolean {
        if (runtime == null || closed) return false
        runtimeHandler.post {
            val activeRuntime = runtime
            if (activeRuntime == null) {
                finishStopped(terminationUsed = false)
                return@post
            }
            if (stopping) return@post
            probeGeneration += 1
            stopping = true
            healthProbeInFlight = false
            stopDeadline = SystemClock.uptimeMillis() + ENGINE_GRACEFUL_DEADLINE_MILLIS
            try {
                activeRuntime.getExecutor(launchSpec.stopScript).executeVoid()
                runtimeHandler.removeCallbacks(eventLoopTick)
                runtimeHandler.post(eventLoopTick)
            } catch (error: Throwable) {
                fail("Node runtime graceful stop failed: ${error.safeMessage()}")
            }
        }
        return true
    }

    fun terminateExecutionFromWatchdog(): Result<Unit> = runCatching {
        val activeRuntime = requireNotNull(runtime) { "Node runtime is not active" }
        terminationRequested = true
        activeRuntime.terminateExecution(V8RuntimeTerminationMode.Synchronous)
    }

    fun destroy() {
        if (!destroyStarted.compareAndSet(false, true)) return
        runtimeHandler.removeCallbacksAndMessages(null)
        probeGeneration += 1
        healthExecutor.shutdownNow()
        if (!runtimeHandler.post {
                val activeRuntime = runtime
                if (!closed && activeRuntime != null) {
                    closeRuntime(activeRuntime)
                } else {
                    closed = true
                    runtimeThread.quitSafely()
                    teardownFinished.countDown()
                }
            }
        ) {
            destroyStarted.set(false)
            callback.onFailure(sessionId, "Node runtime teardown could not be scheduled")
            return
        }
        Thread(
            {
                if (!teardownFinished.await(DESTROY_GRACE_SECONDS, TimeUnit.SECONDS) &&
                    !teardownStarted
                ) {
                    runtime?.let { activeRuntime ->
                        terminationRequested = true
                        runCatching {
                            activeRuntime.terminateExecution(V8RuntimeTerminationMode.Synchronous)
                        }
                        runtimeHandler.post {
                            if (!closed && runtime === activeRuntime) closeRuntime(activeRuntime)
                        }
                    }
                }
                if (!teardownFinished.await(DESTROY_FORCE_SECONDS, TimeUnit.SECONDS)) {
                    callback.onFailure(sessionId, "Node runtime teardown timed out")
                }
            },
            "STM-Core-Destroy-${sessionId.take(8)}",
        ).apply {
            isDaemon = true
            start()
        }
    }

    fun awaitDestroyed(timeoutSeconds: Long): Boolean =
        teardownFinished.await(timeoutSeconds, TimeUnit.SECONDS)

    private fun closeRuntime(activeRuntime: NodeRuntime) {
        if (closed) return
        teardownStarted = true
        closed = true
        runtimeHandler.removeCallbacks(eventLoopTick)
        val teardownErrors = mutableListOf<String>()
        if (terminationRequested) {
            runCatching { activeRuntime.cancelTerminateExecution() }
                .onFailure { teardownErrors += "cancel terminate: ${it.safeMessage()}" }
        }
        if (launchSpec.cleanupScript.isNotBlank()) {
            runCatching { activeRuntime.getExecutor(launchSpec.cleanupScript).executeVoid() }
                .onFailure { teardownErrors += "cleanup script: ${it.safeMessage()}" }
        }
        runCatching { activeRuntime.setStopping(true) }
            .onFailure { teardownErrors += "set stopping: ${it.safeMessage()}" }
        runCatching { activeRuntime.close() }
            .onFailure { teardownErrors += "runtime close: ${it.safeMessage()}" }
        if (!activeRuntime.isClosed) teardownErrors += "runtime remained open"
        val runtimeClosed = activeRuntime.isClosed
        try {
            if (runtimeClosed) runtime = null
            nodeVersion = null
            ready = false
            stopping = false
            healthExecutor.shutdownNow()
            if (runtimeClosed) runtimeThread.quitSafely()
        } finally {
            if (teardownErrors.isEmpty()) {
                teardownFinished.countDown()
                finishStopped(terminationUsed = terminationRequested)
            } else {
                if (runtimeClosed) {
                    teardownFinished.countDown()
                } else {
                    closed = false
                    teardownStarted = false
                    destroyStarted.set(false)
                }
                callback.onFailure(
                    sessionId,
                    "Node runtime teardown failed: ${teardownErrors.joinToString("; ")}",
                )
            }
        }
    }

    private fun finishStopped(terminationUsed: Boolean) {
        if (!closed) closed = true
        callback.onStopped(sessionId, terminationUsed)
    }

    private fun fail(detail: String) {
        if (closed) return
        val activeRuntime = runtime
        if (activeRuntime == null) {
            closed = true
            teardownStarted = true
            runtimeHandler.removeCallbacks(eventLoopTick)
            healthExecutor.shutdownNow()
            runtimeThread.quitSafely()
            teardownFinished.countDown()
            callback.onFailure(sessionId, detail)
            return
        }
        teardownStarted = true
        closed = true
        runtimeHandler.removeCallbacks(eventLoopTick)
        val teardownErrors = mutableListOf<String>()
        if (launchSpec.cleanupScript.isNotBlank()) {
            runCatching { activeRuntime.getExecutor(launchSpec.cleanupScript).executeVoid() }
                .onFailure { teardownErrors += "cleanup script: ${it.safeMessage()}" }
        }
        runCatching { activeRuntime.setStopping(true) }
            .onFailure { teardownErrors += "set stopping: ${it.safeMessage()}" }
        runCatching { activeRuntime.close() }
            .onFailure { teardownErrors += "runtime close: ${it.safeMessage()}" }
        if (!activeRuntime.isClosed) teardownErrors += "runtime remained open"
        val runtimeClosed = activeRuntime.isClosed
        if (runtimeClosed) runtime = null
        nodeVersion = null
        ready = false
        stopping = false
        healthExecutor.shutdownNow()
        if (runtimeClosed) {
            runtimeThread.quitSafely()
            teardownFinished.countDown()
        } else {
            closed = false
            teardownStarted = false
            destroyStarted.set(false)
        }
        val suffix = teardownErrors.takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = "; teardown: ", separator = "; ")
            .orEmpty()
        callback.onFailure(sessionId, detail + suffix)
    }

    private fun isServerClosed(activeRuntime: NodeRuntime): Boolean =
        activeRuntime.getExecutor(
            launchSpec.closedExpression,
        ).executeBoolean()

    private fun runHealthProbe(port: Int) {
        healthProbeInFlight = true
        val generation = probeGeneration
        val baseUrl = "http://127.0.0.1:$port"
        healthExecutor.execute {
            val result = launchSpec.readinessProbe(baseUrl)
            runtimeHandler.post {
                if (generation != probeGeneration || stopping || runtime == null) return@post
                healthProbeInFlight = false
                when (result) {
                    is LoopbackProbeResult.Healthy -> {
                        ready = true
                        callback.onReady(sessionId, port, requireNotNull(nodeVersion))
                    }

                    is LoopbackProbeResult.Failed -> {
                        val diagnostics = runtime?.getExecutor(
                            launchSpec.diagnosticsExpression,
                        )?.executeString().orEmpty()
                        fail("${result.detail}; Node diagnostics: ${diagnostics.take(500)}")
                    }
                }
            }
        }
    }

    private companion object {
        const val EVENT_LOOP_TICK_MILLIS = 10L
        // Core owns the user-visible 15-second graceful boundary and escalation policy.
        // Keep the Engine's internal guard later so it cannot race that watchdog into CRASHED.
        const val ENGINE_GRACEFUL_DEADLINE_MILLIS = 16_000L
        const val DESTROY_GRACE_SECONDS = 1L
        const val DESTROY_FORCE_SECONDS = 9L
    }
}
