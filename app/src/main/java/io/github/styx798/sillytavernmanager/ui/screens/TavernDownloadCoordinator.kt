package io.github.styx798.sillytavernmanager.ui.screens

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebView
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Locale
import org.json.JSONObject

internal enum class TavernDownloadKind {
    LOOPBACK_HTTP,
    LOOPBACK_BLOB,
}

internal data class TavernDownloadRequest(
    val url: String,
    val suggestedName: String,
    val mimeType: String,
    val userAgent: String?,
    val kind: TavernDownloadKind,
) {
    companion object {
        fun fromWebView(
            origin: TavernLoopbackOrigin,
            url: String,
            contentDisposition: String?,
            mimeType: String?,
            userAgent: String?,
        ): TavernDownloadRequest? {
            val kind = when {
                origin.allowsHttpResource(url) -> TavernDownloadKind.LOOPBACK_HTTP
                origin.allowsBlobResource(url) -> TavernDownloadKind.LOOPBACK_BLOB
                else -> return null
            }
            val normalizedMime = mimeType
                ?.substringBefore(';')
                ?.trim()
                ?.takeIf { it.matches(MIME_PATTERN) }
                ?: "application/octet-stream"
            return TavernDownloadRequest(
                url = url,
                suggestedName = safeDownloadName(
                    url = url,
                    contentDisposition = contentDisposition,
                    mimeType = normalizedMime,
                ),
                mimeType = normalizedMime,
                userAgent = userAgent,
                kind = kind,
            )
        }

        private val MIME_PATTERN =
            Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")
    }
}

internal sealed interface TavernDownloadEvent {
    data object DestinationRequested : TavernDownloadEvent

    data class Completed(
        val displayName: String,
        val bytes: Long,
    ) : TavernDownloadEvent

    data object Cancelled : TavernDownloadEvent

    data object Rejected : TavernDownloadEvent

    data class Failed(val reason: String) : TavernDownloadEvent
}

