package dev.buddy

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

// Drain synthetic fixture frames without copying pixels or saving screenshots.
internal class FixtureFrameSink : Closeable {
    private val reader = ImageReader.newInstance(720, 1280, PixelFormat.RGBA_8888, 2)
    private val thread = HandlerThread("fixture-frame-drain").apply { start() }
    private val lock = Any()
    private var closed = false
    private val frames = AtomicInteger()
    val surface get() = reader.surface
    val count get() = frames.get()
    init {
        reader.setOnImageAvailableListener({ source ->
            synchronized(lock) {
                if (!closed) source.acquireLatestImage()?.use { frames.incrementAndGet() }
            }
        }, Handler(thread.looper))
    }
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            reader.setOnImageAvailableListener(null, null)
            reader.close()
        }
        thread.quitSafely()
    }
}
