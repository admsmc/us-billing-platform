package com.example.usbilling.payroll.engine

import com.example.usbilling.payroll.model.*
import com.example.usbilling.payroll.model.audit.PaycheckAudit
import com.example.usbilling.payroll.model.audit.PaycheckComputation
import com.example.usbilling.payroll.model.audit.TraceLevel
import com.example.usbilling.payroll.model.config.DeductionConfigRepository
import com.example.usbilling.payroll.model.config.EarningConfigRepository
import com.example.usbilling.payroll.model.garnishment.SupportCapContext
import com.example.usbilling.shared.Money
import java.time.Instant

object PayrollEngine {
    fun version(): String = "0.0.1-SNAPSHOT"

    /**
     * Deterministic paycheck calculation.
     *
     * High-level flow:
     * - Compute earning lines from time slice + earning config (including overtime policy).
     * - Add any additional overtime premium required by nondiscretionary bonus.
     * - Apply FLSA tip-credit make-up (when applicable).
     * - Compute deductions (pre-tax + post-tax) from deduction config.
     * - Compute tax bases and taxes (employee + employer).
     * - Compute garnishments.
     * - Update YTD snapshot.
     * - Compute net as: cash gross - employee taxes - deductions.
     *
     * The engine is intended to be expressed as pure transformations over immutable values.
     */
    fun calculatePaycheck(
        input: PaycheckInput,
        earningConfig: EarningConfigRepository? = null,
        deductionConfig: DeductionConfigRepository? = null,
        overtimePolicy: OvertimePolicy = OvertimePolicy.Default,
        employerContributions: List<EmployerContributionLine> = emptyList(),
        strictYtdYear: Boolean = false,
        supportCapContext: SupportCapContext? = null,
    ): PaycheckResult = calculatePaycheckComputation(
        input = input,
        computedAt = Instant.EPOCH,
        traceLevel = TraceLevel.AUDIT,
        earningConfig = earningConfig,
        deductionConfig = deductionConfig,
        overtimePolicy = overtimePolicy,
        employerContributions = employerContributions,
        strictYtdYear = strictYtdYear,
        supportCapContext = supportCapContext,
    ).paycheck

