package io.github.styx798.sillytavernmanager.stmcore

import com.caoccao.javet.interop.NodeRuntime
import com.caoccao.javet.interop.V8Host
import com.caoccao.javet.interop.options.NodeRuntimeOptions

internal object StmNodeRuntimeFactory {
    fun create(consoleArguments: Array<String>): NodeRuntime {
        val options = NodeRuntimeOptions()
            .setConsoleArguments(consoleArguments)
        val host = if (BuildConfig.JAVET_ARTIFACT.endsWith("-i18n")) {
            V8Host.getNodeI18nInstance()
        } else {
            V8Host.getNodeInstance()
        }
        val runtime: NodeRuntime = host.createV8Runtime(options)
        return try {
            // Javet creates direct runtimes with string code generation disabled. SillyTavern and
            // its production dependencies use both eval() and Function(), matching normal Node.js
            // semantics, so enable the public per-context Javet switch before any script runs.
            runtime.allowEval(true)
            runtime
        } catch (error: Throwable) {
            runCatching { runtime.close() }
            throw error
        }
    }
}
