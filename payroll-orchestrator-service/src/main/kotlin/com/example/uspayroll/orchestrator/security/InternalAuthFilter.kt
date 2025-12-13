package com.example.uspayroll.orchestrator.security

import com.example.uspayroll.web.ProblemDetails
import com.example.uspayroll.web.WebHeaders
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter
import java.net.URI

@Configuration
@EnableConfigurationProperties(InternalAuthProperties::class)
class InternalAuthFilter(
    private val props: InternalAuthProperties,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {

    private val logger = LoggerFactory.getLogger(InternalAuthFilter::class.java)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI ?: ""
        // Only guard the internal endpoints.
        return !(path.contains("/payruns/internal/") || path.contains("/paychecks/internal/"))
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val expected = props.sharedSecret
        val headerValue = request.getHeader(props.headerName)

        if (expected.isNotBlank() && headerValue == expected) {
            filterChain.doFilter(request, response)
            return
        }

        val instance = request.requestURI
            ?.let { raw ->
                try {
                    URI.create(raw)
                } catch (ex: IllegalArgumentException) {
                    logger.debug("Invalid requestURI for ProblemDetail.instance: {}", raw, ex)
                    null
                }
            }

        val problem = ProblemDetails.unauthorized(
            detail = "unauthorized",
            instance = instance,
        )

        val correlationId = problem.properties?.get("correlationId") as? String
        if (!correlationId.isNullOrBlank() && !response.containsHeader(WebHeaders.CORRELATION_ID)) {
            response.setHeader(WebHeaders.CORRELATION_ID, correlationId)
        }

        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.writer.write(objectMapper.writeValueAsString(problem))
    }
}
