package io.github.styx798.sillytavernmanager.stmcore.testing

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import com.caoccao.javet.enums.V8AwaitMode
import com.caoccao.javet.enums.V8RuntimeTerminationMode
import com.caoccao.javet.interop.NodeRuntime
import io.github.styx798.sillytavernmanager.stmcore.StmCorePaths
import io.github.styx798.sillytavernmanager.stmcore.StmNodeRuntimeFactory
import io.github.styx798.sillytavernmanager.stmcore.installer.StmDependencySupplyCandidate
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun saturatingTimeoutBudgetMillis(vararg phases: Long): Long {
    phases.forEach { phase ->
        require(phase >= 0L) { "Timeout phases cannot be negative" }
    }
    var total = 0L
    for (phase in phases) {
        if (phase > Long.MAX_VALUE - total) return Long.MAX_VALUE
        total += phase
    }
    return total
}

internal class StmCoreExperimentRequestCancellation(val requestId: String) {
    private val requested = AtomicBoolean(false)

    init {
        require(requestId.isNotBlank()) { "Experiment request ID cannot be blank" }
    }

    fun request(matchingRequestId: String): Boolean {
        if (matchingRequestId != requestId) return false
        requested.set(true)
        return true
    }

    fun isRequested(matchingRequestId: String): Boolean =
        matchingRequestId == requestId && requested.get()
}

internal class StmCoreExperimentRequestCancellationState {
    private var active: StmCoreExperimentRequestCancellation? = null

    @Synchronized
    fun begin(requestId: String): StmCoreExperimentRequestCancellation {
        return StmCoreExperimentRequestCancellation(requestId).also { active = it }
    }

    @Synchronized
    fun activeRequestId(): String? = active?.requestId

    @Synchronized
    fun isActive(requestId: String): Boolean = active?.requestId == requestId

    @Synchronized
    fun requestCancellation(requestId: String): Boolean = active?.request(requestId) == true

    @Synchronized
    fun isCancellationRequested(requestId: String): Boolean =
        active?.isRequested(requestId) == true

    @Synchronized
    fun clear(requestId: String): Boolean {
        if (active?.requestId != requestId) return false
        active = null
        return true
    }
}

internal fun failClosedExperimentResult(
    requestId: String,
    cancellation: StmCoreExperimentRequestCancellation,
    result: Map<String, String>,
): Map<String, String> = if (cancellation.isRequested(requestId)) {
    mapOf(
        "result" to "cancelled",
        "failure" to "Experiment was cancelled before result delivery",
    )
} else {
    result
}

class StmCoreExperimentService : Service() {
    private val lifecycleLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val incoming = Messenger(
        Handler(Looper.getMainLooper()) { message -> handleMessage(message) },
    )
    private var experimentRunning = false

    private val activeRequestCancellation = StmCoreExperimentRequestCancellationState()

    @Volatile
    private var activeReplyTarget: Messenger? = null

    @Volatile
    private var teardownAckRequestId: String? = null

    @Volatile
    private var teardownFinishing = false

    @Volatile
    private var serviceDestroyed = false

    private var activeExperimentHandler: Handler? = null
    private var activeExperimentThread: HandlerThread? = null

    @Volatile
    private var activeRuntime: NodeRuntime? = null

    @Volatile
    private var activeGate3aExperiment: StmCoreGate3aExperiment? = null

    @Volatile
    private var activeGate3bExperiment: StmCoreGate3bExperimentRunner? = null

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    override fun onDestroy() {
        val requestId: String?
        val gate3a: StmCoreGate3aExperiment?
        val gate3b: StmCoreGate3bExperimentRunner?
        val experimentHandler: Handler?
        val experimentThread: HandlerThread?
        synchronized(lifecycleLock) {
            serviceDestroyed = true
            requestId = activeRequestCancellation.activeRequestId()
            gate3a = activeGate3aExperiment
            activeGate3aExperiment = null
            gate3b = activeGate3bExperiment
            experimentHandler = activeExperimentHandler
            experimentThread = activeExperimentThread
        }
        gate3a?.cancel()
        gate3b?.cancel()
        activeRuntime?.let { runtime ->
            runCatching { runtime.terminateExecution(V8RuntimeTerminationMode.Synchronous) }
        }
        if (requestId != null) {
            val finalizer = Runnable {
                val safe = runCatching {
                    gate3b?.finishTeardown() != false && gate3b?.hasLiveResources() != true
                }.getOrDefault(false)
                if (safe) {
                    PROCESS_EXPERIMENT_LEASE.release(requestId)
                } else {
                    Process.killProcess(Process.myPid())
                }
            }
            if (experimentHandler?.post(finalizer) != true) {
                Process.killProcess(Process.myPid())
            }
        }
        experimentThread?.quitSafely()
        super.onDestroy()
    }

