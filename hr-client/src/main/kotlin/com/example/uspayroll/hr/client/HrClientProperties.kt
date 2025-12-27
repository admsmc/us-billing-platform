package com.example.uspayroll.hr.client

import com.example.uspayroll.web.client.DownstreamHttpClientProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "downstreams.hr")
class HrClientProperties : DownstreamHttpClientProperties() {
    init {
        // Default to the local dev HR service, but prefer the DOWNSTREAMS_HR_BASE_URL
        // environment variable when present (e.g., Docker compose).
        val envBaseUrl = System.getenv("DOWNSTREAMS_HR_BASE_URL")
        baseUrl = envBaseUrl?.takeIf { it.isNotBlank() } ?: "http://localhost:8081"
    }
}