internal class TavernDownloadCoordinator(
    context: Context,
    private val baseUrl: String,
    private val origin: TavernLoopbackOrigin,
    private val onEvent: (TavernDownloadEvent) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("STM-WebDownload").apply { start() }
    private val workerHandler = Handler(workerThread.looper)
    private val bridgeMarker = "stmBlob_" + randomToken()
    private val lock = Any()

    private var destinationLauncher: ((Intent) -> Unit)? = null
    private var attachedWebView: WebView? = null
    private var pendingRequest: TavernDownloadRequest? = null
    private var activeTransfer: ActiveTransfer? = null
    private var closed = false

    fun setDestinationLauncher(launcher: (Intent) -> Unit) {
        destinationLauncher = launcher
    }

    fun attach(webView: WebView) {
        check(Looper.myLooper() == Looper.getMainLooper())
        attachedWebView = webView
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            beginDownload(
                url = url,
                userAgent = userAgent,
                contentDisposition = contentDisposition,
                mimeType = mimeType,
            )
        }
    }

    fun installBlobCaptureHook(webView: WebView, pageUrl: String) {
        if (webView !== attachedWebView || !origin.allowsHttpResource(pageUrl)) return
        webView.evaluateJavascript(blobCaptureScript(), null)
    }

    fun detach(webView: WebView) {
        if (attachedWebView === webView) {
            webView.setDownloadListener(null)
            cancelPending(deleteDestination = true)
            attachedWebView = null
        }
    }

    fun onDestinationResult(destination: Uri?) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val request = synchronized(lock) {
            pendingRequest.also { pendingRequest = null }
        } ?: return
        if (destination == null) {
            releaseBlob(request)
            emit(TavernDownloadEvent.Cancelled)
            return
        }
        val output = runCatching {
            requireNotNull(resolver.openOutputStream(destination, "w")) {
                "The selected document provider returned no output stream"
            }
        }.getOrElse { error ->
            deleteDestination(destination)
            releaseBlob(request)
            emit(TavernDownloadEvent.Failed(error.message ?: "Could not open destination"))
            return
        }
        val transfer = ActiveTransfer(
            token = randomToken(),
            request = request,
            destination = destination,
            output = output,
            displayName = queryDisplayName(destination) ?: request.suggestedName,
        )
        synchronized(lock) {
            if (closed || activeTransfer != null) {
                runCatching { output.close() }
                deleteDestination(destination)
                releaseBlob(request)
                emit(TavernDownloadEvent.Failed("Another download is already active"))
                return
            }
            activeTransfer = transfer
        }
        when (request.kind) {
            TavernDownloadKind.LOOPBACK_HTTP -> startHttpTransfer(transfer)
            TavernDownloadKind.LOOPBACK_BLOB -> startBlobTransfer(transfer)
        }
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        closed = true
        cancelPending(deleteDestination = true)
        attachedWebView?.setDownloadListener(null)
        attachedWebView = null
        destinationLauncher = null
        workerThread.quitSafely()
    }

    private fun beginDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        if (closed || activeTransfer != null || pendingRequest != null) {
            emit(TavernDownloadEvent.Rejected)
            return
        }
        val request = TavernDownloadRequest.fromWebView(
            origin = origin,
            url = url,
            contentDisposition = contentDisposition,
            mimeType = mimeType,
            userAgent = userAgent,
        )
        if (request == null) {
            emit(TavernDownloadEvent.Rejected)
            return
        }
        pendingRequest = request
        if (request.kind == TavernDownloadKind.LOOPBACK_BLOB) {
            resolveBlobMetadata(request)
            return
        }
        launchDestination(request)
    }

    private fun resolveBlobMetadata(request: TavernDownloadRequest) {
        val webView = attachedWebView
        if (webView == null) {
            pendingRequest = null
            releaseBlob(request)
            emit(TavernDownloadEvent.Failed("SillyTavern WebView is unavailable"))
            return
        }
        val script =
            "window[${JSONObject.quote(bridgeMarker)}]?.describe(" +
                "${JSONObject.quote(request.url)}) ?? null"
        webView.evaluateJavascript(script) { raw ->
            val resolved = blobRequestWithPageMetadata(request, raw)
            val stillPending = synchronized(lock) {
                if (pendingRequest !== request) {
                    false
                } else {
                    pendingRequest = resolved
                    true
                }
            }
            if (stillPending) launchDestination(resolved)
        }
    }

    private fun launchDestination(request: TavernDownloadRequest) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = request.mimeType
            putExtra(Intent.EXTRA_TITLE, request.suggestedName)
        }
        val launcher = destinationLauncher
        if (launcher == null) {
            pendingRequest = null
            releaseBlob(request)
            emit(TavernDownloadEvent.Failed("System document picker is unavailable"))
            return
        }
        emit(TavernDownloadEvent.DestinationRequested)
        runCatching { launcher(intent) }.onFailure { error ->
            pendingRequest = null
            releaseBlob(request)
            emit(TavernDownloadEvent.Failed(error.message ?: "Could not open document picker"))
        }
    }

    private fun startHttpTransfer(transfer: ActiveTransfer) {
        val cookie = CookieManager.getInstance().getCookie(baseUrl)
        workerHandler.post {
            var connection: HttpURLConnection? = null
            runCatching {
                connection = URL(transfer.request.url)
                    .openConnection(Proxy.NO_PROXY) as HttpURLConnection
                requireNotNull(connection).apply {
                    connectTimeout = HTTP_TIMEOUT_MILLIS
                    readTimeout = HTTP_TIMEOUT_MILLIS
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty("Origin", baseUrl)
                    transfer.request.userAgent?.let { setRequestProperty("User-Agent", it) }
                    cookie?.let { setRequestProperty("Cookie", it) }
                }
                val code = requireNotNull(connection).responseCode
                check(code in 200..299) { "SillyTavern download returned HTTP $code" }
                requireNotNull(connection).inputStream.use { input ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        transfer.write(buffer, count)
                    }
                }
                finishTransfer(transfer)
            }.onFailure { error ->
                failTransfer(transfer, error.message ?: "HTTP download failed")
            }
            connection?.disconnect()
        }
    }

    private fun startBlobTransfer(transfer: ActiveTransfer) {
        val webView = attachedWebView
        if (webView == null) {
            failTransfer(transfer, "SillyTavern WebView is unavailable")
            return
        }
        val ports = webView.createWebMessageChannel()
        transfer.nativePort = ports[0]
        ports[0].setWebMessageCallback(
            object : WebMessagePort.WebMessageCallback() {
                override fun onMessage(port: WebMessagePort, message: WebMessage) {
                    handleBlobMessage(transfer, message.data.orEmpty())
                }
            },
            workerHandler,
        )
        val init = JSONObject()
            .put("marker", bridgeMarker)
            .put("token", transfer.token)
            .put("url", transfer.request.url)
            .toString()
        webView.postWebMessage(
            WebMessage(init, arrayOf(ports[1])),
            Uri.parse(baseUrl),
        )
        workerHandler.postDelayed(
            { failTransfer(transfer, "Blob download timed out") },
            BLOB_TRANSFER_TIMEOUT_MILLIS,
        )
    }

    private fun handleBlobMessage(transfer: ActiveTransfer, raw: String) {
        if (!isActive(transfer)) return
        val message = runCatching { JSONObject(raw) }.getOrElse {
            failTransfer(transfer, "Blob transfer sent malformed data")
            return
        }
        if (message.optString("token") != transfer.token) {
            failTransfer(transfer, "Blob transfer identity mismatch")
            return
        }
        when (message.optString("type")) {
            "meta" -> {
                val size = message.optLong("size", -1L)
                if (size !in 0..MAX_DOWNLOAD_BYTES || transfer.expectedBytes != null) {
                    failTransfer(transfer, "Blob download size is invalid")
                    return
                }
                transfer.expectedBytes = size
            }

            "chunk" -> {
                val expected = transfer.expectedBytes
                val sequence = message.optInt("sequence", -1)
                val encoded = message.optString("data")
                if (expected == null ||
                    sequence != transfer.nextSequence ||
                    encoded.length > MAX_BASE64_CHARS
                ) {
                    failTransfer(transfer, "Blob download sequence is invalid")
                    return
                }
                val decoded = runCatching {
                    Base64.decode(encoded, Base64.DEFAULT)
                }.getOrElse {
                    failTransfer(transfer, "Blob download chunk is invalid")
                    return
                }
                runCatching { transfer.write(decoded, decoded.size) }.onFailure {
                    failTransfer(transfer, it.message ?: "Could not write Blob download")
                    return
                }
                transfer.nextSequence += 1
            }

            "complete" -> {
                if (transfer.expectedBytes == null ||
                    transfer.bytesWritten != transfer.expectedBytes
                ) {
                    failTransfer(transfer, "Blob download length mismatch")
                    return
                }
                finishTransfer(transfer)
            }

            "error" -> failTransfer(
                transfer,
                message.optString("message").ifBlank { "Blob download failed" },
            )

            else -> failTransfer(transfer, "Blob transfer sent an unsupported message")
        }
    }

    private fun finishTransfer(transfer: ActiveTransfer) {
        if (!claimTransfer(transfer)) return
        runCatching {
            transfer.output.flush()
            transfer.output.close()
        }.onFailure { error ->
            deleteDestination(transfer.destination)
            releaseBlob(transfer.request)
            closePort(transfer)
            emit(TavernDownloadEvent.Failed(error.message ?: "Could not finish download"))
            return
        }
        releaseBlob(transfer.request)
        closePort(transfer)
        emit(TavernDownloadEvent.Completed(transfer.displayName, transfer.bytesWritten))
    }

    private fun failTransfer(transfer: ActiveTransfer, reason: String) {
        if (!claimTransfer(transfer)) return
        runCatching { transfer.output.close() }
        deleteDestination(transfer.destination)
        releaseBlob(transfer.request)
        closePort(transfer)
        emit(TavernDownloadEvent.Failed(reason))
    }

    private fun cancelPending(deleteDestination: Boolean) {
        val pending: TavernDownloadRequest?
        val active: ActiveTransfer?
        synchronized(lock) {
            pending = pendingRequest
            pendingRequest = null
            active = activeTransfer
            activeTransfer = null
        }
        pending?.let(::releaseBlob)
        active?.let { transfer ->
            runCatching { transfer.output.close() }
            if (deleteDestination) deleteDestination(transfer.destination)
            releaseBlob(transfer.request)
            closePort(transfer)
        }
    }

    private fun claimTransfer(transfer: ActiveTransfer): Boolean = synchronized(lock) {
        if (activeTransfer !== transfer) {
            false
        } else {
            activeTransfer = null
            true
        }
    }

    private fun isActive(transfer: ActiveTransfer): Boolean = synchronized(lock) {
        activeTransfer === transfer
    }

    private fun closePort(transfer: ActiveTransfer) {
        runCatching { transfer.nativePort?.close() }
        transfer.nativePort = null
    }

    private fun releaseBlob(request: TavernDownloadRequest) {
        if (request.kind != TavernDownloadKind.LOOPBACK_BLOB) return
        val webView = attachedWebView ?: return
        val script =
            "window[${JSONObject.quote(bridgeMarker)}]?.release(" +
                "${JSONObject.quote(request.url)});"
        mainHandler.post {
            if (attachedWebView === webView) webView.evaluateJavascript(script, null)
        }
    }

    private fun emit(event: TavernDownloadEvent) {
        mainHandler.post { if (!closed) onEvent(event) }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun deleteDestination(uri: Uri) {
        val deletedAsDocument = runCatching {
            DocumentsContract.isDocumentUri(appContext, uri) &&
                DocumentsContract.deleteDocument(resolver, uri)
        }.getOrDefault(false)
        if (!deletedAsDocument) {
            runCatching { resolver.delete(uri, null, null) }
        }
    }

    private fun blobCaptureScript(): String {
        val marker = JSONObject.quote(bridgeMarker)
        return """
            (() => {
              const key = $marker;
              if (window[key]) return;
              const originalCreate = URL.createObjectURL.bind(URL);
              const originalRevoke = URL.revokeObjectURL.bind(URL);
              const originalAnchorClick = HTMLAnchorElement.prototype.click;
              const created = new Map();
              const held = new Map();
              const release = (url) => {
                const entry = held.get(url);
                if (entry) clearTimeout(entry.timer);
                held.delete(url);
                created.delete(url);
                try { originalRevoke(url); } catch (_) {}
              };
              URL.createObjectURL = (value) => {
                const url = originalCreate(value);
                if (value instanceof Blob) created.set(url, value);
                return url;
              };
              URL.revokeObjectURL = (url) => {
                if (held.has(url)) return;
                created.delete(url);
                originalRevoke(url);
              };
              const holdAnchorBlob = (anchor) => {
                const url = anchor.href;
                const blob = created.get(url);
                if (anchor.download && url.startsWith('blob:') && blob) {
                  const old = held.get(url);
                  if (old) clearTimeout(old.timer);
                  held.set(url, {
                    blob,
                    name: anchor.download,
                    timer: setTimeout(() => release(url), ${BLOB_HOLD_MILLIS}),
                  });
                }
              };
              HTMLAnchorElement.prototype.click = function() {
                holdAnchorBlob(this);
                return originalAnchorClick.call(this);
              };
              document.addEventListener('click', (event) => {
                const anchor = event.composedPath().find(
                  (item) => item instanceof HTMLAnchorElement,
                );
                if (anchor) holdAnchorBlob(anchor);
              }, true);
              window.addEventListener('message', async (event) => {
                let init;
                try { init = JSON.parse(event.data); } catch (_) { return; }
                if (!init || init.marker !== key || !event.ports?.length) return;
                const port = event.ports[0];
                const entry = held.get(init.url);
                const send = (value) => port.postMessage(JSON.stringify({
                  token: init.token,
                  ...value,
                }));
                if (!entry) {
                  send({ type: 'error', message: 'Blob is no longer available' });
                  port.close();
                  return;
                }
                try {
                  send({ type: 'meta', size: entry.blob.size, mime: entry.blob.type || '' });
                  const reader = entry.blob.stream().getReader();
                  let sequence = 0;
                  while (true) {
                    const part = await reader.read();
                    if (part.done) break;
                    for (let offset = 0; offset < part.value.length; offset += ${BLOB_CHUNK_BYTES}) {
                      const chunk = part.value.subarray(
                        offset,
                        Math.min(offset + ${BLOB_CHUNK_BYTES}, part.value.length),
                      );
                      let binary = '';
                      for (let index = 0; index < chunk.length; index++) {
                        binary += String.fromCharCode(chunk[index]);
                      }
                      send({ type: 'chunk', sequence, data: btoa(binary) });
                      sequence += 1;
                    }
                  }
                  send({ type: 'complete' });
                } catch (error) {
                  send({ type: 'error', message: String(error?.message || error) });
                } finally {
                  release(init.url);
                  port.close();
                }
              });
              const describe = (url) => {
                const entry = held.get(url);
                if (!entry) return null;
                return encodeURIComponent(entry.name);
              };
              window[key] = Object.freeze({ describe, release });
            })();
        """.trimIndent()
    }

    private class ActiveTransfer(
        val token: String,
        val request: TavernDownloadRequest,
        val destination: Uri,
        val output: OutputStream,
        val displayName: String,
    ) {
        var expectedBytes: Long? = null
        var bytesWritten: Long = 0L
        var nextSequence: Int = 0
        var nativePort: WebMessagePort? = null

        fun write(bytes: ByteArray, count: Int) {
            check(count >= 0 && bytesWritten <= MAX_DOWNLOAD_BYTES - count) {
                "Download exceeds the STM safety limit"
            }
            output.write(bytes, 0, count)
            bytesWritten += count
        }
    }

    private companion object {
        const val HTTP_TIMEOUT_MILLIS = 60_000
        const val BLOB_TRANSFER_TIMEOUT_MILLIS = 5L * 60L * 1_000L
        const val BLOB_HOLD_MILLIS = 5L * 60L * 1_000L
        const val STREAM_BUFFER_BYTES = 64 * 1024
        const val BLOB_CHUNK_BYTES = 48 * 1024
        const val MAX_BASE64_CHARS = 70 * 1024
        const val MAX_DOWNLOAD_BYTES = 4L * 1024L * 1024L * 1024L

        fun randomToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
        }
    }
}

