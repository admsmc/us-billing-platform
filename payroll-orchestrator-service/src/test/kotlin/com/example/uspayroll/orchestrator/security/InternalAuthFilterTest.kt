package com.example.uspayroll.orchestrator.security

import com.example.uspayroll.web.security.InternalJwtHs256
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.concurrent.atomic.AtomicBoolean

class InternalAuthFilterTest {

    private val objectMapper = ObjectMapper()

    @Test
    fun `allows internal endpoint when Authorization bearer internal JWT is valid`() {
        val props = InternalAuthProperties(
            sharedSecret = "",
            headerName = "X-Internal-Token",
            jwtSharedSecret = "jwt-secret",
            jwtIssuer = "us-payroll-platform",
            jwtAudience = "payroll-orchestrator-service",
        )

        val filter = InternalAuthFilter(props, objectMapper)

        val token = InternalJwtHs256.issue(
            secret = props.jwtSharedSecret,
            issuer = props.jwtIssuer,
            subject = "payroll-worker-service",
            audience = props.jwtAudience,
            ttlSeconds = 60,
        )

        val request = MockHttpServletRequest("POST", "/employers/EMP1/payruns/internal/PR1/execute")
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer $token")
        val response = MockHttpServletResponse()

        val called = AtomicBoolean(false)
        val chain = FilterChain { _, _ -> called.set(true) }

        filter.doFilter(request, response, chain)

        assertTrue(called.get())
        assertEquals(200, response.status)
    }

    @Test
    fun `allows internal endpoint when shared secret header matches`() {
        val props = InternalAuthProperties(
            sharedSecret = "legacy-secret",
            headerName = "X-Internal-Token",
            jwtSharedSecret = "",
        )

        val filter = InternalAuthFilter(props, objectMapper)

        val request = MockHttpServletRequest("POST", "/employers/EMP1/payruns/internal/PR1/execute")
        request.addHeader(props.headerName, props.sharedSecret)
        val response = MockHttpServletResponse()

        val called = AtomicBoolean(false)
        val chain = FilterChain { _, _ -> called.set(true) }

        filter.doFilter(request, response, chain)

        assertTrue(called.get())
        assertEquals(200, response.status)
    }

    @Test
    fun `rejects internal endpoint when bearer token is invalid and shared secret is absent`() {
        val props = InternalAuthProperties(
            sharedSecret = "",
            headerName = "X-Internal-Token",
            jwtSharedSecret = "jwt-secret",
            jwtIssuer = "us-payroll-platform",
            jwtAudience = "payroll-orchestrator-service",
        )

        val filter = InternalAuthFilter(props, objectMapper)

        val request = MockHttpServletRequest("POST", "/employers/EMP1/payruns/internal/PR1/execute")
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
        val response = MockHttpServletResponse()

        val called = AtomicBoolean(false)
        val chain = FilterChain { _, _ -> called.set(true) }

        filter.doFilter(request, response, chain)

        assertEquals(401, response.status)
        assertEquals(false, called.get())
    }
}
