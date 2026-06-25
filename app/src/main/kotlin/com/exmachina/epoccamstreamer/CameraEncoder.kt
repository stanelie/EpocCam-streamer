package com.exmachina.epoccamstreamer

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "CameraEncoder"

class CameraEncoder(
    private val context: Context,
    @Volatile var previewHolder: SurfaceHolder?,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val onNalUnit: (data: ByteArray, offset: Int, size: Int, isSps: Boolean) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var cameraCloseLatch: CountDownLatch? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null

    private val cameraThread = HandlerThread("epoc-camera").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)

    private val drainThread = Thread({ drainLoop() }, "epoc-drain")
    @Volatile private var nalLogCount = 0

    // AF state
    @Volatile private var currentAfApiMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
    private val afStateListener = AtomicReference<((Int) -> Unit)?>(null)

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val state = result.get(CaptureResult.CONTROL_AF_STATE) ?: return
            afStateListener.get()?.invoke(state)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!running.compareAndSet(false, true)) return
        Log.w(TAG, "start() w=$width h=$height bitrate=$bitrate")

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList[0]
        Log.w(TAG, "camera=$cameraId")

        val allCodecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        allCodecs.filter { it.isEncoder && it.supportedTypes.any { t -> t == "video/avc" } }
            .forEach { Log.w(TAG, "h264 encoder: ${it.name}") }

        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 60) // on-demand IDRs via requestIDR(); 60s fallback only
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }
        val enc = MediaCodec.createEncoderByType("video/avc")
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderSurface = enc.createInputSurface()
        enc.start()
        encoder = enc
        drainThread.start()

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                Log.w(TAG, "onOpened running=${running.get()}")
                if (!running.get()) { camera.close(); return }
                cameraDevice = camera
                startCapture(camera)
            }
            override fun onDisconnected(camera: CameraDevice) {
                Log.w(TAG, "onDisconnected")
                camera.close(); cameraDevice = null
            }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "onError $error"); camera.close(); cameraDevice = null
            }
            override fun onClosed(camera: CameraDevice) {
                Log.w(TAG, "camera onClosed")
                cameraCloseLatch?.countDown()
            }
        }, cameraHandler)
    }

    private fun buildRequest(
        camera: CameraDevice,
        preview: Surface?,
        enc: Surface,
        afMode: Int,
        afTrigger: Int = CaptureRequest.CONTROL_AF_TRIGGER_IDLE
    ) = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
        preview?.let { addTarget(it) }
        addTarget(enc)
        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(fps, fps))
        set(CaptureRequest.CONTROL_AF_MODE, afMode)
        set(CaptureRequest.CONTROL_AF_TRIGGER, afTrigger)
        set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
        set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF)
        set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_OFF)
        set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF)
        set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
    }.build()

    private fun startCapture(camera: CameraDevice) {
        val enc = encoderSurface ?: return
        val preview = previewHolder?.surface
        val surfaces = listOfNotNull(preview, enc)
        camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (!running.get()) { session.close(); return }
                captureSession = session
                try {
                    session.setRepeatingRequest(
                        buildRequest(camera, preview, enc, currentAfApiMode),
                        captureCallback, cameraHandler
                    )
                } catch (e: IllegalStateException) {
                    // Session was superseded by a newer createCaptureSession call before
                    // this onConfigured callback fired. The newer session will take over.
                    Log.w(TAG, "onConfigured: session already closed, superseded: $e")
                }
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "capture session config failed")
            }
        }, cameraHandler)
    }

    fun updatePreview(holder: SurfaceHolder?) {
        val wasNull = previewHolder == null
        previewHolder = holder
        val cam = cameraDevice ?: return
        // null→null means the surface resized during a format change where this encoder never
        // had a preview. Restarting the session here would race with the onConfigured callback
        // that's already coming. Let surfaceCreated deliver the first real holder instead.
        if (wasNull && holder == null) return
        captureSession?.close()
        captureSession = null
        startCapture(cam)
        if (holder != null) requestIDR()
    }

    fun setContinuousAf() {
        currentAfApiMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        afStateListener.set(null)
        val cam = cameraDevice ?: return
        val session = captureSession ?: return
        val enc = encoderSurface ?: return
        val preview = previewHolder?.surface
        try {
            session.setRepeatingRequest(
                buildRequest(cam, preview, enc, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO),
                captureCallback, cameraHandler
            )
        } catch (e: Exception) { Log.e(TAG, "setContinuousAf failed: $e") }
    }

    fun triggerAfAndLock(onDone: (focused: Boolean) -> Unit) {
        val cam = cameraDevice ?: return
        val session = captureSession ?: return
        val enc = encoderSurface ?: return
        val preview = previewHolder?.surface
        currentAfApiMode = CaptureRequest.CONTROL_AF_MODE_AUTO
        try {
            // Cancel any prior lock so AF state machine resets
            session.capture(
                buildRequest(cam, preview, enc, CaptureRequest.CONTROL_AF_MODE_AUTO,
                    CaptureRequest.CONTROL_AF_TRIGGER_CANCEL),
                null, cameraHandler
            )
            // Arm listener before triggering
            var reported = false
            afStateListener.set { state ->
                if (!reported && (state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                                  state == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)) {
                    reported = true
                    afStateListener.set(null)
                    onDone(state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED)
                }
            }
            // One-shot trigger
            session.capture(
                buildRequest(cam, preview, enc, CaptureRequest.CONTROL_AF_MODE_AUTO,
                    CaptureRequest.CONTROL_AF_TRIGGER_START),
                captureCallback, cameraHandler
            )
            // Repeating with AF_MODE_AUTO keeps AF locked and feeds state to the listener
            session.setRepeatingRequest(
                buildRequest(cam, preview, enc, CaptureRequest.CONTROL_AF_MODE_AUTO),
                captureCallback, cameraHandler
            )
        } catch (e: Exception) { Log.e(TAG, "triggerAfAndLock failed: $e") }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        val enc = encoder ?: return
        var frameCount = 0
        var lastHeartbeatMs = 0L
        var lastNalMs = 0L

        while (running.get()) {
            val idx = try {
                enc.dequeueOutputBuffer(info, 10_000L)
            } catch (e: Exception) {
                break  // encoder stopped from another thread during resolution change
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.w(TAG, "output format changed: ${enc.outputFormat}")
                continue
            }
            if (idx < 0) continue

            frameCount++
            val now = android.os.SystemClock.uptimeMillis()
            if (lastNalMs > 0 && now - lastNalMs > 50) {
                Log.w(TAG, "ENCODER STALL: ${now - lastNalMs}ms (fc=$frameCount)")
            }
            lastNalMs = now
            if (now - lastHeartbeatMs >= 2000) {
                Log.w(TAG, "heartbeat: $frameCount NAL units in last 2s")
                frameCount = 0; lastHeartbeatMs = now
            }

            val buf = enc.getOutputBuffer(idx)
            if (buf == null) { enc.releaseOutputBuffer(idx, false); continue }
            buf.position(info.offset); buf.limit(info.offset + info.size)
            val data = ByteArray(info.size)
            buf.get(data)
            enc.releaseOutputBuffer(idx, false)

            val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            if (isConfig) Log.w(TAG, "codec config (${info.size}B): ${data.take(info.size).joinToString(" ") { "%02x".format(it) }}")
            if (info.size > 0) splitAndDispatch(data, 0, info.size, isConfig)
        }
    }

    private fun splitAndDispatch(data: ByteArray, offset: Int, size: Int, isConfig: Boolean) {
        val end = offset + size
        var nalStart = -1
        var i = offset

        fun dispatch(from: Int, to: Int) {
            if (to <= from) return
            var sc = from
            while (sc < to - 1 && !(data[sc] == 0.toByte() && data[sc+1] == 0.toByte() &&
                        (sc+2 < to && (data[sc+2] == 1.toByte() ||
                            (data[sc+2] == 0.toByte() && sc+3 < to && data[sc+3] == 1.toByte()))))) sc++
            val typeByte = if (sc + 3 < to && data[sc+2] == 0.toByte()) data[sc+4].toInt() and 0x1F
                           else if (sc + 2 < to) data[sc+3].toInt() and 0x1F
                           else -1
            if (nalLogCount < 80 && typeByte >= 0) { nalLogCount++; Log.w(TAG, "NAL type=$typeByte size=${to-from} isConfig=$isConfig") }
            if (typeByte == 12 || typeByte == 9 || typeByte == 6) return  // strip filler/AUD/SEI
            if (!isConfig && (typeByte == 7 || typeByte == 8)) return
            onNalUnit(data, from, to - from, isConfig && (typeByte == 7 || typeByte == 8))
        }

        while (i < end - 3) {
            val is3 = data[i] == 0.toByte() && data[i+1] == 0.toByte() && data[i+2] == 1.toByte()
            val is4 = i + 3 < end && data[i] == 0.toByte() && data[i+1] == 0.toByte() &&
                      data[i+2] == 0.toByte() && data[i+3] == 1.toByte()
            if (is3 || is4) {
                if (nalStart >= 0) dispatch(nalStart, i)
                nalStart = i
                i += if (is4) 4 else 3
            } else i++
        }
        if (nalStart >= 0) dispatch(nalStart, end)
    }

    fun requestIDR() {
        encoder?.setParameters(android.os.Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        })
    }

    fun stop() {
        Log.w(TAG, "stop() called w=$width h=$height")
        running.set(false)
        afStateListener.set(null)
        captureSession?.close(); captureSession = null
        val cam = cameraDevice; cameraDevice = null
        if (cam != null) {
            cameraCloseLatch = CountDownLatch(1)
            cam.close()
            // Wait for HAL to confirm camera closed before releasing encoder surface.
            // Without this, the camera HAL keeps writing to a released surface → buffer timeout.
            cameraCloseLatch?.await(2, TimeUnit.SECONDS)
            cameraCloseLatch = null
        }
        encoderSurface?.release(); encoderSurface = null
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
        cameraThread.quitSafely()
    }
}
