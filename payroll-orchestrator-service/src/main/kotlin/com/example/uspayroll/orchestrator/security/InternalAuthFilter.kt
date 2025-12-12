package com.example.uspayroll.orchestrator.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.web.filter.OncePerRequestFilter

@Configuration
@EnableConfigurationProperties(InternalAuthProperties::class)
class InternalAuthFilter(
    private val props: InternalAuthProperties,
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI ?: ""
        // Only guard the internal execution endpoints.
        return !path.contains("/payruns/internal/")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val expected = props.sharedSecret
        val headerValue = request.getHeader(props.headerName)

        if (expected.isNotBlank() && headerValue == expected) {
            filterChain.doFilter(request, response)
            return
        }

        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = "application/json"
        response.writer.write("{\"error\":\"unauthorized\"}")
    }
}
