package com.orbita.webhook.inbound

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Duration

@Repository
class DeliveryRepository(private val jdbc: JdbcClient) {

    fun enqueue(rawBody: ByteArray) {
        jdbc.sql("INSERT INTO webhook_deliveries (raw_body) VALUES (?)").param(rawBody).update()
    }

    /**
     * Attempts are counted here, at claim time, not on failure. A message that kills the process
     * never reaches the failure path, so counting later would let it crash-loop forever.
     * SKIP LOCKED lets any number of workers or replicas claim without overlapping.
     */
    fun claim(batchSize: Int, lease: Duration): List<Delivery> =
        jdbc.sql(
            """
            UPDATE webhook_deliveries d
               SET status = 'PROCESSING', attempts = d.attempts + 1,
                   locked_until = now() + (? * interval '1 millisecond')
              FROM (SELECT id FROM webhook_deliveries
                     WHERE (status = 'PENDING' AND next_attempt_at <= now())
                        OR (status = 'PROCESSING' AND locked_until < now())
                     ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED) c
             WHERE d.id = c.id
            RETURNING d.id, d.raw_body, d.attempts
            """,
        )
            .param(lease.toMillis())
            .param(batchSize)
            .query { rs, _ -> Delivery(rs.getLong("id"), rs.getBytes("raw_body"), rs.getInt("attempts")) }
            .list()
            .sortedBy { it.id }

    /** Runs before claim: a delivery that used all its attempts and lost its lease must not run again. */
    fun buryExhaustedLeases(maxAttempts: Int): Int =
        jdbc.sql(
            """
            UPDATE webhook_deliveries
               SET status = 'DEAD', finished_at = now(), locked_until = NULL,
                   last_error = 'Lease expired on the last attempt (worker died or hung)'
             WHERE status = 'PROCESSING' AND locked_until < now() AND attempts >= ?
            """,
        ).param(maxAttempts).update()

    /**
     * The attempts match is a fencing check: a worker that lost its lease must not finish
     * a delivery someone else reclaimed. Returns false in that case.
     */
    fun markDone(delivery: Delivery, duplicates: Int): Boolean =
        jdbc.sql(
            """
            UPDATE webhook_deliveries
               SET status = 'DONE', finished_at = now(), locked_until = NULL,
                   last_error = NULL, duplicate_messages = ?
             WHERE id = ? AND status = 'PROCESSING' AND attempts = ?
            """,
        ).param(duplicates).param(delivery.id).param(delivery.attempts).update() == 1

    fun scheduleRetry(delivery: Delivery, delay: Duration, error: String) {
        jdbc.sql(
            """
            UPDATE webhook_deliveries
               SET status = 'PENDING', locked_until = NULL, last_error = ?,
                   next_attempt_at = now() + (? * interval '1 millisecond')
             WHERE id = ? AND status = 'PROCESSING' AND attempts = ?
            """,
        ).param(error).param(delay.toMillis()).param(delivery.id).param(delivery.attempts).update()
    }

    fun markDead(delivery: Delivery, error: String) {
        jdbc.sql(
            """
            UPDATE webhook_deliveries
               SET status = 'DEAD', finished_at = now(), locked_until = NULL, last_error = ?
             WHERE id = ? AND status = 'PROCESSING' AND attempts = ?
            """,
        ).param(error).param(delivery.id).param(delivery.attempts).update()
    }

    fun replay(id: Long): Boolean =
        jdbc.sql(
            """
            UPDATE webhook_deliveries
               SET status = 'PENDING', attempts = 0, next_attempt_at = now(), finished_at = NULL
             WHERE id = ? AND status = 'DEAD'
            """,
        ).param(id).update() == 1
}
