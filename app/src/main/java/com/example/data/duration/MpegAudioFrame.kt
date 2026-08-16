package com.example.data.duration

/**
 * spec-24 T8 (#169) — pure JVM MPEG audio frame header parser. No Android,
 * no streaming: the module takes raw bytes and answers one question — is
 * this a CBR MPEG audio stream, and if so at what bitrate / sample rate?
 *
 * The probe feeds it the head window of a stream: the parser skips a leading
 * ID3v2 tag (v2.3/v2.4, footer-aware), locates the first MPEG audio frame
 * header, and requires a SECOND consecutive frame to agree on the bitrate —
 * the CBR gate. A VBR/Xing stream (consecutive frames disagree), a
 * free-format stream (bitrate index 0 — the duration is unknowable without
 * the Xing header) and garbage never yield a result: the probe refuses to
 * guess.
 */
object MpegAudioFrame {

    /** The frame facts the duration probe needs. */
    data class Header(
        val bitrateKbps: Int,
        val sampleRateHz: Int
    )

    /**
     * @return the CBR frame header, or null when the window holds no
     * verifiable CBR MPEG audio (garbage, VBR/Xing, free-format, or a
     * window too short for the two-frame gate).
     */
    fun parse(bytes: ByteArray): Header? {
        if (bytes.size < 8) return null
        val firstOffset = findFrameHeader(bytes, from = id3v2Offset(bytes)) ?: return null
        val first = headerAt(bytes, firstOffset) ?: return null
        if (first.bitrateKbps <= 0) return null
        // CBR gate: a second consecutive frame must agree on the bitrate. The
        // search starts at frameLen - 2 (the padding bit shifts the next sync
        // by one byte) and tolerates a few bytes of slack; anything further
        // apart is not a consecutive frame.
        val secondOffset = findFrameHeader(
            bytes,
            from = firstOffset + first.frameLength - 2,
            untilExclusive = firstOffset + first.frameLength + 8
        ) ?: return null
        if (secondOffset <= firstOffset) return null
        val second = headerAt(bytes, secondOffset) ?: return null
        if (second.bitrateKbps != first.bitrateKbps) return null
        return Header(first.bitrateKbps, first.sampleRateHz)
    }

    /**
     * Offset of the first audio byte: 0 when no ID3v2 tag leads the window,
     * else 10 (tag header) + syncsafe size (+ 10 for the v2.4 footer flag).
     * A tag larger than the window pushes the offset past the data, which
     * simply fails the later frame search — never a guess.
     */
    private fun id3v2Offset(bytes: ByteArray): Int {
        if (bytes.size < 10) return 0
        if (bytes[0].toInt() != 'I'.code || bytes[1].toInt() != 'D'.code || bytes[2].toInt() != '3'.code) return 0
        val size = syncsafeInt(bytes, 6)
        var offset = 10 + size
        // ID3v2.4: bit 4 of the flags byte marks an appended 10-byte footer.
        if (bytes[5].toInt() and 0x10 != 0) offset += 10
        return offset
    }

    /** 28-bit big-endian syncsafe integer (7 data bits per byte). */
    private fun syncsafeInt(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0x7F) shl 21 or
            ((bytes[at + 1].toInt() and 0x7F) shl 14) or
            ((bytes[at + 2].toInt() and 0x7F) shl 7) or
            (bytes[at + 3].toInt() and 0x7F)

    /** Scans [from, untilExclusive) for a syntactically valid frame header. */
    private fun findFrameHeader(bytes: ByteArray, from: Int, untilExclusive: Int = bytes.size - 3): Int? {
        val lastStart = minOf(untilExclusive, bytes.size - 4)
        var i = from.coerceIn(0, lastStart)
        while (i <= lastStart) {
            if (bytes[i].toInt() and 0xFF == 0xFF && headerAt(bytes, i) != null) return i
            i++
        }
        return null
    }

    /** Parses the 4-byte header at [offset]; null when it is not a valid frame. */
    private fun headerAt(bytes: ByteArray, offset: Int): Frame? {
        if (offset < 0 || offset + 4 > bytes.size) return null
        val b1 = bytes[offset + 1].toInt() and 0xFF
        val b2 = bytes[offset + 2].toInt() and 0xFF
        if (b1 and 0xE0 != 0xE0) return null // 11-bit sync word 0xFFE
        val versionBits = b1 shr 3 and 0x03
        val layerBits = b1 shr 1 and 0x03
        if (versionBits == 1 || layerBits == 0) return null // reserved
        val bitrateIndex = b2 shr 4 and 0x0F
        val sampleRateIndex = b2 shr 2 and 0x03
        // 0 = free format (unknowable without Xing), 15 = bad.
        if (bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) return null
        val bitrateKbps = bitrateKbpsFor(versionBits, layerBits, bitrateIndex) ?: return null
        val sampleRateHz = sampleRateHzFor(versionBits, sampleRateIndex)
        val padding = b2 shr 1 and 0x01
        // Official frame-length formulas (bytes): Layer I is
        // (12 × bitrate / sampleRate + padding) × 4; Layer II/III are
        // 144 (MPEG1) / 72 (MPEG2/2.5) × bitrate / sampleRate + padding.
        val frameLength = when (layerBits) {
            3 -> (12 * bitrateKbps * 1000 / sampleRateHz + padding) * 4
            else -> (if (versionBits == 3) 144 else 72) * bitrateKbps * 1000 / sampleRateHz + padding
        }
        return Frame(bitrateKbps, sampleRateHz, frameLength)
    }

    private fun bitrateKbpsFor(versionBits: Int, layerBits: Int, index: Int): Int? {
        val table = when (layerBits) {
            3 -> if (versionBits == 3) MPEG1_LAYER1 else MPEG2_LAYER1
            2 -> if (versionBits == 3) MPEG1_LAYER2 else MPEG2_LAYER2
            else -> if (versionBits == 3) MPEG1_LAYER3 else MPEG2_LAYER3
        }
        return table.getOrNull(index)?.takeIf { it > 0 }
    }

    private fun sampleRateHzFor(versionBits: Int, index: Int): Int = when (versionBits) {
        3 -> SAMPLE_RATES_MPEG1[index]
        2 -> SAMPLE_RATES_MPEG2[index]
        else -> SAMPLE_RATES_MPEG2_5[index]
    }

    private data class Frame(val bitrateKbps: Int, val sampleRateHz: Int, val frameLength: Int)

    // Bitrate tables in kbps, indexed by the 4-bit header field. Index 0 is
    // free format (excluded above); index 15 is "bad" and never valid.
    private val MPEG1_LAYER1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
    private val MPEG1_LAYER2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
    private val MPEG1_LAYER3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val MPEG2_LAYER1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
    private val MPEG2_LAYER2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    private val MPEG2_LAYER3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

    private val SAMPLE_RATES_MPEG1 = intArrayOf(44_100, 48_000, 32_000)
    private val SAMPLE_RATES_MPEG2 = intArrayOf(22_050, 24_000, 16_000)
    private val SAMPLE_RATES_MPEG2_5 = intArrayOf(11_025, 12_000, 8_000)
}
