package com.orbita.webhook.config

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class OrbitaPropertiesTest {
    private val queue = QueueProperties(1, Duration.ofSeconds(1), 1, Duration.ofSeconds(1), Duration.ofSeconds(1))

    private fun withSecret(secret: String) = OrbitaProperties(
        secret, queue, queue, GatewayProperties(Duration.ZERO), ReplyWindowProperties(Duration.ZERO, Duration.ZERO),
    )

    @Test
    fun `refuses to start without a secret`() {
        assertThatThrownBy { withSecret("") }.hasMessageContaining("WHATSAPP_APP_SECRET")
        assertThatThrownBy { withSecret("   ") }.hasMessageContaining("WHATSAPP_APP_SECRET")
    }

    @Test
    fun `refuses an unresolved placeholder, which anyone reading the code could sign with`() {
        assertThatThrownBy { withSecret("\${WHATSAPP_APP_SECRET}") }.hasMessageContaining("placeholder")
    }

    @Test
    fun `accepts a real secret`() {
        assertThatCode { withSecret("a-real-secret") }.doesNotThrowAnyException()
    }
}
