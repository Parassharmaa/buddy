package dev.buddy.browser

import android.app.Presentation
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** App-owned private display. Frames are drained and discarded without reading pixels. */
class PrivateWebHost private constructor(private val application: Context) : AutoCloseable {
    private val thread = HandlerThread("buddy-web-frames").apply { start() }
    private val handler = Handler(thread.looper)
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var session: WebSession? = null
    private var hostedView: WebView? = null
    private var closed = false
    private val frames = AtomicLong()
    val renderedFrames: Long get() = frames.get()

    companion object {
        fun create(context: Context, width: Int = 720, height: Int = 1280): PrivateWebHost {
            check(Looper.myLooper() == Looper.getMainLooper())
            return PrivateWebHost(context.applicationContext).also { host ->
                try {
                    host.start(width, height)
                } catch (failure: Throwable) {
                    host.close()
                    throw failure
                }
            }
        }
    }

    private fun frames(width: Int, height: Int): ImageReader {
        require(width in 320..1280 && height in 320..1920 && width.toLong() * height <= 1_048_576)
        return ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ source ->
                // Do not access image planes or retain screenshots of website contents.
                runCatching {
                    source.acquireLatestImage()?.use { frames.incrementAndGet() }
                }
            }, handler)
        }
    }

    private fun start(width: Int, height: Int) {
        val output = frames(width, height).also { reader = it }
        val manager = application.getSystemService(DisplayManager::class.java)
        val density = application.resources.displayMetrics.densityDpi.coerceIn(160, 640)
        val target = requireNotNull(
            manager.createVirtualDisplay(
                "Buddy private browser",
                width,
                height,
                density,
                output.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            )
        ).also { display = it }
        presentation =
            Presentation(application, target.display, android.R.style.Theme_DeviceDefault_NoActionBar).apply {
                setCancelable(false)
                requireNotNull(window).apply {
                    addFlags(
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    )
                }
            }
    }

    suspend fun park(current: WebSession) {
        check(Looper.myLooper() == Looper.getMainLooper() && !closed)
        check(session == null || session === current)
        current.detach()
        val window = requireNotNull(presentation)
        val previousFrames = renderedFrames
        val view = current.attach(window.context, userControl = false)
        hostedView = view
        session = current
        window.setContentView(view)
        window.show()
        window.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        withTimeout(5_000) {
            while (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0 ||
                renderedFrames <= previousFrames
            ) {
                check(!closed && !current.isClosed)
                delay(16)
            }
        }
    }

    fun resize(width: Int, height: Int) {
        check(Looper.myLooper() == Looper.getMainLooper() && !closed)
        val replacement = frames(width, height)
        val previous = reader
        try {
            val target = requireNotNull(display)
            target.resize(width, height, application.resources.displayMetrics.densityDpi.coerceIn(160, 640))
            target.surface = replacement.surface
            reader = replacement
        } catch (failure: Throwable) {
            replacement.close()
            throw failure
        }
        previous?.close()
        hostedView?.requestLayout()
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        closed = true
        // A handover may already have moved this same view to the user's Activity.
        val view = hostedView
        if (view != null && view.context is android.content.MutableContextWrapper &&
            (view.context as android.content.MutableContextWrapper).baseContext === presentation?.context
        ) {
            session?.detach()
            (view.parent as? ViewGroup)?.removeView(view)
        }
        presentation?.dismiss()
        presentation = null
        display?.release()
        display = null
        reader?.close()
        reader = null
        thread.quitSafely()
        session = null
        hostedView = null
    }
}
