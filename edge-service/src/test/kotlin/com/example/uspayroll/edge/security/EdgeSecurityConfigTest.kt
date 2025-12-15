package com.example.uspayroll.edge.security

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EdgeSecurityConfigTest {

    @Test
    fun `hs256 mode requires secret`() {
        val cfg = EdgeSecurityConfig()
        val props = EdgeAuthProperties(
            mode = "HS256",
            allowDisabled = false,
            hs256Secret = "",
        )

        assertThrows(IllegalArgumentException::class.java) {
            cfg.jwtDecoder(props)
        }
    }

    @Test
    fun `oidc mode requires issuer or jwk set uri`() {
        val cfg = EdgeSecurityConfig()
        val props = EdgeAuthProperties(
            mode = "OIDC",
            allowDisabled = false,
            issuerUri = "",
            jwkSetUri = "",
        )

        assertThrows(IllegalStateException::class.java) {
            cfg.jwtDecoder(props)
        }
    }

    @Test
    fun `oidc mode builds decoder from jwk set uri`() {
        val cfg = EdgeSecurityConfig()
        val props = EdgeAuthProperties(
            mode = "OIDC",
            allowDisabled = false,
            jwkSetUri = "https://example.invalid/.well-known/jwks.json",
        )

        val decoder = cfg.jwtDecoder(props)
        assertNotNull(decoder)
    }
}
