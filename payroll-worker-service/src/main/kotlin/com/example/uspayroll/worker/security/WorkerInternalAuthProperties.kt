package com.example.uspayroll.worker.security

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "worker.internal-auth")
data class WorkerInternalAuthProperties(
    /** Shared secret required on internal operational endpoints (DLQ replay etc.). */
    var sharedSecret: String = "",
    /** Header name that carries the shared secret. */
    var headerName: String = "X-Internal-Token",
)
