package com.example.usbilling.filings.service

import com.example.usbilling.filings.persistence.PaycheckLedgerRepository
import com.example.usbilling.filings.persistence.PaycheckPaymentStatusRepository
import com.example.usbilling.messaging.events.payments.PaycheckPaymentLifecycleStatus
import com.example.usbilling.messaging.events.reporting.PaycheckLedgerTaxLine
import org.springframework.stereotype.Service

@Service
class FilingsComputationService(
    private val ledger: PaycheckLedgerRepository,
    private val paymentStatuses: PaycheckPaymentStatusRepository,
) {

    data class PaymentsReconciliation(
        val paycheckCount: Int,
        val missingPaymentStatusCount: Int,
        val settledPaycheckCount: Int,
        val failedPaycheckCount: Int,
        val expectedNetCents: Long,
        val settledNetCents: Long,
    )

    data class Form941Quarterly(
        val employerId: String,
        val year: Int,
        val quarter: Int,
        val dateRange: FilingPeriods.DateRange,

        val cashGrossCents: Long,
        val federalTaxableCents: Long,
        val socialSecurityWagesCents: Long,
        val medicareWagesCents: Long,
        val futaWagesCents: Long,

        val federalWithholdingCents: Long,
        val employeeSocialSecurityTaxCents: Long,
        val employerSocialSecurityTaxCents: Long,
        val employeeMedicareTaxCents: Long,
        val employerMedicareTaxCents: Long,

        val payments: PaymentsReconciliation,
        val warnings: List<String> = emptyList(),
    )

    data class Form940Annual(
        val employerId: String,
        val year: Int,
        val dateRange: FilingPeriods.DateRange,
        val futaWagesCents: Long,
        val futaTaxCents: Long,
        val warnings: List<String> = emptyList(),
    )

    data class W2EmployeeAnnual(
        val employerId: String,
        val year: Int,
        val employeeId: String,
        val wagesCents: Long,
        val federalWithholdingCents: Long,
        val socialSecurityWagesCents: Long,
        val socialSecurityTaxCents: Long,
        val medicareWagesCents: Long,
        val medicareTaxCents: Long,
        val stateWithholdingByState: Map<String, Long>,
        val warnings: List<String> = emptyList(),
    )

    data class W3Annual(
        val employerId: String,
        val year: Int,
        val employeeCount: Int,
        val totalWagesCents: Long,
        val totalFederalWithholdingCents: Long,
        val totalSocialSecurityWagesCents: Long,
        val totalSocialSecurityTaxCents: Long,
        val totalMedicareWagesCents: Long,
        val totalMedicareTaxCents: Long,
        val warnings: List<String> = emptyList(),
    )

    data class StateWithholdingQuarterly(
        val employerId: String,
        val year: Int,
        val quarter: Int,
        val dateRange: FilingPeriods.DateRange,
        val withheldByState: Map<String, Long>,
        val taxableWagesCents: Long,
        val warnings: List<String> = emptyList(),
    )

    data class FilingsValidation(
        val employerId: String,
        val fromInclusive: String,
        val toInclusive: String,
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
    )

    fun compute941(employerId: String, year: Int, quarter: Int): Form941Quarterly {
        val range = FilingPeriods.quarter(year, quarter)
        val events = ledger.listEventsByEmployerAndCheckDateRange(employerId, range.startInclusive, range.endInclusive)

        val warnings = mutableListOf<String>()

        val cashGross = events.sumOf { it.audit?.cashGrossCents ?: it.grossCents }
        val federalTaxable = events.sumOf { it.audit?.federalTaxableCents ?: 0L }
        val ssWages = events.sumOf { it.audit?.socialSecurityWagesCents ?: 0L }
        val medWages = events.sumOf { it.audit?.medicareWagesCents ?: 0L }
        val futaWages = events.sumOf { it.audit?.futaWagesCents ?: 0L }

        val employeeFederalWithholding = events.sumOf { evt ->
            evt.employeeTaxes.sumOf { line -> if (isFederalIncomeWithholding(line)) line.amountCents else 0L }
        }

        val employeeSs = events.sumOf { it.employeeTaxes.sumOf { line -> if (isSocialSecurity(line)) line.amountCents else 0L } }
        val employerSs = events.sumOf { it.employerTaxes.sumOf { line -> if (isSocialSecurity(line)) line.amountCents else 0L } }
        val employeeMed = events.sumOf { it.employeeTaxes.sumOf { line -> if (isMedicare(line)) line.amountCents else 0L } }
        val employerMed = events.sumOf { it.employerTaxes.sumOf { line -> if (isMedicare(line)) line.amountCents else 0L } }

        if (events.isNotEmpty()) {
            // Heuristic warning: if we have federal taxable wages but cannot identify federal income withholding lines.
            if (federalTaxable != 0L && employeeFederalWithholding == 0L) {
                warnings.add("federal withholding computed as 0; ensure federal income tax lines use jurisdictionCode='US'")
            }
        }

        val reconciliation = reconcilePaymentsForCheckDateRange(employerId, range)

        return Form941Quarterly(
            employerId = employerId,
            year = year,
            quarter = quarter,
            dateRange = range,
            cashGrossCents = cashGross,
            federalTaxableCents = federalTaxable,
            socialSecurityWagesCents = ssWages,
            medicareWagesCents = medWages,
            futaWagesCents = futaWages,
            federalWithholdingCents = employeeFederalWithholding,
            employeeSocialSecurityTaxCents = employeeSs,
            employerSocialSecurityTaxCents = employerSs,
            employeeMedicareTaxCents = employeeMed,
            employerMedicareTaxCents = employerMed,
            payments = reconciliation,
            warnings = warnings,
        )
    }

    fun compute940(employerId: String, year: Int): Form940Annual {
        val range = FilingPeriods.year(year)
        val events = ledger.listEventsByEmployerAndCheckDateRange(employerId, range.startInclusive, range.endInclusive)

        val futaWages = events.sumOf { it.audit?.futaWagesCents ?: 0L }
        val futaTax = events.sumOf { it.employerTaxes.sumOf { line -> if (isFuta(line)) line.amountCents else 0L } }

        val warnings = mutableListOf<String>()
        if (futaWages != 0L && futaTax == 0L) {
            warnings.add("futa tax computed as 0; ensure FUTA tax lines use jurisdictionCode='FUTA'")
        }

        return Form940Annual(
            employerId = employerId,
            year = year,
            dateRange = range,
            futaWagesCents = futaWages,
            futaTaxCents = futaTax,
            warnings = warnings,
        )
    }

    fun computeW2s(employerId: String, year: Int): List<W2EmployeeAnnual> {
        val range = FilingPeriods.year(year)
        val events = ledger.listEventsByEmployerAndCheckDateRange(employerId, range.startInclusive, range.endInclusive)

        return events.groupBy { it.employeeId }.map { (employeeId, employeeEvents) ->
            val wages = employeeEvents.sumOf { it.audit?.cashGrossCents ?: it.grossCents }
            val fedWithholding = employeeEvents.sumOf { evt ->
                evt.employeeTaxes.sumOf { line -> if (isFederalIncomeWithholding(line)) line.amountCents else 0L }
            }
            val ssWages = employeeEvents.sumOf { it.audit?.socialSecurityWagesCents ?: 0L }
            val ssTax = employeeEvents.sumOf { it.employeeTaxes.sumOf { line -> if (isSocialSecurity(line)) line.amountCents else 0L } }
            val medWages = employeeEvents.sumOf { it.audit?.medicareWagesCents ?: 0L }
            val medTax = employeeEvents.sumOf { it.employeeTaxes.sumOf { line -> if (isMedicare(line)) line.amountCents else 0L } }

            val stateWithholding = employeeEvents
                .flatMap { it.employeeTaxes }
                .filter { it.jurisdictionType == "STATE" }
                .groupBy { it.jurisdictionCode }
                .mapValues { (_, lines) -> lines.sumOf { it.amountCents } }

            W2EmployeeAnnual(
                employerId = employerId,
                year = year,
                employeeId = employeeId,
                wagesCents = wages,
                federalWithholdingCents = fedWithholding,
                socialSecurityWagesCents = ssWages,
                socialSecurityTaxCents = ssTax,
                medicareWagesCents = medWages,
                medicareTaxCents = medTax,
                stateWithholdingByState = stateWithholding,
                warnings = emptyList(),
            )
        }.sortedBy { it.employeeId }
    }

    fun computeW3(employerId: String, year: Int): W3Annual {
        val w2s = computeW2s(employerId, year)
        return W3Annual(
            employerId = employerId,
            year = year,
            employeeCount = w2s.size,
            totalWagesCents = w2s.sumOf { it.wagesCents },
            totalFederalWithholdingCents = w2s.sumOf { it.federalWithholdingCents },
            totalSocialSecurityWagesCents = w2s.sumOf { it.socialSecurityWagesCents },
            totalSocialSecurityTaxCents = w2s.sumOf { it.socialSecurityTaxCents },
            totalMedicareWagesCents = w2s.sumOf { it.medicareWagesCents },
            totalMedicareTaxCents = w2s.sumOf { it.medicareTaxCents },
        )
    }

    fun computeStateWithholding(employerId: String, year: Int, quarter: Int): StateWithholdingQuarterly {
        val range = FilingPeriods.quarter(year, quarter)
        val events = ledger.listEventsByEmployerAndCheckDateRange(employerId, range.startInclusive, range.endInclusive)

        val withheldByState = events
            .flatMap { it.employeeTaxes }
            .filter { it.jurisdictionType == "STATE" }
            .groupBy { it.jurisdictionCode }
            .mapValues { (_, lines) -> lines.sumOf { it.amountCents } }

        val taxableWages = events.sumOf { it.audit?.stateTaxableCents ?: 0L }

        return StateWithholdingQuarterly(
            employerId = employerId,
            year = year,
            quarter = quarter,
            dateRange = range,
            withheldByState = withheldByState,
            taxableWagesCents = taxableWages,
        )
    }

    fun validateFilingsBaseData(employerId: String, year: Int, quarter: Int? = null): FilingsValidation {
        val range = if (quarter == null) FilingPeriods.year(year) else FilingPeriods.quarter(year, quarter)
        val paycheckNets = ledger.listPaycheckNetByEmployerAndCheckDateRange(employerId, range.startInclusive, range.endInclusive)

        val paycheckIds = paycheckNets.map { it.paycheckId }
        val statuses = paymentStatuses.findStatusesByPaycheckIds(employerId, paycheckIds)

        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val missing = paycheckIds.count { it !in statuses }
        if (missing > 0) {
            warnings.add("$missing paychecks missing payment status projection (no corresponding payment status events seen)")
        }

        val failed = statuses.values.count { it == PaycheckPaymentLifecycleStatus.FAILED }
        if (failed > 0) {
            errors.add("$failed paychecks are FAILED in payment status projection")
        }

        return FilingsValidation(
            employerId = employerId,
            fromInclusive = range.startInclusive.toString(),
            toInclusive = range.endInclusive.toString(),
            errors = errors,
            warnings = warnings,
        )
    }

    private fun reconcilePaymentsForCheckDateRange(employerId: String, range: FilingPeriods.DateRange): PaymentsReconciliation {
        val paycheckNets = ledger.listPaycheckNetByEmployerAndCheckDateRange(employerId, range.startInclusive, range.endInclusive)
        val paycheckIds = paycheckNets.map { it.paycheckId }

        val statuses = paymentStatuses.findStatusesByPaycheckIds(employerId, paycheckIds)

        val missing = paycheckIds.count { it !in statuses }
        val failed = statuses.values.count { it == PaycheckPaymentLifecycleStatus.FAILED }
        val settled = statuses.values.count { it == PaycheckPaymentLifecycleStatus.SETTLED }

        val expectedNet = paycheckNets.sumOf { it.netCents }

        val settledNet = paycheckNets
            .filter { statuses[it.paycheckId] == PaycheckPaymentLifecycleStatus.SETTLED }
            .sumOf { it.netCents }

        return PaymentsReconciliation(
            paycheckCount = paycheckNets.size,
            missingPaymentStatusCount = missing,
            settledPaycheckCount = settled,
            failedPaycheckCount = failed,
            expectedNetCents = expectedNet,
            settledNetCents = settledNet,
        )
    }

    private fun isFederalIncomeWithholding(line: PaycheckLedgerTaxLine): Boolean = line.jurisdictionType == "FEDERAL" && line.jurisdictionCode == "US"

    private fun isSocialSecurity(line: PaycheckLedgerTaxLine): Boolean = line.jurisdictionType == "FEDERAL" && line.jurisdictionCode == "SS"

    private fun isMedicare(line: PaycheckLedgerTaxLine): Boolean = line.jurisdictionType == "FEDERAL" && line.jurisdictionCode == "MED"

    private fun isFuta(line: PaycheckLedgerTaxLine): Boolean = line.jurisdictionType == "FEDERAL" && line.jurisdictionCode == "FUTA"
}
