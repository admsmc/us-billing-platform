package com.example.uspayroll.worker

import com.example.uspayroll.payroll.model.EarningCategory
import com.example.uspayroll.payroll.model.EarningCode
import com.example.uspayroll.payroll.model.config.EarningConfigRepository
import com.example.uspayroll.payroll.model.config.EarningDefinition
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money

/**
 * Temporary in-memory earning config implementation for demo purposes.
 * In the future this will be backed by a real config service / database.
 */
@org.springframework.stereotype.Component
class InMemoryEarningConfigRepository : EarningConfigRepository {

    private val data: Map<EmployerId, List<EarningDefinition>> = mapOf(
        EmployerId("emp-1") to listOf(
            EarningDefinition(
                code = EarningCode("BASE"),
                displayName = "Base Salary",
                category = EarningCategory.REGULAR,
                defaultRate = null,
            ),
            EarningDefinition(
                code = EarningCode("HOURLY"),
                displayName = "Hourly Wages",
                category = EarningCategory.REGULAR,
                defaultRate = Money(50_00L),
            ),
        ),
    )

    override fun findByEmployerAndCode(employerId: EmployerId, code: EarningCode): EarningDefinition? = data[employerId]?.find { it.code == code }
}
