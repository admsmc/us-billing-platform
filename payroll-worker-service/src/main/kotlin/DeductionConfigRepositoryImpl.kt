package com.example.uspayroll.worker

import com.example.uspayroll.payroll.model.Percent
import com.example.uspayroll.payroll.model.config.DeductionConfigRepository
import com.example.uspayroll.payroll.model.config.DeductionKind
import com.example.uspayroll.payroll.model.config.DeductionPlan
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money

/**
 * Temporary in-memory deduction config implementation for demo purposes.
 */
@org.springframework.stereotype.Component
class InMemoryDeductionConfigRepository : DeductionConfigRepository {

    private val data: Map<EmployerId, List<DeductionPlan>> = mapOf(
        EmployerId("emp-1") to listOf(
            DeductionPlan(
                id = "PLAN_VOLUNTARY",
                name = "Voluntary Post-Tax Deduction",
                kind = DeductionKind.POSTTAX_VOLUNTARY,
                employeeFlat = Money(100_00L), // $100
            ),
        ),
        EmployerId("EMP-BENCH") to listOf(
            DeductionPlan(
                id = "PLAN_401K",
                name = "401(k) Employee Pre-Tax",
                kind = DeductionKind.PRETAX_RETIREMENT_EMPLOYEE,
                subtype = "401k",
                employeeRate = Percent(0.05),
            ),
            DeductionPlan(
                id = "PLAN_ROTH_401K",
                name = "Roth 401(k) Employee",
                kind = DeductionKind.ROTH_RETIREMENT_EMPLOYEE,
                subtype = "roth401k",
                employeeRate = Percent(0.02),
            ),
            DeductionPlan(
                id = "PLAN_HSA",
                name = "HSA Employee",
                kind = DeductionKind.HSA,
                employeeFlat = Money(75_00L),
            ),
            DeductionPlan(
                id = "PLAN_VOLUNTARY",
                name = "Voluntary Post-Tax Deduction",
                kind = DeductionKind.POSTTAX_VOLUNTARY,
                employeeFlat = Money(25_00L),
            ),
        ),
    )

    override fun findPlansForEmployer(employerId: EmployerId): List<DeductionPlan> = data[employerId] ?: emptyList()
}
