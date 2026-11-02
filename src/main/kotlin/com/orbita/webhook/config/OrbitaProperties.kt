package com.orbita.webhook.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("orbita")
data class OrbitaProperties(
    val appSecret: String,
    val inbound: QueueProperties,
    val outbound: QueueProperties,
    val gateway: GatewayProperties,
    val replyWindow: ReplyWindowProperties,
    /** Identifies this pod in the work it records. In Kubernetes, HOSTNAME is the pod name. */
    val instanceId: String = "local",
) {
    init {
        require(appSecret.isNotBlank()) { "orbita.app-secret must not be blank: set WHATSAPP_APP_SECRET" }
        require(!appSecret.startsWith("\${")) {
            "orbita.app-secret is an unresolved placeholder: set WHATSAPP_APP_SECRET"
        }
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

data class ReplyWindowProperties(val quietPeriod: Duration, val maxWait: Duration)
