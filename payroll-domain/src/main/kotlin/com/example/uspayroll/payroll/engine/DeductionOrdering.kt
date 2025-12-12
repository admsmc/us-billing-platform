package com.example.uspayroll.payroll.engine

import com.example.uspayroll.payroll.model.config.DeductionKind
import com.example.uspayroll.payroll.model.config.DeductionPlan

/**
 * Deterministic ordering for deduction plans so that pre-tax and garnishment
 * behaviors are predictable. Rough order:
 * - Section 125 pre-tax (HSA, FSA)
 * - Retirement pre-tax (PRETAX_RETIREMENT_EMPLOYEE)
 * - Garnishments
 * - Other post-tax (Roth, voluntary, other)
 */
object DeductionOrdering {

    fun sort(plans: List<DeductionPlan>): List<DeductionPlan> = plans.sortedWith(compareBy({ priority(it.kind) }, { it.id }))

    private fun priority(kind: DeductionKind): Int = when (kind) {
        DeductionKind.HSA, DeductionKind.FSA -> 1
        DeductionKind.PRETAX_RETIREMENT_EMPLOYEE -> 2
        DeductionKind.GARNISHMENT -> 3
        DeductionKind.ROTH_RETIREMENT_EMPLOYEE,
        DeductionKind.POSTTAX_VOLUNTARY,
        DeductionKind.OTHER_POSTTAX,
        -> 4
    }
}
