package com.example.usbilling.orchestrator.config

import com.example.usbilling.payroll.model.EarningCategory
import com.example.usbilling.payroll.model.EarningCode
import com.example.usbilling.payroll.model.config.EarningConfigRepository
import com.example.usbilling.payroll.model.config.EarningDefinition
import com.example.usbilling.shared.UtilityId
import com.example.usbilling.shared.Money
import org.springframework.stereotype.Component

/**
 * Temporary in-memory earning config implementation.
 *
 * In production this will be backed by a config service / database.
 */
@Component
class InMemoryEarningConfigRepository : EarningConfigRepository {

    private val data: Map<UtilityId, List<EarningDefinition>> = mapOf(
        UtilityId("emp-1") to listOf(
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
    )

    override fun findByEmployerAndCode(employerId: UtilityId, code: EarningCode): EarningDefinition? = data[employerId]?.find { it.code == code }
}
