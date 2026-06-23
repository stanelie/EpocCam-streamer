package com.exmachina.epoccamstreamer

object SpsUtil {

    /**
     * Strip VUI from an H264 SPS NAL unit. All other SPS fields are passed through unchanged.
     *
     * The Qualcomm encoder emits VUI with timing/HRD parameters that cause FFmpeg to enable
     * strict CPB buffering. Stripping VUI removes this behavior. poc_type, frame_num bits,
     * and all other semantics are left exactly as the encoder wrote them.
     */
    fun stripVui(spsWithStartCode: ByteArray): ByteArray {
        val nalOff = nalOffset(spsWithStartCode)
        val sps = spsWithStartCode.copyOfRange(nalOff, spsWithStartCode.size)

        val r = BitReader(sps)
        val w = BitWriter()

        w.copyFrom(r, 8)  // NAL unit header

        val profileIdc = r.peekByte()
        w.copyFrom(r, 8)  // profile_idc
        w.copyFrom(r, 8)  // constraint_set_flags + reserved_zero_2bits
        w.copyFrom(r, 8)  // level_idc

        w.passUE(r)       // seq_parameter_set_id

        if (profileIdc in listOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138)) {
            val chromaFmtIdc = r.readUE(); w.writeUE(chromaFmtIdc)
            if (chromaFmtIdc == 3) w.copyFrom(r, 1)
            w.passUE(r); w.passUE(r)  // bit_depth_luma/chroma_minus8
            w.copyFrom(r, 1)           // qpprime_y_zero_transform_bypass_flag
            val scalingPresent = r.readBit(); w.writeBit(scalingPresent)
            if (scalingPresent == 1) {
                val listCount = if (chromaFmtIdc != 3) 8 else 12
                repeat(listCount) { idx ->
                    val listPresent = r.readBit(); w.writeBit(listPresent)
                    if (listPresent == 1) {
                        val sz = if (idx < 6) 16 else 64
                        var last = 8; var next = 8
                        repeat(sz) {
                            if (next != 0) { val delta = r.readSE(); w.writeSE(delta); next = (last + delta + 256) % 256 }
                            last = if (next == 0) last else next
                        }
                    }
                }
            }
        }

        w.passUE(r)  // log2_max_frame_num_minus4 (pass through as-is)

        val pocType = r.readUE(); w.writeUE(pocType)
        when (pocType) {
            0 -> w.passUE(r)  // log2_max_pic_order_cnt_lsb_minus4
            1 -> {
                w.copyFrom(r, 1)  // delta_pic_order_always_zero_flag
                w.passSE(r)       // offset_for_non_ref_pic
                w.passSE(r)       // offset_for_top_to_bottom_field
                val n = r.readUE(); w.writeUE(n)
                repeat(n) { w.passSE(r) }  // offset_for_ref_frame[i]
            }
            // poc_type=2: no additional fields
        }

        w.passUE(r)        // max_num_ref_frames
        w.copyFrom(r, 1)   // gaps_in_frame_num_value_allowed_flag
        w.passUE(r)        // pic_width_in_mbs_minus1
        w.passUE(r)        // pic_height_in_map_units_minus1

        val frameMbsOnly = r.readBit(); w.writeBit(frameMbsOnly)
        if (frameMbsOnly == 0) w.copyFrom(r, 1)

        w.copyFrom(r, 1)  // direct_8x8_inference_flag

        val cropping = r.readBit(); w.writeBit(cropping)
        if (cropping == 1) { w.passUE(r); w.passUE(r); w.passUE(r); w.passUE(r) }

        r.readBit()       // discard vui_parameters_present_flag
        w.writeBit(0)     // write 0 (no VUI)

        return byteArrayOf(0, 0, 1) + w.toByteArray()
    }

    private fun nalOffset(data: ByteArray) = when {
        data.size > 4 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
        data[2] == 0.toByte() && data[3] == 1.toByte() -> 4
        data.size > 3 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
        data[2] == 1.toByte() -> 3
        else -> 0
    }

    // ──────────────────────────────────────────────────────────────────
    // Bit-level reader (MSB first)
    // ──────────────────────────────────────────────────────────────────
    private class BitReader(private val data: ByteArray) {
        var pos = 0

        fun readBit(): Int {
            val b = pos / 8; val bit = 7 - (pos % 8); pos++
            return (data[b].toInt() ushr bit) and 1
        }

        fun readBits(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or readBit() }; return v }

        fun peekByte(): Int { val saved = pos; val v = readBits(8); pos = saved; return v }

        fun hasMoreBits() = pos < data.size * 8

        fun readUE(): Int { var m = 0; while (readBit() == 0) m++; return (1 shl m) - 1 + readBits(m) }

        fun readSE(): Int { val v = readUE(); return if (v % 2 == 0) -(v / 2) else (v + 1) / 2 }
    }

    // ──────────────────────────────────────────────────────────────────
    // Bit-level writer (MSB first)
    // ──────────────────────────────────────────────────────────────────
    private class BitWriter {
        private val bits = mutableListOf<Int>()

        fun writeBit(b: Int) { bits.add(b and 1) }
        fun writeBits(v: Int, n: Int) { for (i in n - 1 downTo 0) writeBit((v ushr i) and 1) }
        fun writeUE(v: Int) {
            if (v == 0) { writeBit(1); return }
            val code = v + 1; val m = 31 - Integer.numberOfLeadingZeros(code)
            writeBits(0, m); writeBit(1); writeBits(code - (1 shl m), m)
        }
        fun writeSE(v: Int) = writeUE(if (v <= 0) (-v) * 2 else v * 2 - 1)
        fun copyFrom(r: BitReader, n: Int) { repeat(n) { writeBit(r.readBit()) } }
        fun passUE(r: BitReader) { writeUE(r.readUE()) }
        fun passSE(r: BitReader) { writeSE(r.readSE()) }

        /** With RBSP stop bit — use for SPS/PPS. */
        fun toByteArray(): ByteArray {
            val out = bits.toMutableList()
            out.add(1)
            while (out.size % 8 != 0) out.add(0)
            return ByteArray(out.size / 8) { i -> var b = 0; for (j in 0..7) b = (b shl 1) or out[i * 8 + j]; b.toByte() }
        }
    }
}