    private fun handleMessage(message: Message): Boolean = when (message.what) {
        StmCoreExperimentClient.MESSAGE_REGISTER -> {
            message.replyTo?.let(::sendReady)
            true
        }

        StmCoreExperimentClient.MESSAGE_RUN -> {
            val requestId = message.data.getString(StmCoreExperimentClient.KEY_REQUEST_ID)
            val experiment = message.data.getString(StmCoreExperimentClient.KEY_EXPERIMENT)
                ?.let { runCatching { StmCoreExperiment.valueOf(it) }.getOrNull() }
            val replyTarget = message.replyTo
            if (requestId != null && experiment != null && replyTarget != null) {
                if (
                    experimentRunning ||
                    !PROCESS_EXPERIMENT_LEASE.tryAcquire(requestId)
                ) {
                    sendResult(
                        replyTarget,
                        requestId,
                        experiment,
                        mapOf(
                            "result" to "busy",
                            "failure" to "Another Core experiment still owns the process lease",
                        ),
                        teardownComplete = true,
                    )
                } else {
                    val cancellation = synchronized(lifecycleLock) {
                        val created = activeRequestCancellation.begin(requestId)
                        activeReplyTarget = replyTarget
                        created
                    }
                    runExperiment(
                        StmCoreExperimentRequest(requestId, experiment),
                        replyTarget,
                        cancellation,
                    )
                }
            }
            true
        }

        StmCoreExperimentClient.MESSAGE_CANCEL -> {
            val requestId = message.data.getString(StmCoreExperimentClient.KEY_REQUEST_ID)
            var gate3a: StmCoreGate3aExperiment? = null
            var gate3b: StmCoreGate3bExperimentRunner? = null
            var runtime: NodeRuntime? = null
            var scheduleTeardown = false
            val cancellationAccepted = synchronized(lifecycleLock) {
                if (requestId == null || !activeRequestCancellation.requestCancellation(requestId)) {
                    false
                } else {
                    teardownAckRequestId = requestId
                    gate3a = activeGate3aExperiment
                    gate3b = activeGate3bExperiment
                    runtime = activeRuntime
                    scheduleTeardown = !experimentRunning
                    true
                }
            }
            if (cancellationAccepted && requestId != null) {
                gate3a?.cancel()
                gate3b?.cancel()
                runtime?.let { active ->
                    runCatching {
                        active.terminateExecution(V8RuntimeTerminationMode.Synchronous)
                    }
                }
                if (scheduleTeardown) scheduleRetainedTeardown(requestId)
            }
            true
        }

        else -> false
    }

    private fun sendReady(target: Messenger) {
        send(target, Message.obtain(null, StmCoreExperimentClient.MESSAGE_READY).apply {
            data = Bundle().apply {
                putInt(StmCoreExperimentClient.KEY_PROCESS_ID, Process.myPid())
            }
        })
    }

