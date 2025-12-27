package com.example.usbilling.worker.support

import com.example.usbilling.payroll.model.TaxContext
import com.example.usbilling.shared.EmployerId
import com.example.usbilling.worker.client.LaborStandardsClient
import com.example.usbilling.worker.client.TaxClient
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.LocalDate

@TestConfiguration
class StubTaxLaborClientsTestConfig {

    @Bean
    @Primary
    fun stubTaxClient(): TaxClient = object : TaxClient {
        override fun getTaxContext(employerId: EmployerId, asOfDate: LocalDate, residentState: String?, workState: String?, localityCodes: List<String>): TaxContext = TaxContext()
    }

    @Bean
    @Primary
    fun stubLaborStandardsClient(): LaborStandardsClient = object : LaborStandardsClient {
        override fun getLaborStandards(employerId: EmployerId, asOfDate: LocalDate, workState: String?, homeState: String?, localityCodes: List<String>) = null
    }
}
