package com.example.usbilling.worker

import com.example.usbilling.shared.EmployerId
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@ConfigurationProperties(prefix = "worker.garnishments")
data class GarnishmentEngineProperties(
    /**
     * Optional allow-list of employer IDs that should use the new
     * GarnishmentsCalculator-based engine. When empty, the engine is enabled for
     * all employers.
     */
    var enabledEmployers: Set<String> = emptySet(),
) {
    fun isEnabledFor(employerId: EmployerId): Boolean = enabledEmployers.isEmpty() || enabledEmployers.contains(employerId.value)
}

@Configuration
@EnableConfigurationProperties(GarnishmentEngineProperties::class)
class GarnishmentEngineConfig
