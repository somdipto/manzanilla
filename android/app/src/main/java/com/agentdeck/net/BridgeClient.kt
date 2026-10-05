package com.agentdeck.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket link to the AgentDeck Bridge on the PC.
 * Discovers the Bridge via mDNS (_agentdeck._tcp), falls back to a manually
 * configured IP. Auto-reconnects forever with backoff.
 */
class BridgeClient(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onConnected(name: String)
        fun onDisconnected()
        fun onJson(o: JSONObject)
        fun onBinary(tag: Byte, data: ByteArray) {}
    }

    private val prefs = ctx.getSharedPreferences("deck", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    companion object {
        /**
         * USB-debug fallback. `adb reverse tcp:8777 tcp:8777` makes the Windows
         * bridge available here even when the Orange device has no Wi-Fi.
         * Normal mDNS discovery still replaces this with the PC's LAN address.
         */
        const val DEFAULT_HOST = "127.0.0.1"
        const val LEGACY_DEFAULT_HOST = "192.168.1.133"
    }

    @Volatile private var ws: WebSocket? = null
    @Volatile private var wantRun = false
    @Volatile private var connecting = false
    @Volatile private var generation = 0
    @Volatile private var loopRunning = false
    private val connectionLock = Any()
    @Volatile private var host: String? = prefs.getString("host", DEFAULT_HOST).let {
        if (it == LEGACY_DEFAULT_HOST) DEFAULT_HOST else it
    }
    @Volatile private var port: Int = prefs.getInt("port", 8777)
    private var nsd: NsdManager? = null
    private var discovering = false

    init {
        if (prefs.getString("host", DEFAULT_HOST) == LEGACY_DEFAULT_HOST) {
            prefs.edit().putString("host", DEFAULT_HOST).apply()
        }
    }

    var token: String?
        get() = prefs.getString("token", null)
        set(v) { prefs.edit().putString("token", v).apply() }

    fun manualHost(h: String, p: Int = 8777) {
        host = h; port = p
        prefs.edit().putString("host", h).putInt("port", p).apply()
        reconnectNow()
    }

    fun start() {
        synchronized(connectionLock) {
            if (wantRun) return
            wantRun = true
            generation += 1
        }
        startDiscovery()
        connectLoop()
    }

    fun stop() {
        val old = synchronized(connectionLock) {
            wantRun = false
            generation += 1
            connecting = false
            val socket = ws
            ws = null
            socket
        }
        old?.close(1000, "bye")
        stopDiscovery()
    }

    fun sendJson(o: JSONObject) { ws?.send(o.toString()) }

    fun send(type: String, vararg kv: Pair<String, Any>) {
        val o = JSONObject().put("t", type)
        for ((k, v) in kv) o.put(k, v)
        sendJson(o)
    }

    /** binary frame: tag byte + payload (0x01 audio, 0x02 jpeg) */
    fun sendBinary(tag: Byte, data: ByteArray, len: Int = data.size) {
        val buf = ByteArray(len + 1)
        buf[0] = tag
        System.arraycopy(data, 0, buf, 1, len)
        ws?.send(buf.toByteString(0, len + 1))
    }

    val isConnected get() = ws != null

    // ---- discovery ----------------------------------------------------------

    private fun startDiscovery() {
        if (discovering) return
        try {
            nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
            nsd?.discoverServices("_agentdeck._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            discovering = true
        } catch (_: Exception) {}
    }

    private fun stopDiscovery() {
        if (!discovering) return
        try { nsd?.stopServiceDiscovery(discoveryListener) } catch (_: Exception) {}
        discovering = false
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(si: NsdServiceInfo) {
            try {
                nsd?.resolveService(si, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(r: NsdServiceInfo) {
                        val resolvedHost = r.host?.hostAddress ?: return
                        val resolvedPort = r.port
                        val changed = synchronized(connectionLock) {
                            if (host == resolvedHost && port == resolvedPort) false
                            else {
                                host = resolvedHost
                                port = resolvedPort
                                true
                            }
                        }
                        prefs.edit().putString("host", resolvedHost)
                            .putInt("port", resolvedPort).apply()
                        if (changed) reconnectNow()
                    }
                    override fun onResolveFailed(si: NsdServiceInfo, e: Int) {}
                })
            } catch (_: Exception) {}
        }
        override fun onServiceLost(si: NsdServiceInfo) {}
        override fun onDiscoveryStarted(t: String) {}
        override fun onDiscoveryStopped(t: String) {}
        override fun onStartDiscoveryFailed(t: String, e: Int) { discovering = false }
        override fun onStopDiscoveryFailed(t: String, e: Int) {}
    }

    // ---- connection loop ----------------------------------------------------

    private var backoffMs = 1000L
    private var tryUsbNext = false

    private fun reconnectNow() {
        val old = synchronized(connectionLock) {
            generation += 1
            connecting = false
            val socket = ws
            ws = null
            backoffMs = 500
            socket
        }
        // cancel() is intentional: a graceful WebSocket close can overlap the
        // replacement transport long enough to leave several authenticated
        // sockets alive on the PC bridge.
        old?.cancel()
    }

    private fun connectLoop() {
        synchronized(connectionLock) {
            if (loopRunning) return
            loopRunning = true
        }
        Thread {
            try {
                while (wantRun) {
                    var target: Triple<String, Int, Int>? = null
                    synchronized(connectionLock) {
                        val h = host
                        if (wantRun && h != null && ws == null && !connecting) {
                            connecting = true
                            // Try the USB tunnel between failed LAN attempts without
                            // replacing the saved Wi-Fi address or pairing token.
                            val useUsb = h != DEFAULT_HOST && tryUsbNext
                            target = Triple(if (useUsb) DEFAULT_HOST else h,
                                if (useUsb) 8777 else port, generation)
                            tryUsbNext = !tryUsbNext
                        }
                    }
                    target?.let { (h, p, g) ->
                        try { open(h, p, g) }
                        catch (_: Exception) {
                            synchronized(connectionLock) {
                                if (generation == g) connecting = false
                            }
                        }
                    }
                    Thread.sleep(backoffMs)
                    if (backoffMs < 8000) backoffMs += 500
                }
            } finally {
                synchronized(connectionLock) { loopRunning = false }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun open(h: String, p: Int, openGeneration: Int) {
        val req = Request.Builder().url("ws://$h:$p/deck").build()
        http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(socket: WebSocket, resp: Response) {
                val accepted = synchronized(connectionLock) {
                    if (!wantRun || generation != openGeneration || ws != null) false
                    else {
                        ws = socket
                        connecting = false
                        backoffMs = 1000
                        true
                    }
                }
                if (!accepted) {
                    socket.cancel()
                    return
                }
                Log.i("ManzanillaBridge", "connected transport to $h:$p")
                val hello = JSONObject().put("t", "hello").put("device", "orange-deskphone")
                token?.let { hello.put("token", it) }
                socket.send(hello.toString())
            }
            override fun onMessage(socket: WebSocket, text: String) {
                try {
                    val o = JSONObject(text)
                    when (o.optString("t")) {
                        "paired" -> { token = o.getString("token"); listener.onConnected(o.optString("name", "PC")) }
                        "welcome" -> listener.onConnected(o.optString("name", "PC"))
                        else -> {}
                    }
                    listener.onJson(o)
                } catch (_: Exception) {}
            }
            override fun onMessage(socket: WebSocket, bytes: ByteString) {
                if (bytes.size > 1) {
                    val arr = bytes.toByteArray()
                    listener.onBinary(arr[0], arr.copyOfRange(1, arr.size))
                }
            }
            override fun onClosed(socket: WebSocket, code: Int, reason: String) { drop(socket) }
            override fun onFailure(socket: WebSocket, t: Throwable, r: Response?) {
                Log.w("ManzanillaBridge", "connection to $h:$p failed: ${t.message}")
                drop(socket)
            }
            private fun drop(socket: WebSocket) {
                val wasActive = synchronized(connectionLock) {
                    if (ws === socket) {
                        ws = null
                        connecting = false
                        true
                    } else {
                        if (generation == openGeneration) connecting = false
                        false
                    }
                }
                if (wasActive) listener.onDisconnected()
            }
        })
    }
}
