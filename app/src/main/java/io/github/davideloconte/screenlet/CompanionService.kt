package io.github.davideloconte.screenlet

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Always ready for the PC: listens over TLS on the network (said with mDNS,
 * _screenlet._tcp), on the USB cable (UsbTunnel) and, with no network to
 * share, on a Wi-Fi Direct group of its own (WifiDirect), so Screenlet on
 * the PC can show its desktop here. A foreground service (a quiet
 * notification); it does nothing until the PC connects.
 */
class CompanionService : Service() {
    @Volatile private var server: SSLServerSocket? = null
    @Volatile private var registration: NsdManager.RegistrationListener? = null
    @Volatile private var stopped = false
    private val connections = ThreadPoolExecutor(0, MAX_CONNECTIONS, 60, TimeUnit.SECONDS, SynchronousQueue())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Notifications.channels(this)
        try {
            startForeground(Notifications.SERVICE_ID, Notifications.ready(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            // Restarted from the background (START_STICKY) where Android does
            // not allow it: the next time the app opens starts it again.
            Log.w(TAG, "foreground service not allowed now: $e")
            stopSelf()
            return
        }
        Thread(::serve, "screenlet-server").start()
        getSystemService(android.net.ConnectivityManager::class.java)
            .registerNetworkCallback(android.net.NetworkRequest.Builder()
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build(), wifi)
        handler.postDelayed(::idleCheck, IDLE_CHECK)
        registerReceiver(usbGone, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            addAction(USB_STATE)
        }, RECEIVER_NOT_EXPORTED)
    }

