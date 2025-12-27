package com.example.usbilling.orchestrator.config

import com.example.usbilling.payroll.model.config.DeductionConfigRepository
import com.example.usbilling.payroll.model.config.DeductionKind
import com.example.usbilling.payroll.model.config.DeductionPlan
import com.example.usbilling.shared.UtilityId
import com.example.usbilling.shared.Money
import org.springframework.stereotype.Component

/**
 * Temporary in-memory deduction config implementation.
 */
@Component
class InMemoryDeductionConfigRepository : DeductionConfigRepository {

    private val data: Map<UtilityId, List<DeductionPlan>> = mapOf(
        UtilityId("emp-1") to listOf(
            DeductionPlan(
                id = "PLAN_VOLUNTARY",
                name = "Voluntary Post-Tax Deduction",
                kind = DeductionKind.POSTTAX_VOLUNTARY,
                employeeFlat = Money(100_00L),
            ),
        ),
    )

    override fun findPlansForEmployer(employerId: UtilityId): List<DeductionPlan> = data[employerId] ?: emptyList()
}
