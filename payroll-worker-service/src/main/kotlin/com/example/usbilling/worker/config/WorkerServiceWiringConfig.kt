package com.example.usbilling.worker.config

import com.example.usbilling.labor.api.LaborStandardsContextProvider
import com.example.usbilling.shared.EmployerId
import com.example.usbilling.tax.api.TaxContextProvider
import com.example.usbilling.tax.service.DefaultFederalWithholdingCalculator
import com.example.usbilling.tax.service.FederalWithholdingCalculator
import com.example.usbilling.worker.client.LaborStandardsClient
import com.example.usbilling.worker.client.TaxClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.LocalDate

@Configuration
@EnableConfigurationProperties(WorkerPayrollProperties::class)
class WorkerServiceWiringConfig {

    @Bean
    fun taxContextProvider(taxClient: TaxClient): TaxContextProvider = object : TaxContextProvider {
        override fun getTaxContext(employerId: EmployerId, asOfDate: LocalDate) = taxClient.getTaxContext(
            employerId = employerId,
            asOfDate = asOfDate,
            localityCodes = emptyList(),
        )
    }

    @Bean
    fun laborStandardsContextProvider(laborClient: LaborStandardsClient): LaborStandardsContextProvider = object : LaborStandardsContextProvider {
        override fun getLaborStandards(employerId: EmployerId, asOfDate: LocalDate, workState: String?, homeState: String?, localityCodes: List<String>) = laborClient.getLaborStandards(
            employerId = employerId,
            asOfDate = asOfDate,
            workState = workState,
            homeState = homeState,
            localityCodes = localityCodes,
        )
    }

    @Bean
    fun federalWithholdingCalculator(@Value("\${tax.federalWithholding.method:PERCENTAGE}") method: String): FederalWithholdingCalculator = DefaultFederalWithholdingCalculator(
        method = method,
    )
}
