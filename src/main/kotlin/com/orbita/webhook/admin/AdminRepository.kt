package com.orbita.webhook.admin

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

data class Reconciliation(
    val deliveries: Map<String, Long>,
    val messages: Long,
    val duplicatesIgnored: Long,
    val outbound: Map<String, Long>,
    val messagesWithoutReply: Long,
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
            messagesWithoutReply = count(
                """
                SELECT count(*) FROM messages m
                 WHERE NOT EXISTS (SELECT 1 FROM outbound_messages o
                                    WHERE o.tenant_id = m.tenant_id
                                      AND o.idempotency_key = 'auto-reply:' || m.wamid)
                """,
            ),
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
