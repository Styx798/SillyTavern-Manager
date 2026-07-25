package io.github.styx798.sillytavernmanager.stmcore.testing

import android.os.Process
import java.util.concurrent.atomic.AtomicReference

/**
 * Debug-only, one-shot process failure bridge reached reflectively by the production coordinator.
 * Release builds do not contain this class, and an unarmed bridge is always a no-op.
 */
object StmInstallerDebugFaultBridge {
    private val armedFailpoint = AtomicReference<String?>(null)

    @JvmStatic
    fun arm(failpoint: String) {
        require(failpoint in SUPPORTED_FAILPOINTS) { "Unsupported installer failpoint: $failpoint" }
        check(armedFailpoint.compareAndSet(null, failpoint)) {
            "An installer process-kill failpoint is already armed"
        }
    }

    @JvmStatic
    fun clear() {
        armedFailpoint.set(null)
    }

    @JvmStatic
    fun hit(failpoint: String) {
        while (true) {
            val armed = armedFailpoint.get() ?: return
            if (armed != failpoint) return
            if (armedFailpoint.compareAndSet(armed, null)) {
                Process.killProcess(Process.myPid())
                throw IllegalStateException(
                    "Debug process-kill failpoint returned before process termination: $failpoint",
                )
            }
        }
    }

    const val BEFORE_INSTALL_EXTRACTION = "BEFORE_INSTALL_EXTRACTION"
    const val BEFORE_ACTIVE_POINTER_WRITE = "BEFORE_ACTIVE_POINTER_WRITE"
    const val ACTIVE_AFTER_TEMP_SYNC_BEFORE_ATOMIC_REPLACE =
        "ACTIVE_AFTER_TEMP_SYNC_BEFORE_ATOMIC_REPLACE"
    const val ACTIVE_AFTER_ATOMIC_REPLACE_BEFORE_DIRECTORY_SYNC =
        "ACTIVE_AFTER_ATOMIC_REPLACE_BEFORE_DIRECTORY_SYNC"
    const val AFTER_TERMINAL_JOURNAL_COMMIT_BEFORE_JOB_EVENT =
        "AFTER_TERMINAL_JOURNAL_COMMIT_BEFORE_JOB_EVENT"

    private val SUPPORTED_FAILPOINTS = setOf(
        BEFORE_INSTALL_EXTRACTION,
        BEFORE_ACTIVE_POINTER_WRITE,
        ACTIVE_AFTER_TEMP_SYNC_BEFORE_ATOMIC_REPLACE,
        ACTIVE_AFTER_ATOMIC_REPLACE_BEFORE_DIRECTORY_SYNC,
        AFTER_TERMINAL_JOURNAL_COMMIT_BEFORE_JOB_EVENT,
    )
}
