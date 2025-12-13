package com.example.uspayroll.orchestrator.diagnostics

import com.example.uspayroll.orchestrator.security.InternalAuthProperties
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
        logger.info(
            "diag.internal_auth configured={} headerName={} secretLength={}",
            props.sharedSecret.isNotBlank(),
            props.headerName,
            props.sharedSecret.length,
        )
    }
}
