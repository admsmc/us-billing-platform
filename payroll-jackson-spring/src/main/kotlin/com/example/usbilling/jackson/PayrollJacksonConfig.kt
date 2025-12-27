package com.example.usbilling.jackson

import com.example.usbilling.payroll.jackson.PayrollDomainKeyJacksonModule
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class PayrollJacksonConfig {

    @Bean
    @ConditionalOnMissingBean(Jackson2ObjectMapperBuilderCustomizer::class)
    fun payrollJacksonCustomizer(): Jackson2ObjectMapperBuilderCustomizer = Jackson2ObjectMapperBuilderCustomizer { builder ->
        builder.modulesToInstall(
            KotlinModule.Builder().build(),
            JavaTimeModule(),
            PayrollDomainKeyJacksonModule.module(),
        )
        builder.featuresToEnable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
    }
}
