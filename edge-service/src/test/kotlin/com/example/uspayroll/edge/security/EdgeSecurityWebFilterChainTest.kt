package com.example.uspayroll.edge.security

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.security.config.web.server.ServerHttpSecurity

class EdgeSecurityWebFilterChainTest {

    @Test
    fun `disabled mode is blocked unless allowDisabled is true`() {
        val cfg = EdgeSecurityConfig()

        val blocked = EdgeAuthProperties(mode = "DISABLED", allowDisabled = false)
        assertThrows(IllegalStateException::class.java) {
            cfg.securityWebFilterChain(ServerHttpSecurity.http(), blocked)
        }

        val allowed = EdgeAuthProperties(mode = "DISABLED", allowDisabled = true)
        val chain = cfg.securityWebFilterChain(ServerHttpSecurity.http(), allowed)
        assertNotNull(chain)
    }
}
