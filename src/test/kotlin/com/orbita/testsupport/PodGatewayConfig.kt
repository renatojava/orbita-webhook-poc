package com.orbita.testsupport

import com.orbita.webhook.outbound.OutboundMessage
import com.orbita.webhook.outbound.SendResult
import com.orbita.webhook.outbound.WhatsAppGateway
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment
import java.util.concurrent.CopyOnWriteArrayList

data class PodCall(val pod: String, val message: OutboundMessage)

/** One shared log of sends for all pods in the JVM: what Meta would have received, and from which pod. */
object SharedSends {
    val calls = CopyOnWriteArrayList<PodCall>()
}

class PodGateway(private val pod: String) : WhatsAppGateway {
    override fun send(message: OutboundMessage): SendResult {
        SharedSends.calls += PodCall(pod, message)
        return SendResult.Accepted("wamid.$pod-${message.id}")
    }
}

@TestConfiguration(proxyBeanMethods = false)
class PodGatewayConfig {
    @Bean
    @Primary
    fun podGateway(env: Environment): WhatsAppGateway = PodGateway(env.getRequiredProperty("test.pod-name"))
}
