package com.example.uspayroll.web

/**
 * Stable, machine-readable error codes for ProblemDetail responses.
 *
 * These codes are intended for clients to branch on without parsing titles/details.
 */
enum class WebErrorCode(val code: String) {
    BAD_REQUEST("bad_request"),
    VALIDATION_FAILED("validation_failed"),
    NOT_FOUND("not_found"),
    UNAUTHORIZED("unauthorized"),
    FORBIDDEN("forbidden"),
    CONFLICT("conflict"),
    RATE_LIMITED("rate_limited"),
    SERVICE_UNAVAILABLE("service_unavailable"),
    INTERNAL_ERROR("internal_error"),
    HTTP_ERROR("http_error"),

    // Useful when we cannot classify but still want a code.
    UNKNOWN("unknown"),
}
