package com.example.usbilling.web.security

import com.example.usbilling.web.WebHeaders
import com.example.usbilling.web.WebMdcKeys
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.slf4j.MDC

class SecurityAuditLoggerTest {

    @AfterEach
    fun tearDown() {
        MDC.clear()
    }

    @Test
    fun `correlationIdFromHeader returns first non-blank correlation id`() {
        val headers = mapOf(
            WebHeaders.CORRELATION_ID to listOf("c"),
        )

        assertEquals("c", SecurityAuditLogger.correlationIdFromHeader(headers))
    }

    @Test
    fun `correlationIdFromHeader returns null for blank correlation id`() {
        val headers = mapOf(
            WebHeaders.CORRELATION_ID to listOf("   "),
        )

        assertNull(SecurityAuditLogger.correlationIdFromHeader(headers))
    }

    @Test
    fun `correlationIdFromServletHeader returns correlation id when present`() {
        val getHeader: (String) -> String? = { name -> if (name == WebHeaders.CORRELATION_ID) "c" else null }
        assertEquals("c", SecurityAuditLogger.correlationIdFromServletHeader(getHeader))
    }

    @Test
    fun `correlationIdFromServletHeader returns null for blank correlation id`() {
        val getHeader: (String) -> String? = { name -> if (name == WebHeaders.CORRELATION_ID) " " else null }
        assertNull(SecurityAuditLogger.correlationIdFromServletHeader(getHeader))
    }

    @Test
    fun `audit methods do not throw and can fall back to MDC context`() {
        MDC.put(WebMdcKeys.CORRELATION_ID, "c")
        MDC.put(WebMdcKeys.EMPLOYER_ID, "EMP1")
        MDC.put(WebMdcKeys.PRINCIPAL_SUB, "sub")

        SecurityAuditLogger.authorizationDenied(
            component = "test",
            method = "POST",
            path = "/employers/EMP1/payruns/finalize",
            status = 403,
            reason = "insufficient_scope",
            requiredScope = "payroll:write",
        )

        SecurityAuditLogger.authenticationFailed(
            component = "test",
            method = "POST",
            path = "/employers/EMP1/payruns/finalize",
            status = 401,
            reason = "missing_principal",
        )

        SecurityAuditLogger.internalAuthFailed(
            component = "test",
            method = "POST",
            path = "/payruns/internal/123/execute",
            status = 401,
            reason = "invalid_jwt",
            mechanism = "bearer_jwt",
        )

        SecurityAuditLogger.breakGlassAccessGranted(
            component = "test",
            method = "GET",
            path = "/employers/EMP1/payruns",
        )

        SecurityAuditLogger.privilegedOperationGranted(
            component = "test",
            method = "POST",
            path = "/employers/EMP1/payruns/PR1/approve",
            operation = "payroll_approve",
            status = 200,
        )

        SecurityAuditLogger.privilegedOperationFailed(
            component = "test",
            method = "POST",
            path = "/employers/EMP1/payruns/PR1/approve",
            operation = "payroll_approve",
            status = 403,
            reason = "insufficient_scope",
        )
    }
}
