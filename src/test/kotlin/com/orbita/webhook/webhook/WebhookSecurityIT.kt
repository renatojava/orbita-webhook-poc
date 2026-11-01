package com.orbita.webhook.webhook

import com.orbita.webhook.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WebhookSecurityIT : IntegrationTestBase() {

    @Test
    fun `missing, wrong and tampered signatures are refused and nothing is enqueued`() {
        val body = payload("wamid.SEC")

        assertThat(post(body, signature = null).statusCode()).isEqualTo(401)
        assertThat(post(body, signature = sign(body, "another-secret")).statusCode()).isEqualTo(401)
        assertThat(post(body + ' '.code.toByte(), signature = sign(body)).statusCode()).isEqualTo(401)

        assertThat(count("SELECT count(*) FROM webhook_deliveries")).isZero()
    }

    @Test
    fun `signature is computed on the raw bytes, whatever the JSON formatting`() {
        val oddlyFormatted = "  {  \"entry\" :\n [ ]  , \"acentuação\": \"ção\\u00e7\" }\n".toByteArray()

        assertThat(post(oddlyFormatted).statusCode()).isEqualTo(200)
    }
}
