package com.example.uspayroll.web

import org.springframework.http.HttpStatus

/**
 * Exception for service-level/domain-level failures that should be rendered as a ProblemDetail.
 *
 * - [transportCode] is a stable, cross-service taxonomy (see [WebErrorCode]).
 * - [domainErrorCode] is a stable, service-scoped code like `hr.employee_not_found`.
 */
class DomainProblemException(
    val status: HttpStatus,
    val transportCode: WebErrorCode,
    val domainErrorCode: String,
    message: String,
    cause: Throwable? = null,
    val title: String? = null,
) : RuntimeException(message, cause)
