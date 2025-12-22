package com.example.uspayroll.orchestrator.payments

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "orchestrator.payments")
data class OrchestratorPaymentsProperties(
    var enabled: Boolean = false,
    @field:NotBlank
    var consumerName: String = "payroll-orchestrator-service",
    @field:NotBlank
    var groupId: String = "payroll-orchestrator-payments",
    @field:NotBlank
    var paymentRequestedTopic: String = "paycheck.payment.requested",
    @field:NotBlank
    var paymentStatusChangedTopic: String = "paycheck.payment.status_changed",
)

@Configuration
@EnableConfigurationProperties(OrchestratorPaymentsProperties::class)
class OrchestratorPaymentsConfig
