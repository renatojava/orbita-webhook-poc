package com.orbita.testsupport

import com.orbita.webhook.outbound.WhatsAppGateway
import com.orbita.webhook.support.RecordingGateway
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/** Lives outside com.orbita.webhook so that pods started by hand do not pick it up by component scan. */
@TestConfiguration(proxyBeanMethods = false)
class RecordingGatewayConfig {
    @Bean
    @Primary
    fun recordingGateway(): WhatsAppGateway = RecordingGateway()
}
