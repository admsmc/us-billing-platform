package com.example.uspayroll.worker.config

import com.example.uspayroll.labor.api.LaborStandardsContextProvider
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.tax.api.TaxContextProvider
import com.example.uspayroll.tax.service.DefaultFederalWithholdingCalculator
import com.example.uspayroll.tax.service.FederalWithholdingCalculator
import com.example.uspayroll.worker.client.LaborStandardsClient
import com.example.uspayroll.worker.client.TaxClient
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
