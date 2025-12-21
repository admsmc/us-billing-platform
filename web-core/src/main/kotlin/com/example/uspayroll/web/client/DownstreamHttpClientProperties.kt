package com.example.uspayroll.web.client

import java.time.Duration

/**
 * Shared configuration shape for outbound HTTP (RestTemplate) downstream clients.
 *
 * Intentionally defined as a simple mutable bean (no Spring Boot dependency) so individual
 * services can bind it via their own `@ConfigurationProperties(prefix = "downstreams.<name>")`.
 */
open class DownstreamHttpClientProperties {
    var baseUrl: String = "http://localhost:8081"

    var connectTimeout: Duration = Duration.ofSeconds(2)
    var readTimeout: Duration = Duration.ofSeconds(5)

    /** Number of retry attempts (maxRetries=2 -> up to 3 total attempts). */
    var maxRetries: Int = 2

    // Retry backoff (bounded)
    var retryInitialBackoff: Duration = Duration.ofMillis(100)
    var retryMaxBackoff: Duration = Duration.ofSeconds(1)
    var retryBackoffMultiplier: Double = 2.0

    // Circuit breaker (simple, in-memory)
    var circuitBreakerEnabled: Boolean = true
    var circuitBreaker: CircuitBreakerPolicy = CircuitBreakerPolicy()
}
