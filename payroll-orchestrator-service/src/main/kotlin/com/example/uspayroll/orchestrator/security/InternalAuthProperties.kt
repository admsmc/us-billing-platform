package com.example.uspayroll.orchestrator.security

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "orchestrator.internal-auth")
data class InternalAuthProperties(
    /**
     * Optional HS256 secret for internal JWT verification (single key).
     *
     * Prefer using [jwtKeys] for key rotation.
     */
    var jwtSharedSecret: String = "",

    /**
     * HS256 verification keyring for internal JWTs (kid -> secret).
     *
     * Use this to support key rotation: publish multiple keys, then rotate the issuer.
     */
    var jwtKeys: Map<String, String> = emptyMap(),

    /** Optional default kid to use when a token has no kid header. */
    var jwtDefaultKid: String = "",

    /** Expected issuer for internal JWTs. */
    var jwtIssuer: String = "us-payroll-platform",

    /** Expected audience for internal JWTs. */
    var jwtAudience: String = "payroll-orchestrator-service",
)