    private fun runExperiment(
        request: StmCoreExperimentRequest,
        replyTarget: Messenger,
        cancellation: StmCoreExperimentRequestCancellation,
    ) {
        val experiment = request.experiment
        if (serviceDestroyed) {
            PROCESS_EXPERIMENT_LEASE.release(request.requestId)
            return
        }
        experimentRunning = true
        val thread = HandlerThread("STM-Core-Experiment-${experiment.name}").apply { start() }
        val handler = Handler(thread.looper)
        synchronized(lifecycleLock) {
            if (serviceDestroyed) {
                experimentRunning = false
                thread.quitSafely()
                PROCESS_EXPERIMENT_LEASE.release(request.requestId)
                return
            }
            activeExperimentThread = thread
            activeExperimentHandler = handler
        }
        val posted = handler.post {
            var teardownComplete = false
            val cancellationRequested = cancellation.isRequested(request.requestId)
            val result = try {
                if (serviceDestroyed || cancellationRequested) {
                    mapOf(
                        "result" to "cancelled",
                        "failure" to if (serviceDestroyed) {
                            "Experiment service was destroyed before execution"
                        } else {
                            "Experiment was cancelled before execution"
                        },
                    )
                } else {
                    runCatching {
                        when (experiment) {
                    StmCoreExperiment.NODE_SEMANTICS -> runNodeSemantics()
                    StmCoreExperiment.TEARDOWN_PROTOCOL_PROBE ->
                        runTeardownProtocolProbe(request.requestId)
                    StmCoreExperiment.TERMINATE_EXECUTION -> runTerminateExecution()
                    StmCoreExperiment.PROCESS_EXIT -> runFatalScript("process.exit(42);")
                    StmCoreExperiment.UNCAUGHT_EXCEPTION -> runFatalScript(
                        """
                        setImmediate(() => { throw new Error('stm-gate1-uncaught'); });
                        setInterval(() => {}, 25);
                        """.trimIndent(),
                    )

                    StmCoreExperiment.UNHANDLED_REJECTION -> runFatalScript(
                        """
                        Promise.reject(new Error('stm-gate1-unhandled-rejection'));
                        setInterval(() => {}, 25);
                        """.trimIndent(),
                    )

                    StmCoreExperiment.GATE3A_REAL_ST -> runGate3aExperiment()
                    StmCoreExperiment.GATE3A_REAL_ST_PERFORMANCE ->
                        runGate3aExperiment(Gate3aRunProfile.PERFORMANCE)

                    StmCoreExperiment.GATE3A_REAL_ST_PERFORMANCE_NO_COMPRESSION ->
                        runGate3aExperiment(Gate3aRunProfile.PERFORMANCE_NO_COMPRESSION)

                    StmCoreExperiment.GATE3A_REAL_ST_PERFORMANCE_PREBUILT_BUNDLE ->
                        runGate3aExperiment(Gate3aRunProfile.PERFORMANCE_PREBUILT_BUNDLE)

                    StmCoreExperiment.GATE3B_NPM_CLI ->
                        runGate3bExperiment(
                            request.requestId,
                            StmDependencySupplyCandidate.NPM_CLI,
                        )

                    StmCoreExperiment.GATE3B_NPM_CLI_RUNNABLE ->
                        runGate3bExperiment(
                            request.requestId,
                            StmDependencySupplyCandidate.NPM_CLI,
                            runnableAcceptance = true,
                        )

                    StmCoreExperiment.GATE3B_NPM_CLI_LOCAL_BUNDLE_RUNNABLE ->
                        runGate3bExperiment(
                            request.requestId,
                            StmDependencySupplyCandidate.NPM_CLI,
                            runnableAcceptance = true,
                            localBundleBuild = true,
                        )

                    StmCoreExperiment.GATE3B_NPM_CLI_BOUNDED_INTERRUPTION ->
                        runGate3bExperiment(
                            request.requestId,
                            StmDependencySupplyCandidate.NPM_CLI,
                            installTimeoutMillis = GATE3B_INTERRUPTION_TIMEOUT_MILLIS,
                        )

                    StmCoreExperiment.GATE3B_NPM_CLI_CANCEL ->
                        runGate3bExperiment(
                            request.requestId,
                            StmDependencySupplyCandidate.NPM_CLI,
                            cancelAfterMillis = GATE3B_CANCELLATION_DELAY_MILLIS,
                        )

                    StmCoreExperiment.GATE3B_ARBORIST ->
                        runGate3bExperiment(
                            request.requestId,
                            StmDependencySupplyCandidate.ARBORIST,
                        )

                    StmCoreExperiment.GATE3B_ARBORIST_RUNNABLE ->
                        runGate3bExperiment(
                            request.requestId,
                            StmDependencySupplyCandidate.ARBORIST,
                            runnableAcceptance = true,
                        )

                    StmCoreExperiment.GATE3B_ARBORIST_BOUNDED_INTERRUPTION ->
                        runGate3bExperiment(
                            request.requestId,
                            StmDependencySupplyCandidate.ARBORIST,
                            installTimeoutMillis = GATE3B_INTERRUPTION_TIMEOUT_MILLIS,
                        )

                    StmCoreExperiment.GATE3B_ARBORIST_CANCEL ->
                        runGate3bExperiment(
                            request.requestId,
                            StmDependencySupplyCandidate.ARBORIST,
                            cancelAfterMillis = GATE3B_CANCELLATION_DELAY_MILLIS,
                        )

                    StmCoreExperiment.GATE3B_SIGNED_PREBUILT ->
                        runGate3bExperiment(
                            request.requestId,
                            StmDependencySupplyCandidate.SIGNED_PREBUILT,
                        )

                    StmCoreExperiment.GATE3B_TREE_DIFF ->
                        runGate3bTreeDiffExperiment(request.requestId)

                    StmCoreExperiment.GATE3B_READY_SLOT ->
                        runGate3bReadySlotExperiment(request.requestId)

                    StmCoreExperiment.GATE3B_READY_SLOT_COLD ->
                        runGate3bReadySlotExperiment(
                            request.requestId,
                            retainCommittedSlot = false,
                        )

                    StmCoreExperiment.GATE3B_RUNTIME_IMAGE_OBB ->
                        runGate3bRuntimeImageObbExperiment(request.requestId)

                    StmCoreExperiment.GATE3B_FAULT_MATRIX ->
                        runGate3bFaultMatrixExperiment(request.requestId)

                    StmCoreExperiment.GATE2_FIXTURES -> runGate2Fixtures()
                    StmCoreExperiment.GATE2_INTERRUPT_FIXTURE ->
                        createGate2Fixture("interrupt", "c".repeat(40)).toValues("interrupt")

                    StmCoreExperiment.COUNT_OPEN_FILE_DESCRIPTORS ->
                        countOpenFileDescriptors()

                    StmCoreExperiment.ARM_BEFORE_INSTALL_EXTRACTION_KILL -> armInstallerKill(
                        StmInstallerDebugFaultBridge.BEFORE_INSTALL_EXTRACTION,
                    )

                    StmCoreExperiment.ARM_BEFORE_ACTIVE_POINTER_WRITE_KILL -> armInstallerKill(
                        StmInstallerDebugFaultBridge.BEFORE_ACTIVE_POINTER_WRITE,
                    )

                    StmCoreExperiment.ARM_ACTIVE_AFTER_TEMP_SYNC_BEFORE_ATOMIC_REPLACE_KILL ->
                        armInstallerKill(
                            StmInstallerDebugFaultBridge
                                .ACTIVE_AFTER_TEMP_SYNC_BEFORE_ATOMIC_REPLACE,
                        )

                    StmCoreExperiment
                        .ARM_ACTIVE_AFTER_ATOMIC_REPLACE_BEFORE_DIRECTORY_SYNC_KILL ->
                        armInstallerKill(
                            StmInstallerDebugFaultBridge
                                .ACTIVE_AFTER_ATOMIC_REPLACE_BEFORE_DIRECTORY_SYNC,
                        )

                    StmCoreExperiment
                        .ARM_AFTER_TERMINAL_JOURNAL_COMMIT_BEFORE_JOB_EVENT_KILL ->
                        armInstallerKill(
                            StmInstallerDebugFaultBridge
                                .AFTER_TERMINAL_JOURNAL_COMMIT_BEFORE_JOB_EVENT,
                        )

                    StmCoreExperiment.CLEAR_INSTALLER_KILL_FAILPOINT -> {
                        StmInstallerDebugFaultBridge.clear()
                        mapOf("cleared" to "true")
                    }
                        }
                    }.getOrElse { error ->
                        mapOf(
                            "process_survived" to "true",
                            "result" to "java_exception",
                            "exception_class" to error.javaClass.name,
                            "exception_message" to error.safeMessage(),
                        )
                    }
                }
            } finally {
                activeRuntime = null
                val hasLiveGate3bResources = activeGate3bExperiment?.hasLiveResources() == true
                if (!hasLiveGate3bResources) {
                    teardownComplete = PROCESS_EXPERIMENT_LEASE.release(request.requestId)
                }
                if (teardownComplete) {
                    thread.quitSafely()
                    synchronized(lifecycleLock) {
                        if (activeExperimentThread === thread) activeExperimentThread = null
                        if (activeExperimentHandler === handler) activeExperimentHandler = null
                    }
                }
            }
            if (serviceDestroyed) return@post
            mainHandler.post {
                if (serviceDestroyed) return@post
                experimentRunning = false
                val deliveryResult = failClosedExperimentResult(
                    request.requestId,
                    cancellation,
                    result,
                )
                sendResult(
                    replyTarget,
                    request.requestId,
                    experiment,
                    deliveryResult,
                    teardownComplete,
                )
                if (teardownComplete) {
                    if (teardownAckRequestId == request.requestId) {
                        sendTeardown(replyTarget, request.requestId)
                    }
                    clearCompletedRequest(request.requestId)
                } else if (teardownAckRequestId == request.requestId) {
                    scheduleRetainedTeardown(request.requestId)
                }
            }
        }
        if (!posted) {
            synchronized(lifecycleLock) {
                if (activeExperimentThread === thread) activeExperimentThread = null
                if (activeExperimentHandler === handler) activeExperimentHandler = null
            }
            experimentRunning = false
            thread.quitSafely()
            val teardownComplete = PROCESS_EXPERIMENT_LEASE.release(request.requestId)
            sendResult(
                replyTarget,
                request.requestId,
                experiment,
                mapOf(
                    "result" to "java_exception",
                    "failure" to "Experiment worker rejected its task",
                ),
                teardownComplete = teardownComplete,
            )
            clearCompletedRequest(request.requestId)
        }
    }

