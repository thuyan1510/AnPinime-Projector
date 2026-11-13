package com.anpinime.projector

import androidx.media3.common.MimeTypes
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.GZIPOutputStream

class SubtitleSupportTest {

    @Test
    fun decodesBomUtf8Utf16GzipAndWindows1258Cleanly() {
        val sample = "1\r\n00:00:01,000 --> 00:00:03,000\r\nXin chào thế giới!\r\n\r\n"

        // UTF-8 BOM + CRLF
        val utf8BomBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            sample.toByteArray(Charsets.UTF_8)
        assertEquals(sample.replace("\r\n", "\n"), SubtitleSupport.decodeSubtitleBytes(utf8BomBytes))

        // UTF-16LE with BOM
        val utf16LeBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            sample.toByteArray(Charsets.UTF_16LE)
        assertEquals(sample.replace("\r\n", "\n"), SubtitleSupport.decodeSubtitleBytes(utf16LeBom))

        // GZIP wrapped UTF-8 BOM SRT (OpenSubtitles pattern)
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(utf8BomBytes) }
        assertEquals(sample.replace("\r\n", "\n"), SubtitleSupport.decodeSubtitleBytes(baos.toByteArray()))

        // Windows-1258 / ISO-8859-1 fallback (non-UTF8 bytes must not throw or turn into tofu)
        val win1252 = runCatching { Charset.forName("windows-1252") }.getOrElse { Charsets.ISO_8859_1 }
        val nonUtf8 = "1\n00:00:01,000 --> 00:00:02,000\nCafé résumé\n\n".toByteArray(win1252)
        val decodedFallback = SubtitleSupport.decodeSubtitleBytes(nonUtf8)
        assertTrue(decodedFallback.contains("Café résumé"))
    }

    @Test
    fun normalizesQuirkySrtAndVttToCanonicalSubRip() {
        val quirkySrt = "\uFEFF" +
            "1   \r\n" +
            "0:01:02.3 --> 0:01:04,50 align:center\r\n" +
            "<i>Line one</i>\r\n" +
            "Line two\r\n" +
            "\r\n" +
            "01:10.500 --> 01:12.750\r\n" +
            "Missing index & hours\r\n" +
            "3 \r\n" +
            "00:01:15,100 --> 00:01:17,900\r\n" +
            "No blank line above me\r\n"

        val normalized = SubtitleSupport.normalizeToSubRip(quirkySrt)
        assertTrue(normalized.contains("1\n00:01:02,300 --> 00:01:04,500\n<i>Line one</i>\nLine two\n\n"))
        assertTrue(normalized.contains("2\n00:01:10,500 --> 00:01:12,750\nMissing index & hours\n\n"))
        assertTrue(normalized.contains("3\n00:01:15,100 --> 00:01:17,900\nNo blank line above me\n\n"))

        val cues = SubtitleTranslator.parseTimedCues(normalized)
        assertEquals(3, cues.size)
        assertEquals(62_300L, cues[0].startMs)
        assertEquals(64_500L, cues[0].endMs)
        assertEquals("Line one\nLine two", cues[0].text)
        assertEquals(70_500L, cues[1].startMs)
        assertEquals(75_100L, cues[2].startMs)
    }

    @Test
    fun convertsVttServedForSrtTrackAndRejectsHtmlPages() {
        val vttPayload = """
            WEBVTT
            Kind: captions
            Language: en

            NOTE comment block

            00:05.120 --> 00:07.800 position:50%
            <v Alice>Hello <i>there</i>!</v>
        """.trimIndent()

        val bytes = SubtitleSupport.normalizeSubtitleForMime(
            vttPayload.toByteArray(Charsets.UTF_8),
            MimeTypes.APPLICATION_SUBRIP,
            "https://dl.opensubtitles.org/en/download/subencoding-utf8/filead/123"
        )
        val out = String(bytes, Charsets.UTF_8)
        assertFalse(out.startsWith("WEBVTT"))
        assertTrue(out.startsWith("1\n00:00:05,120 --> 00:00:07,800\nHello <i>there</i>!\n"))

        assertTrue(SubtitleSupport.isHtmlErrorPayload("<!DOCTYPE html><html><body>Cloudflare rate limit</body></html>"))
        assertFalse(SubtitleSupport.isHtmlErrorPayload(vttPayload))
    }

    @Test
    fun detectsFormatsAndFormatsPickerBadges() {
        assertEquals(MimeTypes.APPLICATION_SUBRIP, SubtitleSupport.detectSubtitleFormat("https://dl.opensubtitles.org/en/download/filead/999"))
        assertEquals(MimeTypes.TEXT_VTT, SubtitleSupport.detectSubtitleFormat("https://sub.example.com/123?format=vtt"))
        assertEquals(MimeTypes.TEXT_SSA, SubtitleSupport.detectSubtitleFormat("https://sub.example.com/movie.ass"))
        assertEquals("SRT", SubtitleSupport.formatTrackBadgeLabel(MimeTypes.APPLICATION_SUBRIP))
        assertEquals("VTT", SubtitleSupport.formatTrackBadgeLabel(MimeTypes.TEXT_VTT))
        assertEquals("ASS", SubtitleSupport.formatTrackBadgeLabel(MimeTypes.TEXT_SSA))
    }
}
