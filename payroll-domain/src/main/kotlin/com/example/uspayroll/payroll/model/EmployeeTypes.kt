package com.example.uspayroll.payroll.model

import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import java.time.LocalDate

// Employee snapshot and compensation

enum class FilingStatus { SINGLE, MARRIED, HEAD_OF_HOUSEHOLD }

sealed class BaseCompensation {
    data class Salaried(
        val annualSalary: Money,
        val frequency: PayFrequency,
    ) : BaseCompensation()

    data class Hourly(val hourlyRate: Money) : BaseCompensation()
}

data class EmployeeSnapshot(
    val employerId: EmployerId,
    val employeeId: EmployeeId,
    val homeState: String,
    val workState: String,
    val filingStatus: FilingStatus,
    val baseCompensation: BaseCompensation,
    val hireDate: LocalDate? = null,
    val terminationDate: LocalDate? = null,
    /**
     * Optional additional withholding per paycheck on top of rule-based tax.
     * This is intended for employee-elected extra withholding.
     */
    val additionalWithholdingPerPeriod: com.example.uspayroll.shared.Money? = null,
    /** Simple dependent count hook for future use; currently informational. */
    val dependents: Int? = null,
)
