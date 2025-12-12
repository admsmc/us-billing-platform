package com.example.uspayroll.worker.support

import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.worker.client.LaborStandardsClient
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.LocalDate

@TestConfiguration
class StubLaborClientTestConfig {

    @Bean
    @Primary
    fun stubLaborStandardsClient(): LaborStandardsClient = object : LaborStandardsClient {
        override fun getLaborStandards(employerId: EmployerId, asOfDate: LocalDate, workState: String?, homeState: String?, localityCodes: List<String>) = null
    }
}
