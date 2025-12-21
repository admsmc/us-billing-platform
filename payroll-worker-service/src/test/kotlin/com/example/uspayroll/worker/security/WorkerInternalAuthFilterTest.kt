package com.example.uspayroll.worker.security

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

class WorkerInternalAuthFilterTest {

    private val objectMapper = ObjectMapper()

    @Test
    fun `allows internal endpoint when Authorization bearer internal JWT is valid`() {
        val props = WorkerInternalAuthProperties(
            jwtKeys = mapOf("k1" to "jwt-secret-1"),
            jwtDefaultKid = "k1",
            jwtIssuer = "us-payroll-platform",
            jwtAudience = "payroll-worker-service",
        )

        val filter = WorkerInternalAuthFilter(props, objectMapper)

        val token = InternalJwtHs256.issue(
            secret = props.jwtKeys.getValue("k1"),
            issuer = props.jwtIssuer,
            subject = "ops",
            audience = props.jwtAudience,
            ttlSeconds = 60,
            kid = "k1",
        )

        val request = MockHttpServletRequest("POST", "/internal/jobs/finalize-employee/dlq/replay")
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer $token")
        val response = MockHttpServletResponse()

        val called = AtomicBoolean(false)
        val chain = FilterChain { _, _ -> called.set(true) }

        filter.doFilter(request, response, chain)

        assertTrue(called.get())
        assertEquals(200, response.status)
    }

    @Test
    fun `rejects internal endpoint when bearer token missing`() {
        val props = WorkerInternalAuthProperties(jwtKeys = mapOf("k1" to "jwt-secret-1"))
        val filter = WorkerInternalAuthFilter(props, objectMapper)

        val request = MockHttpServletRequest("POST", "/internal/jobs/finalize-employee/dlq/replay")
        val response = MockHttpServletResponse()

        val called = AtomicBoolean(false)
        val chain = FilterChain { _, _ -> called.set(true) }

        filter.doFilter(request, response, chain)

        assertEquals(401, response.status)
        assertEquals(false, called.get())
    }
}
