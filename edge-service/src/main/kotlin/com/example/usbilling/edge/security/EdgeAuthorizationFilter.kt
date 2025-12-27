package com.example.usbilling.edge.security

import com.example.usbilling.web.WebErrorCode
import com.example.usbilling.web.WebHeaders
import com.example.usbilling.web.security.SecurityAuditLogger
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.net.URI

/**
 * Enforces enterprise-grade authorization at ingress.
 *
 * Responsibilities:
 * - Require appropriate scopes for reads/writes/admin.
 * - Enforce employer scoping: JWT claim employer_id/employer_ids must match /employers/{employerId}/... unless platform_admin.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class EdgeAuthorizationFilter(
    private val objectMapper: ObjectMapper,
) : GlobalFilter {

    private val policy: EdgeAuthorizationPolicy = EdgeAuthorizationPolicy.default()

    private val employerPathRegex = Regex("^/employers/([^/]+)(/.*)?$")

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val path = exchange.request.uri.path
        if (path.startsWith("/actuator")) {
            return chain.filter(exchange)
        }

        val method = exchange.request.method?.name()?.uppercase() ?: ""
        val requiredScope = policy.requiredScope(path, method)
            ?: requiredScopeFallback(path, method)

        return exchange.getPrincipal<Authentication>()
            .flatMap { auth ->
                val jwt = auth.principal as? Jwt
                    ?: run {
                        SecurityAuditLogger.authenticationFailed(
                            component = "edge",
                            method = method,
                            path = path,
                            status = HttpStatus.UNAUTHORIZED.value(),
                            reason = "missing_jwt_principal",
                            correlationId = exchange.request.headers.getFirst(WebHeaders.CORRELATION_ID),
                        )
                        return@flatMap deny(exchange, HttpStatus.UNAUTHORIZED, "unauthorized").thenReturn(Unit)
                    }

                val scopes = extractScopes(jwt)
                if (!isScopeSatisfied(scopes, requiredScope)) {
                    SecurityAuditLogger.authorizationDenied(
                        component = "edge",
                        method = method,
                        path = path,
                        status = HttpStatus.FORBIDDEN.value(),
                        reason = "insufficient_scope",
                        principalSub = jwt.subject,
                        correlationId = exchange.request.headers.getFirst(WebHeaders.CORRELATION_ID),
                        requiredScope = requiredScope,
                    )
                    return@flatMap deny(exchange, HttpStatus.FORBIDDEN, "insufficient_scope").thenReturn(Unit)
                }

                val employerIdFromPath = employerIdFromPath(path)
                if (employerIdFromPath != null) {
                    val decision = employerDecision(jwt, employerIdFromPath)
                    if (!decision.allowed) {
                        SecurityAuditLogger.authorizationDenied(
                            component = "edge",
                            method = method,
                            path = path,
                            status = HttpStatus.FORBIDDEN.value(),
                            reason = "employer_scope_mismatch",
                            principalSub = jwt.subject,
                            employerId = employerIdFromPath,
                            correlationId = exchange.request.headers.getFirst(WebHeaders.CORRELATION_ID),
                            requiredScope = requiredScope,
                        )
                        return@flatMap deny(exchange, HttpStatus.FORBIDDEN, "employer_scope_mismatch").thenReturn(Unit)
                    }
                    if (decision.breakGlassUsed) {
                        SecurityAuditLogger.breakGlassAccessGranted(
                            component = "edge",
                            method = method,
                            path = path,
                            principalSub = jwt.subject,
                            correlationId = exchange.request.headers.getFirst(WebHeaders.CORRELATION_ID),
                        )
                    }
                }

                chain.filter(exchange).thenReturn(Unit)
            }
            .switchIfEmpty(
                Mono.defer {
                    SecurityAuditLogger.authenticationFailed(
                        component = "edge",
                        method = method,
                        path = path,
                        status = HttpStatus.UNAUTHORIZED.value(),
                        reason = "missing_principal",
                        correlationId = exchange.request.headers.getFirst(WebHeaders.CORRELATION_ID),
                    )
                    deny(exchange, HttpStatus.UNAUTHORIZED, "unauthorized").thenReturn(Unit)
                },
            )
            .then()
    }

    private fun requiredScopeFallback(path: String, method: String): String {
        // Fallback heuristic for new/unknown endpoints.
        if (path.startsWith("/internal/")) return "payroll:admin"
        return when (method) {
            "GET", "HEAD", "OPTIONS" -> "payroll:read"
            else -> "payroll:write"
        }
    }

    private fun isScopeSatisfied(scopes: Set<String>, required: String): Boolean {
        // Admin is an umbrella permission.
        return required in scopes || "payroll:admin" in scopes
    }

    private fun employerIdFromPath(path: String): String? {
        val m = employerPathRegex.matchEntire(path) ?: return null
        return m.groupValues[1].trim().takeIf { it.isNotBlank() }
    }

    private data class EmployerDecision(val allowed: Boolean, val breakGlassUsed: Boolean)

    private fun employerDecision(jwt: Jwt, employerId: String): EmployerDecision {
        val allowed = mutableSetOf<String>()
        jwt.getClaimAsString("employer_id")?.takeIf { it.isNotBlank() }?.let { allowed += it }

        val employerIds = jwt.getClaim<Any>("employer_ids")
        when (employerIds) {
            is Collection<*> -> employerIds.filterIsInstance<String>().map { it.trim() }.filter { it.isNotBlank() }.forEach { allowed += it }
        }

        val isAllowed = employerId in allowed
        val isPlatformAdmin = jwt.getClaimAsBoolean("platform_admin") == true

        return when {
            isAllowed -> EmployerDecision(allowed = true, breakGlassUsed = false)
            isPlatformAdmin -> EmployerDecision(allowed = true, breakGlassUsed = true)
            else -> EmployerDecision(allowed = false, breakGlassUsed = false)
        }
    }

    private fun extractScopes(jwt: Jwt): Set<String> {
        val out = mutableSetOf<String>()

        jwt.getClaimAsString("scope")
            ?.split(" ")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.forEach { out += it }

        val scp = jwt.getClaim<Any>("scp")
        when (scp) {
            is Collection<*> -> scp.filterIsInstance<String>().map { it.trim() }.filter { it.isNotBlank() }.forEach { out += it }
        }

        return out
    }

    private fun deny(exchange: ServerWebExchange, status: HttpStatus, code: String): Mono<Void> {
        val instance = try {
            URI.create(exchange.request.uri.path)
        } catch (_: IllegalArgumentException) {
            null
        }

        val pd = ProblemDetail.forStatusAndDetail(status, code)
        pd.title = status.reasonPhrase
        if (instance != null) pd.instance = instance

        val errorCode = when (status) {
            HttpStatus.UNAUTHORIZED -> WebErrorCode.UNAUTHORIZED
            HttpStatus.FORBIDDEN -> WebErrorCode.FORBIDDEN
            else -> WebErrorCode.HTTP_ERROR
        }
        pd.setProperty("errorCode", errorCode.code)
        pd.setProperty("authError", code)

        // Best-effort: include correlationId if edge already set it.
        exchange.request.headers.getFirst(WebHeaders.CORRELATION_ID)?.let { pd.setProperty("correlationId", it) }

        val bytes = objectMapper.writeValueAsBytes(pd)
        exchange.response.headers.contentType = MediaType.APPLICATION_PROBLEM_JSON
        exchange.response.statusCode = status
        return exchange.response.writeWith(Mono.just(exchange.response.bufferFactory().wrap(bytes)))
    }
}
