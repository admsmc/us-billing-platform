package com.example.uspayroll.orchestrator.payments

import com.example.uspayroll.orchestrator.client.PaymentsQueryClient
import org.mockito.Mockito
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

@TestConfiguration
class PaymentsQueryClientTestConfig {
    @Bean
    @Primary
    fun mockPaymentsQueryClient(): PaymentsQueryClient = Mockito.mock(PaymentsQueryClient::class.java)
}
