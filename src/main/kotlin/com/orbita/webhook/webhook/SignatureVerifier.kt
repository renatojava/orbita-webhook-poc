package com.orbita.webhook.webhook

import com.orbita.webhook.config.OrbitaProperties
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class SignatureVerifier(properties: OrbitaProperties) {
    private val key = SecretKeySpec(properties.appSecret.toByteArray(), ALGORITHM)

    /** Must receive the raw request bytes: re-serialized JSON would not produce the same HMAC. */
    fun isValid(rawBody: ByteArray, header: String?): Boolean {
        if (header == null || !header.startsWith(PREFIX)) return false
        val received = header.removePrefix(PREFIX).lowercase().toByteArray()
        val expected = HexFormat.of().formatHex(sign(rawBody)).toByteArray()
        return MessageDigest.isEqual(expected, received)
    }

    private fun sign(body: ByteArray): ByteArray = Mac.getInstance(ALGORITHM).run {
        init(key)
        doFinal(body)
    }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
        const val PREFIX = "sha256="
    }
}
