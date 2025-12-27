package com.example.usbilling.orchestrator.support

import com.example.usbilling.hr.client.HrClient
import com.example.usbilling.hr.http.GarnishmentWithholdingRequest
import com.example.usbilling.orchestrator.client.LaborStandardsClient
import com.example.usbilling.orchestrator.client.TaxClient
import com.example.usbilling.payroll.model.BaseCompensation
import com.example.usbilling.payroll.model.EmployeeSnapshot
import com.example.usbilling.payroll.model.FilingStatus
import com.example.usbilling.payroll.model.LocalDateRange
import com.example.usbilling.payroll.model.PayFrequency
import com.example.usbilling.payroll.model.PayPeriod
import com.example.usbilling.payroll.model.TaxContext
import com.example.usbilling.payroll.model.garnishment.GarnishmentOrder
import com.example.usbilling.shared.EmployeeId
import com.example.usbilling.shared.EmployerId
import com.example.usbilling.shared.Money
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

        override fun findPayPeriodByCheckDate(employerId: EmployerId, checkDate: LocalDate): PayPeriod? = null

        override fun getGarnishmentOrders(employerId: EmployerId, employeeId: EmployeeId, asOfDate: LocalDate): List<GarnishmentOrder> = emptyList()

        override fun recordGarnishmentWithholding(employerId: EmployerId, employeeId: EmployeeId, request: GarnishmentWithholdingRequest) = Unit
    }

    @Bean
    @Primary
    fun stubTaxClient(): TaxClient = object : TaxClient {
        override fun getTaxContext(employerId: EmployerId, asOfDate: LocalDate, residentState: String?, workState: String?, localityCodes: List<String>): TaxContext = TaxContext()
    }

    @Bean
    @Primary
    fun stubLaborClient(): LaborStandardsClient = object : LaborStandardsClient {
        override fun getLaborStandards(employerId: EmployerId, asOfDate: LocalDate, workState: String?, homeState: String?, localityCodes: List<String>) = null
    }
}
