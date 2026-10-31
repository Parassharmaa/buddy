package dev.buddy.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Looper
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class WebSession(context: Context) : WebPort {
    private val application = context.applicationContext
    private val wrapper = MutableContextWrapper(application)
    private val _state = MutableStateFlow(WebState())
    override val state = _state.asStateFlow()
    private val observerKey = "buddy_" + UUID.randomUUID().toString().replace("-", "")
    private val observer = application.assets.open("buddy_observer.js").bufferedReader().use { it.readText() }
        .replace("__BUDDY_OBSERVER_KEY__", observerKey)
    private var script: ScriptHandler? = null
    private var view: WebView? = null
    private var approvedOrigin: String? = null
    private var pendingAddress: WebAddress? = null
    private var attached = false
    private var userVisible = false
    private var beforePause = WebStatus.EMPTY
    private var pageFailed = false
    private var closed = false
    private var generation = 0L
    private var upload: ValueCallback<Array<Uri>>? = null
    private var uploadGeneration = -1L
    private var uploadTypes = emptyList<String>()
    private var multipleUploads = false
    private var downloadGeneration = -1L
    val isClosed: Boolean get() = closed
    val hasWindow: Boolean get() = attached && view?.isAttachedToWindow == true
    private var excludedOrigins = emptySet<String>()

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        maxNestingDepth = 16
        exceptionsWithDebugInfo = false
    }

    private fun mainThread() {
        check(Looper.myLooper() == Looper.getMainLooper())
    }
    fun attach(context: Context, userControl: Boolean = true): WebView {
        mainThread()
        check(!closed)
        wrapper.baseContext = context
        attached = true
        userVisible = userControl
        val current = view ?: createView().also { view = it }
        (current.parent as? ViewGroup)?.removeView(current)
        current.onResume()
        current.resumeTimers()
        if (_state.value.status == WebStatus.PAUSED) _state.value = _state.value.copy(status = beforePause)
        return current
    }
    fun detach() {
        mainThread()
        if (closed || !attached) return
        if (!_state.value.canMute) {
            // Old providers cannot guarantee audio suppression when a page loses user control.
            close()
            return
        }
        attached = false
        userVisible = false
        beforePause = _state.value.status
        cancelUpload()
        cancelDownload()
        view?.let {
            it.clearFocus()
            mute(it, true)
            it.onPause()
            it.pauseTimers()
        }
        wrapper.baseContext = application
        if (!closed) _state.value = _state.value.copy(status = WebStatus.PAUSED, soundAllowed = false)
    }
    fun resume(userControl: Boolean = true) {
        mainThread()
        val current = view ?: return
        if (closed || !current.isAttachedToWindow || attached) return
        attached = true
        userVisible = userControl
        current.onResume()
        current.resumeTimers()
        if (_state.value.status == WebStatus.PAUSED) _state.value = _state.value.copy(status = beforePause)
    }
    fun navigate(address: WebAddress) {
        mainThread()
        check(!closed && attached)
        if (address.origin in excludedOrigins) {
            _state.value = _state.value.copy(status = WebStatus.BLOCKED)
            return
        }
        val current = requireNotNull(view)
        generation++
        setSoundAllowed(false)
        cancelUpload()
        cancelDownload()
        approvedOrigin = address.origin
        pendingAddress = null
        script?.remove()
        script = if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(current, observer, setOf(address.origin))
        } else {
            null
        }
        _state.value =
            _state.value.copy(
                status = WebStatus.LOADING,
                address = address.url,
                title = "",
                pendingOrigin = null,
                interruption = null
            )
        current.loadUrl(address.url)
    }
    fun allowPendingNavigation() {
        mainThread()
        pendingAddress?.let(::navigate)
    }
    fun cancelPendingNavigation() {
        mainThread()
        pendingAddress = null
        _state.value = _state.value.copy(status = WebStatus.READY, pendingOrigin = null)
    }
    fun excludeOrigin(origin: String) {
        mainThread()
        require(WebAddress.parse(origin).origin == origin)
        excludedOrigins = excludedOrigins + origin
        if (approvedOrigin == origin) {
            generation++
            view?.stopLoading()
            cancelUpload()
            _state.value = _state.value.copy(status = WebStatus.BLOCKED)
        }
    }
    fun setSoundAllowed(allowed: Boolean) {
        mainThread()
        val enabled = allowed && attached && userVisible && _state.value.canMute
        view?.let { mute(it, !enabled) }
        _state.value = _state.value.copy(soundAllowed = enabled)
    }
    fun acceptedUploadTypes(): List<String> = uploadTypes
    fun acceptsMultipleUploads(): Boolean = multipleUploads
    fun chooseFiles(uris: List<Uri>) {
        mainThread()
        val callback = upload
        val valid = callback != null && attached && uploadGeneration == generation &&
            (multipleUploads || uris.size == 1) && uris.size in 1..16 && uris.all {
                it.scheme == "content" && it.authority == application.packageName + ".artifacts" &&
                    it.query == null && it.fragment == null && it.pathSegments.size == 2
            }
        upload = null
        _state.value = _state.value.copy(hasUploadRequest = false)
        callback?.onReceiveValue(if (valid) uris.toTypedArray() else null)
    }
    fun cancelUpload() {
        mainThread()
        upload?.onReceiveValue(null)
        upload = null
        uploadTypes = emptyList()
        _state.value = _state.value.copy(hasUploadRequest = false)
    }
    fun cancelDownload() {
        mainThread()
        _state.value = _state.value.copy(download = null)
    }
    fun approveDownload(id: String): WebDownloadGrant? {
        mainThread()
        val request = _state.value.download ?: return null
        if (request.id != id || closed || !hasWindow || !userVisible || downloadGeneration != generation ||
            request.address.origin != approvedOrigin ||
            view?.url?.let { runCatching { WebAddress.parse(it).origin }.getOrNull() } != approvedOrigin
        ) {
            cancelDownload()
            return null
        }
        cancelDownload()
        val cookie = CookieManager.getInstance().getCookie(request.address.url)
        require(cookie == null || cookie.length <= 16384)
        return WebDownloadGrant(request.address, cookie)
    }
    private fun needsHuman(reason: WebInterruption) {
        cancelUpload()
        cancelDownload()
        _state.value = _state.value.copy(status = WebStatus.HUMAN_REQUIRED, interruption = reason, title = "")
    }
    override suspend fun observe(expectedOrigin: String): WebReadResult = withContext(Dispatchers.Main.immediate) {
        val human = _state.value.interruption
        if (_state.value.status == WebStatus.HUMAN_REQUIRED && human != WebInterruption.VERIFICATION) {
            return@withContext WebReadResult.Suppressed(WebReadStatus.HUMAN_REQUIRED)
        }
        val readable = _state.value.status == WebStatus.READY ||
            (_state.value.status == WebStatus.HUMAN_REQUIRED && human == WebInterruption.VERIFICATION)
        if (closed || !attached || !readable || !_state.value.canRead ||
            expectedOrigin != approvedOrigin || expectedOrigin in excludedOrigins
        ) {
            return@withContext WebReadResult.Suppressed(WebReadStatus.UNSUPPORTED)
        }
        val current = requireNotNull(view)
        if (!current.isAttachedToWindow || current.width <= 0 || current.height <= 0) {
            return@withContext WebReadResult.Suppressed(WebReadStatus.STALE)
        }
        val observedUrl = current.url
        val address = observedUrl?.let { runCatching { WebAddress.parse(it) }.getOrNull() }
            ?: return@withContext WebReadResult.Suppressed(WebReadStatus.STALE)
        if (address.origin != expectedOrigin || credentialRoute(address.url)) {
            return@withContext WebReadResult.Suppressed(WebReadStatus.PRIVATE)
        }
        val ticket = generation
        val result = withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine<String?> { continuation ->
                current.evaluateJavascript("this[\"$observerKey\"] ? this[\"$observerKey\"]() : null") { value ->
                    if (continuation.isActive) continuation.resume(value)
                }
            }
        }
        if (closed || ticket != generation || !attached || current.url != observedUrl) {
            return@withContext WebReadResult.Suppressed(WebReadStatus.STALE)
        }
        runCatching {
            require(result != null && result.length <= 256 * 1024)
            val encoded = requireNotNull(json.parseToJsonElement(result).jsonPrimitive.contentOrNull)
            require(encoded.length <= 128 * 1024)
            val data = json.parseToJsonElement(encoded).jsonObject
            when (data["status"]?.jsonPrimitive?.contentOrNull) {
                "human" -> {
                    needsHuman(WebInterruption.VERIFICATION)
                    WebReadResult.Suppressed(WebReadStatus.HUMAN_REQUIRED)
                }

                "private" -> WebReadResult.Suppressed(WebReadStatus.PRIVATE)

                "allowed" -> {
                    val title = data.getValue("title").jsonPrimitive.content
                    val text = data.getValue("text").jsonPrimitive.content
                    require(title.length <= 256 && text.length <= 32768)
                    if (human == WebInterruption.VERIFICATION) {
                        _state.value = _state.value.copy(status = WebStatus.READY, interruption = null)
                    }
                    WebReadResult.Allowed(
                        WebSnapshot(
                            address.origin,
                            address.sourceLabel,
                            title,
                            text,
                            data["truncated"]?.jsonPrimitive?.booleanOrNull ?: false
                        )
                    )
                }

                else -> WebReadResult.Suppressed(WebReadStatus.UNSUPPORTED)
            }
        }.getOrElse { WebReadResult.Suppressed(WebReadStatus.UNSUPPORTED) }
    }

    suspend fun clearProfile() = withContext(Dispatchers.Main.immediate) {
        val completeDeletion = WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)
        closeView(clearCache = !completeDeletion)
        val storage = WebStorage.getInstance()
        withTimeoutOrNull(15_000) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    WebStorageCompat.deleteBrowsingData(storage, application.mainExecutor) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
            } else {
                storage.deleteAllData()
            }
            // Complete the cookie store's own asynchronous deletion after profile deletion.
            val cookies = CookieManager.getInstance()
            suspendCancellableCoroutine<Unit> { continuation ->
                cookies.removeAllCookies {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
            check(!cookies.hasCookies()) { "Website cookies remain after cleanup" }
        } ?: error("Website data cleanup incomplete")
    }

    fun close() = closeView(clearCache = true)
    private fun closeView(clearCache: Boolean) {
        mainThread()
        if (closed) return
        closed = true
        generation++
        attached = false
        cancelUpload()
        script?.remove()
        script = null
        view?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.stopLoading()
            if (clearCache) runCatching { it.clearCache(true) }
            runCatching { it.clearHistory() }
            it.destroy()
        }
        view = null
        wrapper.baseContext = application
        _state.value = WebState()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createView(): WebView = WebView(wrapper).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.safeBrowsingEnabled = true
        settings.mediaPlaybackRequiresUserGesture = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        val canMute = WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)
        val canObserve = canMute && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        _state.value = _state.value.copy(canMute = canMute, canRead = canObserve)
        mute(this, true)
        setDownloadListener { url, _, disposition, mime, length ->
            val address = runCatching { WebAddress.parse(url) }.getOrNull()
            if (!closed && userVisible && hasWindow && address != null && address.origin == approvedOrigin &&
                address.origin !in excludedOrigins && _state.value.download == null
            ) {
                downloadGeneration = generation
                _state.value = _state.value.copy(
                    download = WebDownloadRequest(
                        UUID.randomUUID().toString(),
                        address,
                        disposition.orEmpty().take(256),
                        mime.orEmpty().take(128),
                        length
                    )
                )
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val address = runCatching { WebAddress.parse(request.url.toString()) }.getOrNull()
                if (address == null || address.origin in excludedOrigins) {
                    _state.value = _state.value.copy(status = WebStatus.BLOCKED)
                    return true
                }
                if (request.isForMainFrame && address.origin != approvedOrigin) {
                    pendingAddress = address
                    _state.value = _state.value.copy(status = WebStatus.BLOCKED, pendingOrigin = address.origin)
                    return true
                }
                return false
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (closed) return
                val address = runCatching { WebAddress.parse(url) }.getOrNull()
                if (address == null || address.origin != approvedOrigin || address.origin in excludedOrigins) {
                    view.stopLoading()
                    if (address != null && address.origin !in excludedOrigins) pendingAddress = address
                    _state.value = _state.value.copy(status = WebStatus.BLOCKED, pendingOrigin = pendingAddress?.origin)
                    return
                }
                generation++
                pageFailed = false
                cancelUpload()
                cancelDownload()
                if (!closed) {
                    _state.value =
                        _state.value.copy(status = WebStatus.LOADING, address = url, interruption = null)
                }
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (closed || view !== this@WebSession.view || url != view.url ||
                    (_state.value.status == WebStatus.LOADING && url != _state.value.address)
                ) {
                    return
                }
                if (!closed) {
                    _state.value =
                        _state.value.copy(
                            status = if (pendingAddress !=
                                null
                            ) {
                                WebStatus.BLOCKED
                            } else if (_state.value.interruption != null) {
                                WebStatus.HUMAN_REQUIRED
                            } else if (pageFailed) {
                                WebStatus.FAILED
                            } else {
                                WebStatus.READY
                            },
                            address = url,
                            title = if (_state.value.interruption == null) view.title.orEmpty().take(256) else ""
                        )
                }
            }
            override fun onSafeBrowsingHit(
                view: WebView,
                request: WebResourceRequest,
                threatType: Int,
                response: SafeBrowsingResponse
            ) {
                response.backToSafety(false)
                pageFailed = true
                if (!closed) _state.value = _state.value.copy(status = WebStatus.BLOCKED)
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                pageFailed = true
                if (!closed) _state.value = _state.value.copy(status = WebStatus.FAILED)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!closed && request.isForMainFrame) {
                    pageFailed = true
                    _state.value = _state.value.copy(status = WebStatus.FAILED)
                }
            }
            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                response: WebResourceResponse
            ) {
                if (closed || !request.isForMainFrame ||
                    runCatching { WebAddress.parse(request.url.toString()).origin }.getOrNull() != approvedOrigin
                ) {
                    return
                }
                val reason = when (response.statusCode) {
                    401 -> WebInterruption.SIGN_IN
                    403 -> WebInterruption.ACCESS_DENIED
                    429 -> WebInterruption.RATE_LIMIT
                    else -> return
                }
                needsHuman(reason)
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (!closed) {
                    close()
                    _state.value = _state.value.copy(status = WebStatus.RENDERER_GONE)
                }
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean = true
            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, false, false)
            }
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                if (userVisible && !closed) return false
                result.cancel()
                return true
            }
            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                if (userVisible && !closed) return false
                result.cancel()
                return true
            }
            override fun onJsPrompt(
                view: WebView,
                url: String,
                message: String,
                defaultValue: String?,
                result: JsPromptResult
            ): Boolean {
                if (userVisible && !closed) return false
                result.cancel()
                return true
            }

            override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: JsResult): Boolean {
                if (userVisible && !closed) return false
                result.cancel()
                return true
            }
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                parameters: FileChooserParams
            ): Boolean {
                cancelUpload()
                if (!userVisible || closed || _state.value.status != WebStatus.READY ||
                    parameters.mode !in setOf(FileChooserParams.MODE_OPEN, FileChooserParams.MODE_OPEN_MULTIPLE)
                ) {
                    callback.onReceiveValue(null)
                    return true
                }
                upload = callback
                uploadGeneration = generation
                uploadTypes = parameters.acceptTypes.orEmpty().take(16).map { it.take(128) }
                multipleUploads = parameters.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                _state.value = _state.value.copy(hasUploadRequest = true)
                return true
            }
        }
    }
    private fun mute(view: WebView, muted: Boolean) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) WebViewCompat.setAudioMuted(view, muted)
    }
    private fun credentialRoute(url: String): Boolean {
        val path = Uri.parse(url).path.orEmpty().lowercase()
        return listOf(
            "login", "signin", "sign-in", "password", "oauth", "otp", "challenge", "api-key", "tokens", "callback"
        )
            .any { path.split('/').any { segment -> it in segment } }
    }
}
