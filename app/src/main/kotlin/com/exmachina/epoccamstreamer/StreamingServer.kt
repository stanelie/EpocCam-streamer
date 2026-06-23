package com.exmachina.epoccamstreamer

import android.util.Log
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "StreamingServer"
private const val PORT_LISTEN = 5054
private const val MAX_QUEUE = 60

class StreamingServer(
    private val onStatus: (String) -> Unit,
    private val onFormatSelect: (Int) -> Unit = {},
    private val onViewerDisconnect: () -> Unit = {},
    var capabilityPacket: ByteArray? = null
) {
    private val running = AtomicBoolean(true)
    private val queue = LinkedBlockingQueue<ByteArray>(MAX_QUEUE)

    @Volatile private var output: OutputStream? = null
    @Volatile private var currentSocket: Socket? = null
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var lastSuccessfulWriteMs = 0L

    var configPacket: ByteArray? = null

    // Returns ms since last successful write, or null if no viewer connected / no write yet.
    fun msSinceLastWrite(): Long? {
        val last = lastSuccessfulWriteMs
        return if (last == 0L || output == null) null
        else android.os.SystemClock.elapsedRealtime() - last
    }

    fun hasActiveConnection() = output != null

    fun forceDisconnect() {
        Log.w(TAG, "forceDisconnect: closing socket")
        output = null
        try { currentSocket?.close() } catch (_: Exception) {}
    }

    private val listenThread = Thread({ listenLoop() }, "epoc-listen")
    private val sendThread   = Thread({ sendLoop() },   "epoc-send")

    fun start() {
        listenThread.start()
        sendThread.start()
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        try { currentSocket?.close() } catch (_: Exception) {}
        listenThread.interrupt()
        sendThread.interrupt()
    }

    fun flushQueue() { queue.clear() }

    fun enqueue(packet: ByteArray) {
        if (output == null) return
        if (!queue.offer(packet)) {
            queue.poll()
            queue.offer(packet)
        }
    }

    private fun listenLoop() {
        try {
            serverSocket = ServerSocket(PORT_LISTEN).also { it.reuseAddress = true }
        } catch (e: Exception) {
            Log.e(TAG, "failed to bind port $PORT_LISTEN: ${e.message}")
            return
        }
        val srv = serverSocket ?: return
        while (running.get()) {
            try {
                Log.w(TAG, "accept: waiting")
                onStatus("Waiting for viewer…")
                val sock = srv.accept()
                Log.w(TAG, "accept: got connection")
                sock.tcpNoDelay = true
                val addr = sock.inetAddress.hostAddress
                onStatus("Viewer connected ($addr)")
                Log.w(TAG, "connection accepted from $addr configPacket=${if (configPacket != null) "${configPacket!!.size}B" else "null"}")
                handleConnection(sock)
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "listen: ${e.message}")
            }
        }
    }

    private fun handleConnection(sock: Socket) {
        currentSocket = sock
        try {
            val out = sock.getOutputStream()

            capabilityPacket?.let { out.write(it); out.flush() }
            configPacket?.let  { out.write(it); out.flush() }

            queue.clear()
            output = out

            val inp = sock.getInputStream()
            val rxBuf = ByteArray(256)
            Thread({
                try {
                    while (running.get()) {
                        val n = inp.read(rxBuf)
                        if (n < 0) break
                        val hex = rxBuf.take(minOf(n, 64)).joinToString(" ") { "%02x".format(it) }
                        Log.w(TAG, "viewer→phone ($n bytes): $hex")
                        if (n >= 12) {
                            val type = (rxBuf[8].toInt() and 0xFF) or
                                       ((rxBuf[9].toInt() and 0xFF) shl 8) or
                                       ((rxBuf[10].toInt() and 0xFF) shl 16) or
                                       ((rxBuf[11].toInt() and 0xFF) shl 24)
                            if (type == 0x00020003) {
                                val idx = (rxBuf[16].toInt() and 0xFF) or ((rxBuf[17].toInt() and 0xFF) shl 8)
                                Log.w(TAG, "format-select idx=$idx")
                                onFormatSelect(idx)
                            } else {
                                Log.w(TAG, "viewer packet type=0x${"%08x".format(type)}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "rx: ${e.message}")
                } finally {
                    output = null  // stop send loop before socket close to prevent writes to closing connection
                    try { sock.close() } catch (_: Exception) {}
                }
            }, "epoc-rx").start()

            while (running.get() && output != null && !sock.isClosed) {
                Thread.sleep(200)
            }
        } catch (e: Exception) {
            Log.w(TAG, "connection lost: ${e.message}")
        } finally {
            lastSuccessfulWriteMs = 0L
            output = null
            try { sock.close() } catch (_: Exception) {}
            currentSocket = null
            Log.w(TAG, "connection closed, calling onViewerDisconnect")
            onStatus("Viewer disconnected")
            onViewerDisconnect()
        }
    }

    private fun sendLoop() {
        while (running.get()) {
            try {
                val packet = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                val out = output ?: continue
                val now = android.os.SystemClock.elapsedRealtime()
                val last = lastSuccessfulWriteMs
                if (last > 0) {
                    val gap = now - last
                    if (gap > 50) Log.w(TAG, "SEND GAP: ${gap}ms queue=${queue.size} pktSize=${packet.size}")
                }
                val t0 = android.os.SystemClock.elapsedRealtime()
                out.write(packet)
                val dt = android.os.SystemClock.elapsedRealtime() - t0
                if (dt > 10) Log.w(TAG, "SLOW WRITE: ${dt}ms size=${packet.size}")
                lastSuccessfulWriteMs = android.os.SystemClock.elapsedRealtime()
            } catch (e: InterruptedException) {
                // Normal during shutdown
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "send error: ${e.message}")
                output = null
                try { currentSocket?.close() } catch (_: Exception) {}
            }
        }
    }
}
