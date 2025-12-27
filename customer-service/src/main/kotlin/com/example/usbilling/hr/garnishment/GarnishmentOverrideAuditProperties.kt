package com.example.usbilling.hr.garnishment

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "hr.audit.garnishment-overrides")
data class GarnishmentOverrideAuditProperties(
    /** Enable running the audit at application startup. */
    var enabled: Boolean = false,
    /** Exit the process with status code 0/1 after running the audit. */
    var exitAfterRun: Boolean = false,
    /** Enable the HTTP endpoint under /admin. */
    var httpEnabled: Boolean = false,
)
