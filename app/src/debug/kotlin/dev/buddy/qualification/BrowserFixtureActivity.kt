package dev.buddy.qualification

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import dev.buddy.browser.WebSession

/** Real window for WebView qualification; never included in release builds. */
class BrowserFixtureActivity : Activity() {
    lateinit var session: WebSession
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        session = WebSession(this)
        setContentView(session.attach(this))
    }

    fun resetSession() {
        session.close()
        session = WebSession(this)
        setContentView(session.attach(this))
    }

    override fun onDestroy() {
        session.close()
        super.onDestroy()
    }
}
