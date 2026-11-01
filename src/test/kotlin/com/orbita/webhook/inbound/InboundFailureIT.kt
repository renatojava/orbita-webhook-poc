package com.orbita.webhook.inbound

import com.orbita.webhook.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.util.concurrent.Executors

class InboundFailureIT : IntegrationTestBase() {

    @Autowired
    private lateinit var deliveries: DeliveryRepository

    private fun singleDeliveryStatus() = text("SELECT status FROM webhook_deliveries")

    @Test
    fun `a body that can never be processed retries with backoff and lands in the dead letter queue`() {
        assertThat(post("this is not json".toByteArray()).statusCode()).isEqualTo(200)

        await().atMost(Duration.ofSeconds(5)).untilAsserted { assertThat(singleDeliveryStatus()).isEqualTo("DEAD") }
        assertThat(count("SELECT attempts FROM webhook_deliveries")).isEqualTo(3)
        assertThat(text("SELECT last_error FROM webhook_deliveries")).isNotBlank()
        assertThat(String(jdbc.sql("SELECT raw_body FROM webhook_deliveries").query { rs, _ -> rs.getBytes(1) }.single()))
            .isEqualTo("this is not json")
    }

    @Test
    fun `an unknown number goes to the dead letter queue and can be replayed once fixed`() {
        post(payload("wamid.LATE", "999"))
        await().atMost(Duration.ofSeconds(5)).untilAsserted { assertThat(singleDeliveryStatus()).isEqualTo("DEAD") }

        createTenant("tenant-late", "999")
        val id = count("SELECT id FROM webhook_deliveries")
        assertThat(deliveries.replay(id)).isTrue()

        await().atMost(Duration.ofSeconds(5)).untilAsserted { assertThat(gateway.calls).hasSize(1) }
        assertThat(count("SELECT count(*) FROM messages")).isEqualTo(1)
    }

    @Test
    fun `a failure midway leaves nothing half written`() {
        val firstGood = """{"field":"messages","value":{"metadata":{"phone_number_id":"$PHONE_A"},
            "messages":[{"from":"1","id":"wamid.GOOD","timestamp":"1760364000","type":"text","text":{"body":"a"}}]}}"""
        val thenBad = """{"field":"messages","value":{"metadata":{"phone_number_id":"unknown"},
            "messages":[{"from":"1","id":"wamid.BAD","timestamp":"1760364000","type":"text","text":{"body":"a"}}]}}"""
        post("""{"entry":[{"changes":[$firstGood,$thenBad]}]}""".toByteArray())

        await().atMost(Duration.ofSeconds(5)).untilAsserted { assertThat(singleDeliveryStatus()).isEqualTo("DEAD") }
        assertThat(count("SELECT count(*) FROM messages")).isZero()
        assertThat(count("SELECT count(*) FROM outbound_messages")).isZero()
    }

    @Test
    fun `work held by a worker that died is picked up when its lease expires`() {
        insertAbandonedDelivery(payload("wamid.CRASH"), attempts = 1)

        await().atMost(Duration.ofSeconds(5)).untilAsserted { assertThat(singleDeliveryStatus()).isEqualTo("DONE") }
        assertThat(count("SELECT attempts FROM webhook_deliveries")).isEqualTo(2)
        assertThat(count("SELECT count(*) FROM messages")).isEqualTo(1)
    }

    @Test
    fun `a message that keeps killing the worker stops at the attempt limit instead of looping forever`() {
        insertAbandonedDelivery(payload("wamid.POISON"), attempts = 3)

        await().atMost(Duration.ofSeconds(5)).untilAsserted { assertThat(singleDeliveryStatus()).isEqualTo("DEAD") }
        assertThat(count("SELECT count(*) FROM messages")).isZero()
        assertThat(text("SELECT last_error FROM webhook_deliveries")).contains("Lease expired")
    }

    @Test
    fun `a worker that lost its lease cannot overwrite the outcome of whoever reclaimed the work`() {
        val id = jdbc.sql(
            """
            INSERT INTO webhook_deliveries (raw_body, status, attempts, locked_until)
            VALUES (?, 'PROCESSING', 2, now() + interval '1 hour') RETURNING id
            """,
        ).param("{}".toByteArray()).query { rs, _ -> rs.getLong(1) }.single()
        val staleWorkerView = Delivery(id, "{}".toByteArray(), attempts = 1)

        assertThat(deliveries.markDone(staleWorkerView, 0)).isFalse()
        assertThat(singleDeliveryStatus()).isEqualTo("PROCESSING")
    }

    @Test
    fun `concurrent workers never claim the same delivery`() {
        jdbc.sql(
            """
            INSERT INTO webhook_deliveries (raw_body)
            SELECT convert_to('{}', 'UTF8') FROM generate_series(1, 400)
            """,
        ).update()

        val claimed = Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            (1..8).map {
                pool.submit<List<Long>> {
                    (1..5).flatMap { deliveries.claim(10, Duration.ofMinutes(5)).map(Delivery::id) }
                }
            }.flatMap { it.get() }
        }

        assertThat(claimed).isNotEmpty().doesNotHaveDuplicates()
    }

    private fun insertAbandonedDelivery(body: ByteArray, attempts: Int) {
        jdbc.sql(
            """
            INSERT INTO webhook_deliveries (raw_body, status, attempts, locked_until)
            VALUES (?, 'PROCESSING', ?, now() - interval '1 second')
            """,
        ).param(body).param(attempts).update()
    }
}
