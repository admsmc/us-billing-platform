package com.example.uspayroll.web.security

import com.example.uspayroll.web.WebHeaders
import com.example.uspayroll.web.WebMdcKeys
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * Structured security audit logging.
 *
 * This is intentionally lightweight and emits one-line, PII-safe audit log entries.
 *
 * Goals:
 * - Provide an enforceable signal for authn/authz decisions.
 * - Include correlation + tenant + principal context where available.
 * - Never log secrets, JWTs, or request bodies.
 */
object SecurityAuditLogger {

    private val logger = LoggerFactory.getLogger("security_audit")

    fun authorizationDenied(
        component: String,
        method: String,
        path: String,
        status: Int,
        reason: String,
        principalSub: String? = null,
        employerId: String? = null,
        correlationId: String? = null,
        requiredScope: String? = null,
    ) {
        logger.warn(
            "security_audit event=authz_denied component={} method={} path={} status={} reason={} correlationId={} employerId={} principalSub={} requiredScope={}",
            component,
            method,
            path,
            status,
            reason,
            correlationId ?: MDC.get(WebMdcKeys.CORRELATION_ID),
            employerId ?: MDC.get(WebMdcKeys.EMPLOYER_ID),
            principalSub ?: MDC.get(WebMdcKeys.PRINCIPAL_SUB),
            requiredScope,
        )
    }

    fun authenticationFailed(component: String, method: String, path: String, status: Int, reason: String, correlationId: String? = null, employerId: String? = null) {
        logger.warn(
            "security_audit event=authn_failed component={} method={} path={} status={} reason={} correlationId={} employerId={}",
            component,
            method,
            path,
            status,
            reason,
            correlationId ?: MDC.get(WebMdcKeys.CORRELATION_ID),
            employerId ?: MDC.get(WebMdcKeys.EMPLOYER_ID),
        )
    }

    fun internalAuthFailed(component: String, method: String, path: String, status: Int, reason: String, mechanism: String, correlationId: String? = null) {
        logger.warn(
            "security_audit event=internal_auth_failed component={} method={} path={} status={} mechanism={} reason={} correlationId={}",
            component,
            method,
            path,
            status,
            mechanism,
            reason,
            correlationId ?: MDC.get(WebMdcKeys.CORRELATION_ID),
        )
    }

    fun breakGlassAccessGranted(component: String, method: String, path: String, principalSub: String? = null, correlationId: String? = null, reason: String = "platform_admin") {
        logger.warn(
            "security_audit event=break_glass_granted component={} method={} path={} reason={} correlationId={} principalSub={}",
            component,
            method,
            path,
            reason,
            correlationId ?: MDC.get(WebMdcKeys.CORRELATION_ID),
            principalSub ?: MDC.get(WebMdcKeys.PRINCIPAL_SUB),
        )
    }

    fun privilegedOperationGranted(
        component: String,
        method: String,
        path: String,
        operation: String,
        status: Int,
        principalSub: String? = null,
        employerId: String? = null,
        correlationId: String? = null,
    ) {
        logger.warn(
            "security_audit event=privileged_op outcome=granted component={} method={} path={} operation={} status={} correlationId={} employerId={} principalSub={}",
            component,
            method,
            path,
            operation,
            status,
            correlationId ?: MDC.get(WebMdcKeys.CORRELATION_ID),
            employerId ?: MDC.get(WebMdcKeys.EMPLOYER_ID),
            principalSub ?: MDC.get(WebMdcKeys.PRINCIPAL_SUB),
        )
    }

    fun privilegedOperationFailed(
        component: String,
        method: String,
        path: String,
        operation: String,
        status: Int,
        reason: String,
        principalSub: String? = null,
        employerId: String? = null,
        correlationId: String? = null,
    ) {
        logger.warn(
            "security_audit event=privileged_op outcome=failed component={} method={} path={} operation={} status={} reason={} correlationId={} employerId={} principalSub={}",
            component,
            method,
            path,
            operation,
            status,
            reason,
            correlationId ?: MDC.get(WebMdcKeys.CORRELATION_ID),
            employerId ?: MDC.get(WebMdcKeys.EMPLOYER_ID),
            principalSub ?: MDC.get(WebMdcKeys.PRINCIPAL_SUB),
        )
    }

    /**
     * Utility to extract correlation id from request headers in services that don't use MDC.
     */
    fun correlationIdFromHeader(headers: Map<String, List<String>>): String? = headers[WebHeaders.CORRELATION_ID]
        ?.firstOrNull()
        ?.takeIf { it.isNotBlank() }

    /**
     * Utility to extract correlation id from servlet request headers.
     */
    fun correlationIdFromServletHeader(getHeader: (String) -> String?): String? = getHeader(WebHeaders.CORRELATION_ID)
        ?.takeIf { it.isNotBlank() }
}
