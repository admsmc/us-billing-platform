package com.example.uspayroll.payments.events

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "payments.events")
data class PaymentsEventsProperties(
    @field:NotBlank
    var paymentStatusChangedTopic: String = "paycheck.payment.status_changed",
    @field:NotBlank
    var paymentBatchStatusChangedTopic: String = "payment.batch.status_changed",
    @field:NotBlank
    var paymentBatchTerminalTopic: String = "payment.batch.terminal",
)

@Configuration
@EnableConfigurationProperties(PaymentsEventsProperties::class)
class PaymentsEventsConfig
