package com.agentdeck.hotel

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Staff setup trusts only Android's enrolled device credential, never an Intent or shared PIN. */
class KeyEntryActivity : Activity() {
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
    private var authenticated = false
    private var authenticationInFlight = false
    private var authenticationAccepted = false
    private var foreground = false
    private val fields = ArrayList<EditText>()
    private var dialog: AlertDialog? = null
    private val background = Color.rgb(8, 15, 21)
    private val ink = Color.rgb(229, 237, 243)

    override fun onCreate(state: Bundle?) {
        // Do not restore a prior configuration view hierarchy or typed credentials.
        super.onCreate(null)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        lockedView("Use this device's screen lock to open staff setup.")
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (authenticationAccepted && keyguard.isDeviceSecure && !keyguard.isKeyguardLocked) {
            authenticationAccepted = false
            authenticated = true
            showConfiguration()
        } else if (!authenticationInFlight) {
            requestAuthentication()
        }
    }

    override fun onPause() {
        foreground = false
        lock()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        lock()
        if (foreground && !authenticationInFlight) requestAuthentication()
    }

    @Deprecated("Uses Android device credential confirmation for API 26 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != AUTH_REQUEST || !authenticationInFlight) return
        authenticationInFlight = false
        authenticationAccepted = resultCode == RESULT_OK && keyguard.isDeviceSecure
        if (!authenticationAccepted) finish()
    }

    private fun lock() {
        authenticated = false
        authenticationAccepted = false
        dialog?.dismiss()
        dialog = null
        fields.forEach { it.setText("") }
        fields.clear()
        lockedView("Staff setup is locked. Device credential confirmation is required.")
    }

    @Suppress("DEPRECATION")
    private fun requestAuthentication() {
        authenticated = false
        if (!keyguard.isDeviceSecure) {
            lockedView("Staff setup is unavailable: no device screen-lock credential is enrolled. A tablet administrator must set a PIN, password or pattern through the tablet's managed Android Security settings, then return and authenticate. Guest setup cannot enroll or reset a credential.")
            return
        }
        val confirmation = keyguard.createConfirmDeviceCredentialIntent("Staff setup", "Confirm the device screen lock to manage the hotel connection.")
        if (confirmation == null) {
            lockedView("Android could not open device credential confirmation. Unlock the tablet and retry Staff setup.", retry = true)
            return
        }
        authenticationInFlight = true
        try {
            startActivityForResult(confirmation, AUTH_REQUEST)
        } catch (_: Exception) {
            authenticationInFlight = false
            lockedView("Device credential confirmation is unavailable. Ask the tablet administrator to check Android Security settings.", retry = true)
        }
    }

    private fun authorized(): Boolean {
        if (foreground && authenticated && keyguard.isDeviceSecure && !keyguard.isKeyguardLocked) return true
        lock()
        if (foreground && !authenticationInFlight) requestAuthentication()
        return false
    }

    private fun lockedView(message: String, retry: Boolean = false) {
        val column = column()
        column.addView(label("Staff setup", 24f))
        column.addView(label(message))
        if (retry) column.addView(button("Authenticate again") { requestAuthentication() })
        column.addView(button("Close") { finish() })
        setContentView(column)
    }

    private fun showConfiguration() {
        if (!authorized()) return
        val column = column()
        column.addView(label("Staff setup", 24f))
        column.addView(label("Live translation uses the audio credential built into this release. Saving a credential below replaces it on this device only. Keys stay encrypted on this device."))
        column.addView(label("Hotel service", 20f))
        val connectionUrl = EditText(this).apply {
            hint = "HTTPS hotel service URL"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            isSaveEnabled = false
            setSaveFromParentEnabled(false)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setTextColor(ink)
            setHintTextColor(Color.rgb(157, 178, 194))
            setText(HotelConnection(this@KeyEntryActivity).load()?.base.orEmpty())
        }
        fields.add(connectionUrl)
        column.addView(connectionUrl)
        addProvider(column, "Hotel connection", "hotel_connection", connectionUrl)
        addProvider(column, "Voice credential (release override)", "fish")
        column.addView(button("Done") { finish() })
        setContentView(ScrollView(this).apply {
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            addView(column)
        })
    }

