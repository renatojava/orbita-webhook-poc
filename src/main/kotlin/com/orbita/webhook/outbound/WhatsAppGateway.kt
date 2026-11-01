package com.orbita.webhook.outbound

/** Port for the Meta send API. The three outcomes exist because they demand different handling. */
interface WhatsAppGateway {
    fun send(message: OutboundMessage): SendResult
}

sealed interface SendResult {
    data class Accepted(val providerMessageId: String) : SendResult

    /** Meta certainly did not take it (429, 5xx before processing, connection refused): safe to retry. */
    data class Rejected(val reason: String) : SendResult

    /** We cannot know (timeout after writing): retrying could produce a second message for the lead. */
    data class Unknown(val reason: String) : SendResult
}
