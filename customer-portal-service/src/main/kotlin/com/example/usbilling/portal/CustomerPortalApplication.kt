package com.example.usbilling.portal

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.reactive.function.client.WebClient

/**
 * Customer Portal Service - Customer-facing self-service REST API.
 * 
 * Provides customer portal endpoints for:
 * - Viewing bills and payment history
 * - Making payments
 * - Managing account information
 * - Submitting cases and service requests
 * - Managing notification preferences
 * - Viewing usage data and insights
 * 
 * Port: 8090
 */
@SpringBootApplication(scanBasePackages = ["com.example.usbilling.portal", "com.example.usbilling.web"])
class CustomerPortalApplication {
    
    @Bean
    fun webClientBuilder(): WebClient.Builder {
        return WebClient.builder()
    }
}

fun main(args: Array<String>) {
    runApplication<CustomerPortalApplication>(*args)
}
