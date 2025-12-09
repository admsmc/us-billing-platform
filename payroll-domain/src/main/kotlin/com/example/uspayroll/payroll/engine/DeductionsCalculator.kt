package com.example.uspayroll.payroll.engine

import com.example.uspayroll.shared.Money
import com.example.uspayroll.payroll.model.*
import com.example.uspayroll.payroll.model.config.DeductionConfigRepository
import com.example.uspayroll.payroll.model.config.DeductionKind
import com.example.uspayroll.payroll.model.config.DeductionPlan
import com.example.uspayroll.payroll.model.config.defaultEmployeeEffects

/** Result of deduction computation for a single paycheck. */
data class DeductionComputationResult(
    val preTaxDeductions: List<DeductionLine>,
    val postTaxDeductions: List<DeductionLine>,
    val traceSteps: List<TraceStep>,
    val plansByCode: Map<DeductionCode, DeductionPlan>,
)

/**
 * Deductions calculator that supports multiple plan kinds, annual and per-period
 * caps, and a deterministic evaluation order.
 */
object DeductionsCalculator {

    fun computeDeductions(
        input: PaycheckInput,
        earnings: List<EarningLine>,
        repo: DeductionConfigRepository? = null,
    ): DeductionComputationResult {
        if (repo == null) {
            return DeductionComputationResult(
                preTaxDeductions = emptyList(),
                postTaxDeductions = emptyList(),
                traceSteps = emptyList(),
                plansByCode = emptyMap(),
            )
        }

        val plans = repo.findPlansForEmployer(input.employerId)

        val preTax = mutableListOf<DeductionLine>()
        val postTax = mutableListOf<DeductionLine>()
        val traceSteps = mutableListOf<TraceStep>()
        val plansByCode = mutableMapOf<DeductionCode, DeductionPlan>()

        val gross = earnings.fold(0L) { acc, e -> acc + e.amount.amount }.let { Money(it) }

        // Track running totals to support garnishment "disposable income" limits
        var mandatoryPreTaxCents = 0L
        var garnishmentCents = 0L

        val sortedPlans = DeductionOrdering.sort(plans)

        fun ytdFor(plan: DeductionPlan): Long {
            val code = DeductionCode(plan.id)
            return input.priorYtd.deductionsByCode[code]?.amount ?: 0L
        }

        fun applyCaps(plan: DeductionPlan, rawCents: Long, ytdCents: Long): Pair<Long, Money?> {
            var finalCents = rawCents
            var cappedAt: Money? = null

            plan.annualCap?.let { cap ->
                val remaining = cap.amount - ytdCents
                if (remaining <= 0L) {
                    finalCents = 0L
                } else if (finalCents > remaining) {
                    finalCents = remaining
                    cappedAt = cap
                }
            }

            plan.perPeriodCap?.let { cap ->
                if (finalCents > cap.amount) {
                    finalCents = cap.amount
                    cappedAt = cap
                }
            }

            return finalCents to cappedAt
        }

        for (plan in sortedPlans) {
            // Compute raw employee amount from rate and/or flat
            var rawCents = 0L
            plan.employeeRate?.let { rate ->
                rawCents += (gross.amount * rate.value).toLong()
            }
            plan.employeeFlat?.let { flat ->
                rawCents += flat.amount
            }
            if (rawCents == 0L) continue

            val ytd = ytdFor(plan)
            var (finalCents, cappedAt) = applyCaps(plan, rawCents, ytd)
            if (finalCents == 0L) continue

            val code = DeductionCode(plan.id)

            // For garnishments, enforce that amount does not exceed disposable income
            if (plan.kind == DeductionKind.GARNISHMENT) {
                val disposableBefore = gross.amount - mandatoryPreTaxCents
                val remainingDisposable = disposableBefore - garnishmentCents
                if (remainingDisposable <= 0L) {
                    finalCents = 0L
                } else if (finalCents > remainingDisposable) {
                    finalCents = remainingDisposable
                }
                if (finalCents == 0L) continue
            }

            val amount = Money(finalCents, gross.currency)

            val targetList = when (plan.kind) {
                DeductionKind.PRETAX_RETIREMENT_EMPLOYEE,
                DeductionKind.HSA,
                DeductionKind.FSA -> preTax

                DeductionKind.ROTH_RETIREMENT_EMPLOYEE,
                DeductionKind.POSTTAX_VOLUNTARY,
                DeductionKind.GARNISHMENT,
                DeductionKind.OTHER_POSTTAX -> postTax
            }

            targetList += DeductionLine(
                code = code,
                description = plan.name,
                amount = amount,
            )
            plansByCode[code] = plan

            val planEffects = if (plan.employeeEffects.isNotEmpty()) plan.employeeEffects else plan.kind.defaultEmployeeEffects()

            if (plan.kind == DeductionKind.PRETAX_RETIREMENT_EMPLOYEE || plan.kind == DeductionKind.HSA || plan.kind == DeductionKind.FSA) {
                mandatoryPreTaxCents += finalCents
            }
            if (plan.kind == DeductionKind.GARNISHMENT) {
                garnishmentCents += finalCents
            }

            traceSteps += TraceStep.DeductionApplied(
                description = plan.name,
                basis = gross,
                rate = plan.employeeRate,
                amount = amount,
                cappedAt = cappedAt,
                effects = planEffects,
            )
        }

        return DeductionComputationResult(
            preTaxDeductions = preTax,
            postTaxDeductions = postTax,
            traceSteps = traceSteps,
            plansByCode = plansByCode,
        )
    }
}
