package com.exmachina.epoccamstreamer

import java.lang.Float.floatToIntBits

object Protocol {

    // Find the next H264 Annex-B start code (0x00 0x00 0x01) starting at offset.
    // Returns the index of the 0x01 byte, or -1 if not found.
    fun findStartCode(buf: ByteArray, offset: Int, limit: Int): Int {
        var i = offset
        while (i < limit - 2) {
            if (buf[i] == 0.toByte() && buf[i + 1] == 0.toByte() && buf[i + 2] == 1.toByte()) {
                return i
            }
            i++
        }
        return -1
    }

    // Build the 28-byte packet header (all fields little-endian).
    // Layout (confirmed from u.smali + pcap analysis):
    //   [0-3]   0xDEADC0DE magic
    //   [4-7]   0x00000000 (iPhone uses 0 here; original Android used 0xDEADC0DE)
    //   [8-11]  0x00020002 type (video)
    //   [12-15] nalSize + 12
    //   [16-19] camera orientation flags: 0 = back camera (no mirror), 0x10 = front camera (mirror)
    //           The viewer uses this to apply horizontal flip. Must NOT be the frame counter.
    //   [20-23] timestamp ms (0xFFFFFFFF = display immediately, no viewer buffering — iPhone protocol)
    //   [24-27] nalSize
    fun buildHeader(nalSize: Int, timestampMs: Long, cameraFlags: Int = 0): ByteArray {
        val h = ByteArray(28)
        putLE32(h, 0, 0xDEADC0DE.toInt())
        putLE32(h, 4, 0x00000000)
        putLE32(h, 8, 0x00020002)
        putLE32(h, 12, nalSize + 12)
        putLE32(h, 16, cameraFlags)
        putLE32(h, 20, timestampMs.toInt())
        putLE32(h, 24, nalSize)
        return h
    }

    // Special header for the SPS+PPS config packet: cameraFlags=8, timestamp=0.
    // Original app (u.smali a([B)V) hardcodes flags=8 for config packets.
    fun buildConfigHeader(nalSize: Int): ByteArray = buildHeader(nalSize, 0, 8)

    // Build the 352-byte capability announcement packet (type 0x00020005).
    //
    // iPhone HD sends 2 formats: [1280×720, H.264, 30fps] and [640×480, H.264, 30fps].
    // Android 1.13 sends 1 format: [640×480, H.264, 0fps].
    //
    // Viewer classifies device type (and whether to show watermark) from these fields:
    //   [16-19] protocol version: iPhone=0xC9=201, Android=0x12D=301
    //   payload: number of formats and whether HD (1280×720) is advertised
    //
    // We advertise 2 formats matching the iPhone HD protocol to avoid the watermark.
    // The viewer format-select response will choose format index 1 (640×480), which we
    // deliver — it chose SD even for the real iPhone HD.
    //
    // VideoSize struct (from viewer binary type encoding):
    //   bits 0-11:  width  (12 bits)
    //   bits 12-23: height (12 bits)
    //   bits 24-31: codec type (8 bits): 1=H.264
    //   bytes 4-7:  frame rate as float32 LE
    fun buildCapabilityPacket(): ByteArray {
        val total = 0x160           // 352 bytes, always fixed
        val dataSize = total - 28   // 324
        val buf = ByteArray(total)  // zero-filled

        putLE32(buf, 0,  0xDEADC0DE.toInt())
        putLE32(buf, 4,  0x00000000)
        putLE32(buf, 8,  0x00020005)
        putLE32(buf, 12, dataSize + 12)  // 336 = 0x150
        putLE32(buf, 16, 0xC9)           // protocol version: iPhone HD value (Android used 0x12D)
        // [20-23] timestamp = 0
        // [24-27] = 0x01000000 as found in iPhone pcap (matches iPhone byte pattern)
        putLE32(buf, 24, 0x01000000)

        // Payload: 2 formats (HD + SD), each 8 bytes: [4-byte VideoSize | 4-byte float rate]
        putLE32(buf, 28, 2)  // numFormats = 2

        // Format 0: 1280×720, codec=1 (H.264), 30fps — marks us as HD device (no watermark)
        putLE32(buf, 32, (1 shl 24) or (720 shl 12) or 1280)  // 0x012D0500
        putLE32(buf, 36, floatToIntBits(30.0f))                // 0x41F00000

        // Format 1: 640×480, codec=1 (H.264), 30fps — what we actually stream at
        putLE32(buf, 40, (1 shl 24) or (480 shl 12) or 640)   // 0x011E0280
        putLE32(buf, 44, floatToIntBits(30.0f))                // 0x41F00000

        return buf
    }

    private fun putLE32(buf: ByteArray, offset: Int, value: Int) {
        buf[offset]     = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
