package com.example.uspayroll.edge.headers

import com.example.uspayroll.web.WebHeaders
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import reactor.test.StepVerifier
import java.time.Instant
import java.util.Date

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdentityPropagationFilterIT {

    @LocalServerPort
    private var port: Int = 0

    data class EchoHeaders(
        val correlationId: String?,
        val principalSub: String?,
        val principalScope: String?,
        val employerId: String?,
    )

    companion object {
        private const val hs256Secret = "dev-edge-hs256-secret-0123456789abcdef" // >= 32 bytes for MACSigner

        private var backend: DisposableServer? = null

        private fun requireBackend(): DisposableServer = backend
            ?: HttpServer
                .create()
                .port(0)
                .handle { req, res ->
                    val corr = req.requestHeaders().get(WebHeaders.CORRELATION_ID)
                    val sub = req.requestHeaders().get(WebHeaders.PRINCIPAL_SUB)
                    val scope = req.requestHeaders().get(WebHeaders.PRINCIPAL_SCOPE)
                    val employer = req.requestHeaders().get(WebHeaders.EMPLOYER_ID)

                    val json = """
                        {
                          "correlationId": ${toJsonString(corr)},
                          "principalSub": ${toJsonString(sub)},
                          "principalScope": ${toJsonString(scope)},
                          "employerId": ${toJsonString(employer)}
                        }
                    """.trimIndent()

                    res.status(200)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .sendString(reactor.core.publisher.Mono.just(json))
                }
                .bindNow()
                .also { backend = it }

        private fun toJsonString(value: String?): String = if (value == null) {
            "null"
        } else {
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            val server = requireBackend()
            registry.add("WORKER_BASE_URL") { "http://localhost:${server.port()}" }
            registry.add("edge.auth.mode") { "HS256" }
            registry.add("edge.auth.hs256-secret") { hs256Secret }
        }

        @JvmStatic
        @AfterAll
        fun shutdown() {
            backend?.disposeNow()
            backend = null
        }

        private fun mintJwt(subject: String, scope: String, scp: List<String>, employerIds: List<String>, employerId: String? = null): String {
            val now = Instant.now()
            val claims = JWTClaimsSet.Builder()
                .issuer("edge-it")
                .audience("edge")
                .subject(subject)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("scope", scope)
                .claim("scp", scp)
                .claim("employer_ids", employerIds)
                .also { b ->
                    if (!employerId.isNullOrBlank()) b.claim("employer_id", employerId)
                }
                .build()

            val header = JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(JOSEObjectType.JWT)
                .build()

            val jwt = SignedJWT(header, claims)
            jwt.sign(MACSigner(hs256Secret.toByteArray()))
            return jwt.serialize()
        }
    }

    private fun client(): WebTestClient = WebTestClient
        .bindToServer()
        .baseUrl("http://localhost:$port")
        .build()

    @Test
    fun `propagates identity headers and generates correlation id, overwriting injected employer header`() {
        val token = mintJwt(
            subject = "user-123",
            scope = "payroll:read",
            scp = listOf("payroll:write"),
            employerIds = listOf("EMP1"),
            employerId = null,
        )

        val result = client()
            .get()
            .uri("/employers/EMP1/ping")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            // Attempt header injection; edge should overwrite.
            .header(WebHeaders.EMPLOYER_ID, "EVIL")
            .exchange()
            .expectStatus().isOk
            .expectHeader().exists(WebHeaders.CORRELATION_ID)
            .returnResult(EchoHeaders::class.java)

        val correlationId = result.responseHeaders.getFirst(WebHeaders.CORRELATION_ID)
        assertNotNull(correlationId)
        assertFalse(correlationId!!.isBlank())

        StepVerifier.create(result.responseBody)
            .assertNext { body ->
                assertEquals(correlationId, body.correlationId)
                assertEquals("user-123", body.principalSub)
                assertEquals("payroll:read payroll:write", body.principalScope)
                assertEquals("EMP1", body.employerId)
            }
            .verifyComplete()
    }

    @Test
    fun `preserves provided correlation id and overwrites injected principal headers`() {
        val token = mintJwt(
            subject = "user-456",
            scope = "payroll:read",
            scp = emptyList(),
            employerIds = listOf("EMP1"),
            employerId = "EMP1",
        )

        val correlationId = "cid-123"

        val result = client()
            .get()
            .uri("/employers/EMP1/ping")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .header(WebHeaders.CORRELATION_ID, correlationId)
            .header(WebHeaders.PRINCIPAL_SUB, "evil")
            .header(WebHeaders.PRINCIPAL_SCOPE, "evil")
            .header(WebHeaders.EMPLOYER_ID, "EVIL")
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals(WebHeaders.CORRELATION_ID, correlationId)
            .returnResult(EchoHeaders::class.java)

        StepVerifier.create(result.responseBody)
            .assertNext { body ->
                assertEquals(correlationId, body.correlationId)
                assertEquals("user-456", body.principalSub)
                assertTrue(body.principalScope?.contains("payroll:read") == true)
                assertEquals("EMP1", body.employerId)
            }
            .verifyComplete()
    }
}
