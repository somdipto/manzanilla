package com.agentdeck.hotel

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * The staff key surface. Opened by a notification from the reception gear, because the
 * WebView owns no credentials of its own. The key is never shown here and never logged.
 */
class KeyEntryActivity : Activity() {
    private val store by lazy { SecretStore(this, "fish") }
    private lateinit var field: EditText

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val dark = getSharedPreferences("deck", MODE_PRIVATE).getString("reception_appearance", "light") == "dark"
        val bg = if (dark) Color.rgb(16, 16, 18) else Color.rgb(235, 232, 225)
        val ink = if (dark) Color.rgb(244, 243, 239) else Color.rgb(36, 36, 38)
        val muted = if (dark) Color.rgb(154, 153, 148) else Color.rgb(107, 105, 100)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val status = TextView(this).apply {
            text = if (store.has()) "A Fish Audio key is already saved on this tablet. Saving a new one replaces it." else "No Fish Audio key is saved. Live duplex is unavailable until one is."
            textSize = 15f
            setTextColor(muted)
            gravity = Gravity.CENTER
        }
        field = EditText(this).apply {
            hint = "Fish Audio key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setTextColor(ink)
            setHintTextColor(muted)
            textSize = 17f
            setSingleLine()
        }
        val save = Button(this).apply {
            text = "Save"
            setOnClickListener { commit() }
        }
        val cancel = Button(this).apply {
            text = "Cancel"
            setOnClickListener { finish() }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bg)
            setPadding(40, 32, 40, 32)
            addView(TextView(this@KeyEntryActivity).apply {
                text = "Staff setup"
                textSize = 24f
                setTextColor(ink)
                gravity = Gravity.CENTER
            })
            addView(status)
            addView(field, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 24 })
            addView(save, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 24 })
            addView(cancel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12 })
        }
        setContentView(column)
    }

    private fun commit() {
        val value = field.text.toString().trim()
        if (value.length < 20 || value.any { it.isWhitespace() }) {
            Toast.makeText(this, "That is not a Fish Audio key. It must be at least 20 characters with no spaces.", Toast.LENGTH_LONG).show()
            return
        }
        if (store.save(value)) {
            field.setText("")
            setResult(RESULT_OK, Intent().putExtra("saved", true))
            finish()
        } else {
            Toast.makeText(this, "The key could not be saved on this tablet.", Toast.LENGTH_LONG).show()
        }
    }
}
