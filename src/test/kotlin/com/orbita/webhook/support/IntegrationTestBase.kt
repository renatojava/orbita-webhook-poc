package com.orbita.webhook.support

import com.orbita.testsupport.RecordingGatewayConfig
import com.orbita.webhook.outbound.WhatsAppGateway
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Real HTTP on a random port against a real Postgres, with the real workers running.
 * Only the Meta send call is replaced, so tests can see exactly what would reach the lead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(RecordingGatewayConfig::class)
abstract class IntegrationTestBase {

    @LocalServerPort
    protected var port: Int = 0

    @Autowired
    protected lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var injectedGateway: WhatsAppGateway

    protected val gateway: RecordingGateway get() = injectedGateway as RecordingGateway

    protected val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    @BeforeEach
    fun cleanState() {
        jdbc.sql("TRUNCATE reply_windows, outbound_messages, messages, webhook_deliveries, tenants").update()
        gateway.reset()
        createTenant(TENANT_A, PHONE_A)
        createTenant(TENANT_B, PHONE_B)
    }

    protected fun createTenant(id: String, phoneNumberId: String, rate: Double = 1000.0, burst: Int = 1000) {
        jdbc.sql(
            """
            INSERT INTO tenants (id, phone_number_id, send_rate_per_second, send_burst, tokens)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET send_rate_per_second = EXCLUDED.send_rate_per_second,
                send_burst = EXCLUDED.send_burst, tokens = EXCLUDED.tokens, tokens_refilled_at = now()
            """,
        ).param(id).param(phoneNumberId).param(rate).param(burst).param(burst.toDouble()).update()
    }

    protected fun post(body: ByteArray, signature: String? = sign(body)): HttpResponse<Void> =
        http.send(webhookRequest(body, signature), HttpResponse.BodyHandlers.discarding())

    protected fun webhookRequest(body: ByteArray, signature: String? = sign(body)): HttpRequest =
        HttpRequest.newBuilder(URI("http://localhost:$port/webhooks/whatsapp"))
            .header("Content-Type", "application/json")
            .apply { signature?.let { header("X-Hub-Signature-256", it) } }
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()

    protected fun count(sql: String, vararg params: Any): Long =
        jdbc.sql(sql).params(*params).query { rs, _ -> rs.getLong(1) }.single()

    protected fun text(sql: String, vararg params: Any): String? =
        jdbc.sql(sql).params(*params).query { rs, _ -> rs.getString(1) }.single()

    protected fun payload(
        wamid: String,
        phoneNumberId: String = PHONE_A,
        body: String = "Oi, quero saber da formação de closer",
        from: String = "5511" + wamid.hashCode().toUInt(),
    ): ByteArray = payloadWith(phoneNumberId, listOf(wamid), body, from)

    protected fun payloadWith(phoneNumberId: String, wamids: List<String>, body: String = "Oi", from: String = "5511988887777"): ByteArray {
        val messages = wamids.joinToString(",") { wamid ->
            """{"from":"$from","id":"$wamid","timestamp":"1760364000","type":"text","text":{"body":"$body"}}"""
        }
        return """
            {"object":"whatsapp_business_account","entry":[{"id":"108800000000001","changes":[{"field":"messages","value":{
              "messaging_product":"whatsapp",
              "metadata":{"display_phone_number":"5511999990000","phone_number_id":"$phoneNumberId"},
              "contacts":[{"profile":{"name":"Ana Souza"},"wa_id":"$from"}],
              "messages":[$messages]}}]}]}
        """.trimIndent().toByteArray()
    }

    companion object {
        const val SECRET = "test-app-secret"
        const val TENANT_A = "tenant-a"
        const val TENANT_B = "tenant-b"
        const val PHONE_A = "100200300400500"
        const val PHONE_B = "100200300400600"

        private val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }

        fun sign(body: ByteArray, secret: String = SECRET): String {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
            return "sha256=" + mac.doFinal(body).joinToString("") { "%02x".format(it) }
        }
    }
}
