package com.orbita.webhook.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class BackoffTest {
    private val base = Duration.ofSeconds(1)
    private val max = Duration.ofSeconds(30)

    @Test
    fun `ceiling doubles on each attempt`() {
        repeat(200) {
            assertThat(backoffDelay(1, base, max)).isBetween(Duration.ofMillis(500), Duration.ofSeconds(1))
            assertThat(backoffDelay(3, base, max)).isBetween(Duration.ofSeconds(2), Duration.ofSeconds(4))
        }
    }

    @Test
    fun `never exceeds the maximum even on absurd attempt counts`() {
        assertThat(backoffDelay(1000, base, max)).isLessThanOrEqualTo(max)
    }
}
