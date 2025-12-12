package com.example.uspayroll.payments.events

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@ConfigurationProperties(prefix = "payments.events")
data class PaymentsEventsProperties(
    var paymentStatusChangedTopic: String = "paycheck.payment.status_changed",
    var paymentBatchStatusChangedTopic: String = "payment.batch.status_changed",
    var paymentBatchTerminalTopic: String = "payment.batch.terminal",
)

@Configuration
@EnableConfigurationProperties(PaymentsEventsProperties::class)
class PaymentsEventsConfig
