package com.orbita.webhook.webhook

import com.orbita.webhook.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class WebhookIdempotencyIT : IntegrationTestBase() {

    @Test
    fun `50 identical parallel requests produce a single record and a single automatic reply`() {
        val body = payload("wamid.ORBITA-TESTE-0001")
        val request = webhookRequest(body)
        val startGun = CountDownLatch(1)

        val statuses = Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            (1..50).map {
                pool.submit<Int> {
                    startGun.await()
                    http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
                }
            }.also { startGun.countDown() }.map { it.get() }
        }

        assertThat(statuses).hasSize(50).containsOnly(200)
        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            assertThat(count("SELECT count(*) FROM webhook_deliveries WHERE status = 'DONE'")).isEqualTo(50)
            assertThat(gateway.calls).hasSize(1)
        }
        assertThat(count("SELECT count(*) FROM messages WHERE wamid = 'wamid.ORBITA-TESTE-0001'")).isEqualTo(1)
        assertThat(count("SELECT count(*) FROM outbound_messages")).isEqualTo(1)
        assertThat(count("SELECT sum(duplicate_messages) FROM webhook_deliveries")).isEqualTo(49)
    }

    @Test
    fun `a resend after the message was fully handled still does nothing`() {
        val body = payload("wamid.RESEND")
        post(body)
        await().atMost(Duration.ofSeconds(5)).untilAsserted { assertThat(gateway.calls).hasSize(1) }

        post(body)

        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(count("SELECT count(*) FROM webhook_deliveries WHERE status = 'DONE'")).isEqualTo(2)
        }
        assertThat(count("SELECT count(*) FROM messages")).isEqualTo(1)
        assertThat(gateway.calls).hasSize(1)
    }

    @Test
    fun `every message of a single webhook is recorded and answered together`() {
        post(payloadWith(PHONE_A, listOf("wamid.M1", "wamid.M2", "wamid.M3")))

        await().atMost(Duration.ofSeconds(5)).untilAsserted { assertThat(gateway.calls).hasSize(1) }
        assertThat(count("SELECT count(*) FROM messages")).isEqualTo(3)
    }

    @Test
    fun `the same id on two different tenants is not treated as a duplicate`() {
        post(payload("wamid.SHARED", PHONE_A))
        post(payload("wamid.SHARED", PHONE_B))

        await().atMost(Duration.ofSeconds(5)).untilAsserted { assertThat(gateway.calls).hasSize(2) }
        assertThat(count("SELECT count(*) FROM messages")).isEqualTo(2)
    }

    @Test
    fun `fields of the documented payload are stored`() {
        post(payload("wamid.FIELDS"))

        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(count("SELECT count(*) FROM messages")).isEqualTo(1)
        }
        assertThat(text("SELECT contact_name FROM messages")).isEqualTo("Ana Souza")
        assertThat(text("SELECT body FROM messages")).isEqualTo("Oi, quero saber da formação de closer")
        assertThat(text("SELECT to_char(provider_timestamp AT TIME ZONE 'UTC', 'YYYY-MM-DD') FROM messages"))
            .isEqualTo("2025-10-13")
    }
}
