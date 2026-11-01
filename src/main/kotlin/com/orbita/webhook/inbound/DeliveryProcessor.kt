package com.orbita.webhook.inbound

import com.orbita.webhook.outbound.OutboundRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.orbita.webhook.webhook.WebhookPayload
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant

/** Thrown when another worker reclaimed the delivery, so this transaction must leave no trace. */
class LeaseLostException(deliveryId: Long) : RuntimeException("Lease lost for delivery $deliveryId")

@Service
class DeliveryProcessor(
    private val mapper: ObjectMapper,
    private val jdbc: JdbcClient,
    private val deliveries: DeliveryRepository,
    private val outbound: OutboundRepository,
) {
    /**
     * History row, automatic reply and queue status commit in one transaction. A crash anywhere
     * in here rolls everything back and the delivery runs again, with nothing half-written.
     */
    @Transactional
    fun process(delivery: Delivery) {
        val payload = mapper.readValue(delivery.rawBody, WebhookPayload::class.java)
        var duplicates = 0
        payload.entry
            .flatMap { it.changes }
            .filter { it.field == "messages" }
            .map { it.value }
            .forEach { value ->
                val tenantId = findTenant(value.metadata.phoneNumberId)
                value.messages.forEach { message ->
                    if (!store(tenantId, value, message)) duplicates++
                }
            }
        if (!deliveries.markDone(delivery, duplicates)) throw LeaseLostException(delivery.id)
    }

    /** Returns false if this message was already recorded. ON CONFLICT makes the check atomic. */
    private fun store(tenantId: String, value: WebhookPayload.Value, message: WebhookPayload.Message): Boolean {
        val contactName = value.contacts.firstOrNull { it.waId == message.from }?.profile?.name
        val inserted = jdbc.sql(
            """
            INSERT INTO messages (tenant_id, wamid, from_phone, contact_name, type, body, provider_timestamp)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, wamid) DO NOTHING
            """,
        )
            .param(tenantId).param(message.id).param(message.from).param(contactName)
            .param(message.type).param(message.text?.body)
            .param(Timestamp.from(Instant.ofEpochSecond(message.timestamp)))
            .update() == 1
        if (inserted) {
            outbound.enqueue(
                tenantId = tenantId,
                idempotencyKey = "auto-reply:${message.id}",
                priority = OutboundRepository.PRIORITY_REPLY,
                toPhone = message.from,
                body = AUTO_REPLY,
            )
        }
        return inserted
    }

    private fun findTenant(phoneNumberId: String): String =
        jdbc.sql("SELECT id FROM tenants WHERE phone_number_id = ?")
            .param(phoneNumberId)
            .query { rs, _ -> rs.getString(1) }
            .optional()
            .orElseThrow { IllegalStateException("No tenant for phone_number_id $phoneNumberId") }

    private companion object {
        const val AUTO_REPLY = "Recebemos sua mensagem. Um atendente vai falar com você em breve."
    }
}
