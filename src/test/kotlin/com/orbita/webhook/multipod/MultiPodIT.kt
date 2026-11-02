package com.orbita.webhook.multipod

import com.orbita.testsupport.PodGatewayConfig
import com.orbita.testsupport.SharedSends
import com.orbita.webhook.Application
import com.orbita.webhook.outbound.OutboundRepository
import com.orbita.webhook.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.simple.JdbcClient
import org.testcontainers.postgresql.PostgreSQLContainer
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.locks.LockSupport

/**
 * Three full instances of the application, each with its own HTTP server, schedulers and
 * connection pool, sharing one Postgres: what three pods behind a Service look like.
 * They coordinate only through the database; nothing is shared in memory.
 */
class MultiPodIT {
    private val pods = mutableListOf<ConfigurableApplicationContext>()
    private val http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(1))
        .build()

    private val jdbc: JdbcClient get() = pods.first { it.isActive }.getBean(JdbcClient::class.java)

    @BeforeEach
    fun startPods() {
        SharedSends.calls.clear()
        // As in production: migrations run once, as their own step, then the pods start together.
        // Letting every pod migrate on boot fails on an empty database: Liquibase's own tables race.
        migrate()
        pods += Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            (1..PODS).map { n -> pool.submit<ConfigurableApplicationContext> { startPod("pod-$n") } }.map { it.get() }
        }
        jdbc.sql("TRUNCATE reply_windows, outbound_messages, messages, webhook_deliveries, tenants").update()
    }

    @AfterEach
    fun stopPods() {
        pods.filter { it.isActive }.forEach { it.close() }
        pods.clear()
    }

    @Test
    fun `three pods share the work, and losing one in the middle loses and duplicates nothing`() {
        createTenant(rate = 1000.0, burst = 1000)
        val total = 1500
        val accepted = ConcurrentHashMap.newKeySet<String>()
        val podLoss = Thread.ofVirtual().start {
            Thread.sleep(5000)
            pods[1].close()
        }

        Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            val start = System.nanoTime()
            (0 until total).map { i ->
                LockSupport.parkNanos(start + i * INTERVAL_NANOS - System.nanoTime())
                pool.submit { if (deliver("wamid.MP-$i", firstPod = i % PODS)) accepted += "wamid.MP-$i" }
            }.forEach { it.get() }
        }
        podLoss.join()

        assertThat(accepted).hasSize(total)
        await().atMost(Duration.ofSeconds(60)).untilAsserted {
            assertThat(count("SELECT count(*) FROM webhook_deliveries WHERE status <> 'DONE'")).isZero()
            assertThat(count("SELECT count(*) FROM reply_windows")).isZero()
            assertThat(count("SELECT count(*) FROM outbound_messages WHERE status IN ('PENDING', 'SENDING')")).isZero()
        }
        assertThat(count("SELECT count(*) FROM messages")).isEqualTo(total.toLong())
        assertThat(count("SELECT count(*) FROM messages WHERE reply_key IS NULL")).isZero()
        assertThat(SharedSends.calls.map { it.message.idempotencyKey }).doesNotHaveDuplicates()
        assertThat(count("SELECT count(*) FROM outbound_messages WHERE status IN ('SENT', 'UNCERTAIN')"))
            .isEqualTo(total.toLong())

        val deliveriesByPod = byWorker("webhook_deliveries")
        val sendsByPod = SharedSends.calls.groupingBy { it.pod }.eachCount().toSortedMap()
        assertThat(deliveriesByPod.keys).containsExactlyInAnyOrder("pod-1", "pod-2", "pod-3")
        assertThat(sendsByPod.keys.size).isGreaterThanOrEqualTo(2)
        println(
            "MULTI-POD messages=$total accepted=${accepted.size} deliveriesByPod=$deliveriesByPod " +
                "sendsByPod=$sendsByPod uncertain=${count("SELECT count(*) FROM outbound_messages WHERE status = 'UNCERTAIN'")}",
        )
    }

    @Test
    fun `the send limit is per tenant across all pods, not per pod`() {
        createTenant(rate = 30.0, burst = 10)
        pods[0].getBean(OutboundRepository::class.java).enqueueCampaign(TENANT, "multi-pod", 3000, "promo")
        val start = System.nanoTime()

        Thread.sleep(3000)

        val sent = SharedSends.calls.size
        val allowed = 10 + 30 * (System.nanoTime() - start) / 1e9
        val pods = SharedSends.calls.map { it.pod }.toSet()
        println("MULTI-POD-RATE sent=$sent allowed=${"%.0f".format(allowed)} pods=$pods")
        assertThat(sent.toDouble()).isLessThanOrEqualTo(allowed).isGreaterThan(allowed * 0.75)
        assertThat(pods.size).isGreaterThanOrEqualTo(2)
    }

    /** Like Meta: if a pod refuses or is gone, the same event is resent, here to the next pod. */
    private fun deliver(wamid: String, firstPod: Int): Boolean {
        val body = payload(wamid)
        val signature = IntegrationTestBase.sign(body)
        repeat(PODS * 2) { attempt ->
            val pod = pods[(firstPod + attempt) % PODS]
            if (!pod.isActive) return@repeat
            val port = pod.environment.getProperty("local.server.port") ?: return@repeat
            val request = HttpRequest.newBuilder(URI("http://localhost:$port/webhooks/whatsapp"))
                .header("X-Hub-Signature-256", signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build()
            try {
                if (http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) return true
            } catch (_: IOException) {
                // The pod went away during the request: try the next one.
            }
        }
        return false
    }

    /** Same jar, migration mode: no web server, no workers. It applies the changelog and exits. */
    private fun migrate() {
        SpringApplicationBuilder(Application::class.java, PodGatewayConfig::class.java)
            .profiles("test")
            .run(
                *datasourceArgs(),
                "--spring.main.web-application-type=none",
                "--orbita.workers.enabled=false",
                "--test.pod-name=migration",
            )
            .close()
    }

    private fun datasourceArgs() = arrayOf(
        "--spring.datasource.url=${postgres.jdbcUrl}",
        "--spring.datasource.username=${postgres.username}",
        "--spring.datasource.password=${postgres.password}",
    )

    private fun startPod(name: String): ConfigurableApplicationContext =
        SpringApplicationBuilder(Application::class.java, PodGatewayConfig::class.java)
            .profiles("test")
            .run(
                *datasourceArgs(),
                "--server.port=0",
                "--spring.liquibase.enabled=false",
                "--spring.datasource.hikari.maximum-pool-size=12",
                "--orbita.instance-id=$name",
                "--orbita.inbound.lease=2s",
                "--orbita.outbound.lease=2s",
                "--test.pod-name=$name",
            )

    private fun createTenant(rate: Double, burst: Int) {
        jdbc.sql(
            """
            INSERT INTO tenants (id, phone_number_id, send_rate_per_second, send_burst, tokens)
            VALUES (?, ?, ?, ?, ?)
            """,
        ).param(TENANT).param(PHONE).param(rate).param(burst).param(burst.toDouble()).update()
    }

    private fun payload(wamid: String): ByteArray {
        val from = "5511" + wamid.hashCode().toUInt()
        return """
            {"entry":[{"changes":[{"field":"messages","value":{
              "metadata":{"phone_number_id":"$PHONE"},
              "contacts":[{"profile":{"name":"Ana"},"wa_id":"$from"}],
              "messages":[{"from":"$from","id":"$wamid","timestamp":"1760364000","type":"text","text":{"body":"Oi"}}]}}]}]}
        """.trimIndent().toByteArray()
    }

    private fun count(sql: String): Long = jdbc.sql(sql).query { rs, _ -> rs.getLong(1) }.single()

    private fun byWorker(table: String): Map<String, Long> =
        jdbc.sql("SELECT worker_id, count(*) FROM $table GROUP BY 1 ORDER BY 1")
            .query { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().toMap()

    private companion object {
        const val PODS = 3
        const val TENANT = "tenant-a"
        const val PHONE = "100200300400500"
        const val INTERVAL_NANOS = 10_000_000L // 100 msg/s

        // Its own database: test contexts cached by other classes would otherwise act as a fourth pod.
        val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }
    }
}
