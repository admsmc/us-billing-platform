package com.example.uspayroll.hr.client

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "hr")
data class HrClientProperties(
    var baseUrl: String = "http://localhost:8081",
    var connectTimeout: Duration = Duration.ofSeconds(2),
    var readTimeout: Duration = Duration.ofSeconds(5),
    /**
     * Optional number of retry attempts for transient failures (implementation-defined).
     *
     * Services that don't implement retry may ignore this.
     */
    var maxRetries: Int = 2,
)
