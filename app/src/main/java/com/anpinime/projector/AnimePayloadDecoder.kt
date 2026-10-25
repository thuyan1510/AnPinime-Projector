package com.anpinime.projector

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object AnimePayloadDecoder {
    fun decode(raw: ByteArray, keyText: String): String {
        require(raw.size > 16) { "Missing encrypted player payload" }
        val key = MessageDigest.getInstance("SHA-256").digest(keyText.toByteArray(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(raw.copyOfRange(0, 16)))
        val clear = cipher.doFinal(raw.copyOfRange(16, raw.size))
        val inflater = Inflater(true)
        try {
            inflater.setInput(clear)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) break
                output.write(buffer, 0, count)
                require(output.size() <= 2 * 1024 * 1024) { "Player payload too large" }
            }
            if (inflater.finished() && output.size() > 0) return output.toString("UTF-8")
        } catch (_: java.util.zip.DataFormatException) {
            // Some responses encrypt plain JSON/HTML without raw DEFLATE.
        } finally {
            inflater.end()
        }
        return String(clear, Charsets.UTF_8)
    }
}
