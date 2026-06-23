package com.exmachina.epoccamstreamer

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.content.Intent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean


private const val TAG = "MainActivity"
private const val NSD_TYPE  = "_epoccam._tcp"
private const val NSD_TYPE2 = "_epoccamsvc._tcp"
private const val LISTEN_PORT = 5054

// Must match the formats advertised in Protocol.buildCapabilityPacket(): index 0=HD, index 1=SD.
private val FORMATS  = listOf(Pair(1280, 720), Pair(640, 480))
// Bitrates from Android 1.13 smali: h.c=0x3567E0 for 1280×720, h.c=0x2625A0 for 720×480 (SD).
private val BITRATES = listOf(3_500_000, 2_500_000)

class MainActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var statusText: TextView
    private lateinit var surfaceHolder: SurfaceHolder
    private lateinit var focusModeButton: Button
    private var tapFocusMode = false  // false = continuous AF, true = tap-to-lock
    private var server: StreamingServer? = null
    private var encoder: CameraEncoder? = null
    private var nsdManager: NsdManager? = null
    private var nsdRegistered  = false
    private var nsdRegistered2 = false
    private var nsdListener1: NsdManager.RegistrationListener? = null
    private var nsdListener2: NsdManager.RegistrationListener? = null

    private val fps = 30

    // Default to SD (format index 1 = 640×480); switched to HD on viewer request.
    @Volatile private var currentFmt = 1

    private val configSent     = AtomicBoolean(false)
    private val formatSelected = AtomicBoolean(false)
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var codecConfig: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        val sv = findViewById<android.view.SurfaceView>(R.id.surfaceView)
        surfaceHolder = sv.holder
        surfaceHolder.addCallback(this)

        focusModeButton = findViewById(R.id.focusModeButton)
        focusModeButton.setOnClickListener {
            tapFocusMode = !tapFocusMode
            if (tapFocusMode) {
                focusModeButton.text = "MF"
            } else {
                focusModeButton.text = "AF"
                encoder?.setContinuousAf()
            }
        }

        val root = findViewById<FrameLayout>(R.id.rootLayout)
        root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN && tapFocusMode) {
                triggerFocus(); true
            } else false
        }

        val missingPerms = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missingPerms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPerms.toTypedArray(), 1)
        }
    }

    private fun triggerFocus() {
        focusModeButton.text = "..."
        encoder?.triggerAfAndLock { focused ->
            runOnUiThread { focusModeButton.text = if (focused) "MF" else "MF?" }
        }
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(req, perms, results)
        if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) startStreaming()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val cameraOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val audioOk  = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!cameraOk || !audioOk) return
        if (server != null) {
            encoder?.updatePreview(holder)  // returning to foreground — restore preview
        } else {
            startStreaming()
        }
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        encoder?.updatePreview(null)  // drop preview but keep camera + encoder running
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        moveTaskToBack(true)  // minimize instead of finish
    }

    fun onViewerDisconnected() {
        formatSelected.set(false)
        configSent.set(false)
        server?.configPacket = null
        Log.d(TAG, "viewer disconnected — reset stream gate, bouncing mDNS")
        // Viewer waits for mDNS goodbye+reannounce before reconnecting after resolution change.
        runOnUiThread {
            unregisterMdns()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ registerMdns() }, 400)
        }
    }

    fun onFormatSelected(index: Int) {
        val fmt = index.coerceIn(0, FORMATS.size - 1)
        Log.w(TAG, "onFormatSelected: idx=$fmt (${FORMATS[fmt].first}×${FORMATS[fmt].second}) currentFmt=$currentFmt configSent=${configSent.get()} formatSelected=${formatSelected.get()} codecConfig=${codecConfig?.size}B")
        configSent.set(false)

        if (fmt == currentFmt) {
            // Same resolution: just open gate and request a fresh IDR.
            Log.w(TAG, "same resolution — requesting IDR, setting formatSelected=true")
            formatSelected.set(true)
            encoder?.requestIDR()
        } else {
            // Resolution change: close gate, flush stale frames, stop old encoder, start new one.
            Log.w(TAG, "resolution change: ${FORMATS[currentFmt].first}×${FORMATS[currentFmt].second} → ${FORMATS[fmt].first}×${FORMATS[fmt].second}")
            formatSelected.set(false)
            currentFmt = fmt
            codecConfig = null
            server?.flushQueue()
            val old = encoder
            encoder = null
            Log.w(TAG, "stopping old encoder")
            old?.stop()
            Log.w(TAG, "old encoder stopped, starting new encoder ${FORMATS[fmt].first}×${FORMATS[fmt].second}")
            encoder = CameraEncoder(
                context       = this,
                previewHolder = if (surfaceHolder.surface.isValid) surfaceHolder else null,
                width         = FORMATS[fmt].first,
                height        = FORMATS[fmt].second,
                fps           = fps,
                bitrate       = BITRATES[fmt],
                onNalUnit     = ::onNalUnit
            ).also { it.start() }
            Log.w(TAG, "new encoder started, setting formatSelected=true")
            formatSelected.set(true)
        }
    }

    private fun makeMdnsListener(slot: Int) = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(si: NsdServiceInfo) {
            Log.w(TAG, "mDNS slot$slot registered: ${si.serviceType}")
            if (slot == 1) { nsdRegistered = true; runOnUiThread { statusText.text = "Waiting for viewer…" } }
            else nsdRegistered2 = true
        }
        override fun onRegistrationFailed(si: NsdServiceInfo, err: Int) { Log.e(TAG, "mDNS slot$slot registration failed: $err") }
        override fun onServiceUnregistered(si: NsdServiceInfo) {
            Log.w(TAG, "mDNS slot$slot unregistered: ${si.serviceType}")
            if (slot == 1) nsdRegistered = false else nsdRegistered2 = false
        }
        override fun onUnregistrationFailed(si: NsdServiceInfo, err: Int) { Log.e(TAG, "mDNS slot$slot unregistration failed: $err") }
    }

    private fun registerMdns() {
        val mgr = getSystemService(NSD_SERVICE) as NsdManager
        nsdManager = mgr
        nsdListener1 = makeMdnsListener(1).also { listener ->
            mgr.registerService(
                NsdServiceInfo().apply { serviceName = "mobile"; serviceType = NSD_TYPE;  port = LISTEN_PORT },
                NsdManager.PROTOCOL_DNS_SD, listener)
        }
        nsdListener2 = makeMdnsListener(2).also { listener ->
            mgr.registerService(
                NsdServiceInfo().apply { serviceName = "mobile"; serviceType = NSD_TYPE2; port = LISTEN_PORT },
                NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    private fun unregisterMdns() {
        val mgr = nsdManager ?: return
        if (nsdRegistered)  try { nsdListener1?.let { mgr.unregisterService(it) } } catch (_: Exception) {}
        if (nsdRegistered2) try { nsdListener2?.let { mgr.unregisterService(it) } } catch (_: Exception) {}
        nsdRegistered = false; nsdRegistered2 = false
    }

    private fun startStreaming() {
        if (server != null) return
        startForegroundService(Intent(this, StreamingService::class.java))
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "epoccam_stream").also { it.acquire() }
        val capPkt = Protocol.buildCapabilityPacket()
        server = StreamingServer(
            onStatus           = { msg -> runOnUiThread { statusText.text = msg } },
            onFormatSelect     = { idx -> onFormatSelected(idx) },
            onViewerDisconnect = { onViewerDisconnected() },
            capabilityPacket   = capPkt
        ).also { it.start() }
        encoder = CameraEncoder(
            context       = this,
            previewHolder = if (surfaceHolder.surface.isValid) surfaceHolder else null,
            width         = FORMATS[currentFmt].first,
            height        = FORMATS[currentFmt].second,
            fps           = fps,
            bitrate       = BITRATES[currentFmt],
            onNalUnit     = ::onNalUnit
        ).also { it.start() }
        registerMdns()
    }

    private fun stopStreaming() {
        unregisterMdns()
        encoder?.stop(); encoder = null
        server?.stop();  server  = null
        wifiLock?.release(); wifiLock = null
    }

    private fun onNalUnit(data: ByteArray, offset: Int, size: Int, isSps: Boolean) {
        if (isSps) {
            val raw = data.copyOfRange(offset, offset + size)
            val nalType = findFirstNalByte(data, offset, size).let { if (it >= 0) data[it].toInt() and 0x1F else -1 }
            if (nalType == 7) {
                // SPS: strip VUI (prevents FFmpeg CPB-buffering); returns with 3-byte start code.
                codecConfig = SpsUtil.stripVui(raw)
                Log.w(TAG, "SPS stored ${codecConfig?.size}B")
            } else {
                // PPS (or other config NAL): normalize to 3-byte start code and append.
                val norm = if (raw.size >= 4 && raw[0]==0.toByte() && raw[1]==0.toByte() &&
                               raw[2]==0.toByte() && raw[3]==1.toByte())
                    raw.copyOfRange(1, raw.size) else raw
                codecConfig = (codecConfig ?: ByteArray(0)) + norm
            }
            return
        }

        if (!formatSelected.get()) return
        val srv = server ?: return
        val cfg = codecConfig ?: return  // drop frames from old encoder until new SPS arrives
        val nalHeaderIdx = findFirstNalByte(data, offset, size)
        val isIdr = nalHeaderIdx >= 0 && (data[nalHeaderIdx].toInt() and 0x1F) == 5
        if (isIdr && configSent.compareAndSet(false, true)) {
            // Match iPhone wire protocol exactly:
            // 1. 4-byte pre-packet (00 00 00 05) — signals decoder reset to viewer
            // 2. SPS+PPS+IDR bundled in one regular video packet (flags=0, not flags=8)
            srv.enqueue(Protocol.buildHeader(4, 0xFFFFFFFFL) + byteArrayOf(0, 0, 0, 5))
            val skip = if (size >= 4 && data[offset]==0.toByte() && data[offset+1]==0.toByte() &&
                          data[offset+2]==0.toByte() && data[offset+3]==1.toByte()) 1 else 0
            val idrData = data.copyOfRange(offset + skip, offset + size)
            val bundle = cfg + idrData  // SPS+PPS (3-byte SC) + IDR (3-byte SC)
            val hdr = Protocol.buildHeader(bundle.size, 0xFFFFFFFFL)
            val pkt = ByteArray(28 + bundle.size)
            System.arraycopy(hdr, 0, pkt, 0, 28)
            System.arraycopy(bundle, 0, pkt, 28, bundle.size)
            Log.w(TAG, "bundled SPS+PPS+IDR: ${bundle.size}B (cfg=${cfg.size}B idr=${idrData.size}B)")
            srv.configPacket = pkt
            srv.enqueue(pkt)
            return  // IDR already included in bundle; don't also send it via sendNalPacket
        }
        sendNalPacket(srv, data, offset, size)
    }

    private fun sendNalPacket(srv: StreamingServer, data: ByteArray, offset: Int, size: Int) {
        val skip = if (size >= 4 &&
            data[offset] == 0.toByte() && data[offset+1] == 0.toByte() &&
            data[offset+2] == 0.toByte() && data[offset+3] == 1.toByte()) 1 else 0
        val nalData = data.copyOfRange(offset + skip, offset + size)
        val hdr = Protocol.buildHeader(nalData.size, 0xFFFFFFFFL)
        val packet = ByteArray(28 + nalData.size)
        System.arraycopy(hdr, 0, packet, 0, 28)
        System.arraycopy(nalData, 0, packet, 28, nalData.size)
        srv.enqueue(packet)
    }

    private fun findFirstNalByte(data: ByteArray, offset: Int, size: Int): Int {
        val limit = offset + size; var i = offset
        while (i < limit - 3) {
            if (data[i] == 0.toByte() && data[i+1] == 0.toByte()) {
                if (data[i+2] == 1.toByte()) return i + 3
                if (data[i+2] == 0.toByte() && i + 3 < limit && data[i+3] == 1.toByte()) return i + 4
            }
            i++
        }
        return -1
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            stopService(Intent(this, StreamingService::class.java))
            stopStreaming()
        }
    }
}
