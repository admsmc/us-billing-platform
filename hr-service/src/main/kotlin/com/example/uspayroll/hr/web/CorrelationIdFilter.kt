package com.example.uspayroll.hr.web

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.IOException
import java.util.UUID

/**
 * Simple correlation ID filter for hr-service.
 */
@Component
class CorrelationIdFilter : OncePerRequestFilter() {

    @Throws(ServletException::class, IOException::class)
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val existing = request.getHeader("X-Correlation-ID")
        val correlationId = existing?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()

        MDC.put("correlationId", correlationId)
        response.setHeader("X-Correlation-ID", correlationId)

        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove("correlationId")
        }
    }
}
