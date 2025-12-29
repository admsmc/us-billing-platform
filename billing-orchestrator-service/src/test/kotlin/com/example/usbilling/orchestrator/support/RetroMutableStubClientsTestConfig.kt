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
import com.example.usbilling.payroll.model.Percent
import com.example.usbilling.payroll.model.TaxBasis
import com.example.usbilling.payroll.model.TaxContext
import com.example.usbilling.payroll.model.TaxJurisdiction
import com.example.usbilling.payroll.model.TaxJurisdictionType
import com.example.usbilling.payroll.model.TaxRule
import com.example.usbilling.payroll.model.garnishment.GarnishmentOrder
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.Money
import com.example.usbilling.shared.UtilityId
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@TestConfiguration
class RetroMutableStubClientsTestConfig {

    @Bean
    fun retroStubState(): RetroStubState = RetroStubState()

    class RetroStubState {
        val applyOverrides: AtomicBoolean = AtomicBoolean(false)
        val includeStateTaxes: AtomicBoolean = AtomicBoolean(true)

        val overrideWorkState: AtomicReference<String?> = AtomicReference(null)
        val overrideAnnualSalaryCents: AtomicReference<Long?> = AtomicReference(null)
        val overrideAdditionalWithholdingCents: AtomicReference<Long?> = AtomicReference(null)

        fun currentSnapshot(employerId: UtilityId, employeeId: CustomerId): EmployeeSnapshot {
            val baseAnnualSalaryCents = 130_000_00L

            val base = EmployeeSnapshot(
                employerId = employerId,
                employeeId = employeeId,
                homeState = "CA",
                workState = "CA",
                filingStatus = FilingStatus.SINGLE,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = Money(baseAnnualSalaryCents),
                    frequency = PayFrequency.BIWEEKLY,
                ),
                workCity = "Detroit",
            )

            if (!applyOverrides.get()) return base

            val workState = overrideWorkState.get() ?: base.workState
            val annualSalary = Money(overrideAnnualSalaryCents.get() ?: baseAnnualSalaryCents)
            val additionalWithholding = overrideAdditionalWithholdingCents.get()?.let { Money(it) }

            return base.copy(
                workState = workState,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = annualSalary,
                    frequency = PayFrequency.BIWEEKLY,
                ),
                additionalWithholdingPerPeriod = additionalWithholding,
            )
        }
    }

    @Bean
    @Primary
    fun stubHrClient(state: RetroStubState): HrClient = object : HrClient {
        override fun getEmployeeSnapshot(employerId: UtilityId, employeeId: CustomerId, asOfDate: LocalDate): EmployeeSnapshot? {
            if (employeeId.value == "e-bad") return null
            return state.currentSnapshot(employerId, employeeId)
        }

        override fun getPayPeriod(employerId: UtilityId, payPeriodId: String): PayPeriod? {
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

        override fun findPayPeriodByCheckDate(employerId: UtilityId, checkDate: LocalDate): PayPeriod? = null

        override fun getGarnishmentOrders(employerId: UtilityId, employeeId: CustomerId, asOfDate: LocalDate): List<GarnishmentOrder> = emptyList()

        override fun recordGarnishmentWithholding(employerId: UtilityId, employeeId: CustomerId, request: GarnishmentWithholdingRequest) = Unit
    }

    @Bean
    @Primary
    fun stubTaxClient(state: RetroStubState): TaxClient = object : TaxClient {
        override fun getTaxContext(employerId: UtilityId, asOfDate: LocalDate, residentState: String?, workState: String?, localityCodes: List<String>): TaxContext {
            val federal = listOf(
                TaxRule.FlatRateTax(
                    id = "US_FED_FLAT_10",
                    jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
                    basis = TaxBasis.Gross,
                    rate = Percent(0.10),
                ),
            )

            val selectedState = (workState ?: residentState)?.trim()?.uppercase()

            val stateRules = if (state.includeStateTaxes.get() && selectedState != null) {
                when (selectedState) {
                    "CA" -> listOf(
                        TaxRule.FlatRateTax(
                            id = "CA_STATE_FLAT_5",
                            jurisdiction = TaxJurisdiction(TaxJurisdictionType.STATE, "CA"),
                            basis = TaxBasis.Gross,
                            rate = Percent(0.05),
                        ),
                    )
                    "NY" -> listOf(
                        TaxRule.FlatRateTax(
                            id = "NY_STATE_FLAT_7",
                            jurisdiction = TaxJurisdiction(TaxJurisdictionType.STATE, "NY"),
                            basis = TaxBasis.Gross,
                            rate = Percent(0.07),
                        ),
                    )
                    else -> emptyList()
                }
            } else {
                emptyList()
            }

            return TaxContext(
                federal = federal,
                state = stateRules,
                local = emptyList(),
                employerSpecific = emptyList(),
            )
        }
    }

    @Bean
    @Primary
    fun stubLaborClient(): LaborStandardsClient = object : LaborStandardsClient {
        override fun getLaborStandards(employerId: UtilityId, asOfDate: LocalDate, workState: String?, homeState: String?, localityCodes: List<String>) = null
    }
}