    private fun scheduleRetainedTeardown(requestId: String) {
        val runner: StmCoreGate3bExperimentRunner
        val handler: Handler
        val thread: HandlerThread
        val target: Messenger
        synchronized(lifecycleLock) {
            if (
                serviceDestroyed ||
                teardownFinishing ||
                !activeRequestCancellation.isActive(requestId) ||
                experimentRunning
            ) {
                return
            }
            runner = activeGate3bExperiment ?: return
            handler = activeExperimentHandler ?: return
            thread = activeExperimentThread ?: return
            target = activeReplyTarget ?: return
            teardownFinishing = true
        }
        if (!handler.post {
                val safe = runCatching {
                    runner.finishTeardown() && !runner.hasLiveResources()
                }.getOrDefault(false)
                mainHandler.post {
                    if (serviceDestroyed) return@post
                    if (safe && PROCESS_EXPERIMENT_LEASE.release(requestId)) {
                        sendTeardown(target, requestId)
                        clearCompletedRequest(requestId)
                        synchronized(lifecycleLock) {
                            if (activeGate3bExperiment === runner) activeGate3bExperiment = null
                            if (activeExperimentHandler === handler) activeExperimentHandler = null
                            if (activeExperimentThread === thread) activeExperimentThread = null
                        }
                        thread.quitSafely()
                    } else {
                        Process.killProcess(Process.myPid())
                    }
                }
            }
        ) {
            Process.killProcess(Process.myPid())
        }
    }

