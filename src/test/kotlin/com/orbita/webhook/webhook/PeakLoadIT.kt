package com.orbita.webhook.webhook

import com.orbita.webhook.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.locks.LockSupport

/**
 * 3x the 25 msg/s peak expected for the event, with 3% of messages resent, as seen in production.
 * Open loop: requests go out on schedule even if the server is slow, so slowness is not hidden.
 * Numbers come from a laptop with the load generator in the same JVM: they are evidence, not capacity.
 */
class PeakLoadIT : IntegrationTestBase() {
    private val ratePerSecond = Integer.getInteger("load.rate", 75)
    private val seconds = Integer.getInteger("load.seconds", 30)

    @Test
    fun `3x the expected peak loses nothing, duplicates nothing and answers within budget`() {
        warmUp()
        val total = ratePerSecond * seconds
        val duplicated = (0 until total).filter { it % DUPLICATE_EVERY == 0 }.toSet()
        val intervalNanos = 1_000_000_000L / ratePerSecond
        val start = System.nanoTime()

        val results = Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            (0 until total).flatMap { i ->
                LockSupport.parkNanos(start + i * intervalNanos - System.nanoTime())
                val request = webhookRequest(payload("wamid.LOAD-$i"))
                (1..if (i in duplicated) 2 else 1).map {
                    pool.submit<Pair<Int, Long>> {
                        val sentAt = System.nanoTime()
                        val status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
                        status to (System.nanoTime() - sentAt) / 1_000_000
                    }
                }
            }.map { it.get() }
        }

        val expectedDeliveries = total + duplicated.size
        assertThat(results).hasSize(expectedDeliveries)
        assertThat(results.map { it.first }).containsOnly(200)

        await().atMost(Duration.ofSeconds(60)).untilAsserted {
            assertThat(count("SELECT count(*) FROM webhook_deliveries WHERE status = 'DONE'"))
                .isEqualTo(expectedDeliveries.toLong())
            assertThat(gateway.calls).hasSize(total)
        }
        assertThat(count("SELECT count(*) FROM messages")).isEqualTo(total.toLong())
        assertThat(count("SELECT count(*) FROM webhook_deliveries WHERE status <> 'DONE'")).isZero()
        assertThat(count("SELECT count(*) FROM outbound_messages WHERE status <> 'SENT'")).isZero()
        assertThat(gateway.calls.map { it.idempotencyKey }).doesNotHaveDuplicates()

        val latencies = results.map { it.second }.sorted()
        val p50 = latencies[latencies.size / 2]
        val p99 = latencies[(latencies.size * 99) / 100]
        val report = "rate=$ratePerSecond/s seconds=$seconds messages=$total deliveries=$expectedDeliveries " +
            "lost=0 duplicated_records=0 p50=${p50}ms p99=${p99}ms max=${latencies.last()}ms"
        println("PEAK-LOAD $report")
        Files.writeString(Path.of("target/peak-load.txt"), report + "\n")
        assertThat(p99).isLessThan(200)
    }

    private fun warmUp() {
        repeat(WARM_UP_REQUESTS) { post(payload("wamid.WARM-$it")) }
        await().atMost(Duration.ofSeconds(30)).untilAsserted { assertThat(gateway.calls).hasSize(WARM_UP_REQUESTS) }
        jdbc.sql("TRUNCATE outbound_messages, messages, webhook_deliveries").update()
        gateway.reset()
    }

    private companion object {
        const val DUPLICATE_EVERY = 33
        const val WARM_UP_REQUESTS = 300
    }
}
