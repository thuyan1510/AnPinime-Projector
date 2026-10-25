package com.anpinime.projector

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest
import java.util.zip.Deflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AnimePayloadDecoderTest {
    private val text = """{"sources":[{"file":"https://fixture.test/master.m3u8"}]}"""
    private fun encrypted(compress: Boolean): ByteArray {
        val plain = text.toByteArray()
        val bytes = if (compress) {
            val deflater = Deflater(6, true)
            deflater.setInput(plain)
            deflater.finish()
            val buffer = ByteArray(1024)
            val size = deflater.deflate(buffer)
            deflater.end()
            buffer.copyOf(size)
        } else plain
        val iv = ByteArray(16) { it.toByte() }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val key = MessageDigest.getInstance("SHA-256").digest("fixture-key".toByteArray())
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return iv + cipher.doFinal(bytes)
    }

    @Test fun decryptsRawDeflate() { assertEquals(text, AnimePayloadDecoder.decode(encrypted(true), "fixture-key")) }
    @Test fun decryptsPlainJson() { assertEquals(text, AnimePayloadDecoder.decode(encrypted(false), "fixture-key")) }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedPayload() { AnimePayloadDecoder.decode(ByteArray(16), "fixture-key") }
}
