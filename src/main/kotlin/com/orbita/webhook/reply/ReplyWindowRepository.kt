package com.orbita.webhook.reply

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Duration

data class DueWindow(val tenantId: String, val contactPhone: String)

data class PendingMessage(val id: Long, val wamid: String)

@Repository
class ReplyWindowRepository(private val jdbc: JdbcClient) {

    /**
     * Sliding window with a ceiling: each new message pushes the reply back by the quiet period,
     * but never past opened_at + maxWait, or someone who keeps typing would never be answered.
     */
    fun openOrExtend(tenantId: String, contactPhone: String, quietPeriod: Duration, maxWait: Duration) {
        jdbc.sql(
            """
            INSERT INTO reply_windows (tenant_id, contact_phone, opened_at, last_message_at, due_at)
            VALUES (:tenant, :contact, now(), now(), now() + (:quiet * interval '1 millisecond'))
            ON CONFLICT (tenant_id, contact_phone) DO UPDATE
               SET last_message_at = now(),
                   due_at = LEAST(now() + (:quiet * interval '1 millisecond'),
                                  reply_windows.opened_at + (:maxWait * interval '1 millisecond'))
            """,
        )
            .param("tenant", tenantId).param("contact", contactPhone)
            .param("quiet", quietPeriod.toMillis()).param("maxWait", maxWait.toMillis())
            .update()
    }

    /** Takes a row lock until the transaction ends; a message arriving now waits, then opens a new window. */
    fun lockNextDue(): DueWindow? =
        jdbc.sql(
            """
            SELECT tenant_id, contact_phone FROM reply_windows
             WHERE due_at <= now() ORDER BY due_at LIMIT 1 FOR UPDATE SKIP LOCKED
            """,
        ).query { rs, _ -> DueWindow(rs.getString(1), rs.getString(2)) }.optional().orElse(null)

    fun unansweredMessages(window: DueWindow): List<PendingMessage> =
        jdbc.sql(
            """
            SELECT id, wamid FROM messages
             WHERE tenant_id = ? AND from_phone = ? AND reply_key IS NULL
             ORDER BY provider_timestamp, id
            """,
        ).param(window.tenantId).param(window.contactPhone)
            .query { rs, _ -> PendingMessage(rs.getLong(1), rs.getString(2)) }.list()

    fun linkToReply(messageIds: List<Long>, replyKey: String) {
        jdbc.sql("UPDATE messages SET reply_key = :key WHERE id IN (:ids)")
            .param("key", replyKey).param("ids", messageIds).update()
    }

    fun delete(window: DueWindow) {
        jdbc.sql("DELETE FROM reply_windows WHERE tenant_id = ? AND contact_phone = ?")
            .param(window.tenantId).param(window.contactPhone).update()
    }
}
