package com.example.uspayroll.web

/** Standard header names propagated by edge-service to downstream services. */
object WebHeaders {
    const val CORRELATION_ID = "X-Correlation-ID"

    // HTTP idempotency
    const val IDEMPOTENCY_KEY = "Idempotency-Key"

    // Identity context propagated by edge-service
    const val PRINCIPAL_SUB = "X-Principal-Sub"
    const val PRINCIPAL_SCOPE = "X-Principal-Scope"
    const val EMPLOYER_ID = "X-Employer-Id"
}

/** MDC keys used for logging correlation/identity across threads. */
object WebMdcKeys {
    const val CORRELATION_ID = "correlationId"
    const val PRINCIPAL_SUB = "principalSub"
    const val PRINCIPAL_SCOPE = "principalScope"
    const val EMPLOYER_ID = "employerId"
}
