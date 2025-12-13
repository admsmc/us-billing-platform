package com.example.uspayroll.worker.config

import com.example.uspayroll.payroll.model.audit.TraceLevel
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "worker.payroll")
data class WorkerPayrollProperties(
    var traceLevel: TraceLevel = TraceLevel.AUDIT,
)