    private fun clearCompletedRequest(requestId: String) {
        synchronized(lifecycleLock) {
            if (!activeRequestCancellation.clear(requestId)) return
            activeReplyTarget = null
            if (teardownAckRequestId == requestId) teardownAckRequestId = null
            teardownFinishing = false
        }
    }

    private fun runGate3aExperiment(
        profile: Gate3aRunProfile = Gate3aRunProfile.ACCEPTANCE,
    ): Map<String, String> {
        val experiment = StmCoreGate3aExperiment(this, profile)
        synchronized(lifecycleLock) {
            check(!serviceDestroyed) { "The experiment service was destroyed" }
            activeGate3aExperiment = experiment
        }
        return try {
            experiment.run()
        } finally {
            synchronized(lifecycleLock) {
                if (activeGate3aExperiment === experiment) activeGate3aExperiment = null
            }
        }
    }

    private fun runGate3bExperiment(
        requestId: String,
        candidate: StmDependencySupplyCandidate,
        installTimeoutMillis: Long? = null,
        cancelAfterMillis: Long? = null,
        runnableAcceptance: Boolean = false,
        localBundleBuild: Boolean = false,
    ): Map<String, String> {
        val experiment: StmCoreGate3bExperimentRunner = when (candidate) {
            StmDependencySupplyCandidate.NPM_CLI,
            StmDependencySupplyCandidate.ARBORIST,
            -> StmCoreGate3bDependencyExperiment(
                this,
                candidate,
                installTimeoutMillis ?: GATE3B_DEFAULT_INSTALL_TIMEOUT_MILLIS,
                cancelAfterMillis,
                runnableAcceptance,
                localBundleBuild,
            )

            StmDependencySupplyCandidate.SIGNED_PREBUILT ->
                StmCoreGate3bPrebuiltExperiment(this)
        }
        return runRegisteredGate3bExperiment(requestId, experiment)
    }

    private fun runTeardownProtocolProbe(requestId: String): Map<String, String> =
        runRegisteredGate3bExperiment(requestId, StmCoreTeardownProtocolProbe())

    private fun runGate3bReadySlotExperiment(
        requestId: String,
        retainCommittedSlot: Boolean = true,
    ): Map<String, String> {
        val experiment = StmCoreGate3bReadySlotExperiment(this, retainCommittedSlot)
        return runRegisteredGate3bExperiment(requestId, experiment)
    }

    private fun runGate3bTreeDiffExperiment(requestId: String): Map<String, String> {
        val experiment = StmCoreGate3bTreeDiffExperiment(this)
        return runRegisteredGate3bExperiment(requestId, experiment)
    }

    private fun runGate3bRuntimeImageObbExperiment(requestId: String): Map<String, String> {
        val experiment = StmCoreGate3bRuntimeImageObbExperiment(this)
        return runRegisteredGate3bExperiment(requestId, experiment)
    }

    private fun runGate3bFaultMatrixExperiment(requestId: String): Map<String, String> {
        val experiment = StmCoreGate3bFaultMatrixExperiment(this)
        return runRegisteredGate3bExperiment(requestId, experiment)
    }

    private fun runRegisteredGate3bExperiment(
        requestId: String,
        experiment: StmCoreGate3bExperimentRunner,
    ): Map<String, String> {
        val cancellationRequested = synchronized(lifecycleLock) {
            check(!serviceDestroyed) { "The experiment service was destroyed" }
            check(activeRequestCancellation.isActive(requestId)) {
                "The experiment request is no longer active"
            }
            activeGate3bExperiment = experiment
            activeRequestCancellation.isCancellationRequested(requestId)
        }
        if (cancellationRequested) experiment.cancel()
        return try {
            experiment.run()
        } finally {
            synchronized(lifecycleLock) {
                if (
                    activeGate3bExperiment === experiment &&
                    !experiment.hasLiveResources()
                ) {
                    activeGate3bExperiment = null
                }
            }
        }
    }

