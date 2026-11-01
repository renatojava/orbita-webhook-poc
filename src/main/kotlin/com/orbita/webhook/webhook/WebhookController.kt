package com.orbita.webhook.webhook

import com.orbita.webhook.inbound.DeliveryRepository
import org.springframework.dao.DataAccessException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class WebhookController(
    private val verifier: SignatureVerifier,
    private val deliveries: DeliveryRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 200 means "durably stored", never "processed". If the database is down we answer 503 on purpose,
     * so Meta's own resend becomes the safety net instead of the message being acknowledged and lost.
     */
    @PostMapping("/webhooks/whatsapp")
    fun receive(
        @RequestBody rawBody: ByteArray,
        @RequestHeader("X-Hub-Signature-256", required = false) signature: String?,
    ): ResponseEntity<Void> {
        if (!verifier.isValid(rawBody, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        return try {
            deliveries.enqueue(rawBody)
            ResponseEntity.ok().build()
        } catch (e: DataAccessException) {
            log.error("Could not enqueue webhook delivery; answering 503 so Meta resends", e)
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
        }
    }
}
