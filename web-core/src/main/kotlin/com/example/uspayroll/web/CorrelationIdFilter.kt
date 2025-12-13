package com.example.uspayroll.web

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.web.filter.OncePerRequestFilter
import java.io.IOException
import java.util.UUID

@Deprecated("Prefer WebHeaders/WebMdcKeys; kept for compatibility")
object CorrelationIds {
    const val HEADER = WebHeaders.CORRELATION_ID
    const val MDC_KEY = WebMdcKeys.CORRELATION_ID
}

/**
 * Shared correlation ID filter.
 *
 * - Reads [CorrelationIds.HEADER] from the incoming request (or generates one).
 * - Stores it in MDC under [CorrelationIds.MDC_KEY].
 * - Echoes it back in the response header.
 */
open class CorrelationIdFilter : OncePerRequestFilter() {

    @Throws(ServletException::class, IOException::class)
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val existing = request.getHeader(WebHeaders.CORRELATION_ID)
        val correlationId = existing?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()

        MDC.put(WebMdcKeys.CORRELATION_ID, correlationId)
        response.setHeader(WebHeaders.CORRELATION_ID, correlationId)

        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(WebMdcKeys.CORRELATION_ID)
        }
    }
}
