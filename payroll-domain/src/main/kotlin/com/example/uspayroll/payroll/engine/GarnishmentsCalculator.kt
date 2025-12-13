package com.example.uspayroll.payroll.engine

import com.example.uspayroll.payroll.model.DeductionCode
import com.example.uspayroll.payroll.model.DeductionLine
import com.example.uspayroll.payroll.model.PaycheckInput
import com.example.uspayroll.payroll.model.TaxLine
import com.example.uspayroll.payroll.model.TraceStep
import com.example.uspayroll.payroll.model.config.DeductionKind
import com.example.uspayroll.payroll.model.config.DeductionPlan
import com.example.uspayroll.payroll.model.config.defaultEmployeeEffects
import com.example.uspayroll.payroll.model.garnishment.GarnishmentFormula
import com.example.uspayroll.payroll.model.garnishment.GarnishmentOrder
import com.example.uspayroll.payroll.model.garnishment.GarnishmentType
import com.example.uspayroll.payroll.model.garnishment.SupportCapContext
import com.example.uspayroll.payroll.model.garnishment.computeSupportCap
import com.example.uspayroll.shared.Money

/**
 * Result of garnishment computation for a single paycheck.
 */
data class GarnishmentCalculationResult(
    val garnishments: List<DeductionLine>,
    val traceSteps: List<TraceStep>,
)

/**
 * Simple garnishments calculator that mirrors the previous behavior in
 * DeductionsCalculator: per-plan caps, and a disposable-income floor based on
 * gross and mandatory pre-tax deductions.
 *
 * Future phases will incorporate employee-specific GarnishmentOrder metadata,
 * protected earnings rules, and multi-order priority logic.
 */
object GarnishmentsCalculator {

    fun computeGarnishments(
        input: PaycheckInput,
        gross: Money,
        employeeTaxes: List<TaxLine>,
        preTaxDeductions: List<DeductionLine>,
        plansByCode: Map<DeductionCode, DeductionPlan>,
        supportCapContext: SupportCapContext? = null,
        includeTrace: Boolean = true,
    ): GarnishmentCalculationResult {
        val orders = input.garnishments.orders
        return if (orders.isNotEmpty()) {
            computeFromOrders(
                input = input,
                gross = gross,
                employeeTaxes = employeeTaxes,
                preTaxDeductions = preTaxDeductions,
                plansByCode = plansByCode,
                orders = orders,
                supportCapContext = supportCapContext,
                includeTrace = includeTrace,
            )
        } else {
            computeFromPlans(
                input = input,
                gross = gross,
                preTaxDeductions = preTaxDeductions,
                plansByCode = plansByCode,
                includeTrace = includeTrace,
            )
        }
    }

