package com.agentdeck.hotel

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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

/** Personal Fish setup is open; hotel administration still requires the device credential. */
class KeyEntryActivity : Activity() {
    private val hotelDistribution get() = BuildConfig.DISTRIBUTION == "hotel"
    // Intent extras cannot weaken the hotel distribution's device-credential boundary.
    private val voiceOnly get() = !hotelDistribution && intent.getBooleanExtra("voice_only", false)
    private val setupLanguage get() = getSharedPreferences("deck", MODE_PRIVATE)
        .getString("reception_staff_language", "es").orEmpty().takeIf(DeskLanguages::has) ?: "es"
    private fun text(message: String, values: Map<String, String> = emptyMap()) = UiText.text(this, setupLanguage, message, values)

    private val setupTitle get() = if (voiceOnly) text("Translation setup") else text("Staff setup")
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
    private var authenticated = false
    private var authenticationInFlight = false
    private var authenticationAccepted = false
    private var foreground = false
    private val fields = ArrayList<EditText>()
    private var dialog: AlertDialog? = null
    private var updateStatusView: TextView? = null
    private val updateStatusPrefs by lazy { getSharedPreferences("update_staff_status", MODE_PRIVATE) }
    private val updateStatusListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "status" && foreground && authenticated && authorized()) {
            updateStatusView?.text = UpdateStatusText.render(updateStatusPrefs.getString("status", "").orEmpty()) { message, values -> text(message, values) }
        }
    }
    private val light by lazy { getSharedPreferences("deck", MODE_PRIVATE).getString("reception_appearance", "dark") == "light" }
    private val background get() = if (light) Color.rgb(243, 245, 242) else Color.rgb(8, 15, 21)
    private val ink get() = if (light) Color.rgb(24, 44, 56) else Color.rgb(229, 237, 243)
    private val hintInk get() = if (light) Color.rgb(68, 93, 108) else Color.rgb(157, 178, 194)
    private val accent get() = if (light) Color.rgb(20, 85, 112) else Color.rgb(97, 201, 237)
    private val surface get() = if (light) Color.rgb(228, 236, 235) else Color.rgb(24, 44, 59)

    override fun onCreate(state: Bundle?) {
        if (light) setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        // Do not restore a prior configuration view hierarchy or typed credentials.
        super.onCreate(null)
        UiText.preload(this)
        window.decorView.layoutDirection = if (UiText.direction(this, setupLanguage) == "rtl") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        window.setBackgroundDrawable(ColorDrawable(background))
        window.statusBarColor = background
        window.navigationBarColor = background
        if (light) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (!voiceOnly) lockedView(text("Use your normal device PIN, password or pattern to open setup. No separate staff account is required."))
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        updateStatusPrefs.registerOnSharedPreferenceChangeListener(updateStatusListener)
        if (voiceOnly) {
            showConfiguration()
        } else if (authenticationAccepted && keyguard.isDeviceSecure && !keyguard.isKeyguardLocked) {
            authenticationAccepted = false
            authenticated = true
            showConfiguration()
        } else if (!authenticationInFlight) {
            requestAuthentication()
        }
    }

    override fun onPause() {
        foreground = false
        updateStatusPrefs.unregisterOnSharedPreferenceChangeListener(updateStatusListener)
        lock()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lock()
        if (foreground && !authenticationInFlight) {
            if (voiceOnly) showConfiguration() else requestAuthentication()
        }
    }

    @Deprecated("Uses Android device credential confirmation for API 26 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != AUTH_REQUEST || !authenticationInFlight) return
        authenticationInFlight = false
        if (voiceOnly) { authenticationAccepted = false; return }
        authenticationAccepted = resultCode == RESULT_OK && keyguard.isDeviceSecure
        if (!authenticationAccepted) finish()
    }

    private fun lock() {
        authenticated = false
        authenticationAccepted = false
        dialog?.dismiss()
        updateStatusView = null
        dialog = null
        fields.forEach { it.setText("") }
        fields.clear()
        if (voiceOnly) {
            setContentView(column().apply { addView(label(setupTitle, 24f)) })
        } else lockedView(text("Setup is locked. Confirm this device's screen lock to continue."))
    }

    @Suppress("DEPRECATION")
    private fun requestAuthentication() {
        authenticated = false
        if (voiceOnly) return
        if (!keyguard.isDeviceSecure) {
            lockedView(text("Staff setup is unavailable: no device screen-lock credential is enrolled. A tablet administrator must set a PIN, password or pattern through the tablet's managed Android Security settings, then return and authenticate. Guest setup cannot enroll or reset a credential."))
            return
        }
        val confirmation = keyguard.createConfirmDeviceCredentialIntent(setupTitle, text("Use your normal device PIN, password or pattern. No separate staff account is required."))
        if (confirmation == null) {
            lockedView(text("Android could not open device credential confirmation. Unlock the tablet and retry Staff setup."), retry = true)
            return
        }
        authenticationInFlight = true
        try {
            startActivityForResult(confirmation, AUTH_REQUEST)
        } catch (_: Exception) {
            authenticationInFlight = false
            lockedView(text("Device credential confirmation is unavailable. Ask the tablet administrator to check Android Security settings."), retry = true)
        }
    }

    private fun authorized(): Boolean {
        if (voiceOnly) return foreground
        if (foreground && authenticated && keyguard.isDeviceSecure && !keyguard.isKeyguardLocked) return true
        lock()
        if (foreground && !authenticationInFlight) requestAuthentication()
        return false
    }

    private fun lockedView(message: String, retry: Boolean = false) {
        val column = column()
        column.addView(label(setupTitle, 24f))
        column.addView(label(message))
        if (retry) column.addView(button(text("Authenticate again")) { requestAuthentication() })
        column.addView(button(text("Close")) { finish() })
        setContentView(column)
    }

    private fun showConfiguration() {
        if (!authorized()) return
        val column = column()
        if (voiceOnly) {
            column.addView(label(setupTitle, 24f))
            addTestingNotice(column)
            addTranslationConfiguration(column, returnAfterSave = true)
            column.addView(button(text("Cancel")) { finish() })
            setContentView(ScrollView(this).apply { isSaveEnabled = false; addView(column) })
            return
        }
        column.addView(label(text("Staff setup"), 24f))
        if (hotelDistribution) {
            column.addView(label(text("Hotel translation uses the enrolled hotel service. Personal voice keys remain saved but are not used in this build.")))
        } else {
            addTestingNotice(column)
            addTranslationConfiguration(column)
        }
        if (FishVoice.needsCleanupCredential(this)) {
            addProvider(column, text("Fish Audio"), FishCleanupCredential.STORE, cleanupOnly = true)
        }
        column.addView(label(text("Hotel service"), 20f))
        val connectionUrl = EditText(this).apply {
            hint = text("HTTPS hotel service URL")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            isSaveEnabled = false
            setSaveFromParentEnabled(false)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setTextColor(ink)
            setHintTextColor(hintInk)
            backgroundTintList = ColorStateList.valueOf(accent)
            setText(HotelConnection(this@KeyEntryActivity).load()?.base.orEmpty())
        }
        fields.add(connectionUrl)
        column.addView(connectionUrl)
        addProvider(column, text("Hotel connection"), "hotel_connection", connectionUrl)
        if (hotelDistribution) {
            column.addView(label(text("Maintenance"), 20f))
            updateStatusView = label("").also {
                updateStatusPrefs.getString("status", null)?.let { status ->
                    it.text = UpdateStatusText.render(status) { message, values -> text(message, values) }
                }
                column.addView(it)
            }
            addMaintenance(column, "Check updates", "check_updates")
            addMaintenance(column, "Apply update", "apply_update")
            addMaintenance(column, "Light appearance", "appearance_light")
            addMaintenance(column, "Dark appearance", "appearance_dark")
            addMaintenance(column, "Turn on after-hours reception", "night_on")
            addMaintenance(column, "Turn off after-hours reception", "night_off")
        }
        column.addView(button(text("Voice diagnostics")) {
            if (!authorized()) return@button
            if (!hotelDistribution) {
                startActivity(Intent(this, DiagnosticsActivity::class.java))
                return@button
            }
            dialog = AlertDialog.Builder(this)
                .setTitle(text("Voice diagnostics"))
                .setMessage(VoiceDiagnostics.render())
                .setPositiveButton(text("Close"), null)
                .create().also {
                    it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    it.show()
                    it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
        })
        column.addView(button(text("Done")) { finish() })
        setContentView(ScrollView(this).apply {
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            addView(column)
        })
    }

    private fun addTestingNotice(column: LinearLayout) {
        column.addView(label(text("Personal testing build")))
    }

    private fun addMaintenance(column: LinearLayout, message: String, action: String) {
        column.addView(button(text(message)) {
            if (!authorized() || !hotelDistribution || action !in MAINTENANCE_ACTIONS) return@button
            setResult(RESULT_OK, Intent().putExtra("maintenance_action", action))
            finish()
        })
    }

    private fun credentialChangeAllowed(): Boolean {
        if (!getSharedPreferences("fishmeta", MODE_PRIVATE).contains("outstanding_session")) return true
        Toast.makeText(this, text("Complete the previous conversation cleanup before changing saved credentials."), Toast.LENGTH_LONG).show()
        return false
    }

    private fun addTranslationConfiguration(column: LinearLayout, returnAfterSave: Boolean = false) {
        val credentials = TranslationCredentials(this)
        val saved = credentials.load()
        val savedFish = credentials.savedFishKey()
        val status = label(text("Fish Audio + Gemini: save both keys and a voice to translate."))
        column.addView(status)
        fun keyField(provider: String, present: Boolean): EditText {
            column.addView(label(text(if (present) "{provider}: a key is securely saved. Entering a new key replaces it."
                else "{provider}: no device key is saved.", mapOf("provider" to provider))))
            return secureKeyField(provider).also(column::addView)
        }
        val fish = keyField("Fish Audio", savedFish != null)
        val gemini = keyField("Gemini", saved != null)
        column.addView(label(text("Fish voice ID")))
        val voice = EditText(this).apply {
            contentDescription = text("Fish voice ID")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            isSaveEnabled = false
            setSaveFromParentEnabled(false)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setTextColor(ink)
            setHintTextColor(hintInk)
            backgroundTintList = ColorStateList.valueOf(accent)
            setText(saved?.voiceId.orEmpty())
        }
        fields.add(voice)
        column.addView(voice)
        column.addView(button(text("Save and return")) {
            if (!authorized() || !credentialChangeAllowed()) return@button
            val fishKey = fish.text.toString().trim().ifEmpty { savedFish.orEmpty() }
            val translationKey = gemini.text.toString().trim().ifEmpty { saved?.translationKey.orEmpty() }
            if (fishKey.isBlank() || translationKey.isBlank() || voice.text.toString().isBlank()) {
                status.text = text("Fish Audio + Gemini: save both keys and a voice to translate.")
                return@button
            }
            if (!credentials.save(fishKey, translationKey, voice.text.toString().trim())) {
                status.text = text("The credential could not be securely saved. Check the service URL and device storage; the previous stored entry has been kept.")
                return@button
            }
            fish.setText("")
            gemini.setText("")
            if (!TranslationSettings(this).selectDirectFish()) {
                status.text = text("The saved key is available, but the translation connection could not be selected. Try again.")
                return@button
            }
            setResult(RESULT_OK)
            if (returnAfterSave) finish() else showConfiguration()
        })
    }

    private fun addProvider(column: LinearLayout, provider: String, prefsName: String, connectionUrl: EditText? = null,
                            cleanupOnly: Boolean = false) {
        // Enrollment and historical cleanup remain behind the existing staff boundary.
        if (!authorized()) return
        val store = SecretStore(this, prefsName)
        val connection = if (connectionUrl != null) HotelConnection(this) else null
        val status = label("")
        fun refresh() {
            if (!authorized()) return
            if (cleanupOnly) {
                status.text = text("Paste the original Fish key to finish the previous conversation cleanup. Only the matching original key is accepted; it will not be used for new hotel conversations.")
                return
            }
            val savedStatus = store.status()
            status.text = when (savedStatus) {
                SecretStore.Status.READY -> text("{provider}: a key is securely saved. Entering a new key replaces it.", mapOf("provider" to provider))
                SecretStore.Status.ABSENT -> text("{provider}: no device key is saved.", mapOf("provider" to provider))
                SecretStore.Status.ERROR -> text("The saved credential could not be read. Replace it or ask the tablet administrator.")
            }
        }
        column.addView(label(provider, 20f))
        column.addView(status)
        val field = secureKeyField(provider)
        column.addView(field)
        column.addView(button(text("Save {provider} key", mapOf("provider" to provider))) {
            if (!authorized() || (!cleanupOnly && !credentialChangeAllowed())) return@button
            val value = field.text.toString().trim()
            if (value.isEmpty() || value.any { it.isWhitespace() }) {
                Toast.makeText(this, text("Paste a provider key without spaces."), Toast.LENGTH_LONG).show()
            } else if (cleanupOnly) {
                if (FishVoice.recoverCleanupCredential(this, value)) {
                    field.setText("")
                    setResult(RESULT_OK)
                    finish()
                } else {
                    status.text = text("Paste the original Fish key to finish the previous conversation cleanup. Only the matching original key is accepted; it will not be used for new hotel conversations.")
                }
            } else if (if (connection != null) connection.save(connectionUrl!!.text.toString(), value) else store.save(value)) {
                field.setText("")
                refresh()
                val settings = TranslationSettings(this)
                val routeSaved = when {
                    connection != null && !hotelDistribution -> settings.selectHotelConnection()
                    else -> true
                }
                if (!routeSaved) {
                    status.text = text("{provider} key is saved, but the translation connection could not be selected. Try saving again.", mapOf("provider" to provider))
                    return@button
                }
                Toast.makeText(this, text("{provider} key securely saved.", mapOf("provider" to provider)), Toast.LENGTH_SHORT).show()
            } else {
                status.text = text("The credential could not be securely saved. Check the service URL and device storage; the previous stored entry has been kept.")
            }
        })
        if (!cleanupOnly) column.addView(button(text("Remove {provider} key", mapOf("provider" to provider))) {
            if (!authorized()) return@button
            dialog = AlertDialog.Builder(this)
                .setTitle(text("Remove saved {provider} key?", mapOf("provider" to provider)))
                .setMessage(text("This only removes this tablet's credential. Keep the provider's original key if you need it again."))
                .setNegativeButton(text("Cancel"), null)
                .setPositiveButton(text("Remove")) { _, _ ->
                    if (authorized() && credentialChangeAllowed()) {
                        store.clear()
                        field.setText("")
                        if (store.status == SecretStore.Status.ERROR) status.text =
                            text("The saved credential could not be removed. Check device storage and try again.")
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

    private fun secureKeyField(provider: String): EditText {
        val field = object : EditText(this) {
            override fun onTextContextMenuItem(id: Int): Boolean {
                if (id == android.R.id.copy || id == android.R.id.cut || id == android.R.id.shareText) return true
                return super.onTextContextMenuItem(id)
            }
            override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(info)
                info.text = null
                info.isPassword = true
                info.contentDescription = text("New {provider} key, masked", mapOf("provider" to provider))
                info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_COPY)
                info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CUT)
            }
            override fun sendAccessibilityEventUnchecked(event: AccessibilityEvent) {
                event.text.clear()
                event.text.add(text("Masked key field"))
                event.beforeText = null
                event.contentDescription = text("New {provider} key, masked", mapOf("provider" to provider))
                event.isPassword = true
                super.sendAccessibilityEventUnchecked(event)
            }
        }.apply {
            hint = text("New {provider} key (not shown)", mapOf("provider" to provider))
            contentDescription = text("New {provider} key, masked", mapOf("provider" to provider))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            // setSingleLine() installs SingleLineTransformationMethod, which does not mask.
            // Lay out first, then install the masking transformation, or a pasted provider
            // key would render in cleartext on screen.
            setSingleLine()
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
            setTextColor(ink)
            setHintTextColor(hintInk)
            backgroundTintList = ColorStateList.valueOf(accent)
            customSelectionActionModeCallback = object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = false
                override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false
                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = true
                override fun onDestroyActionMode(mode: ActionMode) {}
            }
        }
        fields.add(field)
        return field
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
        backgroundTintList = ColorStateList.valueOf(surface)
        minHeight = (48 * resources.displayMetrics.density).toInt()
        filterTouchesWhenObscured = true
        setOnClickListener { action() }
    }

    companion object {
        private const val AUTH_REQUEST = 31
        internal val MAINTENANCE_ACTIONS = setOf(
            "check_updates", "apply_update", "appearance_light", "appearance_dark", "night_on", "night_off")
    }
}
