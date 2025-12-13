package com.example.uspayroll.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Reads identity propagation headers (set by edge-service) and stores them in MDC.
 *
 * This is intentionally best-effort:
 * - missing headers are ignored
 * - blank header values are ignored
 */
open class IdentityMdcFilter : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        fun putIfPresent(header: String, mdcKey: String) {
            val value = request.getHeader(header)
            if (!value.isNullOrBlank()) {
                MDC.put(mdcKey, value)
            }
        }

        putIfPresent(WebHeaders.PRINCIPAL_SUB, WebMdcKeys.PRINCIPAL_SUB)
        putIfPresent(WebHeaders.PRINCIPAL_SCOPE, WebMdcKeys.PRINCIPAL_SCOPE)
        putIfPresent(WebHeaders.EMPLOYER_ID, WebMdcKeys.EMPLOYER_ID)

        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(WebMdcKeys.PRINCIPAL_SUB)
            MDC.remove(WebMdcKeys.PRINCIPAL_SCOPE)
            MDC.remove(WebMdcKeys.EMPLOYER_ID)
        }
    }
}