    /** Android left accessory mode (the PC reset the cable, or it was
     * unplugged): the accessory is let go. Held, it keeps /dev/usb_accessory
     * busy, and the PC's next request to become an accessory fails. */
    private val usbGone = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == USB_STATE && intent.getBooleanExtra("accessory", false)) return
            synchronized(this@CompanionService) {
                if (usb != null) Log.i(TAG, "usb: Android left accessory mode")
                usb?.stop()
                usb = null
            }
            direct()
        }
    }

    /** The Wi-Fi networks the tablet is on: none, and its own Wi-Fi Direct group comes up. */
    private val wifiNetworks = ConcurrentHashMap.newKeySet<android.net.Network>()
    private val wifi = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) { wifiNetworks.add(network); direct() }
        override fun onLost(network: android.net.Network) { wifiNetworks.remove(network); direct() }
    }

    /** Wi-Fi Direct only when nothing else reaches the PC: the group's radio
     * time would slow the Wi-Fi network down, and the cable needs none. */
    private fun direct() = handler.post {
        if (stopped) return@post
        val needed = wifiNetworks.isEmpty() && synchronized(this) { usb == null }
        if (needed) WifiDirect.start(this) else WifiDirect.stop(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastUse = System.currentTimeMillis()  // the app was opened, or a job came
        val accessory = intent?.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
            ?: synchronized(this) { if (usb == null) pluggedUsb() else null }
        accessory?.let(::openUsb)
        direct()
        return if (Options.alwaysReady(this)) START_STICKY else START_NOT_STICKY
    }

    // Not always ready: stop once nothing has happened for a while.
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var lastUse = System.currentTimeMillis()
    private val busy = AtomicInteger()  // PCs connected
    private val unpaired = AtomicInteger()  // connections not yet known to be a paired PC

    private fun idleCheck() {
        if (!Options.alwaysReady(this) && busy.get() == 0 &&
            System.currentTimeMillis() - lastUse > IDLE_STOP) {
            stopSelf()
            return
        }
        handler.postDelayed(::idleCheck, IDLE_CHECK)
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(usbGone) }
        runCatching { getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(wifi) }
        WifiDirect.stop(this)  // not registered if onCreate stopped early
        synchronized(this) {
            stopped = true
            registration?.let { runCatching { (getSystemService(NSD_SERVICE) as NsdManager).unregisterService(it) } }
            registration = null
            server?.close()
            usb?.stop()
        }
        connections.shutdownNow()
        super.onDestroy()
    }

    private fun serve() {
        // Not listening is not ready: tried again (the keystore's first key
        // may fail once) instead of a "Ready" nobody can reach.
        var socket: SSLServerSocket? = null
        while (socket == null) {
            if (stopped) return
            socket = try {
                val factory = Identity.sslContext().serverSocketFactory
                // A port the PC knows: over Wi-Fi Direct there is no mDNS.
                (try { factory.createServerSocket(PORT) } catch (e: java.net.BindException) {
                    factory.createServerSocket(0)
                }) as SSLServerSocket
            } catch (e: Exception) {
                Log.e(TAG, "cannot listen, again in a while", e)
                Thread.sleep(LISTEN_RETRY)
                null
            }
        }
        socket.needClientAuth = true
        socket.enabledProtocols = arrayOf("TLSv1.3")
        synchronized(this) {
            // Stopped while the first key was being made: nothing to leave behind.
            if (stopped) return socket.close()
            server = socket
            try {
                advertise(socket.localPort)
            } catch (e: Exception) {
                Log.e(TAG, "not on the network by mDNS (the PC still finds it at its port): $e")
            }
        }
        while (!socket.isClosed) {
            val client = try {
                socket.accept() as SSLSocket
            } catch (e: Exception) {
                if (socket.isClosed) break
                Log.w(TAG, "accept: $e")  // out of fds a moment, say: not the end of listening
                Thread.sleep(200)
                continue
            }
            // Never the PC: it comes over the network. Another app here, maybe,
            // posing as one to be paired with a tap on a question it timed.
            if (fromHere(client.inetAddress)) {
                Log.w(TAG, "a connection from this tablet itself: closed")
                runCatching { client.close() }
                continue
            }
            // Small messages go at once (pairing's, the answers to the PC's
            // pings), not held back by Nagle's algorithm for delayed ACKs.
            try { client.tcpNoDelay = true } catch (e: Exception) {}
            accept(client)
        }
    }

    /** A PC's connection, from the network or the cable (UsbTunnel). */
    fun accept(client: SSLSocket) {
        // Connections not through TLS yet, few at a time: strangers that
        // never finish their handshake cannot take every thread from a paired PC.
        if (handshaking.incrementAndGet() > MAX_HANDSHAKING) {
            handshaking.decrementAndGet()
            runCatching { client.close() }
            return
        }
        try {
            connections.execute { handle(client) }
        } catch (e: RejectedExecutionException) {
            handshaking.decrementAndGet()
            runCatching { client.close() }
        }
    }

    /** The PC over the USB cable, the accessory Android just handed to UsbActivity. */
    private fun openUsb(accessory: UsbAccessory) {
        val fd = getSystemService(UsbManager::class.java).openAccessory(accessory)
        if (fd == null) {
            Log.w(TAG, "usb: the accessory could not be opened")
            return
        }
        synchronized(this) {
            usb?.stop()
            usb = UsbTunnel(this, fd) { reopenUsb(accessory, USB_REOPEN_TRIES) }.also { it.start() }
        }
        Log.i(TAG, "usb: the cable's link is open")
        direct()
    }

    /** The PC's accessory, left on the cable while the app was not running
     * (Android hands it over only when it attaches): this app's to open. */
    private fun pluggedUsb(): UsbAccessory? {
        val manager = getSystemService(UsbManager::class.java)
        return manager.accessoryList?.firstOrNull { it.manufacturer == "Screenlet" && manager.hasPermission(it) }
    }

    /** The PC resets the cable's link to start afresh, and Android may keep
     * the accessory through it (no new UsbActivity): opened again while it
     * is there. Unplugged, it is not, and nothing happens. */
    private fun reopenUsb(accessory: UsbAccessory, tries: Int) {
        handler.postDelayed({
            val manager = getSystemService(UsbManager::class.java)
            if (stopped || manager.accessoryList?.contains(accessory) != true || !manager.hasPermission(accessory)) {
                if (tries > 1) reopenUsb(accessory, tries - 1)  // between leaving and coming back
                return@postDelayed
            }
            openUsb(accessory)
        }, USB_REOPEN_WAIT)
    }

    private var usb: UsbTunnel? = null  // under this

    private fun advertise(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = deviceName(this@CompanionService).take(63)  // a DNS label's most
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("id", Identity.fingerprint(Identity.certificate.encoded).take(16))
            // A TXT entry, key and value, is 255 bytes at most.
            setAttribute("name", deviceName(this@CompanionService).take(80))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "on the network as ${info.serviceName}, port $port")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                Log.e(TAG, "mDNS registration failed: $error")
                synchronized(this@CompanionService) { if (registration === this) registration = null }
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
        }
        registration = listener
        (getSystemService(NSD_SERVICE) as NsdManager).registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    /** One PC: hello, pairing if either side does not know the other and the
     * PC asks to pair (its user chose this tablet), then its requests. */
    private fun handle(socket: SSLSocket) {
        busy.incrementAndGet()
        lastUse = System.currentTimeMillis()
        var stranger = false  // counted in unpaired: not (yet) a paired PC
        var shaking = true  // counted in handshaking
        var fingerprint: String? = null
        try {
            socket.use {
                // Nothing waits long for a peer that is not even paired: the
                // whole exchange (TLS, hello, pairing's messages) in
                // HANDSHAKE_DEADLINE, not only each read, and a few such
                // peers at a time (a paired PC is never turned away for them).
                val guard = Wire.WATCHDOG.schedule({ socket.close() }, HANDSHAKE_DEADLINE, TimeUnit.MILLISECONDS)
                socket.soTimeout = HANDSHAKE_TIMEOUT
                socket.startHandshake()
                handshaking.decrementAndGet()
                shaking = false
                val pc = socket.session.peerCertificates[0].encoded
                fingerprint = Identity.fingerprint(pc)
                val known = Paired.contains(this, fingerprint)
                if (!known) {
                    stranger = true
                    if (unpaired.incrementAndGet() > MAX_UNPAIRED) return
                }
                val wire = Wire(socket)
                val hello = wire.receive(Wire.UNPAIRED_HEADER)
                require(hello.optString("type") == "hello") { "expected hello" }
                val pcName = readable(hello.optString("name"))
                // "at": the address the PC reached, so it can tell this
                // tablet answered itself (over Wi-Fi Direct, say), not
                // through someone passing the connection on.
                val reply = JSONObject().put("type", "hello").put("name", deviceName(this))
                    .put("version", PROTOCOL).put("paired", known).put("at", address(socket.localAddress))
                // Our Wi-Fi Direct group, for a PC both sides know: it joins
                // it when there is no network to share.
                if (known && hello.optBoolean("paired")) WifiDirect.credentials(this).let { (ssid, passphrase) ->
                    reply.put("p2p", JSONObject().put("ssid", ssid).put("passphrase", passphrase).put("at", WifiDirect.GROUP_OWNER))
                }
                wire.send(reply)
                // Another version: the PC sees ours and says so to its user.
                if (hello.optInt("version") != PROTOCOL) return
                if (!known || !hello.optBoolean("paired")) {
                    // Only when the user pairs, in Screenlet's window on the PC.
                    if (!hello.optBoolean("pair")) return
                    if (!pair(socket, wire, pc, pcName, guard)) return
                    Paired.add(this, fingerprint, pcName)
                }
                guard.cancel(false)
                // Its links, closed the moment the user forgets it (disconnect()).
                links.computeIfAbsent(fingerprint) { ConcurrentHashMap.newKeySet() }.add(socket)
                if (!Paired.contains(this, fingerprint)) return  // forgotten just now
                if (stranger) {
                    unpaired.decrementAndGet()
                    stranger = false
                }
                socket.soTimeout = PAIRED_TIMEOUT
                while (true) {
                    val message = wire.receive()
                    when (message.optString("type")) {
                        "ping" -> wire.send(JSONObject().put("type", "pong"))
                        "display" -> return display(socket, wire, message, pcName)
                        "bye" -> return
                    }
                }
            }
        } catch (e: Throwable) {
            // Errors too: a peer's deeply nested JSON overflows the parser's
            // stack, and an uncaught Error on a pool thread ends the process.
            Log.i(TAG, "connection ended: $e")
        } finally {
            if (shaking) handshaking.decrementAndGet()
            if (stranger) unpaired.decrementAndGet()
            fingerprint?.let { links[it]?.remove(socket) }
            busy.decrementAndGet()
            lastUse = System.currentTimeMillis()
        }
    }

    // Connections still in their TLS handshake, and each paired PC's open links.
    private val handshaking = AtomicInteger()
    private val links = ConcurrentHashMap<String, MutableSet<SSLSocket>>()

    /** The user forgot this PC: what it has open ends now. */
    fun disconnect(fingerprint: String) {
        links.remove(fingerprint)?.forEach { runCatching { it.close() } }
    }

    // Wi-Fi without power saving while the desktop is on screen: saving, it
    // holds packets back and sends them in bunches every 100-300 ms.
    private val wifiLock by lazy {
        getSystemService(android.net.wifi.WifiManager::class.java)
            .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "screenlet:display")
            .apply { setReferenceCounted(false) }
    }

    @Synchronized  // from the main thread (onVisible) and the connections': in order
    private fun lowLatency(starting: Boolean) {
        try {
            val showing = displaying != null && DisplaySession.current?.visible == true
            if (starting || showing) wifiLock.acquire() else wifiLock.release()
        } catch (e: Exception) {
            Log.w(TAG, "Wi-Fi lock: $e")
        }
    }

    /** The PC's desktop on this screen (DisplaySession, DisplayActivity),
     * until the PC hangs up or says stop. The key for its packets comes
     * over this paired link, fresh for each session. */
    private fun display(socket: SSLSocket, wire: Wire, request: JSONObject, pc: String) {
        val key = request.optString("key").chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
        val port = request.optInt("port")
        require(key.size == 32 && port in 1..65535) { "bad display request" }
        if (!(Settings.canDrawOverlays(this) || Front.shown)) {
            wire.send(JSONObject().put("type", "refused").put("reason", "background"))
            return
        }
        // HEVC if the PC asks for it and this tablet decodes it in hardware; H.264 otherwise.
        val codec = if (request.optString("codec") == "hevc" && DisplaySession.decodes(HEVC)) "hevc" else "avc"
        val session = DisplaySession(key, socket.inetAddress, port, if (codec == "hevc") HEVC else AVC)
        // Ended here (Back, Stop): the link closes, and the PC with it.
        session.onEnd = { Thread { runCatching { socket.close() } }.start() }
        session.onVisible = { lowLatency(false) }  // no low-latency Wi-Fi while nothing is shown
        // This one takes the screen: the one before ends, its PC told (its
        // link closes), not left encoding for nobody with its monitor up.
        val before = synchronized(DisplaySession) {
            DisplaySession.current.also { DisplaySession.current = session }
        }
        before?.stop()
        before?.end()
        session.start()
        try {
            // The refresh the panel will run at (DisplayActivity asks for its fastest).
            val mode = DisplayActivity.fastestMode(getSystemService(android.hardware.display.DisplayManager::class.java)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY))
            wire.send(JSONObject().put("type", "display-ready").put("port", session.socket.localPort)
                .put("width", maxOf(mode.physicalWidth, mode.physicalHeight))
                .put("height", minOf(mode.physicalWidth, mode.physicalHeight))
                .put("refresh", mode.refreshRate.toDouble()).put("codec", codec))
            startActivity(Intent(this, DisplayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            displaying = pc
            showStatus()
            lowLatency(true)
            socket.soTimeout = SILENT_PC
            while (wire.receive().optString("type") != "stop") continue
        } finally {
            session.stop()
            synchronized(DisplaySession) { if (DisplaySession.current === session) DisplaySession.current = null }
            android.os.Handler(mainLooper).post { DisplayActivity.ended(session) }
            if (DisplaySession.current == null) displaying = null
            showStatus()
            lowLatency(false)
        }
    }


    @Volatile private var displaying: String? = null  // the PC whose desktop is shown

    private fun showStatus() = getSystemService(android.app.NotificationManager::class.java)
        .notify(Notifications.SERVICE_ID, displaying?.let { Notifications.displaying(this, it) } ?: Notifications.ready(this))

    /** Numeric comparison: the app commits to its random number, learns the
     * PC's, shows its own; both screens then show a code made of the two
     * certificates and the two numbers, and the user confirms it on both. */
    private fun pair(socket: SSLSocket, wire: Wire, pc: ByteArray, pcName: String,
                     guard: java.util.concurrent.ScheduledFuture<*>): Boolean {
        val mine = Identity.certificate.encoded
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        wire.send(JSONObject().put("type", "pair-commit").put("commit", Identity.commitment(nonce, mine, pc)))
        val number = wire.receive(Wire.UNPAIRED_HEADER)
        val pcNonce = hex(number.optString("nonce"))
        require(number.optString("type") == "pair-nonce" && pcNonce?.size == 16) { "expected the PC's number" }
        wire.send(JSONObject().put("type", "pair-nonce").put("nonce", nonce.joinToString("") { "%02x".format(it) }))
        val code = Identity.pairingCode(pc, mine, pcNonce!!, nonce)
        guard.cancel(false)  // the user takes their time
        socket.soTimeout = PAIRING_TIMEOUT
        // The PC's answer may come first (refused there): it closes the
        // question here too, instead of leaving it up for two minutes.
        val answer = java.util.concurrent.atomic.AtomicReference<String>()
        val listening = Thread {
            val type = try { wire.receive(Wire.UNPAIRED_HEADER).optString("type") } catch (e: Throwable) { "" }
            answer.set(type)
            if (type != "pair-ok") Pairing.answer(code, false)
        }.apply { start() }
        val from = address(socket.inetAddress)
        val accepted = Pairing.ask(this, pcName, code, from) ?: return false  // another question is up, or too soon
        wire.send(JSONObject().put("type", if (accepted) "pair-ok" else "pair-no"))
        listening.join(PAIRING_TIMEOUT.toLong())
        return accepted && answer.get() == "pair-ok"
    }

    /** Whether a connection comes from this tablet: loopback, or one of its
     * own addresses (an app here can reach the Wi-Fi one too). */
    private fun fromHere(address: java.net.InetAddress?): Boolean =
        address == null || address.isLoopbackAddress || address.isAnyLocalAddress ||
            runCatching { java.net.NetworkInterface.getByInetAddress(address) != null }.getOrDefault(false)

    /** The PC's name as it says it, to be read: no control characters, none
     * that turn the text around them (U+202E and the like), not too long. */
    private fun readable(name: String): String =
        name.filter { !Character.isISOControl(it) && Character.getType(it) != Character.FORMAT.toInt() }
            .trim().take(64).ifEmpty { "PC" }

    /** An address as the PC writes it: IPv4 without the "::ffff:" prefix, no scope. */
    private fun address(address: java.net.InetAddress): String =
        address.hostAddress.orEmpty().substringBefore('%').removePrefix("::ffff:")

    private fun hex(s: String): ByteArray? =
        if (s.length % 2 != 0 || !s.all { it in "0123456789abcdef" }) null
        else ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    companion object {
        const val TAG = "Screenlet"
        const val SERVICE_TYPE = "_screenlet._tcp"
        const val PROTOCOL = 1
        const val PORT = 47237                 // another if it is taken (mDNS says which)
        const val MAX_CONNECTIONS = 32
        const val MAX_UNPAIRED = 4             // connections of peers not yet known, at a time
        const val MAX_HANDSHAKING = 8          // connections still in their TLS handshake, at a time
        const val LISTEN_RETRY = 30_000L       // ms before trying to listen again
        const val HANDSHAKE_DEADLINE = 20_000L // ms for an unknown peer's whole handshake
        const val HANDSHAKE_TIMEOUT = 20_000   // ms for TLS, hello and the pairing messages
        const val AVC = android.media.MediaFormat.MIMETYPE_VIDEO_AVC
        const val HEVC = android.media.MediaFormat.MIMETYPE_VIDEO_HEVC
        const val PAIRING_TIMEOUT = 150_000    // ms: the user compares the codes
        const val PAIRED_TIMEOUT = 90_000      // ms without a word from a paired PC
        const val SILENT_PC = 20_000           // ms without a ping during a display session
        const val IDLE_CHECK = 60_000L       // ms
        const val IDLE_STOP = 10 * 60_000L   // ms without a PC when not always ready
        const val USB_REOPEN_WAIT = 500L     // ms between looks for the accessory after its stream broke
        const val USB_REOPEN_TRIES = 6
        const val USB_STATE = "android.hardware.usb.action.USB_STATE"  // UsbManager's, hidden

        fun deviceName(context: Context): String =
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL

        /** The running service, for the activities that talk to it. */
        @Volatile var instance: CompanionService? = null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CompanionService::class.java))
        }

        /** Started (if it is not) with the PC's USB accessory to open. */
        fun startUsb(context: Context, accessory: UsbAccessory) {
            context.startForegroundService(Intent(context, CompanionService::class.java)
                .putExtra(UsbManager.EXTRA_ACCESSORY, accessory))
        }
    }
}
