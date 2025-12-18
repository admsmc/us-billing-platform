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
            EarningDefinition(
                code = EarningCode("HOURLY_DT"),
                displayName = "Double-time wages",
                category = EarningCategory.OVERTIME,
                defaultRate = null,
            ),
        ),
        EmployerId("EMP-BENCH") to listOf(
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
                defaultRate = null,
                overtimeMultiplier = 1.5,
            ),
            EarningDefinition(
                code = EarningCode("HOURLY_DT"),
                displayName = "Double-time wages",
                category = EarningCategory.OVERTIME,
                defaultRate = null,
            ),
            EarningDefinition(
                code = EarningCode("TIPS"),
                displayName = "Tips",
                category = EarningCategory.TIPS,
                defaultRate = null,
            ),
            EarningDefinition(
                code = EarningCode("TIPS_CASH"),
                displayName = "Cash tips",
                category = EarningCategory.TIPS,
                defaultRate = null,
            ),
            EarningDefinition(
                code = EarningCode("TIPS_CHARGED"),
                displayName = "Charged tips",
                category = EarningCategory.TIPS,
                defaultRate = null,
            ),
            EarningDefinition(
                code = EarningCode("TIPS_ALLOCATED"),
                displayName = "Allocated tips",
                category = EarningCategory.TIPS,
                defaultRate = null,
            ),
            EarningDefinition(
                code = EarningCode("COMMISSION"),
                displayName = "Commission",
                category = EarningCategory.COMMISSION,
                defaultRate = null,
            ),
            EarningDefinition(
                code = EarningCode("BONUS"),
                displayName = "Bonus",
                category = EarningCategory.BONUS,
                defaultRate = null,
            ),
            EarningDefinition(
                code = EarningCode("EXP_REIMB"),
                displayName = "Expense reimbursement",
                category = EarningCategory.REIMBURSEMENT_NON_TAXABLE,
                defaultRate = null,
            ),
        ),
    )

    override fun findByEmployerAndCode(employerId: EmployerId, code: EarningCode): EarningDefinition? = data[employerId]?.find { it.code == code }
}
