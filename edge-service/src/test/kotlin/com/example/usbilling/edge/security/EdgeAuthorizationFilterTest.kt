package com.example.usbilling.edge.security

import com.example.usbilling.web.WebHeaders
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.util.concurrent.atomic.AtomicInteger

class EdgeAuthorizationFilterTest {

    private val mapper = ObjectMapper()

    @Test
    fun `blocks write when payroll write scope is missing`() {
        val filter = EdgeAuthorizationFilter(mapper)

        val jwt = Jwt.withTokenValue("t")
            .header("alg", "none")
            .subject("sub")
            .claim("scope", "payroll:read")
            .claim("employer_id", "EMP1")
            .build()

        val auth = JwtAuthenticationToken(jwt)

        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.method(HttpMethod.POST, "/employers/EMP1/payruns/finalize")
                .header(WebHeaders.CORRELATION_ID, "c")
                .build(),
        ).mutate().principal(Mono.just(auth)).build()

        val called = AtomicInteger(0)
        val chain = GatewayFilterChain { ex ->
            called.incrementAndGet()
            ex.response.setComplete()
        }

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete()

        assertEquals(0, called.get())
        assertEquals(403, exchange.response.statusCode?.value())
        assertNotNull(exchange.response.headers.getFirst("Content-Type"))
    }

    @Test
    fun `blocks employer mismatch`() {
        val filter = EdgeAuthorizationFilter(mapper)

        val jwt = Jwt.withTokenValue("t")
            .header("alg", "none")
            .subject("sub")
            .claim("scope", "payroll:write")
            .claim("employer_id", "EMP2")
            .build()

        val auth = JwtAuthenticationToken(jwt)

        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.method(HttpMethod.POST, "/employers/EMP1/payruns/finalize")
                .header(WebHeaders.CORRELATION_ID, "c")
                .build(),
        ).mutate().principal(Mono.just(auth)).build()

        val chain = GatewayFilterChain { ex -> ex.response.setComplete() }

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete()
        assertEquals(403, exchange.response.statusCode?.value())
    }

    @Test
    fun `allows when employer_ids contains employer`() {
        val filter = EdgeAuthorizationFilter(mapper)

        val jwt = Jwt.withTokenValue("t")
            .header("alg", "none")
            .subject("sub")
            .claim("scp", listOf("payroll:write"))
            .claim("employer_ids", listOf("EMP1", "EMP2"))
            .build()

        val auth = JwtAuthenticationToken(jwt)

        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.method(HttpMethod.POST, "/employers/EMP1/payruns/finalize")
                .header(WebHeaders.CORRELATION_ID, "c")
                .build(),
        ).mutate().principal(Mono.just(auth)).build()

        val called = AtomicInteger(0)
        val chain = GatewayFilterChain { ex ->
            called.incrementAndGet()
            ex.response.statusCode = HttpStatus.OK
            ex.response.setComplete()
        }

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete()
        assertEquals(1, called.get())
        assertEquals(200, exchange.response.statusCode?.value())
    }

    @Test
    fun `blocks benchmarks when payroll bench scope is missing`() {
        val filter = EdgeAuthorizationFilter(mapper)

        val jwt = Jwt.withTokenValue("t")
            .header("alg", "none")
            .subject("sub")
            .claim("scope", "payroll:write")
            .build()

        val auth = JwtAuthenticationToken(jwt)

        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.method(HttpMethod.POST, "/benchmarks/employers/EMP1/hr-backed-pay-period")
                .header(WebHeaders.CORRELATION_ID, "c")
                .build(),
        ).mutate().principal(Mono.just(auth)).build()

        val called = AtomicInteger(0)
        val chain = GatewayFilterChain { ex ->
            called.incrementAndGet()
            ex.response.setComplete()
        }

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete()
        assertEquals(0, called.get())
        assertEquals(403, exchange.response.statusCode?.value())
    }

    @Test
    fun `allows benchmarks when payroll bench scope is present`() {
        val filter = EdgeAuthorizationFilter(mapper)

        val jwt = Jwt.withTokenValue("t")
            .header("alg", "none")
            .subject("sub")
            .claim("scope", "payroll:bench")
            .build()

        val auth = JwtAuthenticationToken(jwt)

        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.method(HttpMethod.POST, "/benchmarks/employers/EMP1/hr-backed-pay-period")
                .header(WebHeaders.CORRELATION_ID, "c")
                .build(),
        ).mutate().principal(Mono.just(auth)).build()

        val called = AtomicInteger(0)
        val chain = GatewayFilterChain { ex ->
            called.incrementAndGet()
            ex.response.statusCode = HttpStatus.OK
            ex.response.setComplete()
        }

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete()
        assertEquals(1, called.get())
        assertEquals(200, exchange.response.statusCode?.value())
    }

    @Test
    fun `blocks internal replay when payroll replay scope is missing`() {
        val filter = EdgeAuthorizationFilter(mapper)

        val jwt = Jwt.withTokenValue("t")
            .header("alg", "none")
            .subject("sub")
            .claim("scope", "payroll:write")
            .build()

        val auth = JwtAuthenticationToken(jwt)

        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.method(HttpMethod.POST, "/internal/jobs/finalize-employee/dlq/replay")
                .header(WebHeaders.CORRELATION_ID, "c")
                .build(),
        ).mutate().principal(Mono.just(auth)).build()

        val called = AtomicInteger(0)
        val chain = GatewayFilterChain { ex ->
            called.incrementAndGet()
            ex.response.setComplete()
        }

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete()
        assertEquals(0, called.get())
        assertEquals(403, exchange.response.statusCode?.value())
    }
}
