package dev.buddy.qualification

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowInsets
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
        showSession()
    }

    fun resetSession() {
        session.close()
        session = WebSession(this)
        showSession()
    }

    private fun showSession() {
        val view = session.attach(this)
        setContentView(view)
        view.setOnApplyWindowInsetsListener { target, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val layout = target.layoutParams as? ViewGroup.MarginLayoutParams
                ?: return@setOnApplyWindowInsetsListener insets
            if (layout.leftMargin != bars.left || layout.topMargin != bars.top ||
                layout.rightMargin != bars.right || layout.bottomMargin != bars.bottom
            ) {
                layout.setMargins(bars.left, bars.top, bars.right, bars.bottom)
                target.layoutParams = layout
            }
            insets
        }
        view.requestApplyInsets()
    }
    override fun onDestroy() {
        session.close()
        super.onDestroy()
    }
}
