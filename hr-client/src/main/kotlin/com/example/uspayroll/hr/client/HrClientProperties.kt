package com.example.uspayroll.hr.client

import com.example.uspayroll.web.client.DownstreamHttpClientProperties
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "downstreams.hr")
class HrClientProperties : DownstreamHttpClientProperties() {
    init {
        baseUrl = "http://localhost:8081"
    }
}
