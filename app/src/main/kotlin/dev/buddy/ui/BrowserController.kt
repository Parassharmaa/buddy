package dev.buddy.ui

import android.content.Context
import android.net.Uri
import android.view.View
import android.webkit.WebView
import dev.buddy.browser.PrivateWebHost
import dev.buddy.browser.WebAddress
import dev.buddy.browser.WebReadResult
import dev.buddy.browser.WebSession
import dev.buddy.browser.WebState
import dev.buddy.browser.WebStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class BrowserPanelState(
    val web: WebState = WebState(),
    val hasSession: Boolean = false,
    val reading: Boolean = false,
    val result: WebReadResult? = null,
    val error: String? = null
)

internal class BrowserController(context: Context?, private val scope: CoroutineScope) {
    private var application = context?.applicationContext
    private var session: WebSession? = null
    private var collector: Job? = null
    private var reader: Job? = null
    private var pending: WebAddress? = null
    private var privateHost: PrivateWebHost? = null
    private var foreground = true
    private var showing = false
    private val _state = MutableStateFlow(BrowserPanelState())
    val state = _state.asStateFlow()

    private fun ensure(context: Context): WebSession {
        application = context.applicationContext
        val existing = session
        if (existing != null && !existing.isClosed) return existing
        collector?.cancel()
        return WebSession(context).also { created ->
            session = created
            collector = scope.launch {
                created.state.collect { value ->
                    if (session === created) {
                        if (created.isClosed) {
                            privateHost?.close()
                            privateHost = null
                        }
                        _state.update { it.copy(web = value, hasSession = !created.isClosed) }
                    }
                }
            }
        }
    }
    fun open(context: Context, input: String) {
        try {
            val address = WebAddress.parse(input.trim())
            val current = ensure(context)
            reader?.cancel()
            pending = address
            _state.update {
                it.copy(
                    hasSession = true,
                    web = it.web.copy(status = WebStatus.LOADING, address = address.url),
                    result = null,
                    error = null
                )
            }
            if (current.hasWindow) loadPending()
        } catch (_: Exception) {
            _state.update {
                it.copy(error = "Enter a complete http:// or https:// website address without a username or password.")
            }
        }
    }
    fun attach(context: Context): WebView {
        val current = ensure(context)
        showing = true
        return current.attach(context).also { view ->
            privateHost?.close()
            privateHost = null
            if (view.isAttachedToWindow) {
                loadPending()
            } else {
                view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) {
                        view.removeOnAttachStateChangeListener(this)
                        loadPending()
                    }
                    override fun onViewDetachedFromWindow(view: View) = Unit
                })
            }
        }
    }
    fun loadPending() {
        val address = pending ?: return
        pending = null
        session?.navigate(address)
    }
    fun detach() {
        showing = false
        reader?.cancel()
        val current = session ?: return
        if (foreground && !current.isClosed && current.state.value.canMute &&
            current.state.value.address.isNotEmpty()
        ) {
            try {
                val host = privateHost ?: PrivateWebHost.create(requireNotNull(application)).also { privateHost = it }
                host.park(current)
            } catch (_: Exception) {
                privateHost?.close()
                privateHost = null
                current.detach()
                _state.update { it.copy(error = "This device paused the page. Open Browser to continue.") }
            }
        } else {
            current.detach()
        }
    }
    fun foreground(active: Boolean) {
        foreground = active
        if (!active) {
            reader?.cancel()
            session?.detach()
            return
        }
        session?.resume(userControl = showing)
        if (session?.hasWindow == true) loadPending()
    }
    fun allowNavigation() {
        session?.allowPendingNavigation()
    }
    fun cancelNavigation() {
        session?.cancelPendingNavigation()
    }
    fun sound(allowed: Boolean) {
        session?.setSoundAllowed(allowed)
    }
    fun cancelUpload() {
        session?.cancelUpload()
    }
    fun chooseFiles(uris: List<Uri>) {
        session?.chooseFiles(uris)
    }
    fun acceptedUploadTypes() = session?.acceptedUploadTypes().orEmpty()
    fun acceptsMultipleUploads() = session?.acceptsMultipleUploads() == true
    fun dismissError() {
        _state.update { it.copy(error = null) }
    }
    suspend fun source(): WebReadResult {
        val current = session ?: error("No page")
        val origin = WebAddress.parse(current.state.value.address).origin
        return current.observe(origin)
    }
    fun read() {
        val current = session ?: return
        if (_state.value.reading) return
        val origin = runCatching { WebAddress.parse(_state.value.web.address).origin }.getOrNull() ?: return
        _state.update { it.copy(reading = true, result = null, error = null) }
        reader = scope.launch {
            try {
                val result = current.observe(origin)
                if (session === current) _state.update { it.copy(result = result) }
            } catch (
                cancelled: CancellationException
            ) {
                throw cancelled
            } catch (
                _: Exception
            ) {
                _state.update { it.copy(error = "This page could not be read. Try opening it again.") }
            } finally {
                _state.update { it.copy(reading = false) }
            }
        }
    }
    fun close() {
        reader?.cancel()
        collector?.cancel()
        privateHost?.close()
        privateHost = null
        session?.close()
        session = null
        pending = null
        _state.value = BrowserPanelState()
    }
    suspend fun clearData() = withContext(Dispatchers.Main.immediate) {
        reader?.cancel()
        reader?.join()
        collector?.cancel()
        privateHost?.close()
        privateHost = null
        val current = session ?: application?.let(::WebSession)
        session = null
        pending = null
        try {
            current?.clearProfile()
        } finally {
            _state.value = BrowserPanelState()
        }
    }
}
