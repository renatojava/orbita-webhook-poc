package com.orbita.webhook.webhook

import com.orbita.webhook.config.GatewayProperties
import com.orbita.webhook.config.OrbitaProperties
import com.orbita.webhook.config.QueueProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class SignatureVerifierTest {
    private val queue = QueueProperties(1, Duration.ofSeconds(1), 1, Duration.ofSeconds(1), Duration.ofSeconds(1))
    private val verifier = SignatureVerifier(
        OrbitaProperties("secret", queue, queue, GatewayProperties(Duration.ZERO)),
    )

    // Reference value computed outside the code: printf 'hello' | openssl dgst -sha256 -hmac secret
    private val helloSignature = "sha256=88aab3ede8d3adf94d26ab90d3bafd4a2083070c3bcce9c014ee04a443847c0b"

    @Test
    fun `accepts a known good signature`() {
        assertThat(verifier.isValid("hello".toByteArray(), helloSignature)).isTrue()
    }

    @Test
    fun `rejects changed body, missing header and wrong prefix`() {
        assertThat(verifier.isValid("hello!".toByteArray(), helloSignature)).isFalse()
        assertThat(verifier.isValid("hello".toByteArray(), null)).isFalse()
        assertThat(verifier.isValid("hello".toByteArray(), helloSignature.removePrefix("sha256="))).isFalse()
    }
}
