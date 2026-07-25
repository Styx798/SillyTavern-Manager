package io.github.styx798.sillytavernmanager.ui.screens

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import io.github.styx798.sillytavernmanager.R
import io.github.styx798.sillytavernmanager.core.stmcore.StmCoreConnectionState
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
    if (connectionState != StmCoreConnectionState.CONNECTED ||
        !coreState.canOpenTavern ||
        baseUrl == null ||
        sessionId == null
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
            modifier = modifier.fillMaxSize(),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SillyTavernWebView(
    baseUrl: String,
    modifier: Modifier = Modifier,
) {
    val origin = remember(baseUrl) { TavernLoopbackOrigin.fromCoreUrl(baseUrl) }
    var pageError by remember(baseUrl) { mutableStateOf<String?>(null) }
    Box(modifier = modifier) {
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
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.safeBrowsingEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            if (!request.isForMainFrame) return false
                            val allowed = origin.allowsMainFrame(request.url.toString())
                            if (!allowed) {
                                pageError = context.getString(R.string.tavern_navigation_blocked)
                            }
                            return !allowed
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            if (origin.allowsMainFrame(url)) pageError = null
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
                            pageError = context.getString(R.string.tavern_renderer_gone)
                            view.destroy()
                            return true
                        }
                    }
                    doOnLayout {
                        loadUrl(baseUrl)
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { webView ->
                webView.stopLoading()
                webView.webChromeClient = null
                webView.webViewClient = WebViewClient()
                webView.removeAllViews()
                webView.destroy()
            },
        )

        pageError?.let { detail ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = {
                    TextButton(onClick = { pageError = null }) {
                        Text(stringResource(R.string.action_dismiss))
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

internal class TavernLoopbackOrigin private constructor(
    private val port: Int,
) {
    fun allowsMainFrame(url: String): Boolean {
        if (url == "about:blank") return true
        val candidate = runCatching { URI(url) }.getOrNull() ?: return false
        return candidate.scheme.equals("http", ignoreCase = true) &&
            candidate.host == LOOPBACK_HOST &&
            candidate.port == port &&
            candidate.userInfo == null
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
