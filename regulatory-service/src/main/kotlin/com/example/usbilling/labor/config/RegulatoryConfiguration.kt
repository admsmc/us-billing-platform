package com.example.usbilling.labor.config

import com.example.usbilling.billing.repository.InMemoryRegulatoryChargeRepository
import com.example.usbilling.billing.repository.RegulatoryChargeRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Configuration for regulatory service.
 * 
 * Provides the InMemoryRegulatoryChargeRepository as a Spring bean.
 * In production, this could be replaced with a database-backed implementation.
 */
@Configuration
class RegulatoryConfiguration {

    @Bean
    fun regulatoryChargeRepository(): RegulatoryChargeRepository {
        return InMemoryRegulatoryChargeRepository()
    }
}