    /**
     * Enterprise-grade paycheck computation.
     *
     * - [computedAt] must be supplied by the service boundary.
     * - [traceLevel] controls the debug trace behavior; in AUDIT mode, the returned [PaycheckResult.trace]
     *   is enforced as empty and auditability relies on [PaycheckAudit].
     */
    fun calculatePaycheckComputation(
        input: PaycheckInput,
        computedAt: Instant,
        traceLevel: TraceLevel,
        earningConfig: EarningConfigRepository? = null,
        deductionConfig: DeductionConfigRepository? = null,
        overtimePolicy: OvertimePolicy = OvertimePolicy.Default,
        employerContributions: List<EmployerContributionLine> = emptyList(),
        strictYtdYear: Boolean = false,
        supportCapContext: SupportCapContext? = null,
    ): PaycheckComputation {
        val includeTrace = traceLevel == TraceLevel.DEBUG
        val baseEarnings = EarningsCalculator.computeEarnings(input, earningConfig, overtimePolicy)

        // Additional overtime premium on nondiscretionary bonus, for a simple
        // hourly + bonus + overtime case. This does not change the existing
        // overtime lines; it only adds any extra premium required by the
        // increased regular rate due to bonus.
        val additionalBonusPremium = RegularRateCalculator.additionalOvertimePremiumForBonus(input, baseEarnings)
        val additionalBonusPremiumLine: EarningLine? = if (additionalBonusPremium.amount > 0L) {
            val otHours = input.timeSlice.overtimeHours
            val rate = if (otHours > 0.0) {
                val centsPerHour = (additionalBonusPremium.amount / otHours).toLong()
                Money(centsPerHour)
            } else {
                null
            }
            EarningLine(
                code = EarningCode("OT_BONUS_PREMIUM"),
                category = EarningCategory.OVERTIME,
                description = "Additional overtime premium on bonus",
                units = otHours,
                rate = rate,
                amount = additionalBonusPremium,
            )
        } else {
            null
        }

        val withBonusPremium: List<EarningLine> = if (additionalBonusPremiumLine != null) {
            val out = ArrayList<EarningLine>(baseEarnings.size + 1)
            out.addAll(baseEarnings)
            out.add(additionalBonusPremiumLine)
            out
        } else {
            baseEarnings
        }

        // Tip-credit make-up for weekly, non-exempt, tipped hourly employees.
        val earnings = TipCreditEnforcer.applyTipCreditMakeup(
            input = input,
            laborStandards = input.laborStandards,
            earnings = withBonusPremium,
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
        var cashGrossCents = 0L
        for (line in earnings) {
            if (line.category != EarningCategory.IMPUTED) {
                cashGrossCents += line.amount.amount
            }
        }
        val gross = Money(cashGrossCents)

        // Optional proration trace for salaried employees.
        // For off-cycle runs, base earnings are suppressed; proration is therefore not applicable.
        val prorationTraceStep: TraceStep? = if (!input.timeSlice.includeBaseEarnings) {
            null
        } else {
            when (val base = input.employeeSnapshot.baseCompensation) {
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
        }

        val deductionResult = DeductionsCalculator.computeDeductions(
            input = input,
            earnings = earnings,
            repo = deductionConfig,
            includeTrace = includeTrace,
        )

        val basisContext = BasisContext(
            earnings = earnings,
            preTaxDeductions = deductionResult.preTaxDeductions,
            postTaxDeductions = deductionResult.postTaxDeductions,
            plansByCode = deductionResult.plansByCode,
            ytd = input.priorYtd,
        )
        val basisComputation = BasisBuilder.compute(basisContext, includeComponents = includeTrace)
        val taxBases = basisComputation.bases

        val taxResult = TaxesCalculator.computeTaxes(
            input = input,
            bases = taxBases,
            basisComponents = basisComputation.components,
            includeTrace = includeTrace,
        )

        val garnishmentResult = GarnishmentsCalculator.computeGarnishments(
            input = input,
            gross = gross,
            employeeTaxes = taxResult.employeeTaxes,
            preTaxDeductions = deductionResult.preTaxDeductions,
            plansByCode = deductionResult.plansByCode,
            supportCapContext = supportCapContext,
            includeTrace = includeTrace,
        )

        val allDeductions = ArrayList<DeductionLine>(
            deductionResult.preTaxDeductions.size + garnishmentResult.garnishments.size + deductionResult.postTaxDeductions.size,
        ).apply {
            addAll(deductionResult.preTaxDeductions)
            addAll(garnishmentResult.garnishments)
            addAll(deductionResult.postTaxDeductions)
        }

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

        val trace: CalculationTrace = if (includeTrace) {
            val traceSteps = ArrayList<TraceStep>(
                3 + taxResult.traceSteps.size + 3 + deductionResult.traceSteps.size + garnishmentResult.traceSteps.size,
            )
            traceSteps.add(TraceStep.Note("gross=computed_without_employee_taxes_or_deductions"))
            if (ytdYearNote != null) traceSteps.add(ytdYearNote)
            if (prorationTraceStep != null) traceSteps.add(prorationTraceStep)
            traceSteps.addAll(taxResult.traceSteps)
            traceSteps.add(TraceStep.Note("pre_tax_deductions_cents=$preTaxTotalCents"))
            traceSteps.add(TraceStep.Note("garnishment_deductions_cents=$garnishmentTotalCents"))
            traceSteps.add(TraceStep.Note("post_tax_deductions_cents=$postTaxTotalCents"))
            traceSteps.addAll(deductionResult.traceSteps)
            traceSteps.addAll(garnishmentResult.traceSteps)
            CalculationTrace(steps = traceSteps)
        } else {
            // Enforced empty trace for AUDIT/NONE modes.
            CalculationTrace()
        }

        val totalEmployeeTaxCents = taxResult.employeeTaxes.fold(0L) { acc, t -> acc + t.amount.amount }
        val totalEmployerTaxCents = taxResult.employerTaxes.fold(0L) { acc, t -> acc + t.amount.amount }
        val totalDeductionCents = allDeductions.fold(0L) { acc, d -> acc + d.amount.amount }
        val net = Money(gross.amount - totalEmployeeTaxCents - totalDeductionCents, gross.currency)

        val paycheck = PaycheckResult(
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

        fun distinctInOrder(values: List<String>): List<String> {
            if (values.isEmpty()) return emptyList()
            val seen = LinkedHashSet<String>(values.size * 2)
            for (v in values) {
                if (v.isNotEmpty()) seen.add(v)
            }
            return seen.toList()
        }

        val appliedTaxRuleIds = distinctInOrder(
            ArrayList<String>(taxResult.employeeTaxes.size + taxResult.employerTaxes.size).apply {
                for (t in taxResult.employeeTaxes) add(t.ruleId)
                for (t in taxResult.employerTaxes) add(t.ruleId)
            },
        )

        val deductionPlanIds = distinctInOrder(
            ArrayList<String>(deductionResult.preTaxDeductions.size + deductionResult.postTaxDeductions.size).apply {
                for (d in deductionResult.preTaxDeductions) add(d.code.value)
                for (d in deductionResult.postTaxDeductions) add(d.code.value)
            },
        )

        val garnishmentCodes = distinctInOrder(
            ArrayList<String>(garnishmentResult.garnishments.size).apply {
                for (g in garnishmentResult.garnishments) add(g.code.value)
            },
        )

        val appliedGarnishmentOrderIds = if (input.garnishments.orders.isNotEmpty()) garnishmentCodes else emptyList()
        val appliedDeductionPlanIds = if (input.garnishments.orders.isEmpty()) {
            distinctInOrder(deductionPlanIds + garnishmentCodes)
        } else {
            deductionPlanIds
        }

        val audit = PaycheckAudit(
            engineVersion = version(),
            computedAt = computedAt,
            employerId = input.employerId.value,
            employeeId = input.employeeId.value,
            paycheckId = input.paycheckId.value,
            payRunId = input.payRunId?.value,
            payPeriodId = input.period.id,
            checkDate = input.period.checkDate,
            appliedTaxRuleIds = appliedTaxRuleIds,
            appliedDeductionPlanIds = appliedDeductionPlanIds,
            appliedGarnishmentOrderIds = appliedGarnishmentOrderIds,
            cashGrossCents = gross.amount,
            grossTaxableCents = taxBases[TaxBasis.Gross]?.amount ?: 0L,
            federalTaxableCents = taxBases[TaxBasis.FederalTaxable]?.amount ?: 0L,
            stateTaxableCents = taxBases[TaxBasis.StateTaxable]?.amount ?: 0L,
            socialSecurityWagesCents = taxBases[TaxBasis.SocialSecurityWages]?.amount ?: 0L,
            medicareWagesCents = taxBases[TaxBasis.MedicareWages]?.amount ?: 0L,
            supplementalWagesCents = taxBases[TaxBasis.SupplementalWages]?.amount ?: 0L,
            futaWagesCents = taxBases[TaxBasis.FutaWages]?.amount ?: 0L,
            employeeTaxCents = totalEmployeeTaxCents,
            employerTaxCents = totalEmployerTaxCents,
            preTaxDeductionCents = preTaxTotalCents,
            postTaxDeductionCents = postTaxTotalCents,
            garnishmentCents = garnishmentTotalCents,
            netCents = net.amount,
        )

        return PaycheckComputation(
            paycheck = paycheck,
            audit = audit,
        )
    }
}