    private fun addProvider(column: LinearLayout, provider: String, prefsName: String, connectionUrl: EditText? = null) {
        // Called only after credential confirmation; no storage read happens on a locked screen.
        if (!authorized()) return
        val store = SecretStore(this, prefsName)
        val connection = if (connectionUrl != null) HotelConnection(this) else null
        val status = label("")
        fun refresh() {
            if (!authorized()) return
            status.text = when (store.status()) {
                SecretStore.Status.READY -> "$provider: a key is securely saved. Entering a new key replaces it."
                SecretStore.Status.ABSENT -> "$provider: no device key is saved."
                SecretStore.Status.ERROR -> "$provider: ${store.errorMessage}"
            }
        }
        column.addView(label(provider, 20f))
        column.addView(status)
        val field = object : EditText(this) {
            override fun onTextContextMenuItem(id: Int): Boolean {
                if (id == android.R.id.copy || id == android.R.id.cut || id == android.R.id.shareText) return true
                return super.onTextContextMenuItem(id)
            }
            override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(info)
                info.text = null
                info.isPassword = true
                info.contentDescription = "New $provider key, masked"
                info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_COPY)
                info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CUT)
            }
            override fun sendAccessibilityEventUnchecked(event: AccessibilityEvent) {
                event.text.clear()
                event.text.add("Masked key field")
                event.beforeText = null
                event.contentDescription = "New $provider key, masked"
                event.isPassword = true
                super.sendAccessibilityEventUnchecked(event)
            }
        }.apply {
            hint = "New $provider key (not shown)"
            contentDescription = "New $provider key, masked"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            transformationMethod = object : PasswordTransformationMethod() {
                // Android's default password transform can briefly reveal the last typed glyph.
                override fun getTransformation(source: CharSequence, view: View): CharSequence = object : CharSequence {
                    override val length: Int get() = source.length
                    override fun get(index: Int): Char = '\u2022'
                    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = "\u2022".repeat(endIndex - startIndex)
                    override fun toString(): String = "\u2022".repeat(length)
                }
            }
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            isSaveEnabled = false
            setSaveFromParentEnabled(false)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
            filterTouchesWhenObscured = true
            setSingleLine()
            setTextColor(ink)
            setHintTextColor(Color.rgb(157, 178, 194))
            backgroundTintList = ColorStateList.valueOf(Color.rgb(97, 201, 237))
            customSelectionActionModeCallback = object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = false
                override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false
                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = true
                override fun onDestroyActionMode(mode: ActionMode) {}
            }
        }
        fields.add(field)
        column.addView(field)
        column.addView(button("Save $provider key") {
            if (!authorized()) return@button
            val value = field.text.toString().trim()
            if (value.isEmpty() || value.any { it.isWhitespace() }) {
                Toast.makeText(this, "Paste a provider key without spaces.", Toast.LENGTH_LONG).show()
            } else if (if (connection != null) connection.save(connectionUrl!!.text.toString(), value) else store.save(value)) {
                field.setText("")
                refresh()
                Toast.makeText(this, "$provider key securely saved.", Toast.LENGTH_SHORT).show()
            } else {
                status.text = "$provider: ${connection?.errorMessage ?: store.errorMessage}"
            }
        })
        column.addView(button("Remove $provider key") {
            if (!authorized()) return@button
            dialog = AlertDialog.Builder(this)
                .setTitle("Remove saved $provider key?")
                .setMessage("This only removes this tablet's credential. Keep the provider's original key if you need it again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove") { _, _ ->
                    if (authorized()) {
                        store.clear()
                        field.setText("")
                        if (store.status == SecretStore.Status.ERROR) status.text = "$provider: ${store.errorMessage}"
                        else refresh()
                    }
                }.create().also {
                    it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    it.show()
                    it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
        })
        refresh()
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(this@KeyEntryActivity.background)
        setPadding(40, 24, 40, 24)
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    private fun label(value: String, size: Float = 15f) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(ink)
        setPadding(0, 8, 0, 8)
        isSaveEnabled = false
    }

    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        setTextColor(ink)
        backgroundTintList = ColorStateList.valueOf(Color.rgb(24, 44, 59))
        minHeight = (48 * resources.displayMetrics.density).toInt()
        filterTouchesWhenObscured = true
        setOnClickListener { action() }
    }

    companion object { private const val AUTH_REQUEST = 31 }
}
