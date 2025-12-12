package com.example.uspayroll.orchestrator.security

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "orchestrator.internal-auth")
data class InternalAuthProperties(
    /**
     * Shared secret required on internal endpoints.
     *
     * This is intentionally simple for dev; upgrade later to mTLS/JWT.
     */
    var sharedSecret: String = "dev-internal-token",

    /** Header name that carries the shared secret. */
    var headerName: String = "X-Internal-Token",
)
