package com.example.uspayroll.orchestrator.support

import com.example.uspayroll.web.security.InternalJwtHs256
import org.springframework.http.HttpHeaders

object InternalAuthTestSupport {

    private const val defaultIssuer = "us-payroll-platform"
    private const val defaultAudience = "payroll-orchestrator-service"

    // Keep in sync with payroll-orchestrator-service/src/test/resources/application.yml
    private const val defaultKid = "k1"
    private const val defaultSecret = "dev-internal-token"

    fun internalAuthHeaders(subject: String = "test", issuer: String = defaultIssuer, audience: String = defaultAudience, ttlSeconds: Long = 60): HttpHeaders {
        val token = InternalJwtHs256.issue(
            secret = defaultSecret,
            issuer = issuer,
            subject = subject,
            audience = audience,
            ttlSeconds = ttlSeconds,
            kid = defaultKid,
        )

        return HttpHeaders().apply { setBearerAuth(token) }
    }
}