    private fun runNodeSemantics(): Map<String, String> {
        val directory = experimentDirectory("semantics")
        val absoluteFixture = File(directory, "absolute-fixture.txt").absoluteFile
        val dependencyModule = File(directory, "dependency.mjs").absoluteFile
        val dynamicModule = File(directory, "dynamic.mjs").absoluteFile
        val entryModule = File(directory, "entry.mjs").absoluteFile
        absoluteFixture.writeText("absolute-path-ok", Charsets.UTF_8)
        dependencyModule.writeText("export const base = 40;\n", Charsets.UTF_8)
        dynamicModule.writeText("export const increment = 2;\n", Charsets.UTF_8)
        entryModule.writeText(
            """
            import { base } from './dependency.mjs';
            await new Promise(resolve => setTimeout(resolve, 20));
            const dynamic = await import('./dynamic.mjs');
            globalThis.__stmGate1Module = JSON.stringify({
              result: base + dynamic.increment,
              moduleUrl: import.meta.url,
            });
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val runtime = StmNodeRuntimeFactory.create(
            arrayOf("stm-core-gate1", entryModule.absolutePath),
        )
        activeRuntime = runtime
        return try {
            val originalCwd = runtime.getExecutor("process.cwd()").executeString().orEmpty()
            val argv = runtime.getExecutor("JSON.stringify(process.argv)").executeString().orEmpty()
            val directoryLiteral = jsString(directory.absolutePath)
            val fixtureLiteral = jsString(absoluteFixture.absolutePath)
            val cwd = runtime.getExecutor(
                "process.chdir($directoryLiteral); process.cwd();",
            ).executeString().orEmpty()
            val absoluteRead = runtime.getExecutor(
                "require('fs').readFileSync($fixtureLiteral, 'utf8')",
            ).executeString().orEmpty()
            val codeGenerationResult = runtime.getExecutor(
                """
                (() => {
                  const vm = require('node:vm');
                  let newContext;
                  try {
                    newContext = vm.runInNewContext("Function('return 7')()");
                  } catch (error) {
                    newContext = 'ERROR:' + String(error && (error.stack || error));
                  }
                  const lexicalValue = 40;
                  return JSON.stringify({
                    eval: eval('lexicalValue + 2'),
                    function: Function('left', 'right', 'return left + right')(20, 22),
                    nativeFunction: Function.prototype.toString.call(Function).includes('[native code]'),
                    newContext,
                  });
                })();
                """.trimIndent(),
            ).executeString().orEmpty()
            val entryImportExpression = "import(${jsString(entryModule.toURI().toString())})"
            runtime.getExecutor(
                """
                (() => {
                  const vm = require('node:vm');
                  globalThis.__stmGate1Module = '';
                  const script = new vm.Script(${jsString(entryImportExpression)}, {
                    filename: ${jsString(File(directory, "dynamic-loader.cjs").absolutePath)},
                    importModuleDynamically: vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER,
                  });
                  Promise.resolve(script.runInThisContext()).then(
                    () => {},
                    error => {
                      globalThis.__stmGate1Module = 'ERROR:' + String(error && (error.stack || error));
                    },
                  );
                })();
                """.trimIndent(),
            ).executeVoid()
            val moduleResult = awaitString(runtime, "globalThis.__stmGate1Module || ''")
            check(!moduleResult.startsWith("ERROR:")) { moduleResult }
            val intlResult = runCatching {
                runtime.getExecutor(
                    """
                    JSON.stringify({
                      locales: Intl.DateTimeFormat.supportedLocalesOf(['zh-CN', 'ja-JP', 'ar-EG']),
                      formatted: new Intl.DateTimeFormat('zh-CN', {
                        dateStyle: 'full',
                        timeZone: 'UTC',
                      }).format(new Date('2024-01-02T00:00:00Z')),
                    })
                    """.trimIndent(),
                ).executeString().orEmpty()
            }.getOrElse { error -> "ERROR:${error.safeMessage()}" }
            runtime.getExecutor("process.chdir(${jsString(originalCwd)});").executeVoid()
            mapOf(
                "process_survived" to "true",
                "node_version" to runtime.getExecutor("process.version").executeString().orEmpty(),
                "process_argv" to argv,
                "original_cwd" to originalCwd,
                "cwd" to cwd,
                "absolute_fixture" to absoluteFixture.absolutePath,
                "absolute_read" to absoluteRead,
                "code_generation" to codeGenerationResult,
                "module_result" to moduleResult,
                "intl_result" to intlResult,
                "javet_artifact" to io.github.styx798.sillytavernmanager.stmcore.BuildConfig.JAVET_ARTIFACT,
            )
        } finally {
            runtime.setStopping(true)
            runtime.close(true)
            activeRuntime = null
        }
    }

    private fun runTerminateExecution(): Map<String, String> {
        val runtime = StmNodeRuntimeFactory.create(arrayOf("stm-core-gate1-terminate"))
        activeRuntime = runtime
        val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "STM-Core-Experiment-Watchdog").apply { isDaemon = true }
        }
        val engineThread = Thread.currentThread().name
        var watchdogThread = ""
        watchdog.schedule(
            {
                watchdogThread = Thread.currentThread().name
                runtime.terminateExecution(V8RuntimeTerminationMode.Synchronous)
            },
            250,
            TimeUnit.MILLISECONDS,
        )
        var outcome = "script_returned"
        var exceptionClass = ""
        var exceptionMessage = ""
        try {
            runtime.getExecutor("while (true) {}").executeVoid()
        } catch (error: Throwable) {
            outcome = "execution_interrupted"
            exceptionClass = error.javaClass.name
            exceptionMessage = error.safeMessage()
        } finally {
            watchdog.shutdownNow()
            runCatching { runtime.cancelTerminateExecution() }
            runtime.setStopping(true)
            runtime.close(true)
            activeRuntime = null
        }
        return mapOf(
            "process_survived" to "true",
            "result" to outcome,
            "exception_class" to exceptionClass,
            "exception_message" to exceptionMessage,
            "engine_thread" to engineThread,
            "watchdog_thread" to watchdogThread,
            "different_threads" to (engineThread != watchdogThread).toString(),
        )
    }

    private fun runFatalScript(script: String): Map<String, String> {
        val runtime = StmNodeRuntimeFactory.create(arrayOf("stm-core-gate1-fatal"))
        activeRuntime = runtime
        var outcome = "script_returned"
        var exceptionClass = ""
        var exceptionMessage = ""
        try {
            runtime.getExecutor(script).executeVoid()
            val deadline = System.currentTimeMillis() + FATAL_EXPERIMENT_MILLIS
            while (System.currentTimeMillis() < deadline) {
                runtime.await(V8AwaitMode.RunOnce)
            }
        } catch (error: Throwable) {
            outcome = "java_exception"
            exceptionClass = error.javaClass.name
            exceptionMessage = error.safeMessage()
        } finally {
            runCatching { runtime.setStopping(true) }
            runCatching { runtime.close(true) }
            activeRuntime = null
        }
        return mapOf(
            "process_survived" to "true",
            "result" to outcome,
            "exception_class" to exceptionClass,
            "exception_message" to exceptionMessage,
        )
    }

    private fun runGate2Fixtures(): Map<String, String> = buildMap {
        putAll(createGate2Fixture("a", "a".repeat(40)).toValues("a"))
        putAll(createGate2Fixture("b", "b".repeat(40)).toValues("b"))
    }

    private fun createGate2Fixture(label: String, commitSha: String): Gate2Fixture {
        val cacheRoot = StmCorePaths.installerCacheRoot(this).toPath()
        Files.createDirectories(cacheRoot)
        val fileName = "gate2-$label-${UUID.randomUUID()}.zip"
        val archive = cacheRoot.resolve(fileName)
        Files.newOutputStream(
            archive,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { output ->
            ZipOutputStream(output, Charsets.UTF_8).use { zip ->
                writeFixtureEntry(zip, GATE2_MARKER_FILE, GATE2_MARKER)
                writeFixtureEntry(zip, "fixture-id.txt", "gate2-$label\n")
            }
        }
        java.nio.channels.FileChannel.open(archive, StandardOpenOption.WRITE).use { channel ->
            channel.force(true)
        }
        val archiveSha256 = MessageDigest.getInstance("SHA-256").let { digest ->
            Files.newInputStream(archive, StandardOpenOption.READ).use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
        return Gate2Fixture(
            cacheFileName = fileName,
            repository = GATE2_REPOSITORY,
            channel = "gate2-debug",
            commitSha = commitSha,
            downloadUrl = "$GATE2_REPOSITORY/archive/$commitSha.zip",
            downloadedAtEpochMs = System.currentTimeMillis(),
            archiveLength = Files.size(archive),
            archiveSha256 = archiveSha256,
            archiveRoot = "",
            stVersion = "gate2-synthetic-$label",
            nodeRequirement = "",
            packageLockSha256 = "",
            licenseStatus = "Synthetic debug fixture; not SillyTavern",
        )
    }

    private fun writeFixtureEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name).apply { time = FIXED_ZIP_TIME_EPOCH_MS })
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun armInstallerKill(failpoint: String): Map<String, String> {
        StmInstallerDebugFaultBridge.clear()
        StmInstallerDebugFaultBridge.arm(failpoint)
        return mapOf("armed" to "true", "failpoint" to failpoint)
    }

    private fun countOpenFileDescriptors(): Map<String, String> {
        val descriptors = File("/proc/self/fd").list()
            ?: error("Core process file-descriptor directory is unavailable")
        return mapOf(
            "process_id" to Process.myPid().toString(),
            "open_file_descriptors" to descriptors.size.toString(),
        )
    }

    private fun Gate2Fixture.toValues(prefix: String): Map<String, String> = mapOf(
        "${prefix}_cache_file_name" to cacheFileName,
        "${prefix}_kind" to "GATE2_SYNTHETIC",
        "${prefix}_repository" to repository,
        "${prefix}_channel" to channel,
        "${prefix}_commit_sha" to commitSha,
        "${prefix}_download_url" to downloadUrl,
        "${prefix}_downloaded_at_epoch_ms" to downloadedAtEpochMs.toString(),
        "${prefix}_archive_length" to archiveLength.toString(),
        "${prefix}_archive_sha256" to archiveSha256,
        "${prefix}_integrity" to "PENDING",
        "${prefix}_trust" to "DEGRADED_UNSIGNED_CATALOG",
        "${prefix}_catalog_version" to "",
        "${prefix}_archive_root" to archiveRoot,
        "${prefix}_st_version" to stVersion,
        "${prefix}_node_requirement" to nodeRequirement,
        "${prefix}_package_lock_sha256" to packageLockSha256,
        "${prefix}_license_status" to licenseStatus,
    )

    private fun awaitString(runtime: NodeRuntime, expression: String): String {
        val deadline = System.currentTimeMillis() + ASYNC_EXPERIMENT_MILLIS
        var value = ""
        while (value.isBlank() && System.currentTimeMillis() < deadline) {
            runtime.await(V8AwaitMode.RunOnce)
            value = runtime.getExecutor(expression).executeString().orEmpty()
        }
        check(value.isNotBlank()) { "Timed out waiting for the ESM experiment" }
        return value
    }

    private fun experimentDirectory(name: String): File {
        val directory = File(
            StmCorePaths.cacheRoot(this),
            "experiments/$name-${UUID.randomUUID()}",
        ).absoluteFile
        check(directory.isDirectory || directory.mkdirs()) {
            "Experiment directory could not be created"
        }
        return directory
    }

    private fun sendResult(
        target: Messenger,
        requestId: String,
        experiment: StmCoreExperiment,
        values: Map<String, String>,
        teardownComplete: Boolean,
    ) {
        send(target, Message.obtain(null, StmCoreExperimentClient.MESSAGE_RESULT).apply {
            data = Bundle().apply {
                putString(StmCoreExperimentClient.KEY_REQUEST_ID, requestId)
                putString(StmCoreExperimentClient.KEY_EXPERIMENT, experiment.name)
                putBoolean(StmCoreExperimentClient.KEY_TEARDOWN_COMPLETE, teardownComplete)
                putBundle(
                    StmCoreExperimentClient.KEY_VALUES,
                    Bundle().apply { values.forEach(::putString) },
                )
            }
        })
    }

    private fun sendTeardown(target: Messenger, requestId: String) {
        send(target, Message.obtain(null, StmCoreExperimentClient.MESSAGE_TEARDOWN).apply {
            data = Bundle().apply {
                putString(StmCoreExperimentClient.KEY_REQUEST_ID, requestId)
            }
        })
    }

    private fun send(target: Messenger, message: Message) {
        try {
            target.send(message)
        } catch (_: RemoteException) {}
    }

    private fun jsString(value: String): String = buildString(value.length + 2) {
        append('\'')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(character)
            }
        }
        append('\'')
    }

    private fun Throwable.safeMessage(): String =
        (message ?: javaClass.simpleName)
            .lineSequence()
            .firstOrNull()
            .orEmpty()
            .ifBlank { javaClass.simpleName }
            .take(500)

    private companion object {
        val PROCESS_EXPERIMENT_LEASE = StmCoreExperimentProcessLease()
        const val ASYNC_EXPERIMENT_MILLIS = 5_000L
        const val FATAL_EXPERIMENT_MILLIS = 1_000L
        const val GATE3B_DEFAULT_INSTALL_TIMEOUT_MILLIS = 30L * 60L * 1000L
        const val GATE3B_INTERRUPTION_TIMEOUT_MILLIS = 60_000L
        const val GATE3B_CANCELLATION_DELAY_MILLIS = 5_000L
        const val FIXED_ZIP_TIME_EPOCH_MS = 315_532_800_000L
        const val GATE2_MARKER_FILE = "gate2-fixture.txt"
        const val GATE2_MARKER = "STM_GATE2_SYNTHETIC_V1\n"
        const val GATE2_REPOSITORY = "https://github.com/example/stm-gate2-fixture"
    }
}

private class StmCoreTeardownProtocolProbe : StmCoreGate3bExperimentRunner {
    @Volatile
    private var live = true

    override fun cancel() {
        live = false
    }

    override fun run(): Map<String, String> = mapOf(
        "result" to "passed",
        "teardown_probe" to "result_before_teardown",
    )

    override fun hasLiveResources(): Boolean = live
}

private data class Gate2Fixture(
    val cacheFileName: String,
    val repository: String,
    val channel: String,
    val commitSha: String,
    val downloadUrl: String,
    val downloadedAtEpochMs: Long,
    val archiveLength: Long,
    val archiveSha256: String,
    val archiveRoot: String,
    val stVersion: String,
    val nodeRequirement: String,
    val packageLockSha256: String,
    val licenseStatus: String,
)
