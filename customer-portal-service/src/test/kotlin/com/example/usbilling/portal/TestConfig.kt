package com.example.usbilling.portal

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.web.reactive.function.client.WebClient

/**
 * Test configuration for customer-portal-service.
 *
 * Provides a simple WebClient bean so that clients like [CaseManagementClient]
 * can be constructed when running slice/integration tests that load the full
 * application context, without needing a full downstream case-management
 * service.
 */
@TestConfiguration
class TestConfig {

    @Bean
    fun testWebClient(): WebClient = WebClient.builder().build()
}
