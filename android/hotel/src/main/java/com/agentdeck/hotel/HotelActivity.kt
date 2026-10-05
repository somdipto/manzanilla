package com.agentdeck.hotel

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.agentdeck.ui.ReceptionView
import org.json.JSONObject

/** Manzanilla reception: the packaged Reception UI, full screen. No setup, no enrollment. */
class HotelActivity : Activity() {
    private var reception: ReceptionView? = null
    private var night = false
    private val updater by lazy { Updater(this) { } }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val root = FrameLayout(this)
        val view = ReceptionView(this) { handle(it) }
        reception = view
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        view.setShown(true)
        view.push(JSONObject().put("type", "demo_mode").put("enabled", true))
        view.push(JSONObject().put("type", "night_mode").put("enabled", night))
        view.push(JSONObject().put("type", "connection").put("connected", false))
    }

    private fun handle(message: JSONObject) {
        when (message.optString("action")) {
            "night" -> { night = message.optBoolean("enabled", !night); reception?.push(JSONObject().put("type", "night_mode").put("enabled", night)) }
            else -> Unit // exit/start/etc: nothing to leave to, and no live backend yet
        }
    }

    override fun onResume() { super.onResume(); reception?.setShown(true); updater.check() }
    override fun onPause() { super.onPause(); reception?.setShown(false) }
    override fun onDestroy() { reception?.dispose(); reception = null; super.onDestroy() }
}
