package com.example.usbilling.worker.security

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "worker.internal-auth")
data class WorkerInternalAuthProperties(
    /**
     * Optional HS256 secret for internal JWT verification (single key).
     *
     * Prefer using [jwtKeys] for key rotation.
     */
    var jwtSharedSecret: String = "",

    /**
     * HS256 verification keyring for internal JWTs (kid -> secret).
     */
    var jwtKeys: Map<String, String> = emptyMap(),

    /** Optional default kid to use when a token has no kid header. */
    var jwtDefaultKid: String = "",

    /** Expected issuer for internal JWTs. */
    @field:NotBlank
    var jwtIssuer: String = "us-payroll-platform",

    /** Expected audience for internal JWTs. */
    @field:NotBlank
    var jwtAudience: String = "payroll-worker-service",
)
