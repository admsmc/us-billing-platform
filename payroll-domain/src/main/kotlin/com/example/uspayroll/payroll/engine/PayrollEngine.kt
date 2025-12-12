package com.example.uspayroll.payroll.engine

import com.example.uspayroll.payroll.model.*
import com.example.uspayroll.payroll.model.config.DeductionConfigRepository
import com.example.uspayroll.payroll.model.config.EarningConfigRepository
import com.example.uspayroll.payroll.model.garnishment.SupportCapContext
import com.example.uspayroll.shared.Money

object PayrollEngine {
    fun version(): String = "0.0.1-SNAPSHOT"

    /**
     * Minimal, deterministic paycheck calculation:
     * - Computes gross based on base compensation and time slice.
     * - Applies simple flat-rate employer taxes on gross, if configured.
     * - No employee taxes or deductions yet; net == gross.
     */
    fun calculatePaycheck(
        input: PaycheckInput,
        earningConfig: EarningConfigRepository? = null,
        deductionConfig: DeductionConfigRepository? = null,
        overtimePolicy: OvertimePolicy = OvertimePolicy.Default,
        employerContributions: List<EmployerContributionLine> = emptyList(),
        strictYtdYear: Boolean = false,
        supportCapContext: SupportCapContext? = null,
    ): PaycheckResult {
        val baseEarnings = EarningsCalculator.computeEarnings(input, earningConfig, overtimePolicy)
        val earnings = baseEarnings.toMutableList()

        // Additional overtime premium on nondiscretionary bonus, for a simple
        // hourly + bonus + overtime case. This does not change the existing
        // overtime lines; it only adds any extra premium required by the
        // increased regular rate due to bonus.
        val additionalBonusPremium = RegularRateCalculator.additionalOvertimePremiumForBonus(input, earnings)
        if (additionalBonusPremium.amount > 0L) {
            val otHours = input.timeSlice.overtimeHours
            val rate = if (otHours > 0.0) {
                val centsPerHour = (additionalBonusPremium.amount / otHours).toLong()
                Money(centsPerHour)
            } else {
                null
            }
            val extraLine = EarningLine(
                code = EarningCode("OT_BONUS_PREMIUM"),
                category = EarningCategory.OVERTIME,
                description = "Additional overtime premium on bonus",
                units = otHours,
                rate = rate,
                amount = additionalBonusPremium,
            )
            earnings += extraLine
        }

        // Tip-credit make-up for weekly, non-exempt, tipped hourly employees.
        TipCreditEnforcer.applyTipCreditMakeup(
            input = input,
            laborStandards = input.laborStandards,
            earnings = earnings,
        )

        val ytdYear = input.priorYtd.year
        val checkYear = input.period.checkDate.year
        val ytdYearNote: TraceStep.Note? = if (ytdYear != checkYear) {
            if (strictYtdYear) {
                throw IllegalArgumentException("YTD year $ytdYear does not match check year $checkYear")
            }
            TraceStep.Note("ytd_year_mismatch prior=$ytdYear checkYear=$checkYear")
        } else {
            null
        }

        // Cash gross excludes imputed earnings; imputed amounts are taxable but not paid in cash.
        val cashGrossCents = earnings
            .filter { it.category != EarningCategory.IMPUTED }
            .fold(0L) { acc, line -> acc + line.amount.amount }
        val gross = Money(cashGrossCents)

        // Optional proration trace for salaried employees
        val prorationTraceStep: TraceStep? = when (val base = input.employeeSnapshot.baseCompensation) {
            is BaseCompensation.Salaried -> {
                val schedule = input.paySchedule ?: PaySchedule.defaultFor(input.employerId, input.period.frequency)
                val allocation = RemainderAwareEvenAllocation.compute(base.annualSalary, schedule)

                val baseCentsForPeriod: Long = input.period.sequenceInYear
                    ?.let { seq ->
                        val zeroBasedIndex = seq - 1
                        if (zeroBasedIndex in 0 until schedule.periodsPerYear) {
                            allocation.amountForPeriod(zeroBasedIndex, schedule).amount
                        } else {
                            allocation.basePerPeriod.amount
                        }
                    }
                    ?: allocation.basePerPeriod.amount

                val explicitProration = input.timeSlice.proration
                val strategyProration = ProrationStrategy.CalendarDays
                    .computeProration(input.period, input.employeeSnapshot.hireDate, input.employeeSnapshot.terminationDate)

                val resolvedProration = explicitProration ?: strategyProration

                val baseEarningLine = earnings.firstOrNull { it.code == EarningCode("BASE") }
                val appliedCents = baseEarningLine?.amount?.amount ?: baseCentsForPeriod

                val strategyName = if (strategyProration != null) "CalendarDays" else "none"
                val explicitFlag = explicitProration != null
                val fraction = resolvedProration?.fraction ?: 1.0

                TraceStep.ProrationApplied(
                    strategy = strategyName,
                    explicitOverride = explicitFlag,
                    fraction = fraction,
                    fullCents = baseCentsForPeriod,
                    appliedCents = appliedCents,
                )
            }
            is BaseCompensation.Hourly -> null
        }

        val deductionResult = DeductionsCalculator.computeDeductions(
            input = input,
            earnings = earnings,
            repo = deductionConfig,
        )

        val basisContext = BasisContext(
            earnings = earnings,
            preTaxDeductions = deductionResult.preTaxDeductions,
            postTaxDeductions = deductionResult.postTaxDeductions,
            plansByCode = deductionResult.plansByCode,
            ytd = input.priorYtd,
        )
        val basisComputation = BasisBuilder.compute(basisContext)
        val taxBases = basisComputation.bases

        val taxResult = TaxesCalculator.computeTaxes(input, taxBases, basisComputation.components)

        val garnishmentResult = GarnishmentsCalculator.computeGarnishments(
            input = input,
            gross = gross,
            employeeTaxes = taxResult.employeeTaxes,
            preTaxDeductions = deductionResult.preTaxDeductions,
            plansByCode = deductionResult.plansByCode,
            supportCapContext = supportCapContext,
        )

        val allDeductions = deductionResult.preTaxDeductions +
            garnishmentResult.garnishments +
            deductionResult.postTaxDeductions

        val ytdAfter = YtdAccumulator.update(
            prior = input.priorYtd,
            earnings = earnings,
            employeeTaxes = taxResult.employeeTaxes,
            employerTaxes = taxResult.employerTaxes,
            deductions = allDeductions,
            bases = taxBases,
            employerContributions = employerContributions,
        )

        val preTaxTotalCents = deductionResult.preTaxDeductions.fold(0L) { acc, d -> acc + d.amount.amount }
        val postTaxTotalCents = deductionResult.postTaxDeductions.fold(0L) { acc, d -> acc + d.amount.amount }
        val garnishmentTotalCents = garnishmentResult.garnishments.fold(0L) { acc, d -> acc + d.amount.amount }

        val trace = CalculationTrace(
            steps = listOfNotNull(
                TraceStep.Note("gross=computed_without_employee_taxes_or_deductions"),
                ytdYearNote,
                prorationTraceStep,
            ) + taxResult.traceSteps + listOf(
                TraceStep.Note("pre_tax_deductions_cents=$preTaxTotalCents"),
                TraceStep.Note("garnishment_deductions_cents=$garnishmentTotalCents"),
                TraceStep.Note("post_tax_deductions_cents=$postTaxTotalCents"),
            ) + deductionResult.traceSteps + garnishmentResult.traceSteps,
        )

        val totalEmployeeTaxCents = taxResult.employeeTaxes.fold(0L) { acc, t -> acc + t.amount.amount }
        val totalDeductionCents = allDeductions.fold(0L) { acc, d -> acc + d.amount.amount }
        val net = Money(gross.amount - totalEmployeeTaxCents - totalDeductionCents, gross.currency)

        return PaycheckResult(
            paycheckId = input.paycheckId,
            payRunId = input.payRunId,
            employerId = input.employerId,
            employeeId = input.employeeId,
            period = input.period,
            earnings = earnings,
            employeeTaxes = taxResult.employeeTaxes,
            employerTaxes = taxResult.employerTaxes,
            deductions = allDeductions,
            employerContributions = employerContributions,
            gross = gross,
            net = net,
            ytdAfter = ytdAfter,
            trace = trace,
        )
    }
}
