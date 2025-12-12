package com.example.uspayroll.payroll.engine

import com.example.uspayroll.payroll.model.DeductionCode
import com.example.uspayroll.payroll.model.DeductionLine
import com.example.uspayroll.payroll.model.PaycheckInput
import com.example.uspayroll.payroll.model.TaxLine
import com.example.uspayroll.payroll.model.TraceStep
import com.example.uspayroll.payroll.model.garnishment.GarnishmentFormula
import com.example.uspayroll.payroll.model.garnishment.GarnishmentOrder
import com.example.uspayroll.payroll.model.garnishment.GarnishmentType
import com.example.uspayroll.payroll.model.garnishment.SupportCapContext
import com.example.uspayroll.payroll.model.garnishment.computeSupportCap
import com.example.uspayroll.payroll.model.config.DeductionKind
import com.example.uspayroll.payroll.model.config.DeductionPlan
import com.example.uspayroll.payroll.model.config.defaultEmployeeEffects
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
            )
        } else {
            computeFromPlans(
                input = input,
                gross = gross,
                preTaxDeductions = preTaxDeductions,
                plansByCode = plansByCode,
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
    ): GarnishmentCalculationResult {
        if (orders.isEmpty()) {
            return GarnishmentCalculationResult(emptyList(), emptyList())
        }

        val garnishments = mutableListOf<DeductionLine>()
        val traceSteps = mutableListOf<TraceStep>()

        val sortedOrders = orders.sortedWith(
            compareBy<GarnishmentOrder>({ it.priorityClass }, { it.sequenceWithinClass }, { it.orderId.value }),
        )

        val supportOrders = sortedOrders.filter { it.type == GarnishmentType.CHILD_SUPPORT }
        val nonSupportOrders = sortedOrders.filter { it.type != GarnishmentType.CHILD_SUPPORT }

        fun ytdForOrder(order: GarnishmentOrder): Long {
            val code = DeductionCode(order.orderId.value)
            return input.priorYtd.deductionsByCode[code]?.amount ?: 0L
        }

        fun applyCaps(plan: DeductionPlan?, rawCents: Long, ytdCents: Long, lifetimeCap: Money?): Pair<Long, Money?> {
            var finalCents = rawCents
            var cappedAt: Money? = null

            val annualCap = plan?.annualCap
            if (annualCap != null) {
                val remaining = annualCap.amount - ytdCents
                if (remaining <= 0L) {
                    finalCents = 0L
                } else if (finalCents > remaining) {
                    finalCents = remaining
                    cappedAt = annualCap
                }
            }

            // Simple lifetime-cap support (per order).
            if (lifetimeCap != null) {
                val remaining = lifetimeCap.amount - ytdCents
                if (remaining <= 0L) {
                    finalCents = 0L
                } else if (finalCents > remaining) {
                    finalCents = remaining
                    cappedAt = lifetimeCap
                }
            }

            val perPeriodCap = plan?.perPeriodCap
            if (perPeriodCap != null && finalCents > perPeriodCap.amount) {
                finalCents = perPeriodCap.amount
                cappedAt = perPeriodCap
            }

            return finalCents to cappedAt
        }

        fun rawFromFormula(
            order: GarnishmentOrder,
            disposableBefore: Long,
            filingStatus: com.example.uspayroll.payroll.model.FilingStatus?,
        ): Long {
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
        ): DisposableIncome {
            val mandatoryPreTaxCents: Long = preTaxDeductions.sumOf { it.amount.amount }
            val totalEmployeeTaxCents: Long = employeeTaxes.sumOf { it.amount.amount }

            // Record how we derived disposable income for this order.
            val grossCents = gross.amount
            traceSteps += TraceStep.DisposableIncomeComputed(
                orderId = order.orderId.value,
                grossCents = grossCents,
                mandatoryPreTaxCents = mandatoryPreTaxCents,
                employeeTaxCents = totalEmployeeTaxCents,
                baseDisposableCents = when (order.type) {
                    com.example.uspayroll.payroll.model.garnishment.GarnishmentType.STUDENT_LOAN ->
                        grossCents - mandatoryPreTaxCents - totalEmployeeTaxCents
                    else -> grossCents - mandatoryPreTaxCents
                },
                netForProtectedFloorCents = grossCents - mandatoryPreTaxCents - totalEmployeeTaxCents,
            )

            return when (order.type) {
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
        }

        fun protectedEarningsFloorCents(order: GarnishmentOrder): Long? =
            when (val rule = order.protectedEarningsRule) {
                null -> null
                is com.example.uspayroll.payroll.model.garnishment.ProtectedEarningsRule.FixedFloor ->
                    rule.amount.amount
                is com.example.uspayroll.payroll.model.garnishment.ProtectedEarningsRule.MultipleOfMinWage -> {
                    val raw = rule.hourlyRate.amount.toDouble() * rule.hours * rule.multiplier
                    raw.toLong().coerceAtLeast(0L)
                }
            }

        val filingStatus = input.employeeSnapshot.filingStatus

        var garnishmentCents = 0L

        // First pass: compute requested amounts for support orders so we can
        // apply any aggregate caps before mixing in other orders.
        data class RequestedSupport(
            val order: GarnishmentOrder,
            val disposable: DisposableIncome,
            val requestedBeforeCaps: Long,
        )

        val supportRequests = mutableListOf<RequestedSupport>()
 
        // For now we allocate any support caps proportionally across all
        // support orders, in the deterministic order of (priorityClass,
        // sequenceWithinClass, orderId). If a jurisdiction requires
        // priority-based allocation instead, this is where a different
        // strategy would be plugged in.
        for (order in supportOrders) {
            val plan = plansByCode[DeductionCode(order.planId)]

            val disposable = computeDisposableIncome(
                order = order,
                gross = gross,
                employeeTaxes = employeeTaxes,
                preTaxDeductions = preTaxDeductions,
            )

            val disposableBefore = disposable.baseDisposableCents
            if (disposableBefore <= 0L) continue

            var rawCents = rawFromFormula(order, disposableBefore, filingStatus)
            if (rawCents <= 0L) continue

            val ytd = ytdForOrder(order)
            val (finalCents, _) = applyCaps(plan, rawCents, ytd, order.lifetimeCap)
            if (finalCents <= 0L) continue

            supportRequests += RequestedSupport(order, disposable, finalCents)
        }

        // If a support cap is configured and support requests exist, compute
        // per-order scaled amounts that respect the aggregate cap.
        val scaledSupportByOrderId: Map<String, Long> =
            if (supportCapContext != null && supportRequests.isNotEmpty()) {
                val disposableForSupport = Money(
                    amount = supportRequests.map { it.disposable.baseDisposableCents }.min(),
                    currency = gross.currency,
                )
                val capMoney = computeSupportCap(disposableForSupport, supportCapContext)
                val capCents = capMoney.amount
                val totalRequested = supportRequests.sumOf { it.requestedBeforeCaps }

                if (totalRequested > capCents && capCents >= 0) {
                    // Proportionally scale requested amounts to fit within the cap.
                    var runningTotal = 0L
                    val scaled = mutableMapOf<String, Long>()
                    supportRequests.forEachIndexed { index, req ->
                        val isLast = index == supportRequests.lastIndex
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

                    traceSteps += TraceStep.SupportCapApplied(
                        jurisdictionCode = supportCapContext.jurisdictionCode,
                        ccpaCapCents = capCents,
                        stateCapCents = supportCapContext.params.stateAggregateCapRate
                            ?.let { (disposableForSupport.amount * it.value).toLong() },
                        effectiveCapCents = capCents,
                        totalRequestedCents = totalRequested,
                        totalAppliedCents = capCents,
                    )

                    scaled
                } else {
                    // Cap does not bind; use requested amounts as-is.
                    supportRequests.associate { it.order.orderId.value to it.requestedBeforeCaps }
                }
            } else {
                emptyMap()
            }

        for (order in sortedOrders) {
            val plan = plansByCode[DeductionCode(order.planId)]

            val disposable = computeDisposableIncome(
                order = order,
                gross = gross,
                employeeTaxes = employeeTaxes,
                preTaxDeductions = preTaxDeductions,
            )

            val disposableBefore = disposable.baseDisposableCents
            if (disposableBefore <= 0L) continue

            val remainingDisposable = disposableBefore - garnishmentCents
            if (remainingDisposable <= 0L) continue

            // For support orders, start from any scaled amount if a cap was
            // applied; otherwise fall back to the formula-derived amount.
            val scaledSupport = scaledSupportByOrderId[order.orderId.value]

            var rawCents = scaledSupport ?: rawFromFormula(order, disposableBefore, filingStatus)
            if (rawCents <= 0L) continue

            val requestedBeforeCaps = rawCents

            val ytd = ytdForOrder(order)
            var (finalCents, cappedAt) = applyCaps(plan, rawCents, ytd, order.lifetimeCap)
            if (finalCents == 0L) continue

            // Enforce disposable-income ceiling: cannot take more than what is
            // left after prior garnishments.
            if (finalCents > remainingDisposable) {
                finalCents = remainingDisposable
            }

            // Apply protected earnings floor, based on net cash after employee
            // taxes but before voluntary post-tax deductions.
            val requestedBeforeFloor = finalCents
            val protectedFloor = protectedEarningsFloorCents(order)
            var protectedConstrained = false
            protectedFloor?.let { floorCents ->
                val disposableForFloor = disposable.netForProtectedFloorCents
                val maxByFloor = (disposableForFloor - garnishmentCents - floorCents).coerceAtLeast(0L)
                if (finalCents > maxByFloor) {
                    finalCents = maxByFloor
                    protectedConstrained = true
                }
                if (finalCents != requestedBeforeFloor) {
                    traceSteps += TraceStep.ProtectedEarningsApplied(
                        orderId = order.orderId.value,
                        requestedCents = requestedBeforeFloor,
                        adjustedCents = finalCents,
                        floorCents = floorCents,
                    )
                }
            }

            if (finalCents == 0L) continue

            val code = DeductionCode(order.orderId.value)
            val amount = Money(finalCents, gross.currency)

            garnishments += DeductionLine(
                code = code,
                description = plan?.name ?: order.caseNumber ?: order.orderId.value,
                amount = amount,
            )

            garnishmentCents += finalCents

            val planEffects = if (plan?.employeeEffects?.isNotEmpty() == true) {
                plan.employeeEffects
            } else {
                // For now, treat order-based garnishments with the same default
                // effects as generic GARNISHMENT plans.
                DeductionKind.GARNISHMENT.defaultEmployeeEffects()
            }

            // Emit a garnishment-specific trace step before the generic
            // DeductionApplied so consumers can see the full requested vs
            // applied context.
            val arrearsBeforeCents = order.arrearsBefore?.amount
            val appliedToArrears = if (arrearsBeforeCents != null && arrearsBeforeCents > 0L) {
                minOf(finalCents, arrearsBeforeCents)
            } else {
                0L
            }
            val appliedToCurrent = finalCents - appliedToArrears
            val arrearsAfterCents = arrearsBeforeCents?.let { (it - appliedToArrears).coerceAtLeast(0L) }

            traceSteps += TraceStep.GarnishmentApplied(
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
            )

            traceSteps += TraceStep.DeductionApplied(
                description = plan?.name ?: order.caseNumber ?: order.orderId.value,
                basis = gross,
                rate = plan?.employeeRate,
                amount = amount,
                cappedAt = cappedAt,
                effects = planEffects,
            )
        }

        return GarnishmentCalculationResult(
            garnishments = garnishments,
            traceSteps = traceSteps,
        )
    }

    private fun computeFromPlans(
        input: PaycheckInput,
        gross: Money,
        preTaxDeductions: List<DeductionLine>,
        plansByCode: Map<DeductionCode, DeductionPlan>,
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

        val garnishments = mutableListOf<DeductionLine>()
        val traceSteps = mutableListOf<TraceStep>()

        // In the prior implementation, mandatory pre-tax consisted of HSA, FSA,
        // and PRETAX_RETIREMENT_EMPLOYEE employee amounts.
        val mandatoryPreTaxCents: Long = preTaxDeductions.sumOf { it.amount.amount }

        fun ytdForPlan(plan: DeductionPlan): Long {
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

        var garnishmentCents = 0L

        for (plan in sortedPlans) {
            var rawCents = 0L
            plan.employeeRate?.let { rate ->
                rawCents += (gross.amount * rate.value).toLong()
            }
            plan.employeeFlat?.let { flat ->
                rawCents += flat.amount
            }
            if (rawCents == 0L) continue

            val ytd = ytdForPlan(plan)
            var (finalCents, cappedAt) = applyCaps(plan, rawCents, ytd)
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

            garnishments += DeductionLine(
                code = code,
                description = plan.name,
                amount = amount,
            )

            garnishmentCents += finalCents

            val planEffects = if (plan.employeeEffects.isNotEmpty()) plan.employeeEffects else plan.kind.defaultEmployeeEffects()

            traceSteps += TraceStep.DeductionApplied(
                description = plan.name,
                basis = gross,
                rate = plan.employeeRate,
                amount = amount,
                cappedAt = cappedAt,
                effects = planEffects,
            )
        }

        return GarnishmentCalculationResult(
            garnishments = garnishments,
            traceSteps = traceSteps,
        )
    }
}
