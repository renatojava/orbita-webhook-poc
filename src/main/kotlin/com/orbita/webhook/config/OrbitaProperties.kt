package com.orbita.webhook.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("orbita")
data class OrbitaProperties(
    val appSecret: String,
    val inbound: QueueProperties,
    val outbound: QueueProperties,
    val gateway: GatewayProperties,
) {
    init {
        require(appSecret.isNotBlank()) { "orbita.app-secret must not be blank" }
    }
}

data class QueueProperties(
    val batchSize: Int,
    val lease: Duration,
    val maxAttempts: Int,
    val backoffBase: Duration,
    val backoffMax: Duration,
)

data class GatewayProperties(val latency: Duration)
