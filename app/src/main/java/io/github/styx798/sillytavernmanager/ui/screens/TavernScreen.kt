package io.github.styx798.sillytavernmanager.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import io.github.styx798.sillytavernmanager.R
import io.github.styx798.sillytavernmanager.core.stmcore.StmCoreConnectionState
import io.github.styx798.sillytavernmanager.stmcore.STM_CORE_WEB_SESSION_COOKIE_NAME
import io.github.styx798.sillytavernmanager.stmcore.StmCoreState
import java.net.URI

@Composable
fun TavernScreen(
    coreState: StmCoreState,
    connectionState: StmCoreConnectionState,
    modifier: Modifier = Modifier,
) {
    val baseUrl = coreState.localBaseUrl
    val sessionId = coreState.sessionId
    val webSessionCredential = coreState.webSessionCredential
    if (connectionState != StmCoreConnectionState.CONNECTED ||
        !coreState.canOpenTavern ||
        baseUrl == null ||
        sessionId == null ||
        webSessionCredential == null
    ) {
        EmptyState(
            title = stringResource(R.string.tavern_unavailable),
            body = stringResource(R.string.tavern_unavailable_body),
            modifier = modifier,
        )
        return
    }

    key(sessionId, baseUrl) {
        SillyTavernWebView(
            baseUrl = baseUrl,
            webSessionCredential = webSessionCredential.value,
            modifier = modifier.fillMaxSize(),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SillyTavernWebView(
    baseUrl: String,
    webSessionCredential: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val origin = remember(baseUrl) { TavernLoopbackOrigin.fromCoreUrl(baseUrl) }
    val fileChooserState = remember(baseUrl) { TavernFileChooserState() }
    var downloadEvent by remember(baseUrl) { mutableStateOf<TavernDownloadEvent?>(null) }
    val downloadCoordinator = remember(baseUrl) {
        TavernDownloadCoordinator(
            context = context.applicationContext,
            baseUrl = baseUrl,
            origin = origin,
            onEvent = { event ->
                downloadEvent = when (event) {
                    TavernDownloadEvent.DestinationRequested,
                    TavernDownloadEvent.Cancelled,
                    -> null

                    else -> event
                }
            },
        )
    }
    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        fileChooserState.complete(
            if (result.resultCode == Activity.RESULT_OK) {
                WebChromeClient.FileChooserParams.parseResult(
                    result.resultCode,
                    result.data,
                )
            } else {
                null
            },
        )
    }
    val downloadDestinationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        downloadCoordinator.onDestinationResult(
            if (result.resultCode == Activity.RESULT_OK) result.data?.data else null,
        )
    }
    downloadCoordinator.setDestinationLauncher(downloadDestinationLauncher::launch)
    var pageError by remember(baseUrl) { mutableStateOf<String?>(null) }
    var recoveryState by remember(baseUrl) { mutableStateOf(TavernWebViewRecoveryState()) }
    DisposableEffect(fileChooserState) {
        onDispose(fileChooserState::cancel)
    }
    DisposableEffect(downloadCoordinator) {
        onDispose(downloadCoordinator::close)
    }
    val downloadMessage = when (val event = downloadEvent) {
        is TavernDownloadEvent.Completed -> stringResource(
            R.string.tavern_download_saved,
            event.displayName,
            event.bytes,
        )

        is TavernDownloadEvent.Failed -> stringResource(R.string.tavern_download_failed)
        TavernDownloadEvent.Rejected -> stringResource(R.string.tavern_download_rejected)
        TavernDownloadEvent.Cancelled,
        TavernDownloadEvent.DestinationRequested,
        null,
        -> null
    }
    Box(modifier = modifier) {
        key(recoveryState.generation) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        setBackgroundColor(Color.TRANSPARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.setSupportMultipleWindows(false)
                        settings.mixedContentMode =
                            android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.safeBrowsingEnabled = true
                        downloadCoordinator.attach(this)
                        webChromeClient = object : WebChromeClient() {
                            override fun onShowFileChooser(
                                webView: WebView,
                                filePathCallback: ValueCallback<Array<Uri>>,
                                fileChooserParams: FileChooserParams,
                            ): Boolean {
                                fileChooserState.begin(filePathCallback)
                                return runCatching {
                                    fileChooserLauncher.launch(fileChooserParams.createIntent())
                                }.fold(
                                    onSuccess = { true },
                                    onFailure = {
                                        fileChooserState.cancel()
                                        pageError = context.getString(
                                            R.string.tavern_file_chooser_error,
                                        )
                                        false
                                    },
                                )
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                if (!request.isForMainFrame) return false
                                val allowed = origin.allowsMainFrame(request.url.toString())
                                if (!allowed) {
                                    pageError =
                                        context.getString(R.string.tavern_navigation_blocked)
                                }
                                return !allowed
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                if (origin.allowsMainFrame(url)) {
                                    downloadCoordinator.installBlobCaptureHook(view, url)
                                    pageError = null
                                }
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError,
                            ) {
                                if (request.isForMainFrame) {
                                    pageError = context.getString(
                                        R.string.tavern_page_error,
                                        error.errorCode,
                                    )
                                }
                            }

                            override fun onRenderProcessGone(
                                view: WebView,
                                detail: RenderProcessGoneDetail,
                            ): Boolean {
                                fileChooserState.cancel()
                                downloadCoordinator.detach(view)
                                recoveryState = recoveryState.onRendererGone()
                                pageError = context.getString(R.string.tavern_renderer_gone)
                                view.tag = TavernDestroyedRendererTag
                                view.destroy()
                                return true
                            }
                        }
                        doOnLayout {
                            CookieManager.getInstance().apply {
                                setAcceptCookie(true)
                                setCookie(
                                    baseUrl,
                                    "$STM_CORE_WEB_SESSION_COOKIE_NAME=$webSessionCredential; " +
                                        "Path=/; HttpOnly; SameSite=Strict",
                                ) { accepted ->
                                    if (accepted) {
                                        loadUrl(baseUrl)
                                    } else {
                                        pageError = context.getString(
                                            R.string.tavern_session_cookie_error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { webView ->
                    downloadCoordinator.detach(webView)
                    if (webView.tag !== TavernDestroyedRendererTag) {
                        webView.stopLoading()
                        webView.webChromeClient = null
                        webView.webViewClient = WebViewClient()
                        webView.removeAllViews()
                        webView.destroy()
                    }
                },
            )
        }

        (pageError ?: downloadMessage)?.let { detail ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = {
                    TextButton(
                        onClick = {
                            if (recoveryState.rendererGone) {
                                recoveryState = recoveryState.reloadRenderer()
                            }
                            pageError = null
                            downloadEvent = null
                        },
                    ) {
                        Text(
                            stringResource(
                                if (recoveryState.rendererGone) {
                                    R.string.action_reload
                                } else {
                                    R.string.action_dismiss
                                },
                            ),
                        )
                    }
                },
            ) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

internal object TavernDestroyedRendererTag

internal class TavernFileChooserState {
    private var pending: ValueCallback<Array<Uri>>? = null

    fun begin(callback: ValueCallback<Array<Uri>>) {
        cancel()
        pending = callback
    }

    fun complete(result: Array<Uri>?) {
        pending?.onReceiveValue(result)
        pending = null
    }

    fun cancel() {
        complete(null)
    }
}

internal data class TavernWebViewRecoveryState(
    val generation: Int = 0,
    val rendererGone: Boolean = false,
) {
    fun onRendererGone(): TavernWebViewRecoveryState = copy(rendererGone = true)

    fun reloadRenderer(): TavernWebViewRecoveryState {
        check(rendererGone) { "Renderer reload is only valid after renderer exit" }
        return copy(
            generation = generation + 1,
            rendererGone = false,
        )
    }
}

internal class TavernLoopbackOrigin private constructor(
    private val port: Int,
) {
    fun allowsMainFrame(url: String): Boolean {
        if (url == "about:blank") return true
        return allowsHttpResource(url)
    }

    fun allowsHttpResource(url: String): Boolean {
        val candidate = runCatching { URI(url) }.getOrNull() ?: return false
        return candidate.scheme == "http" &&
            candidate.host == LOOPBACK_HOST &&
            candidate.port == port &&
            candidate.userInfo == null
    }

    fun allowsBlobResource(url: String): Boolean {
        if (!url.startsWith("blob:")) return false
        return allowsHttpResource(url.removePrefix("blob:"))
    }

    companion object {
        fun fromCoreUrl(baseUrl: String): TavernLoopbackOrigin {
            val uri = URI(baseUrl)
            require(
                uri.scheme == "http" &&
                    uri.host == LOOPBACK_HOST &&
                    uri.port in 1..65_535 &&
                    uri.userInfo == null &&
                    (uri.path.isNullOrEmpty() || uri.path == "/") &&
                    uri.query == null &&
                    uri.fragment == null,
            ) {
                "STM Core supplied an invalid SillyTavern loopback origin"
            }
            return TavernLoopbackOrigin(uri.port)
        }

        private const val LOOPBACK_HOST = "127.0.0.1"
    }
}
