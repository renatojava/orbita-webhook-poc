package com.orbita.webhook.outbound

import com.orbita.webhook.config.OrbitaProperties
import org.springframework.stereotype.Component
import java.util.UUID

/** Stands in for Meta in this PoC: it accepts every message after a fixed latency. */
@Component
class FakeWhatsAppGateway(properties: OrbitaProperties) : WhatsAppGateway {
    private val latency = properties.gateway.latency

    override fun send(message: OutboundMessage): SendResult {
        Thread.sleep(latency)
        return SendResult.Accepted("wamid.FAKE-${UUID.randomUUID()}")
    }
}
