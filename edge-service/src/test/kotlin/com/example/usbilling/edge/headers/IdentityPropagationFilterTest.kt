package com.example.usbilling.edge.headers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.util.concurrent.atomic.AtomicReference

class IdentityPropagationFilterTest {

    @Test
    fun `adds correlation id when missing and echoes it on response`() {
        val filter = IdentityPropagationFilter()
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/hello").build())

        val seenRequest = AtomicReference<ServerHttpRequest>()
        val chain = GatewayFilterChain { ex ->
            seenRequest.set(ex.request)
            ex.response.setComplete()
        }

        StepVerifier.create(filter.filter(exchange, chain))
            .verifyComplete()

        val requestCorrelation = seenRequest.get().headers.getFirst("X-Correlation-ID")
        assertNotNull(requestCorrelation)

        val responseCorrelation = exchange.response.headers.getFirst("X-Correlation-ID")
        assertEquals(requestCorrelation, responseCorrelation)
    }

    @Test
    fun `propagates jwt identity headers`() {
        val filter = IdentityPropagationFilter()

        val jwt = Jwt.withTokenValue("t")
            .header("alg", "none")
            .subject("sub-123")
            .claim("scope", "payroll:read")
            .claim("employer_id", "EMP-1")
            .build()
        val auth = JwtAuthenticationToken(jwt)

        val baseExchange = MockServerWebExchange.from(MockServerHttpRequest.get("/hello").build())
        val exchange = baseExchange.mutate().principal(Mono.just(auth)).build()

        val seenRequest = AtomicReference<ServerHttpRequest>()
        val chain = GatewayFilterChain { ex ->
            seenRequest.set(ex.request)
            ex.response.setComplete()
        }

        StepVerifier.create(filter.filter(exchange, chain))
            .verifyComplete()

        val req = seenRequest.get()
        assertEquals("sub-123", req.headers.getFirst("X-Principal-Sub"))
        assertEquals("payroll:read", req.headers.getFirst("X-Principal-Scope"))
        assertEquals("EMP-1", req.headers.getFirst("X-Employer-Id"))
    }

    @Test
    fun `does not add identity headers when principal is not jwt`() {
        val filter = IdentityPropagationFilter()
        val auth = TestingAuthenticationToken("user", "pass")

        val baseExchange = MockServerWebExchange.from(MockServerHttpRequest.get("/hello").build())
        val exchange = baseExchange.mutate().principal(Mono.just(auth)).build()

        val seenRequest = AtomicReference<ServerHttpRequest>()
        val chain = GatewayFilterChain { ex ->
            seenRequest.set(ex.request)
            ex.response.setComplete()
        }

        StepVerifier.create(filter.filter(exchange, chain))
            .verifyComplete()

        val req = seenRequest.get()
        assertEquals(null, req.headers.getFirst("X-Principal-Sub"))
        assertEquals(null, req.headers.getFirst("X-Principal-Scope"))
        assertEquals(null, req.headers.getFirst("X-Employer-Id"))
    }
}
