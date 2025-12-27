package com.example.usbilling.orchestrator.security

import com.example.usbilling.web.ProblemDetails
import com.example.usbilling.web.WebHeaders
import com.example.usbilling.web.security.InternalJwtHs256
import com.example.usbilling.web.security.SecurityAuditLogger
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
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
        val bearer = request.getHeader(HttpHeaders.AUTHORIZATION)
            ?.trim()
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val jwtKeyring: Map<String, String> = when {
            props.jwtKeys.isNotEmpty() -> props.jwtKeys
            props.jwtSharedSecret.isNotBlank() -> mapOf("legacy" to props.jwtSharedSecret)
            else -> emptyMap()
        }

        if (bearer == null || jwtKeyring.isEmpty()) {
            SecurityAuditLogger.internalAuthFailed(
                component = "payroll-orchestrator-service",
                method = request.method,
                path = request.requestURI ?: "",
                status = HttpStatus.UNAUTHORIZED.value(),
                reason = if (bearer == null) "missing_bearer_jwt" else "internal_jwt_not_configured",
                mechanism = "bearer_jwt",
                correlationId = SecurityAuditLogger.correlationIdFromServletHeader(request::getHeader),
            )
            deny(request, response)
            return
        }

        val ok = try {
            InternalJwtHs256.verifyWithKeyring(
                token = bearer,
                secretsByKid = jwtKeyring,
                defaultKid = props.jwtDefaultKid.takeIf { it.isNotBlank() },
                expectedIssuer = props.jwtIssuer,
                expectedAudience = props.jwtAudience,
            )
            true
        } catch (ex: IllegalArgumentException) {
            SecurityAuditLogger.internalAuthFailed(
                component = "payroll-orchestrator-service",
                method = request.method,
                path = request.requestURI ?: "",
                status = HttpStatus.UNAUTHORIZED.value(),
                reason = "invalid_bearer_jwt",
                mechanism = "bearer_jwt",
                correlationId = SecurityAuditLogger.correlationIdFromServletHeader(request::getHeader),
            )
            logger.warn("Invalid internal JWT bearer token", ex)
            false
        }

        if (ok) {
            filterChain.doFilter(request, response)
            return
        }

        deny(request, response)
    }

    private fun deny(request: HttpServletRequest, response: HttpServletResponse) {
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
