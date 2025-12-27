package com.example.usbilling.orchestrator.diagnostics

import com.example.usbilling.orchestrator.security.InternalAuthProperties
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class InternalAuthDiagnostics(
    private val props: InternalAuthProperties,
) {

    private val logger = LoggerFactory.getLogger(InternalAuthDiagnostics::class.java)

    @PostConstruct
    fun log() {
        val configured = props.jwtSharedSecret.isNotBlank() || props.jwtKeys.isNotEmpty()
        val keyCount = (if (props.jwtSharedSecret.isNotBlank()) 1 else 0) + props.jwtKeys.size

        logger.info(
            "diag.internal_auth configured={} keyCount={} issuer={} audience={} defaultKidPresent={}",
            configured,
            keyCount,
            props.jwtIssuer,
            props.jwtAudience,
            props.jwtDefaultKid.isNotBlank(),
        )
    }
}
