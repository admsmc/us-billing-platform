package com.example.usbilling.worker.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

/**
 * Configuration for WebClient used by HTTP clients.
 */
@Configuration
class WebClientConfiguration {

    @Bean
    fun webClientBuilder(): WebClient.Builder = WebClient.builder()
}
