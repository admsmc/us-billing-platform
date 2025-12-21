package com.example.uspayroll.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Logs one line per request with correlation + identity from MDC.
 *
 * Services should register this as a @Component wrapper in their own package,
 * so it's included by Spring's component scanning.
 */
open class RequestLoggingFilter : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(RequestLoggingFilter::class.java)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI ?: ""
        // Keep noise low; most services expose these.
        return path.startsWith("/actuator")
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val method = request.method
        val path = request.requestURI ?: ""

        val startNanos = System.nanoTime()
        try {
            filterChain.doFilter(request, response)
        } finally {
            val correlationId = MDC.get(WebMdcKeys.CORRELATION_ID)
            val employerId = MDC.get(WebMdcKeys.EMPLOYER_ID)
            val principalSub = MDC.get(WebMdcKeys.PRINCIPAL_SUB)

            val status = response.status
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

            if (status >= 500) {
                log.warn(
                    "http_request method={} path={} status={} elapsedMs={} correlationId={} employerId={} principalSub={}",
                    method,
                    path,
                    status,
                    elapsedMs,
                    correlationId,
                    employerId,
                    principalSub,
                )
            } else {
                log.info(
                    "http_request method={} path={} status={} elapsedMs={} correlationId={} employerId={} principalSub={}",
                    method,
                    path,
                    status,
                    elapsedMs,
                    correlationId,
                    employerId,
                    principalSub,
                )
            }
        }
    }
}
