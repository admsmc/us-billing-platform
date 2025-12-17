package com.example.uspayroll.orchestrator.config

import com.example.uspayroll.payroll.jackson.PayrollDomainKeyJacksonModule
import com.fasterxml.jackson.databind.Module
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class PayrollDomainJacksonConfig {

    @Bean
    fun payrollDomainKeyModule(): Module = PayrollDomainKeyJacksonModule.module()
}
