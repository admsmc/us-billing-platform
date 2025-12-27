package com.example.usbilling.worker.config

import com.example.usbilling.payroll.model.audit.TraceLevel
import jakarta.validation.constraints.NotNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "worker.payroll")
data class WorkerPayrollProperties(
    @field:NotNull
    var traceLevel: TraceLevel = TraceLevel.AUDIT,
)
