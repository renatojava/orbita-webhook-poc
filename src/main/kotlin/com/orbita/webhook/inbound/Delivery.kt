package com.orbita.webhook.inbound

data class Delivery(val id: Long, val rawBody: ByteArray, val attempts: Int)
