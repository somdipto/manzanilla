package com.agentdeck

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.agentdeck.audio.Chiptune
import com.agentdeck.ui.Themes

/** Best-effort short sign-off while Android is still allowing audio during shutdown. */
class ShutdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_SHUTDOWN) return
        val theme = context.getSharedPreferences("deck", Context.MODE_PRIVATE)
            .getInt("theme", 0)
        if (Themes.all.getOrNull(theme)?.name != "WEB SLINGER") return
        val pending = goAsync()
        Chiptune.webOff()
        Thread {
            try { Thread.sleep(1300L) } finally { pending.finish() }
        }.start()
    }
}
