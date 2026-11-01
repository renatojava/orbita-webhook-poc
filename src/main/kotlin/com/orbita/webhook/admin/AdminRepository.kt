package com.orbita.webhook.admin

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

data class Reconciliation(
    val deliveries: Map<String, Long>,
    val messages: Long,
    val duplicatesIgnored: Long,
    val outbound: Map<String, Long>,
    val messagesAwaitingWindow: Long,
    val orphanMessages: Long,
    val openWindows: Long,
    val deadLetters: Long,
    val uncertainSends: Long,
)

data class DeadLetter(val source: String, val id: Long, val attempts: Int, val error: String?)

@Repository
class AdminRepository(private val jdbc: JdbcClient) {

    fun upsertTenant(id: String, phoneNumberId: String, ratePerSecond: Double, burst: Int) {
        jdbc.sql(
            """
            INSERT INTO tenants (id, phone_number_id, send_rate_per_second, send_burst, tokens)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE
               SET phone_number_id = EXCLUDED.phone_number_id,
                   send_rate_per_second = EXCLUDED.send_rate_per_second,
                   send_burst = EXCLUDED.send_burst,
                   tokens = LEAST(tenants.tokens, EXCLUDED.send_burst)
            """,
        ).param(id).param(phoneNumberId).param(ratePerSecond).param(burst).param(burst.toDouble()).update()
    }

    /** Answers the question nobody could answer after last Tuesday's incident: what is missing, and where? */
    fun reconcile(): Reconciliation {
        val deliveries = countByStatus("webhook_deliveries")
        val outbound = countByStatus("outbound_messages")
        return Reconciliation(
            deliveries = deliveries,
            messages = count("SELECT count(*) FROM messages"),
            duplicatesIgnored = count("SELECT coalesce(sum(duplicate_messages), 0) FROM webhook_deliveries"),
            outbound = outbound,
            messagesAwaitingWindow = count(
                """
                SELECT count(*) FROM messages m
                 WHERE m.reply_key IS NULL
                   AND EXISTS (SELECT 1 FROM reply_windows w
                                WHERE w.tenant_id = m.tenant_id AND w.contact_phone = m.from_phone)
                """,
            ),
            // Must always be zero: no reply, no window that will produce one, or a link to a reply that does not exist.
            orphanMessages = count(
                """
                SELECT count(*) FROM messages m
                 WHERE (m.reply_key IS NULL
                        AND NOT EXISTS (SELECT 1 FROM reply_windows w
                                         WHERE w.tenant_id = m.tenant_id AND w.contact_phone = m.from_phone))
                    OR (m.reply_key IS NOT NULL
                        AND NOT EXISTS (SELECT 1 FROM outbound_messages o
                                         WHERE o.tenant_id = m.tenant_id AND o.idempotency_key = m.reply_key))
                """,
            ),
            openWindows = count("SELECT count(*) FROM reply_windows"),
            deadLetters = deliveries.getOrDefault("DEAD", 0) + outbound.getOrDefault("DEAD", 0),
            uncertainSends = outbound.getOrDefault("UNCERTAIN", 0),
        )
    }

    fun deadLetters(): List<DeadLetter> =
        jdbc.sql(
            """
            SELECT 'delivery' AS source, id, attempts, last_error FROM webhook_deliveries WHERE status = 'DEAD'
            UNION ALL
            SELECT 'outbound', id, attempts, last_error FROM outbound_messages WHERE status IN ('DEAD', 'UNCERTAIN')
            ORDER BY source, id LIMIT 200
            """,
        ).query { rs, _ ->
            DeadLetter(rs.getString("source"), rs.getLong("id"), rs.getInt("attempts"), rs.getString("last_error"))
        }.list()

    private fun count(sql: String): Long = jdbc.sql(sql).query { rs, _ -> rs.getLong(1) }.single()

    private fun countByStatus(table: String): Map<String, Long> =
        jdbc.sql("SELECT status, count(*) AS total FROM $table GROUP BY status")
            .query { rs, _ -> rs.getString("status") to rs.getLong("total") }
            .list()
            .toMap()
}
