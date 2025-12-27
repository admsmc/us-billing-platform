package com.example.usbilling.payroll.engine

import com.example.usbilling.payroll.model.*
import com.example.usbilling.payroll.model.config.DeductionConfigRepository
import com.example.usbilling.payroll.model.config.DeductionKind
import com.example.usbilling.payroll.model.config.DeductionPlan
import com.example.usbilling.payroll.model.config.defaultEmployeeEffects
import com.example.usbilling.shared.Money

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

    fun computeDeductions(input: PaycheckInput, earnings: List<EarningLine>, repo: DeductionConfigRepository? = null, includeTrace: Boolean = true): DeductionComputationResult {
        if (repo == null) {
            return DeductionComputationResult(
                preTaxDeductions = emptyList(),
                postTaxDeductions = emptyList(),
                traceSteps = emptyList(),
                plansByCode = emptyMap(),
            )
        }

        val plans = repo.findPlansForEmployer(input.employerId)
        val plansByCode: Map<DeductionCode, DeductionPlan> = plans.associateBy { DeductionCode(it.id) }

        val gross = earnings.fold(0L) { acc, e -> acc + e.amount.amount }.let { Money(it) }

        val sortedPlans = DeductionOrdering.sort(plans)

        fun ytdFor(plan: DeductionPlan): Long {
            val code = DeductionCode(plan.id)
            return input.priorYtd.deductionsByCode[code]?.amount ?: 0L
        }

        data class CapResult(
            val finalCents: Long,
            val cappedAt: Money?,
        )

        fun applyCaps(plan: DeductionPlan, rawCents: Long, ytdCents: Long): CapResult {
            val annualCapResult: CapResult = plan.annualCap
                ?.let { cap ->
                    val remaining = cap.amount - ytdCents
                    when {
                        remaining <= 0L -> CapResult(finalCents = 0L, cappedAt = cap)
                        rawCents > remaining -> CapResult(finalCents = remaining, cappedAt = cap)
                        else -> CapResult(finalCents = rawCents, cappedAt = null)
                    }
                }
                ?: CapResult(finalCents = rawCents, cappedAt = null)

            val perPeriodCapResult: CapResult = plan.perPeriodCap
                ?.let { cap ->
                    when {
                        annualCapResult.finalCents > cap.amount -> CapResult(finalCents = cap.amount, cappedAt = cap)
                        else -> annualCapResult
                    }
                }
                ?: annualCapResult

            return perPeriodCapResult
        }

        data class DeductionApplied(
            val kind: DeductionKind,
            val line: DeductionLine,
            val trace: TraceStep?,
        )

        val applied: List<DeductionApplied> = sortedPlans
            .asSequence()
            .filterNot { it.kind == DeductionKind.GARNISHMENT }
            .mapNotNull { plan ->
                val code = DeductionCode(plan.id)

                // Compute raw employee amount from rate and/or flat
                val rawCents: Long =
                    (plan.employeeRate?.let { rate -> (gross.amount * rate.value).toLong() } ?: 0L) +
                        (plan.employeeFlat?.amount ?: 0L)

                if (rawCents == 0L) {
                    return@mapNotNull null
                }

                val ytd = ytdFor(plan)
                val capResult = applyCaps(plan, rawCents, ytd)
                if (capResult.finalCents == 0L) {
                    return@mapNotNull null
                }

                val amount = Money(capResult.finalCents, gross.currency)

                val line = DeductionLine(
                    code = code,
                    description = plan.name,
                    amount = amount,
                )

                val trace: TraceStep? = if (includeTrace) {
                    val planEffects = if (plan.employeeEffects.isNotEmpty()) plan.employeeEffects else plan.kind.defaultEmployeeEffects()
                    TraceStep.DeductionApplied(
                        description = plan.name,
                        basis = gross,
                        rate = plan.employeeRate,
                        amount = amount,
                        cappedAt = capResult.cappedAt,
                        effects = planEffects,
                    )
                } else {
                    null
                }

                DeductionApplied(kind = plan.kind, line = line, trace = trace)
            }
            .toList()

        val preTaxKinds = setOf(
            DeductionKind.PRETAX_RETIREMENT_EMPLOYEE,
            DeductionKind.HSA,
            DeductionKind.FSA,
        )

        val preTaxDeductions = applied.filter { it.kind in preTaxKinds }.map { it.line }
        val postTaxDeductions = applied.filter { it.kind !in preTaxKinds }.map { it.line }
        val traceSteps = if (includeTrace) applied.mapNotNull { it.trace } else emptyList()

        return DeductionComputationResult(
            preTaxDeductions = preTaxDeductions,
            postTaxDeductions = postTaxDeductions,
            traceSteps = traceSteps,
            plansByCode = plansByCode,
        )
    }
}
