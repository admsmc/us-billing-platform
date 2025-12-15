package com.example.uspayroll.tenancy.web

import com.example.uspayroll.tenancy.TenantContext
import com.example.uspayroll.web.WebHeaders
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping

/**
 * Establishes the tenant (employer) for the request thread.
 *
 * Source of truth is the path variable {employerId}. If the edge propagated
 * X-Employer-Id header is present, it must match the path value.
 */
class EmployerTenantWebMvcInterceptor : HandlerInterceptor {

    private val logger = LoggerFactory.getLogger(EmployerTenantWebMvcInterceptor::class.java)

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        @Suppress("UNCHECKED_CAST")
        val uriVars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<String, String>
        val employerId = uriVars?.get("employerId")?.trim().orEmpty()

        if (employerId.isBlank()) {
            // Not all endpoints are employer-scoped (e.g. health checks).
            return true
        }

        val headerEmployer = request.getHeader(WebHeaders.EMPLOYER_ID)?.trim().orEmpty()
        if (headerEmployer.isNotBlank() && headerEmployer != employerId) {
            logger.warn(
                "tenant.mismatch path_employer_id={} header_employer_id={} uri={}",
                employerId,
                headerEmployer,
                request.requestURI,
            )
            response.status = 400
            response.contentType = "application/json"
            response.writer.write("{\"error\":\"employer_id_mismatch\"}")
            return false
        }

        TenantContext.set(employerId)
        return true
    }

    override fun afterCompletion(request: HttpServletRequest, response: HttpServletResponse, handler: Any, ex: Exception?) {
        TenantContext.clear()
    }
}
