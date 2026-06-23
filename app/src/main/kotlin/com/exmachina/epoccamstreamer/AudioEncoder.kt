package com.exmachina.epoccamstreamer

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "AudioEncoder"

// AAC-LC AudioSpecificConfig: objectType=2, sampleRateIdx=4 (44100Hz), channels=1 (mono)
// 0001 0010 0000 1000 = 0x12 0x08
private val AUDIO_SPECIFIC_CONFIG = byteArrayOf(0x12.toByte(), 0x08.toByte())

class AudioEncoder(
    private val onAudioPacket: (ByteArray) -> Unit
) {
    private val running = AtomicBoolean(false)
    private val thread = Thread({ run() }, "epoc-audio")

    // The audio config packet — built at start(), stored so it can be re-sent when a viewer connects.
    @Volatile var configPacket: ByteArray? = null

    // Reset the audio PTS to 0 so timestamps re-sync with a new video stream.
    fun resetTimestamp() { audioTs = 0L }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread.start()
    }

    fun stop() {
        running.set(false)
        thread.interrupt()
    }

    private fun run() {
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        val bufSize = maxOf(minBuf, 2048)

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate, channelConfig, encoding, bufSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            return
        }

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufSize)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        // Build and store the audio config — caller sends it at the right time (after format-select).
        configPacket = buildAudioPacket(AUDIO_SPECIFIC_CONFIG, isConfig = true)
        Log.w(TAG, "audio config built: ${AUDIO_SPECIFIC_CONFIG.joinToString(" ") { "%02x".format(it) }}")

        record.startRecording()
        val pcmBuf = ByteArray(bufSize)
        val info = MediaCodec.BufferInfo()
        var presentationUs = 0L
        val bytesPerSample = 2  // 16-bit PCM
        val usPerByte = 1_000_000.0 / (sampleRate * bytesPerSample)

        try {
            while (running.get()) {
                val n = record.read(pcmBuf, 0, bufSize)
                if (n <= 0) continue

                // Feed PCM to encoder
                val inIdx = codec.dequeueInputBuffer(5_000L)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    buf.clear(); buf.put(pcmBuf, 0, n)
                    codec.queueInputBuffer(inIdx, 0, n, presentationUs, 0)
                    presentationUs += (n * usPerByte).toLong()
                }

                // Drain encoded output
                while (true) {
                    val outIdx = codec.dequeueOutputBuffer(info, 0L)
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                    if (outIdx < 0) break
                    if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val out = codec.getOutputBuffer(outIdx)!!
                        out.position(info.offset); out.limit(info.offset + info.size)
                        val aacData = ByteArray(info.size)
                        out.get(aacData)
                        onAudioPacket(buildAudioPacket(aacData, isConfig = false))
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }
        } finally {
            record.stop(); record.release()
            codec.stop(); codec.release()
        }
    }

    private var audioTs = 0L

    private fun buildAudioPacket(payload: ByteArray, isConfig: Boolean): ByteArray {
        val ts = if (isConfig) 0L else audioTs.also { audioTs += 23L }  // ~43 frames/s at 44100
        val pkt = ByteArray(28 + payload.size)
        fun putLE32(offset: Int, v: Long) {
            pkt[offset]   = (v and 0xFF).toByte()
            pkt[offset+1] = ((v shr 8) and 0xFF).toByte()
            pkt[offset+2] = ((v shr 16) and 0xFF).toByte()
            pkt[offset+3] = ((v shr 24) and 0xFF).toByte()
        }
        putLE32(0,  0xDEADC0DEL)
        putLE32(4,  0xDEADC0DEL)
        putLE32(8,  0x00020004L)                          // audio type
        putLE32(12, (payload.size + 12).toLong())
        putLE32(16, if (isConfig) 1L else 0L)             // flags
        putLE32(20, ts)
        putLE32(24, payload.size.toLong())
        System.arraycopy(payload, 0, pkt, 28, payload.size)
        return pkt
    }
}
