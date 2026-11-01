package com.orbita.webhook.support

import com.orbita.webhook.outbound.OutboundMessage
import com.orbita.webhook.outbound.SendResult
import com.orbita.webhook.outbound.WhatsAppGateway
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Records every send the system really attempts. Its behavior is swapped per test. */
class RecordingGateway : WhatsAppGateway {
    val calls = CopyOnWriteArrayList<OutboundMessage>()

    @Volatile
    var behavior: (OutboundMessage) -> SendResult = ACCEPT

    override fun send(message: OutboundMessage): SendResult {
        calls += message
        return behavior(message)
    }

    fun reset() {
        calls.clear()
        behavior = ACCEPT
    }

    companion object {
        val ACCEPT: (OutboundMessage) -> SendResult = { SendResult.Accepted("wamid.TEST-${UUID.randomUUID()}") }
    }
}
