package com.example.uspayroll.orchestrator.support

import com.example.uspayroll.orchestrator.client.HrClient
import com.example.uspayroll.orchestrator.client.LaborStandardsClient
import com.example.uspayroll.orchestrator.client.TaxClient
import com.example.uspayroll.payroll.model.BaseCompensation
import com.example.uspayroll.payroll.model.EmployeeSnapshot
import com.example.uspayroll.payroll.model.FilingStatus
import com.example.uspayroll.payroll.model.LocalDateRange
import com.example.uspayroll.payroll.model.PayFrequency
import com.example.uspayroll.payroll.model.PayPeriod
import com.example.uspayroll.payroll.model.TaxContext
import com.example.uspayroll.payroll.model.garnishment.GarnishmentOrder
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.LocalDate

@TestConfiguration
class StubClientsTestConfig {

    @Bean
    @Primary
    fun stubHrClient(): HrClient = object : HrClient {
        override fun getEmployeeSnapshot(employerId: EmployerId, employeeId: EmployeeId, asOfDate: LocalDate): EmployeeSnapshot? {
            if (employeeId.value == "e-bad") return null

            return EmployeeSnapshot(
                employerId = employerId,
                employeeId = employeeId,
                homeState = "CA",
                workState = "CA",
                filingStatus = FilingStatus.SINGLE,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = Money(120_000_00L),
                    frequency = PayFrequency.BIWEEKLY,
                ),
                workCity = "Detroit",
            )
        }

        override fun getPayPeriod(employerId: EmployerId, payPeriodId: String): PayPeriod? {
            if (payPeriodId != "pp-1") return null

            return PayPeriod(
                id = payPeriodId,
                employerId = employerId,
                dateRange = LocalDateRange(
                    startInclusive = LocalDate.parse("2025-01-01"),
                    endInclusive = LocalDate.parse("2025-01-14"),
                ),
                checkDate = LocalDate.parse("2025-01-17"),
                frequency = PayFrequency.BIWEEKLY,
            )
        }

        override fun getGarnishmentOrders(employerId: EmployerId, employeeId: EmployeeId, asOfDate: LocalDate): List<GarnishmentOrder> = emptyList()
    }

    @Bean
    @Primary
    fun stubTaxClient(): TaxClient = object : TaxClient {
        override fun getTaxContext(employerId: EmployerId, asOfDate: LocalDate, localityCodes: List<String>): TaxContext = TaxContext()
    }

    @Bean
    @Primary
    fun stubLaborClient(): LaborStandardsClient = object : LaborStandardsClient {
        override fun getLaborStandards(employerId: EmployerId, asOfDate: LocalDate, workState: String?, homeState: String?, localityCodes: List<String>) = null
    }
}
