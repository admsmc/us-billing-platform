package com.example.uspayroll.orchestrator.payments

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@ConfigurationProperties(prefix = "orchestrator.payments")
data class OrchestratorPaymentsProperties(
    var enabled: Boolean = false,
    var consumerName: String = "payroll-orchestrator-service",
    var groupId: String = "payroll-orchestrator-payments",
    var paymentRequestedTopic: String = "paycheck.payment.requested",
    var paymentStatusChangedTopic: String = "paycheck.payment.status_changed",
)

@Configuration
@EnableConfigurationProperties(OrchestratorPaymentsProperties::class)
class OrchestratorPaymentsConfig
