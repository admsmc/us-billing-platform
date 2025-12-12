package com.example.uspayroll.payroll.model.garnishment

import com.example.uspayroll.payroll.model.Percent
import com.example.uspayroll.shared.Money

/**
 * Parameterized configuration for support (child/spousal) garnishment caps.
 *
 * CCPA baseline (15 U.S.C. § 1673(b)) is modeled as:
 * - 50% when the obligor supports another spouse or child.
 * - 60% when the obligor does not support another spouse or child.
 * - +5 percentage points (to 55% / 65%) when arrears are at least 12 weeks.
 *
 * This type is intentionally jurisdiction-agnostic; concrete state/federal
 * profiles (for example, Michigan's 50% aggregate cap overlay) live in
 * higher layers (HR/worker) and are passed in as data.
 */
data class SupportCapParams(
    val maxRateWhenSupportingOthers: Percent,
    val maxRateWhenNotSupportingOthers: Percent,
    val arrearsBonusRate: Percent,
    /** Optional aggregate cap rate imposed by a state (e.g. 50% in MI). */
    val stateAggregateCapRate: Percent? = null,
)

/**
 * Per-employee context for computing support caps.
 */
data class SupportCapContext(
    val params: SupportCapParams,
    val supportsOtherDependents: Boolean,
    val arrearsAtLeast12Weeks: Boolean,
    /** Optional jurisdiction code for trace/debug purposes (e.g. "MI"). */
    val jurisdictionCode: String? = null,
)

/**
 * Compute the maximum total support withholding for a paycheck under federal
 * CCPA-style rules plus an optional state aggregate cap.
 *
 * For example, with CCPA-only parameters:
 * - supportsOtherDependents=true, arrearsAtLeast12Weeks=false → 50% of disposable.
 * - supportsOtherDependents=false, arrearsAtLeast12Weeks=false → 60% of disposable.
 * - supportsOtherDependents=false, arrearsAtLeast12Weeks=true  → 65% of disposable.
 *
 * A state overlay such as Michigan's 50% aggregate support cap is modeled by
 * setting [SupportCapParams.stateAggregateCapRate] and taking the minimum of
 * the CCPA cap and the state cap.
 */
fun computeSupportCap(disposable: Money, context: SupportCapContext): Money {
    val p = context.params

    // Base CCPA rate depending on whether the payer supports others.
    val baseRate = if (context.supportsOtherDependents) {
        p.maxRateWhenSupportingOthers
    } else {
        p.maxRateWhenNotSupportingOthers
    }

    val ccpaRateValue = if (context.arrearsAtLeast12Weeks) {
        baseRate.value + p.arrearsBonusRate.value
    } else {
        baseRate.value
    }

    val ccpaCapCents = (disposable.amount * ccpaRateValue).toLong()

    val stateCapCents = p.stateAggregateCapRate
        ?.let { (disposable.amount * it.value).toLong() }

    val effectiveCapCents = stateCapCents
        ?.let { minOf(ccpaCapCents, it) }
        ?: ccpaCapCents

    return Money(effectiveCapCents.coerceAtLeast(0L), disposable.currency)
}
