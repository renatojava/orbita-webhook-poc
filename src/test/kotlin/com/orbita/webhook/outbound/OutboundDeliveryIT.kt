package com.orbita.webhook.outbound

import com.orbita.webhook.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration

class OutboundDeliveryIT : IntegrationTestBase() {

    @Autowired
    private lateinit var outbound: OutboundRepository

    private fun status() = text("SELECT status FROM outbound_messages")

    private fun enqueueOne() = outbound.enqueue(TENANT_A, "key-1", OutboundRepository.PRIORITY_REPLY, "5511", "oi")

    @Test
    fun `a certain rejection is retried with backoff until accepted`() {
        gateway.behavior = { message ->
            if (message.attempts < 3) SendResult.Rejected("429") else SendResult.Accepted("wamid.OK")
        }
        enqueueOne()

        await().atMost(Duration.ofSeconds(5)).untilAsserted { assertThat(status()).isEqualTo("SENT") }
        assertThat(gateway.calls).hasSize(3)
        assertThat(text("SELECT provider_message_id FROM outbound_messages")).isEqualTo("wamid.OK")
    }

    @Test
    fun `permanent rejections end in the dead letter queue with the reason`() {
        gateway.behavior = { SendResult.Rejected("400 invalid recipient") }
        enqueueOne()

        await().atMost(Duration.ofSeconds(5)).untilAsserted { assertThat(status()).isEqualTo("DEAD") }
        assertThat(gateway.calls).hasSize(3)
        assertThat(text("SELECT last_error FROM outbound_messages")).isEqualTo("400 invalid recipient")
    }

    @Test
    fun `an unknown outcome is flagged and never resent`() {
        gateway.behavior = { SendResult.Unknown("timeout after write") }
        enqueueOne()

        await().atMost(Duration.ofSeconds(5)).untilAsserted { assertThat(status()).isEqualTo("UNCERTAIN") }
        Thread.sleep(300)
        assertThat(gateway.calls).hasSize(1)
    }

    @Test
    fun `an unexpected gateway exception counts as unknown, not as a safe retry`() {
        gateway.behavior = { error("connection reset") }
        enqueueOne()

        await().atMost(Duration.ofSeconds(5)).untilAsserted { assertThat(status()).isEqualTo("UNCERTAIN") }
        assertThat(gateway.calls).hasSize(1)
    }

    @Test
    fun `a worker that died while sending leaves the message flagged, and it is not sent again`() {
        jdbc.sql(
            """
            INSERT INTO outbound_messages (tenant_id, idempotency_key, priority, to_phone, body, status, attempts, locked_until)
            VALUES (?, 'crashed', 0, '5511', 'oi', 'SENDING', 1, now() - interval '1 second')
            """,
        ).param(TENANT_A).update()

        await().atMost(Duration.ofSeconds(5)).untilAsserted { assertThat(status()).isEqualTo("UNCERTAIN") }
        assertThat(gateway.calls).isEmpty()
    }

    @Test
    fun `every message the gateway saw is recorded, and every SENT row reached the gateway exactly once`() {
        outbound.enqueueCampaign(TENANT_A, "audit", 500, "promo")

        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assertThat(count("SELECT count(*) FROM outbound_messages WHERE status = 'SENT'")).isEqualTo(500)
        }
        val sentKeys = jdbc.sql("SELECT idempotency_key FROM outbound_messages WHERE status = 'SENT'")
            .query { rs, _ -> rs.getString(1) }.list()
        assertThat(gateway.calls.map { it.idempotencyKey }).containsExactlyInAnyOrderElementsOf(sentKeys)
    }

    @Test
    fun `re-running a campaign does not send it twice`() {
        assertThat(outbound.enqueueCampaign(TENANT_A, "again", 10, "promo")).isEqualTo(10)
        assertThat(outbound.enqueueCampaign(TENANT_A, "again", 10, "promo")).isZero()
    }
}
