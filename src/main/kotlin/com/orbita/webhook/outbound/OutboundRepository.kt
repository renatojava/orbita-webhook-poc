package com.orbita.webhook.outbound

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import kotlin.math.floor
import kotlin.math.min

@Repository
class OutboundRepository(private val jdbc: JdbcClient) {

    fun enqueue(tenantId: String, idempotencyKey: String, priority: Int, toPhone: String, body: String) {
        jdbc.sql(
            """
            INSERT INTO outbound_messages (tenant_id, idempotency_key, priority, to_phone, body)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
            """,
        ).param(tenantId).param(idempotencyKey).param(priority).param(toPhone).param(body).update()
    }

    fun enqueueCampaign(tenantId: String, campaignId: String, count: Int, body: String): Int =
        jdbc.sql(
            """
            INSERT INTO outbound_messages (tenant_id, idempotency_key, priority, to_phone, body)
            SELECT ?, ? || ':' || n, ?, '55119' || lpad(n::text, 8, '0'), ?
              FROM generate_series(1, ?) AS n
            ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
            """,
        ).param(tenantId).param("campaign:$campaignId").param(PRIORITY_CAMPAIGN).param(body).param(count).update()

    fun tenantsWithPendingWork(): List<String> =
        jdbc.sql(
            """
            SELECT t.id FROM tenants t
             WHERE EXISTS (SELECT 1 FROM outbound_messages o
                            WHERE o.tenant_id = t.id AND o.status = 'PENDING' AND o.next_attempt_at <= now())
            """,
        ).query { rs, _ -> rs.getString(1) }.list()

    /**
     * Refills the tenant's token bucket and claims at most that many messages, in one transaction.
     * The row lock serializes pods, so the limit is per tenant, not per pod: 3 pods do not mean 3x the rate.
     */
    @Transactional
    fun claimWithinRateLimit(tenantId: String, batchSize: Int, lease: Duration, workerId: String): List<OutboundMessage> {
        val available = jdbc.sql(
            """
            SELECT LEAST(send_burst, tokens + send_rate_per_second
                         * GREATEST(0, EXTRACT(EPOCH FROM now() - tokens_refilled_at)))
              FROM tenants WHERE id = ? FOR UPDATE
            """,
        ).param(tenantId).query { rs, _ -> rs.getDouble(1) }.single()

        val claimed = jdbc.sql(
            """
            UPDATE outbound_messages o
               SET status = 'SENDING', attempts = o.attempts + 1, worker_id = ?,
                   locked_until = now() + (? * interval '1 millisecond')
              FROM (SELECT id FROM outbound_messages
                     WHERE tenant_id = ? AND status = 'PENDING' AND next_attempt_at <= now()
                     ORDER BY priority, id LIMIT ? FOR UPDATE SKIP LOCKED) c
             WHERE o.id = c.id
            RETURNING o.id, o.tenant_id, o.idempotency_key, o.to_phone, o.body, o.attempts
            """,
        )
            .param(workerId).param(lease.toMillis()).param(tenantId)
            .param(min(floor(available).toInt(), batchSize))
            .query { rs, _ ->
                OutboundMessage(
                    id = rs.getLong("id"),
                    tenantId = rs.getString("tenant_id"),
                    idempotencyKey = rs.getString("idempotency_key"),
                    toPhone = rs.getString("to_phone"),
                    body = rs.getString("body"),
                    attempts = rs.getInt("attempts"),
                )
            }
            .list()

        jdbc.sql("UPDATE tenants SET tokens = ?, tokens_refilled_at = now() WHERE id = ?")
            .param(available - claimed.size).param(tenantId).update()
        return claimed
    }

    /**
     * A lease that expires during SENDING means the worker died with the outcome unknown: Meta may
     * already have the message. Resending could answer the lead twice, so we flag it and stop.
     */
    fun flagExpiredSendsAsUncertain(): Int =
        jdbc.sql(
            """
            UPDATE outbound_messages
               SET status = 'UNCERTAIN', locked_until = NULL,
                   last_error = 'Lease expired while sending: outcome unknown'
             WHERE status = 'SENDING' AND locked_until < now()
            """,
        ).update()

    fun markSent(id: Long, providerMessageId: String) {
        jdbc.sql(
            """
            UPDATE outbound_messages
               SET status = 'SENT', sent_at = now(), provider_message_id = ?, locked_until = NULL, last_error = NULL
             WHERE id = ? AND status = 'SENDING'
            """,
        ).param(providerMessageId).param(id).update()
    }

    fun scheduleRetry(id: Long, delay: Duration, error: String) {
        jdbc.sql(
            """
            UPDATE outbound_messages
               SET status = 'PENDING', locked_until = NULL, last_error = ?,
                   next_attempt_at = now() + (? * interval '1 millisecond')
             WHERE id = ? AND status = 'SENDING'
            """,
        ).param(error).param(delay.toMillis()).param(id).update()
    }

    fun finishAs(id: Long, status: String, error: String) {
        jdbc.sql(
            "UPDATE outbound_messages SET status = ?, locked_until = NULL, last_error = ? WHERE id = ? AND status = 'SENDING'",
        ).param(status).param(error).param(id).update()
    }

    companion object {
        const val PRIORITY_REPLY = 0
        const val PRIORITY_CAMPAIGN = 1
        const val STATUS_DEAD = "DEAD"
        const val STATUS_UNCERTAIN = "UNCERTAIN"
    }
}
