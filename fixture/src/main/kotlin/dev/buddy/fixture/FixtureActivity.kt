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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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
            id =
                android.view.View.generateViewId()
        }
        layout.addView(editor)
        val result = text(savedInstanceState?.getString("result") ?: getString(R.string.fixture_empty))
        layout.addView(
            Button(this).apply {
                text = getString(R.string.fixture_send)
                setOnClickListener {
                    result.text =
                        getString(R.string.fixture_sent, editor.text.toString())
                }
            }
        )
        layout.addView(result)
        layout.addView(
            Button(this).apply {
                text = getString(R.string.fixture_protected)
                setOnClickListener {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    val password = EditText(this@FixtureActivity).apply {
                        hint = getString(R.string.fixture_password)
                        inputType =
                            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    }
                    layout.addView(password)
                }
            }
        )
        setContentView(layout)
    }
}
