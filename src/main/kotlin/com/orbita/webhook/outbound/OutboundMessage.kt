package com.orbita.webhook.outbound

data class OutboundMessage(
    val id: Long,
    val tenantId: String,
    val idempotencyKey: String,
    val toPhone: String,
    val body: String,
    val attempts: Int,
)