internal fun safeDownloadName(
    url: String,
    contentDisposition: String?,
    mimeType: String,
): String {
    val encoded = contentDisposition
        ?.let {
            Regex("""filename\*\s*=\s*UTF-8''([^;]+)""", RegexOption.IGNORE_CASE)
                .find(it)
                ?.groupValues
                ?.get(1)
        }
    val quoted = contentDisposition
        ?.let {
            Regex("""filename\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
                .find(it)
                ?.groupValues
                ?.get(1)
        }
    val plain = contentDisposition
        ?.let {
            Regex("""filename\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE)
                .find(it)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.trim('"')
        }
    val pathName = runCatching {
        URI(url.removePrefix("blob:")).path.substringAfterLast('/').takeIf { it.isNotBlank() }
    }.getOrNull()
    val decoded = encoded?.let {
        runCatching { URLDecoder.decode(it, Charsets.UTF_8.name()) }.getOrNull()
    }
    return normalizedDownloadName(
        candidate = decoded ?: quoted ?: plain ?: pathName ?: "SillyTavern-download",
        mimeType = mimeType,
    )
}

internal fun blobRequestWithPageMetadata(
    request: TavernDownloadRequest,
    rawMetadata: String?,
): TavernDownloadRequest {
    val encodedName = rawMetadata
        ?.takeIf { it.length >= 2 && it.first() == '"' && it.last() == '"' }
        ?.substring(1, rawMetadata.length - 1)
        ?.takeIf { it.matches(ENCODED_PAGE_NAME_PATTERN) }
        ?: return request
    val pageName = runCatching {
        URLDecoder.decode(encodedName, Charsets.UTF_8.name())
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: return request
    return request.copy(
        suggestedName = normalizedDownloadName(pageName, request.mimeType),
    )
}

private val ENCODED_PAGE_NAME_PATTERN = Regex("""[A-Za-z0-9%_.!~*'()\-]+""")

private fun normalizedDownloadName(
    candidate: String,
    mimeType: String,
): String {
    var name = candidate
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("""[\u0000-\u001f\u007f/\\:]"""), "_")
        .trim()
        .trim('.')
        .take(120)
        .ifBlank { "SillyTavern-download" }
    if (!name.substringAfterLast('.', "").isNotBlank()) {
        val extension = when (mimeType.lowercase(Locale.ROOT)) {
            "application/zip" -> "zip"
            "application/json" -> "json"
            "image/png" -> "png"
            "audio/mpeg" -> "mp3"
            else -> null
        }
        if (extension != null) name += ".$extension"
    }
    return name
}
