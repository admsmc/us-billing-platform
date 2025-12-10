package com.example.uspayroll.payroll.model

import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import java.time.LocalDate

// Employee snapshot and compensation

enum class FilingStatus { SINGLE, MARRIED, HEAD_OF_HOUSEHOLD }

/**
 * High-level employment classification used for certain tax rules (for
 * example, special FICA thresholds for household and election workers).
 */
enum class EmploymentType {
    REGULAR,
    HOUSEHOLD,
    ELECTION_WORKER,
    AGRICULTURAL,
}

/**
 * High-level FLSA exemption / coverage classification used for minimum wage
 * and overtime rules. This is intentionally coarse-grained for now.
 */
enum class FlsaExemptStatus {
    NON_EXEMPT,
    EXEMPT,
    RETAIL_7I,
    PUBLIC_SAFETY_7K,
    PUBLIC_SECTOR_COMP_TIME,
}

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
    val employmentType: EmploymentType = EmploymentType.REGULAR,
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
    /**
     * If true, federal income tax withholding should be suppressed for this
     * employee (subject to any statutory exceptions). Other taxes such as FICA
     * and state income tax may still apply.
     */
    val federalWithholdingExempt: Boolean = false,
    /**
     * Indicates that the employee is a nonresident alien for withholding
     * purposes. This is used by tax-service logic (per IRS Pub. 15-T) to apply
     * additional wage adjustments when computing federal income tax
     * withholding.
     */
    val isNonresidentAlien: Boolean = false,
    /**
     * Optional annual credit amount from Form W-4 (for example, dependent
     * credits in Step 3) that reduces annual federal income tax withholding.
     */
    val w4AnnualCreditAmount: Money? = null,
    /**
     * Optional annual other income amount from Form W-4 Step 4(a) to be added
     * to annual wages for federal withholding computations.
     */
    val w4OtherIncomeAnnual: Money? = null,
    /**
     * Optional annual deduction amount from Form W-4 Step 4(b) to be subtracted
     * from annual wages for federal withholding computations.
     */
    val w4DeductionsAnnual: Money? = null,
    /**
     * Indicates that the employee's Form W-4 Step 2 "multiple jobs" box is
     * checked. Tax-service logic can use this to select the appropriate
     * withholding rate schedules.
     */
    val w4Step2MultipleJobs: Boolean = false,
    /**
     * When true, the employee is exempt from FICA (Social Security and
     * Medicare) taxes. Tax-service and/or the tax engine will omit
     * SocialSecurityWages and MedicareWages rules when this flag is set.
     */
    val ficaExempt: Boolean = false,
    /**
     * Indicates whether the employer is treated as FLSA-covered for this
     * employee (e.g., enterprise coverage threshold, special carve-outs).
     * When false, minimum wage and overtime rules may not apply.
     */
    val flsaEnterpriseCovered: Boolean = true,
    /**
     * High-level FLSA exemption / coverage classification for the employee.
     * This is used by the engine when deciding whether to apply overtime and
     * certain minimum wage checks.
     */
    val flsaExemptStatus: FlsaExemptStatus = FlsaExemptStatus.NON_EXEMPT,
    /**
     * Indicates that the employee is treated as a tipped employee for FLSA
     * purposes (customarily and regularly receives more than a threshold of
     * tips per month). Derivation of this flag is left to HR/config.
     */
    val isTippedEmployee: Boolean = false,
    /**
     * Optional work city label for locality resolution (e.g., "Detroit",
     * "Grand Rapids", "Lansing"). The mapping from addresses to locality
     * codes is handled by worker-service using [LocalityCode].
     */
    val workCity: String? = null,
)
