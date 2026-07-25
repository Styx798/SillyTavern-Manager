package io.github.styx798.sillytavernmanager.stmcore.testing

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Debug-only visible launcher for the physical-device Runtime Image spike.
 *
 * Samsung China firmware can classify a headless instrumentation process as blocked autorun.
 * Keeping this activity visible gives the experiment the same foreground status as an actual
 * user-started install without changing production code or the phone's global power policy.
 */
class StmCoreGate3bRuntimeImageObbActivity : Activity(), StmCoreExperimentListener {
    private lateinit var status: TextView
    private lateinit var client: StmCoreExperimentClient
    private val terminal = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var experimentRequested = false
    private var deliveredResult: StmCoreExperimentResult? = null
    private val startExperiment = object : Runnable {
        override fun run() {
            if (terminal.get() || experimentRequested) return
            if (client.run(StmCoreExperiment.GATE3B_RUNTIME_IMAGE_OBB)) {
                experimentRequested = true
                status.text = "STM Gate 3B\nRuntime Image OBB\n\nBinding Core experiment service…"
                writeReport(
                    mapOf(
                        "result" to "running",
                        "phase" to "binding_experiment_service",
                    ),
                )
            } else {
                status.text = "STM Gate 3B\nRuntime Image OBB\n\nWaiting for phone unlock…"
                mainHandler.postDelayed(this, BIND_RETRY_MILLIS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            typeface = Typeface.MONOSPACE
            setPadding(48, 48, 48, 48)
            text = "STM Gate 3B\nRuntime Image OBB\n\nPreparing foreground experiment…"
        }
        setContentView(status)
        client = StmCoreExperimentClient(this, this)
        mainHandler.post(startExperiment)
    }

    override fun onExperimentServiceReady(processId: Int) {
        status.text = "STM Gate 3B\nRuntime Image OBB\n\nRunning on Core PID $processId…\n\nDo not leave this screen."
        writeReport(
            mapOf(
                "result" to "running",
                "phase" to "core_experiment",
                "core_pid" to processId.toString(),
            ),
        )
    }

    override fun onExperimentResult(result: StmCoreExperimentResult) {
        deliveredResult = result
        val values = linkedMapOf(
            "request_id" to result.requestId,
            "experiment" to result.experiment.name,
            "teardown_complete" to result.teardownComplete.toString(),
        ).apply { putAll(result.values) }
        writeReport(values)
        val outcome = result.values["result"].orEmpty().uppercase()
        val failure = result.values["failure"].orEmpty()
        status.text = buildString {
            append("STM Gate 3B\nRuntime Image OBB\n\n")
            append(outcome.ifBlank { "RESULT RECEIVED" })
            append("\n\n")
            append("Waiting for teardown…")
            if (failure.isNotBlank()) {
                append("\n\n")
                append(failure)
            }
        }
    }

    override fun onExperimentTeardown(requestId: String) {
        terminal.set(true)
        runCatching { client.disconnect() }
        val result = deliveredResult
        status.text = buildString {
            append("STM Gate 3B\nRuntime Image OBB\n\n")
            append(result?.values?.get("result")?.uppercase() ?: "TEARDOWN COMPLETE")
            append("\n\nSafe to leave this screen.")
            result?.values?.get("failure")?.takeIf(String::isNotBlank)?.let { failure ->
                append("\n\n")
                append(failure)
            }
        }
    }

    override fun onExperimentServiceDisconnected() {
        if (terminal.compareAndSet(false, true)) {
            val values = mapOf(
                "result" to "failed",
                "failure" to "Debug experiment service disconnected before teardown",
            )
            writeReport(values)
            status.text = "STM Gate 3B\nRuntime Image OBB\n\nFAILED\n\n${values.getValue("failure")}"
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(startExperiment)
        if (!terminal.get()) {
            runCatching { client.cancelPending() }
        }
        super.onDestroy()
    }

    private fun writeReport(values: Map<String, String>) {
        val reportRoot = File(filesDir, REPORT_DIRECTORY)
        check(reportRoot.isDirectory || reportRoot.mkdirs()) {
            "Could not create the debug experiment report directory"
        }
        val target = File(reportRoot, REPORT_FILE)
        val temporary = File(reportRoot, "$REPORT_FILE.tmp")
        val json = JSONObject().apply {
            put("format", "stm-gate3b-runtime-image-obb-debug-v1")
            put("values", JSONObject(values))
        }
        temporary.writeText(json.toString(2) + "\n")
        check(temporary.renameTo(target)) {
            "Could not publish the debug experiment report"
        }
    }

    private companion object {
        const val REPORT_DIRECTORY = "stm_core_experiments"
        const val REPORT_FILE = "runtime-image-obb-latest.json"
        const val BIND_RETRY_MILLIS = 1_000L
    }
}
