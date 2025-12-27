package com.example.usbilling.worker.support

import com.example.usbilling.shared.EmployerId
import com.example.usbilling.worker.client.LaborStandardsClient
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
