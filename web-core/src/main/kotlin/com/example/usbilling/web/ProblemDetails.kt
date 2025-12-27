package com.example.usbilling.web

import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import java.net.URI

object ProblemDetails {

    fun badRequest(detail: String?, instance: URI? = null): ProblemDetail = build(
        status = HttpStatus.BAD_REQUEST,
        title = "Bad Request",
        detail = detail,
        errorCode = WebErrorCode.BAD_REQUEST,
        instance = instance,
    )

    fun notFound(detail: String?, instance: URI? = null): ProblemDetail = build(
        status = HttpStatus.NOT_FOUND,
        title = "Not Found",
        detail = detail,
        errorCode = WebErrorCode.NOT_FOUND,
        instance = instance,
    )

    fun unauthorized(detail: String?, instance: URI? = null): ProblemDetail = build(
        status = HttpStatus.UNAUTHORIZED,
        title = "Unauthorized",
        detail = detail,
        errorCode = WebErrorCode.UNAUTHORIZED,
        instance = instance,
    )

    fun internalError(detail: String? = null, instance: URI? = null): ProblemDetail = build(
        status = HttpStatus.INTERNAL_SERVER_ERROR,
        title = "Internal Server Error",
        detail = detail,
        errorCode = WebErrorCode.INTERNAL_ERROR,
        instance = instance,
    )

    fun build(status: HttpStatus, title: String, detail: String?, errorCode: WebErrorCode, instance: URI? = null, domainErrorCode: String? = null): ProblemDetail {
        val pd = ProblemDetail.forStatusAndDetail(status, detail ?: "")
        pd.title = title
        if (instance != null) {
            pd.instance = instance
        }

        pd.setProperty("errorCode", errorCode.code)
        if (!domainErrorCode.isNullOrBlank()) {
            pd.setProperty("domainErrorCode", domainErrorCode)
        }

        // Always include correlationId when available.
        mdcGet(WebMdcKeys.CORRELATION_ID)?.let { pd.setProperty("correlationId", it) }

        // Best-effort identity context (present when coming through edge-service).
        mdcGet(WebMdcKeys.EMPLOYER_ID)?.let { pd.setProperty("employerId", it) }
        mdcGet(WebMdcKeys.PRINCIPAL_SUB)?.let { pd.setProperty("principalSub", it) }

        return pd
    }

    private fun mdcGet(key: String): String? = MDC.get(key)?.takeIf { it.isNotBlank() }
}
