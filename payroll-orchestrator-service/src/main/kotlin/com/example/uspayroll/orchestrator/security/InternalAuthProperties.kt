package com.example.uspayroll.orchestrator.security

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "orchestrator.internal-auth")
data class InternalAuthProperties(
    /**
     * Shared secret required on internal endpoints.
     *
     * This is intentionally simple for dev; in production prefer internal JWT and/or mTLS.
     */
    // Blank by default so we don't commit a real-looking secret; set via env/secret manager.
    var sharedSecret: String = "",

    /** Header name that carries the shared secret. */
    var headerName: String = "X-Internal-Token",

    /**
     * Optional HS256 secret for internal JWT verification.
     *
     * When set, internal endpoints will accept:
     * - Authorization: Bearer <internal-jwt>
     */
    var jwtSharedSecret: String = "",

    /** Expected issuer for internal JWTs. */
    var jwtIssuer: String = "us-payroll-platform",

    /** Expected audience for internal JWTs. */
    var jwtAudience: String = "payroll-orchestrator-service",
)