    private fun computeFromOrders(
        input: PaycheckInput,
        gross: Money,
        employeeTaxes: List<TaxLine>,
        preTaxDeductions: List<DeductionLine>,
        plansByCode: Map<DeductionCode, DeductionPlan>,
        orders: List<GarnishmentOrder>,
        supportCapContext: SupportCapContext?,
        includeTrace: Boolean,
    ): GarnishmentCalculationResult {
        if (orders.isEmpty()) {
            return GarnishmentCalculationResult(emptyList(), emptyList())
        }

        val sortedOrders = orders.sortedWith(
            compareBy<GarnishmentOrder>({ it.priorityClass }, { it.sequenceWithinClass }, { it.orderId.value }),
        )

        val supportOrders = sortedOrders.filter { it.type == GarnishmentType.CHILD_SUPPORT }

        fun ytdForOrder(order: GarnishmentOrder): Long {
            val code = DeductionCode(order.orderId.value)
            return input.priorYtd.deductionsByCode[code]?.amount ?: 0L
        }

        data class CapResult(
            val finalCents: Long,
            val cappedAt: Money?,
        )

        fun applyCaps(plan: DeductionPlan?, rawCents: Long, ytdCents: Long, lifetimeCap: Money?): CapResult {
            val annualCap = plan?.annualCap
            val afterAnnual = if (annualCap != null) {
                val remaining = annualCap.amount - ytdCents
                when {
                    remaining <= 0L -> CapResult(finalCents = 0L, cappedAt = annualCap)
                    rawCents > remaining -> CapResult(finalCents = remaining, cappedAt = annualCap)
                    else -> CapResult(finalCents = rawCents, cappedAt = null)
                }
            } else {
                CapResult(finalCents = rawCents, cappedAt = null)
            }

            // Simple lifetime-cap support (per order).
            val afterLifetime = if (lifetimeCap != null) {
                val remaining = lifetimeCap.amount - ytdCents
                when {
                    remaining <= 0L -> CapResult(finalCents = 0L, cappedAt = lifetimeCap)
                    afterAnnual.finalCents > remaining -> CapResult(finalCents = remaining, cappedAt = lifetimeCap)
                    else -> afterAnnual
                }
            } else {
                afterAnnual
            }

            val perPeriodCap = plan?.perPeriodCap
            return if (perPeriodCap != null && afterLifetime.finalCents > perPeriodCap.amount) {
                CapResult(finalCents = perPeriodCap.amount, cappedAt = perPeriodCap)
            } else {
                afterLifetime
            }
        }

        fun rawFromFormula(order: GarnishmentOrder, disposableBefore: Long, filingStatus: com.example.uspayroll.payroll.model.FilingStatus?): Long {
            return when (val f = order.formula) {
                is GarnishmentFormula.PercentOfDisposable -> (disposableBefore * f.percent.value).toLong()
                is GarnishmentFormula.FixedAmountPerPeriod -> f.amount.amount
                is GarnishmentFormula.LesserOfPercentOrAmount -> {
                    val pct = (disposableBefore * f.percent.value).toLong()
                    minOf(pct, f.amount.amount)
                }

                is GarnishmentFormula.LevyWithBands -> {
                    if (f.bands.isEmpty()) return 0L
                    val candidates = filingStatus?.let { status ->
                        f.bands.filter { it.filingStatus == null || it.filingStatus == status }
                    } ?: f.bands
                    if (candidates.isEmpty()) return 0L
                    val sorted = candidates.sortedBy { it.upToCents ?: Long.MAX_VALUE }
                    val band = sorted.firstOrNull { it.upToCents == null || disposableBefore <= it.upToCents }
                        ?: sorted.last()
                    (disposableBefore - band.exemptCents).coerceAtLeast(0L)
                }
            }
        }

        data class DisposableIncome(
            val baseDisposableCents: Long,
            val netForProtectedFloorCents: Long,
        )

        fun computeDisposableIncome(
            order: GarnishmentOrder,
            gross: Money,
            employeeTaxes: List<TaxLine>,
            preTaxDeductions: List<DeductionLine>,
            includeTrace: Boolean,
        ): Pair<DisposableIncome, TraceStep.DisposableIncomeComputed?> {
            val mandatoryPreTaxCents: Long = preTaxDeductions.sumOf { it.amount.amount }
            val totalEmployeeTaxCents: Long = employeeTaxes.sumOf { it.amount.amount }

            val grossCents = gross.amount
            val baseDisposableCents = when (order.type) {
                com.example.uspayroll.payroll.model.garnishment.GarnishmentType.STUDENT_LOAN ->
                    grossCents - mandatoryPreTaxCents - totalEmployeeTaxCents
                else -> grossCents - mandatoryPreTaxCents
            }
            val netForProtectedFloorCents = grossCents - mandatoryPreTaxCents - totalEmployeeTaxCents

            val disposable = when (order.type) {
                // For now, model student loan garnishments using a disposable
                // base that subtracts both pre-tax deductions and employee
                // taxes before applying the 15% ceiling.
                com.example.uspayroll.payroll.model.garnishment.GarnishmentType.STUDENT_LOAN -> {
                    val base = gross.amount - mandatoryPreTaxCents - totalEmployeeTaxCents
                    DisposableIncome(
                        baseDisposableCents = base,
                        netForProtectedFloorCents = base,
                    )
                }

                // Default CCPA-style behavior: disposable for formulas is
                // gross minus mandatory pre-tax; disposable for protected
                // earnings subtracts both pre-tax and employee taxes.
                else -> {
                    DisposableIncome(
                        baseDisposableCents = gross.amount - mandatoryPreTaxCents,
                        netForProtectedFloorCents = gross.amount - mandatoryPreTaxCents - totalEmployeeTaxCents,
                    )
                }
            }

            val trace = if (includeTrace) {
                TraceStep.DisposableIncomeComputed(
                    orderId = order.orderId.value,
                    grossCents = grossCents,
                    mandatoryPreTaxCents = mandatoryPreTaxCents,
                    employeeTaxCents = totalEmployeeTaxCents,
                    baseDisposableCents = baseDisposableCents,
                    netForProtectedFloorCents = netForProtectedFloorCents,
                )
            } else {
                null
            }

            return disposable to trace
        }

        fun protectedEarningsFloorCents(order: GarnishmentOrder): Long? = when (val rule = order.protectedEarningsRule) {
            null -> null
            is com.example.uspayroll.payroll.model.garnishment.ProtectedEarningsRule.FixedFloor ->
                rule.amount.amount
            is com.example.uspayroll.payroll.model.garnishment.ProtectedEarningsRule.MultipleOfMinWage -> {
                val raw = rule.hourlyRate.amount.toDouble() * rule.hours * rule.multiplier
                raw.toLong().coerceAtLeast(0L)
            }
        }

        val filingStatus = input.employeeSnapshot.filingStatus

        // Perf-first: local mutation is allowed as long as we don't mutate inputs.
        val traceSteps: MutableList<TraceStep>? = if (includeTrace) ArrayList<TraceStep>(sortedOrders.size * 4) else null
        val garnishments = ArrayList<DeductionLine>(sortedOrders.size)

        // First pass: compute requested amounts for support orders so we can apply any aggregate caps.
        data class RequestedSupport(
            val order: GarnishmentOrder,
            val disposable: DisposableIncome,
            val requestedBeforeCaps: Long,
        )

        val supportRequests = ArrayList<RequestedSupport>(supportOrders.size)
        for (order in supportOrders) {
            val plan = plansByCode[DeductionCode(order.planId)]

            val (disposable, disposableTrace) = computeDisposableIncome(
                order = order,
                gross = gross,
                employeeTaxes = employeeTaxes,
                preTaxDeductions = preTaxDeductions,
                includeTrace = includeTrace,
            )
            if (disposableTrace != null) traceSteps?.add(disposableTrace)

            val disposableBefore = disposable.baseDisposableCents
            if (disposableBefore <= 0L) continue

            val rawCents = rawFromFormula(order, disposableBefore, filingStatus)
            if (rawCents <= 0L) continue

            val ytd = ytdForOrder(order)
            val capResult = applyCaps(plan, rawCents, ytd, order.lifetimeCap)
            if (capResult.finalCents <= 0L) continue

            supportRequests.add(RequestedSupport(order, disposable, capResult.finalCents))
        }

        val scaledSupportByOrderId: Map<String, Long> = if (supportCapContext != null && supportRequests.isNotEmpty()) {
            val minDisposableForSupport = supportRequests.minOf { it.disposable.baseDisposableCents }
            val disposableForSupport = Money(amount = minDisposableForSupport, currency = gross.currency)

            val capMoney = computeSupportCap(disposableForSupport, supportCapContext)
            val capCents = capMoney.amount
            val totalRequested = supportRequests.sumOf { it.requestedBeforeCaps }

            if (totalRequested > capCents && capCents >= 0) {
                val scaled = HashMap<String, Long>(supportRequests.size * 2)
                var runningTotal = 0L

                val lastIndex = supportRequests.lastIndex
                supportRequests.forEachIndexed { index, req ->
                    val isLast = index == lastIndex
                    val applied = if (isLast) {
                        // Assign remainder to last order to avoid rounding gaps.
                        capCents - runningTotal
                    } else {
                        val proportion = req.requestedBeforeCaps.toDouble() / totalRequested.toDouble()
                        (capCents * proportion).toLong()
                    }

                    runningTotal += applied
                    scaled[req.order.orderId.value] = applied.coerceAtLeast(0L)
                }

                traceSteps?.add(
                    TraceStep.SupportCapApplied(
                        jurisdictionCode = supportCapContext.jurisdictionCode,
                        ccpaCapCents = capCents,
                        stateCapCents = supportCapContext.params.stateAggregateCapRate
                            ?.let { (disposableForSupport.amount * it.value).toLong() },
                        effectiveCapCents = capCents,
                        totalRequestedCents = totalRequested,
                        totalAppliedCents = capCents,
                    ),
                )

                scaled
            } else {
                // Cap does not bind; use requested amounts as-is.
                supportRequests.associate { it.order.orderId.value to it.requestedBeforeCaps }
            }
        } else {
            emptyMap()
        }

        var garnishmentCents = 0L

        for (order in sortedOrders) {
            val plan = plansByCode[DeductionCode(order.planId)]

            val (disposable, disposableTrace) = computeDisposableIncome(
                order = order,
                gross = gross,
                employeeTaxes = employeeTaxes,
                preTaxDeductions = preTaxDeductions,
                includeTrace = includeTrace,
            )
            if (disposableTrace != null) traceSteps?.add(disposableTrace)

            val disposableBefore = disposable.baseDisposableCents
            if (disposableBefore <= 0L) continue

            val remainingDisposable = disposableBefore - garnishmentCents
            if (remainingDisposable <= 0L) continue

            // For support orders, start from any scaled amount if a cap was applied.
            val rawCents = scaledSupportByOrderId[order.orderId.value] ?: rawFromFormula(order, disposableBefore, filingStatus)
            if (rawCents <= 0L) continue

            val requestedBeforeCaps = rawCents

            val ytd = ytdForOrder(order)
            val capResult = applyCaps(plan, rawCents, ytd, order.lifetimeCap)
            var finalCents = capResult.finalCents
            if (finalCents <= 0L) continue

            // Enforce disposable-income ceiling: cannot take more than what is left.
            if (finalCents > remainingDisposable) {
                finalCents = remainingDisposable
            }

            // Apply protected earnings floor.
            val requestedBeforeFloor = finalCents
            val protectedFloor = protectedEarningsFloorCents(order)
            var protectedConstrained = false

            if (protectedFloor != null) {
                val disposableForFloor = disposable.netForProtectedFloorCents
                val maxByFloor = (disposableForFloor - garnishmentCents - protectedFloor).coerceAtLeast(0L)
                if (finalCents > maxByFloor) {
                    finalCents = maxByFloor
                    protectedConstrained = true
                }
                if (finalCents != requestedBeforeFloor) {
                    traceSteps?.add(
                        TraceStep.ProtectedEarningsApplied(
                            orderId = order.orderId.value,
                            requestedCents = requestedBeforeFloor,
                            adjustedCents = finalCents,
                            floorCents = protectedFloor,
                        ),
                    )
                }
            }

            if (finalCents <= 0L) continue

            val code = DeductionCode(order.orderId.value)
            val amount = Money(finalCents, gross.currency)

            garnishments.add(
                DeductionLine(
                    code = code,
                    description = plan?.name ?: order.caseNumber ?: order.orderId.value,
                    amount = amount,
                ),
            )

            garnishmentCents += finalCents

            val planEffects = if (plan?.employeeEffects?.isNotEmpty() == true) {
                plan.employeeEffects
            } else {
                DeductionKind.GARNISHMENT.defaultEmployeeEffects()
            }

            val arrearsBeforeCents = order.arrearsBefore?.amount
            val appliedToArrears = if (arrearsBeforeCents != null && arrearsBeforeCents > 0L) {
                minOf(finalCents, arrearsBeforeCents)
            } else {
                0L
            }
            val appliedToCurrent = finalCents - appliedToArrears
            val arrearsAfterCents = arrearsBeforeCents?.let { (it - appliedToArrears).coerceAtLeast(0L) }

            traceSteps?.add(
                TraceStep.GarnishmentApplied(
                    orderId = order.orderId.value,
                    type = order.type.name,
                    description = plan?.name ?: order.caseNumber ?: order.orderId.value,
                    requestedCents = requestedBeforeCaps,
                    appliedCents = finalCents,
                    disposableBeforeCents = disposableBefore,
                    disposableAfterCents = disposableBefore - garnishmentCents,
                    protectedEarningsFloorCents = protectedFloor,
                    protectedFloorConstrained = protectedConstrained,
                    arrearsBeforeCents = arrearsBeforeCents,
                    arrearsAfterCents = arrearsAfterCents,
                    appliedToCurrentCents = appliedToCurrent,
                    appliedToArrearsCents = appliedToArrears,
                ),
            )

            traceSteps?.add(
                TraceStep.DeductionApplied(
                    description = plan?.name ?: order.caseNumber ?: order.orderId.value,
                    basis = gross,
                    rate = plan?.employeeRate,
                    amount = amount,
                    cappedAt = capResult.cappedAt,
                    effects = planEffects,
                ),
            )
        }

        return GarnishmentCalculationResult(
            garnishments = garnishments,
            traceSteps = traceSteps ?: emptyList(),
        )
    }

