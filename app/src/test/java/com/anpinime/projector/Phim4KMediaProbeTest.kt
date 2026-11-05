package com.anpinime.projector

import org.junit.Assert.*
import org.junit.Test

class Phim4KMediaProbeTest {

    @Test
    fun probeBytesDetectsMkvHeader() {
        val mkvBytes = byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte(), 0x01, 0x00)
        assertEquals(StreamMediaKind.MKV, Phim4KMediaProbe.classifyRangeProbe(200, "application/octet-stream", mkvBytes, "https://example.com/play"))
    }

    @Test
    fun probeBytesDetectsMp4FtypHeader() {
        val mp4Bytes = byteArrayOf(0x00, 0x00, 0x00, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), '4'.code.toByte(), '2'.code.toByte())
        assertEquals(StreamMediaKind.MP4, Phim4KMediaProbe.classifyRangeProbe(200, "application/octet-stream", mp4Bytes, "https://example.com/play"))
    }

    @Test
    fun probeBytesDetectsHlsM3u8Header() {
        val hlsBytes = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:10,\nseg1.ts\n".toByteArray(Charsets.UTF_8)
        assertEquals(StreamMediaKind.HLS, Phim4KMediaProbe.classifyRangeProbe(200, "application/octet-stream", hlsBytes, "https://example.com/play"))
    }

    @Test
    fun probeBytesDetectsDashMpdHeader() {
        val dashBytes = "<?xml version=\"1.0\"?>\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">\n</MPD>".toByteArray(Charsets.UTF_8)
        assertEquals(StreamMediaKind.DASH, Phim4KMediaProbe.classifyRangeProbe(200, "application/octet-stream", dashBytes, "https://example.com/play"))
    }

    @Test
    fun probeBytesUnknownOnGarbage() {
        val garbage = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        assertNull(Phim4KMediaProbe.classifyRangeProbe(200, "application/octet-stream", garbage, "https://example.com/play"))
    }
}
