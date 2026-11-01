package com.orbita.webhook.outbound

import com.orbita.webhook.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration

class TenantIsolationIT : IntegrationTestBase() {

    @Autowired
    private lateinit var outbound: OutboundRepository

    private fun sentCount(tenant: String) =
        count("SELECT count(*) FROM outbound_messages WHERE tenant_id = ? AND status = 'SENT'", tenant)

    @Test
    fun `a tenant never sends above its configured rate`() {
        createTenant(TENANT_A, PHONE_A, rate = 50.0, burst = 10)
        outbound.enqueueCampaign(TENANT_A, "big", 5000, "promo")
        val start = System.nanoTime()

        Thread.sleep(3000)

        val attempted = gateway.calls.count { it.tenantId == TENANT_A }
        val elapsedSeconds = (System.nanoTime() - start) / 1e9
        val allowed = 10 + 50 * elapsedSeconds
        assertThat(attempted.toDouble()).isLessThanOrEqualTo(allowed)
        assertThat(attempted.toDouble()).isCloseTo(allowed, within(allowed * 0.25))
    }

    @Test
    fun `a huge campaign on one tenant does not delay another tenant`() {
        createTenant(TENANT_A, PHONE_A, rate = 20.0, burst = 20)
        outbound.enqueueCampaign(TENANT_A, "big", 20_000, "promo")
        await().atMost(Duration.ofSeconds(2)).until { sentCount(TENANT_A) > 0 }

        val start = System.nanoTime()
        post(payload("wamid.B", PHONE_B))
        await().atMost(Duration.ofSeconds(5)).until { sentCount(TENANT_B) == 1L }

        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1000)
    }

    @Test
    fun `a tenant whose sends hang does not delay another tenant`() {
        gateway.behavior = { message ->
            if (message.tenantId == TENANT_A) Thread.sleep(2000)
            SendResult.Accepted("wamid.OK")
        }
        outbound.enqueueCampaign(TENANT_A, "stuck", 50, "promo")
        await().atMost(Duration.ofSeconds(2)).until { gateway.calls.isNotEmpty() }

        val start = System.nanoTime()
        post(payload("wamid.B", PHONE_B))
        await().atMost(Duration.ofSeconds(5)).until { sentCount(TENANT_B) == 1L }

        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1000)
    }

    @Test
    fun `a tenant's own campaign does not delay its automatic replies`() {
        createTenant(TENANT_A, PHONE_A, rate = 20.0, burst = 20)
        outbound.enqueueCampaign(TENANT_A, "big", 5000, "promo")
        await().atMost(Duration.ofSeconds(2)).until { sentCount(TENANT_A) > 0 }

        post(payload("wamid.A", PHONE_A))

        await().atMost(Duration.ofSeconds(2)).until {
            gateway.calls.any { it.idempotencyKey == "auto-reply:wamid.A" }
        }
    }
}