    private fun computeFromPlans(
        input: PaycheckInput,
        gross: Money,
        preTaxDeductions: List<DeductionLine>,
        plansByCode: Map<DeductionCode, DeductionPlan>,
        includeTrace: Boolean,
    ): GarnishmentCalculationResult {
        val garnishmentPlans = plansByCode.values
            .filter { it.kind == DeductionKind.GARNISHMENT }
            .distinctBy { it.id }
        if (garnishmentPlans.isEmpty()) {
            return GarnishmentCalculationResult(
                garnishments = emptyList(),
                traceSteps = emptyList(),
            )
        }

        val sortedPlans = DeductionOrdering.sort(garnishmentPlans)

        // In the prior implementation, mandatory pre-tax consisted of HSA, FSA,
        // and PRETAX_RETIREMENT_EMPLOYEE employee amounts.
        val mandatoryPreTaxCents: Long = preTaxDeductions.sumOf { it.amount.amount }

        fun ytdForPlan(plan: DeductionPlan): Long {
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

            return plan.perPeriodCap
                ?.let { cap ->
                    when {
                        annualCapResult.finalCents > cap.amount -> CapResult(finalCents = cap.amount, cappedAt = cap)
                        else -> annualCapResult
                    }
                }
                ?: annualCapResult
        }

        val traceSteps: MutableList<TraceStep>? = if (includeTrace) ArrayList<TraceStep>(sortedPlans.size) else null
        val garnishments = ArrayList<DeductionLine>(sortedPlans.size)

        var garnishmentCents = 0L

        for (plan in sortedPlans) {
            val rawCents: Long =
                (plan.employeeRate?.let { rate -> (gross.amount * rate.value).toLong() } ?: 0L) +
                    (plan.employeeFlat?.amount ?: 0L)

            if (rawCents == 0L) continue

            val ytd = ytdForPlan(plan)
            val capResult = applyCaps(plan, rawCents, ytd)
            var finalCents = capResult.finalCents
            if (finalCents == 0L) continue

            val disposableBefore = gross.amount - mandatoryPreTaxCents
            val remainingDisposable = disposableBefore - garnishmentCents
            if (remainingDisposable <= 0L) {
                finalCents = 0L
            } else if (finalCents > remainingDisposable) {
                finalCents = remainingDisposable
            }
            if (finalCents == 0L) continue

            val code = DeductionCode(plan.id)
            val amount = Money(finalCents, gross.currency)

            garnishments.add(
                DeductionLine(
                    code = code,
                    description = plan.name,
                    amount = amount,
                ),
            )

            garnishmentCents += finalCents

            val planEffects = if (plan.employeeEffects.isNotEmpty()) plan.employeeEffects else plan.kind.defaultEmployeeEffects()

            traceSteps?.add(
                TraceStep.DeductionApplied(
                    description = plan.name,
                    basis = gross,
                    rate = plan.employeeRate,
                    amount = amount,
                    cappedAt = capResult.cappedAt,
                    effects = planEffects,
                ),
            )
        }

        return GarnishmentCalculationResult(
            garnishments = garnishments,
            traceSteps = traceSteps ?: emptyList(),
        )
    }
}
