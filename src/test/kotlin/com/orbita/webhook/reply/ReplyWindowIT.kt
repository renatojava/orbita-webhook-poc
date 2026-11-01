package com.orbita.webhook.reply

import com.orbita.webhook.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** Test profile: quiet period of 300 ms and maximum wait of 1200 ms. */
class ReplyWindowIT : IntegrationTestBase() {
    private val contact = "5511977776666"

    private fun send(wamid: String, from: String = contact) = post(payload(wamid, from = from))

    private fun awaitNoOpenWindow() = await().atMost(Duration.ofSeconds(5)).until {
        count("SELECT count(*) FROM reply_windows") == 0L &&
            count("SELECT count(*) FROM webhook_deliveries WHERE status <> 'DONE'") == 0L
    }

    @Test
    fun `messages in a burst get a single reply that accounts for all of them`() {
        (1..3).forEach { send("wamid.BURST-$it") }

        await().atMost(Duration.ofSeconds(5)).untilAsserted { assertThat(gateway.calls).hasSize(1) }
        assertThat(gateway.calls.single().toPhone).isEqualTo(contact)
        assertThat(count("SELECT count(*) FROM messages WHERE reply_key = ?", gateway.calls.single().idempotencyKey))
            .isEqualTo(3)
    }

    @Test
    fun `each new message pushes the reply back`() {
        repeat(5) {
            send("wamid.SLIDE-$it")
            Thread.sleep(150)
        }
        // 750 ms have passed, far beyond a 300 ms window that had not slid.
        assertThat(gateway.calls).isEmpty()

        await().atMost(Duration.ofSeconds(5)).untilAsserted { assertThat(gateway.calls).hasSize(1) }
    }

    @Test
    fun `someone who never stops typing is answered at the maximum wait`() {
        val start = System.nanoTime()
        var i = 0
        while (gateway.calls.isEmpty() && (System.nanoTime() - start) < 5_000_000_000L) {
            send("wamid.ENDLESS-${i++}")
            Thread.sleep(100)
        }

        assertThat(gateway.calls).isNotEmpty()
        assertThat((System.nanoTime() - start) / 1_000_000).isBetween(1000L, 2500L)
    }

    @Test
    fun `different contacts never share a window`() {
        send("wamid.C1", "5511911111111")
        send("wamid.C2", "5511922222222")

        await().atMost(Duration.ofSeconds(5)).untilAsserted { assertThat(gateway.calls).hasSize(2) }
        assertThat(gateway.calls.map { it.toPhone }).containsExactlyInAnyOrder("5511911111111", "5511922222222")
    }

    @Test
    fun `a message after the window closed opens a new one and is answered separately`() {
        send("wamid.FIRST")
        await().atMost(Duration.ofSeconds(5)).untilAsserted { assertThat(gateway.calls).hasSize(1) }

        send("wamid.SECOND")

        await().atMost(Duration.ofSeconds(5)).untilAsserted { assertThat(gateway.calls).hasSize(2) }
        assertThat(count("SELECT count(DISTINCT reply_key) FROM messages")).isEqualTo(2)
    }

    @Test
    fun `a resend from Meta does not push the reply back`() {
        val body = payload("wamid.DUP", from = contact)
        post(body)
        await().atMost(Duration.ofSeconds(5)).until { count("SELECT count(*) FROM reply_windows") == 1L }
        val farFuture = "now() + interval '1 hour'"
        jdbc.sql("UPDATE reply_windows SET due_at = $farFuture, last_message_at = '2020-06-15Z'").update()

        post(body)

        await().atMost(Duration.ofSeconds(5)).until {
            count("SELECT count(*) FROM webhook_deliveries WHERE status = 'DONE'") == 2L
        }
        assertThat(count("SELECT EXTRACT(YEAR FROM last_message_at)::bigint FROM reply_windows")).isEqualTo(2020)
    }

    @Test
    fun `a window that was open when the process died still produces the reply after restart`() {
        jdbc.sql(
            """
            INSERT INTO messages (tenant_id, wamid, from_phone, type, provider_timestamp)
            VALUES (?, 'wamid.BEFORE-CRASH', ?, 'text', now())
            """,
        ).param(TENANT_A).param(contact).update()
        jdbc.sql(
            """
            INSERT INTO reply_windows (tenant_id, contact_phone, opened_at, last_message_at, due_at)
            VALUES (?, ?, now() - interval '1 minute', now() - interval '1 minute', now() - interval '30 seconds')
            """,
        ).param(TENANT_A).param(contact).update()

        await().atMost(Duration.ofSeconds(5)).untilAsserted { assertThat(gateway.calls).hasSize(1) }
    }

    @Test
    fun `messages racing with the window closing are all answered exactly once`() {
        val total = 200
        val startGun = CountDownLatch(1)

        // Spread over about 1 s, around the quiet period, so windows close while messages keep landing.
        Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            (1..total).map { i ->
                pool.submit {
                    startGun.await()
                    Thread.sleep(i * 5L)
                    http.send(webhookRequest(payload("wamid.RACE-$i", from = contact)), HttpResponse.BodyHandlers.discarding())
                }
            }.also { startGun.countDown() }.forEach { it.get() }
        }
        awaitNoOpenWindow()

        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(count("SELECT count(*) FROM outbound_messages WHERE status <> 'SENT'")).isZero()
        }
        assertThat(count("SELECT count(*) FROM messages")).isEqualTo(total.toLong())
        assertThat(count("SELECT count(*) FROM messages WHERE reply_key IS NULL")).isZero()
        assertThat(count("SELECT count(DISTINCT reply_key) FROM messages"))
            .isEqualTo(gateway.calls.size.toLong())
        assertThat(gateway.calls.map { it.idempotencyKey }).doesNotHaveDuplicates()
    }
}
