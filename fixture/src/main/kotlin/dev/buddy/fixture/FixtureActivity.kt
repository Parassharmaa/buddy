package dev.buddy.fixture

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class FixtureActivity : Activity() {
    private lateinit var result: TextView
    private var protectedPage = false
    private var securePage = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setPadding(32, 72, 32, 32)
        }
        fun text(value: String) = TextView(this).apply {
            text = value
            textSize = 18f
            setPadding(0, 16, 0, 16)
        }
        layout.addView(text(getString(R.string.fixture_title)))
        layout.addView(
            text(getString(R.string.fixture_messages))
        )
        val editor = EditText(this).apply {
            hint = getString(R.string.fixture_draft)
            minHeight = 64
            id = R.id.fixture_draft_input
        }
        layout.addView(editor)
        result = text(savedInstanceState?.getString("result") ?: getString(R.string.fixture_empty)).apply {
            id = R.id.fixture_result
        }
        layout.addView(
            Button(this).apply {
                id = R.id.fixture_send_button
                text = getString(R.string.fixture_send)
                setOnClickListener {
                    result.text =
                        getString(R.string.fixture_sent, editor.text.toString())
                }
            }
        )
        layout.addView(result)
        fun showSecurePage() {
            securePage = true
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        fun showProtectedPage(secure: Boolean = true) {
            if (secure) showSecurePage()
            if (protectedPage) return
            protectedPage = true
            layout.addView(
                EditText(this).apply {
                    id = R.id.fixture_password_input
                    hint = getString(R.string.fixture_password)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    isSaveEnabled = false
                    importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
                }
            )
        }
        layout.addView(
            Button(this).apply {
                id = R.id.fixture_protected_button
                text = getString(R.string.fixture_protected)
                setOnClickListener { showProtectedPage() }
            }
        )
        layout.addView(
            Button(this).apply {
                id = R.id.fixture_credential_button
                text = getString(R.string.fixture_credential)
                setOnClickListener { showProtectedPage(secure = false) }
            }
        )
        layout.addView(
            Button(this).apply {
                id = R.id.fixture_secure_button
                text = getString(R.string.fixture_secure)
                setOnClickListener { showSecurePage() }
            }
        )
        if (savedInstanceState?.getBoolean("protected") == true) showProtectedPage(secure = false)
        if (savedInstanceState?.getBoolean("secure") == true) showSecurePage()
        setContentView(layout)
        layout.requestFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("result", result.text.toString())
        outState.putBoolean("protected", protectedPage)
        outState.putBoolean("secure", securePage)
        super.onSaveInstanceState(outState)
    }
}
