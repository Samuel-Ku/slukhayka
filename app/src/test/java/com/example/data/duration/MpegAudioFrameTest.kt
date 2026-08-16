package com.example.data.duration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * spec-24 T8 (#169) — pure JVM fixture tests for [MpegAudioFrame]. Synthetic
 * windows are laid out from the public MPEG spec constants (frame lengths
 * computed by hand below), never from the parser's own tables, so a wrong
 * table in the code under test fails the assertion.
 */
class MpegAudioFrameTest {

    // ---------------------------------------------------------------------
    // Synthetic frame builders (spec constants, hardcoded per case)
    // ---------------------------------------------------------------------

    /** 4-byte MPEG audio frame header. */
    private fun header(version: Int, layer: Int, bitrateIndex: Int, sampleRateIndex: Int, padding: Int = 0): ByteArray =
        byteArrayOf(
            0xFF.toByte(),
            (0xE0 or (version shl 3) or (layer shl 1) or 0x01).toByte(),
            ((bitrateIndex shl 4) or (sampleRateIndex shl 2) or (padding shl 1)).toByte(),
            0
        )

    /** MPEG1 Layer III, 128 kbps, 44.1 kHz — frame = 144 × 128000 / 44100 = 417 bytes. */
    private fun mpeg1l3_128_44100(): ByteArray = header(version = 3, layer = 1, bitrateIndex = 9, sampleRateIndex = 0) + ByteArray(417 - 4)

    /** MPEG1 Layer III, 96 kbps, 44.1 kHz — frame = 144 × 96000 / 44100 = 313 bytes. */
    private fun mpeg1l3_96_44100(): ByteArray = header(version = 3, layer = 1, bitrateIndex = 7, sampleRateIndex = 0) + ByteArray(313 - 4)

    /** MPEG2 Layer III, 64 kbps, 22.05 kHz — frame = 72 × 64000 / 22050 = 208 bytes. */
    private fun mpeg2l3_64_22050(): ByteArray = header(version = 2, layer = 1, bitrateIndex = 8, sampleRateIndex = 0) + ByteArray(208 - 4)

    /** ID3v2 tag header with a syncsafe [tagSize] (no footer). */
    private fun id3(tagSize: Int): ByteArray =
        "ID3".toByteArray() + byteArrayOf(3, 0, 0, 0, 0, 0, tagSize.toByte()) + ByteArray(tagSize)

    // ---------------------------------------------------------------------
    // CBR frames yield bitrate + sample rate
    // ---------------------------------------------------------------------

    @Test
    fun `CBR MPEG1 Layer III frame yields bitrate and sample rate`() {
        val header = MpegAudioFrame.parse(mpeg1l3_128_44100() + mpeg1l3_128_44100())!!
        assertEquals(128, header.bitrateKbps)
        assertEquals(44_100, header.sampleRateHz)
    }

    @Test
    fun `CBR MPEG2 Layer III frame uses the low-bitrate tables`() {
        val header = MpegAudioFrame.parse(mpeg2l3_64_22050() + mpeg2l3_64_22050())!!
        assertEquals(64, header.bitrateKbps)
        assertEquals(22_050, header.sampleRateHz)
    }

    // ---------------------------------------------------------------------
    // ID3v2 offsets
    // ---------------------------------------------------------------------

    @Test
    fun `a leading ID3v2 tag is skipped before the first frame`() {
        val window = id3(tagSize = 20) + mpeg1l3_128_44100() + mpeg1l3_128_44100()
        val header = MpegAudioFrame.parse(window)!!
        assertEquals(128, header.bitrateKbps)
        assertEquals(44_100, header.sampleRateHz)
    }

    @Test
    fun `an ID3v2-4 footer flag adds ten bytes to the offset`() {
        // "ID3" + version 04 00 + flags 0x10 (footer) + syncsafe size 20
        val id3v24 = "ID3".toByteArray() + byteArrayOf(4, 0, 0x10.toByte(), 0, 0, 0, 20) + ByteArray(30)
        val window = id3v24 + mpeg1l3_128_44100() + mpeg1l3_128_44100()
        val header = MpegAudioFrame.parse(window)!!
        assertEquals(128, header.bitrateKbps)
    }

    // ---------------------------------------------------------------------
    // Rejections: VBR, free-format, garbage, truncated windows
    // ---------------------------------------------------------------------

    @Test
    fun `VBR stream is rejected - consecutive frames disagree on bitrate`() {
        assertNull(MpegAudioFrame.parse(mpeg1l3_128_44100() + mpeg1l3_96_44100()))
    }

    @Test
    fun `free-format bitrate index is rejected - duration unknowable`() {
        val free = header(version = 3, layer = 1, bitrateIndex = 0, sampleRateIndex = 0) + ByteArray(64)
        assertNull(MpegAudioFrame.parse(free + free))
    }

    @Test
    fun `garbage yields no result`() {
        assertNull(MpegAudioFrame.parse(ByteArray(64) { (it * 7).toByte() }))
        // A sync word with a reserved layer field is not a frame.
        val badLayer = byteArrayOf(0xFF.toByte(), 0xF9.toByte(), 0x90.toByte(), 0) + ByteArray(64)
        assertNull(MpegAudioFrame.parse(badLayer))
    }

    @Test
    fun `a single frame cannot satisfy the CBR gate`() {
        assertNull(MpegAudioFrame.parse(mpeg1l3_128_44100()))
    }

    @Test
    fun `a window too short for a frame yields no result`() {
        assertNull(MpegAudioFrame.parse(ByteArray(4)))
        assertNull(MpegAudioFrame.parse(ByteArray(7)))
    }
}
