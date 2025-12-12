package com.example.uspayroll.orchestrator.config

import com.example.uspayroll.payroll.model.config.DeductionConfigRepository
import com.example.uspayroll.payroll.model.config.DeductionKind
import com.example.uspayroll.payroll.model.config.DeductionPlan
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import org.springframework.stereotype.Component

/**
 * Temporary in-memory deduction config implementation.
 */
@Component
class InMemoryDeductionConfigRepository : DeductionConfigRepository {

    private val data: Map<EmployerId, List<DeductionPlan>> = mapOf(
        EmployerId("emp-1") to listOf(
            DeductionPlan(
                id = "PLAN_VOLUNTARY",
                name = "Voluntary Post-Tax Deduction",
                kind = DeductionKind.POSTTAX_VOLUNTARY,
                employeeFlat = Money(100_00L),
            ),
        ),
    )

    override fun findPlansForEmployer(employerId: EmployerId): List<DeductionPlan> = data[employerId] ?: emptyList()
}
